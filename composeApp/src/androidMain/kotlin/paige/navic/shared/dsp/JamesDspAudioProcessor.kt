package paige.navic.shared.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import paige.navic.util.core.Logger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "JamesDsp"

/**
 * Process-wide bridge between the Compose UI (which only ever sees the
 * common [paige.navic.shared.MediaPlayerViewModel] API) and the native DSP
 * handle, which is actually owned by a [JamesDspAudioProcessor] instance
 * living inside PlaybackService's ExoPlayer audio pipeline.
 *
 * PlaybackService carries no `android:process` in the manifest, so it runs
 * in the app's main process alongside the UI/ViewModel — a plain singleton
 * is enough to bridge them for this Phase 1 proof of concept. If the native
 * effect surface grows in Phase 2, prefer routing this through proper
 * MediaSession custom commands instead (see the shuffle/repeat custom
 * commands in PlaybackService for the existing pattern).
 */
object BassBoostController {
	private val _state = MutableStateFlow(BassBoostUiState())
	val state: StateFlow<BassBoostUiState> = _state.asStateFlow()

	@Volatile
	private var handle: JamesDspHandle = 0L

	// Same jank pattern found and fixed everywhere else this session (see SoundEffectsController's
	// requestEqualizerApply() kdoc for the full rationale): the gain slider's onValueChange calls
	// setGainDb() on every raw drag tick, and until now that called straight through to apply()'s
	// synchronous native setBassBoost() JNI call every single tick. UI state (_state) still updates
	// synchronously so the slider/label stay responsive; the native write is debounced to ~30ms of
	// drag quiescence via collectLatest+delay, reading whatever _state.value is once it actually
	// fires -- so a release mid-window still applies the final dragged value. setEnabled() is a
	// discrete one-shot toggle, not a drag stream, so it keeps calling apply() directly.
	private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
	private val pendingGainApply = MutableStateFlow(0L)

	init {
		scope.launch {
			pendingGainApply.filter { it != 0L }.collectLatest {
				delay(30.milliseconds)
				apply()
			}
		}
	}

	/** Called by [JamesDspAudioProcessor] once its native handle is ready. */
	fun onHandleAllocated(handle: JamesDspHandle) {
		this.handle = handle
		apply()
	}

	/** Called by [JamesDspAudioProcessor] right before it frees its handle. */
	fun onHandleReleased() {
		handle = 0L
	}

	fun setEnabled(enabled: Boolean) {
		_state.update { it.copy(enabled = enabled) }
		apply()
	}

	fun setGainDb(gainDb: Float) {
		_state.update { it.copy(gainDb = gainDb) }
		// Continuous slider drag -- debounced, see pendingGainApply's kdoc above.
		pendingGainApply.update { it + 1 }
	}

	private fun apply() {
		val h = handle
		if (h == 0L) return
		val current = _state.value
		val success = JamesDspWrapper.setBassBoost(h, current.enabled, current.gainDb)
		Logger.i(TAG, "setBassBoost(enabled=${current.enabled}, gainDb=${current.gainDb}) -> success=$success")
	}
}

/**
 * Phase 2: bridges the rest of the effect suite (Equalizer, Compander, Reverb, Crossfeed,
 * Stereo Enhancement, Vacuum Tube) plus the master DSP-chain enable switch, following exactly
 * the same singleton-bridge pattern as [BassBoostController] above (see its kdoc for why a
 * plain object is fine here). Kept as a separate object rather than folded into
 * [BassBoostController] so Phase 1's already-verified bass boost path stays untouched.
 *
 * The master switch doesn't map to any single native `setX` call — the whole native DSP graph
 * always runs when [JamesDspWrapper.processFloat] is invoked, regardless of which individual
 * effects are enabled inside it (see [JamesDspAudioProcessor]'s kdoc). So "master off" is
 * implemented as a clean bypass one level up, in [JamesDspAudioProcessor.queueInput]: input is
 * copied straight to output and `processFloat` is never called, exactly like the "no handle
 * yet" passthrough path Phase 1 already had.
 */
object SoundEffectsController {
	@Volatile
	private var handle: JamesDspHandle = 0L

	// Read on the audio thread on every queueInput() call, written from the UI thread — same
	// @Volatile requirement as JamesDspAudioProcessor.handle below (see its comment for why).
	@Volatile
	var chainEnabled: Boolean = true
		private set

	private val _masterState = MutableStateFlow(DspMasterUiState())
	val masterState: StateFlow<DspMasterUiState> = _masterState.asStateFlow()

	private val _equalizerState = MutableStateFlow(EqualizerUiState())
	val equalizerState: StateFlow<EqualizerUiState> = _equalizerState.asStateFlow()

	private val _companderState = MutableStateFlow(CompanderUiState())
	val companderState: StateFlow<CompanderUiState> = _companderState.asStateFlow()

	private val _reverbState = MutableStateFlow(ReverbUiState())
	val reverbState: StateFlow<ReverbUiState> = _reverbState.asStateFlow()

	private val _crossfeedState = MutableStateFlow(CrossfeedUiState())
	val crossfeedState: StateFlow<CrossfeedUiState> = _crossfeedState.asStateFlow()

	private val _stereoEnhancementState = MutableStateFlow(StereoEnhancementUiState())
	val stereoEnhancementState: StateFlow<StereoEnhancementUiState> = _stereoEnhancementState.asStateFlow()

	private val _vacuumTubeState = MutableStateFlow(VacuumTubeUiState())
	val vacuumTubeState: StateFlow<VacuumTubeUiState> = _vacuumTubeState.asStateFlow()

	// Phase 3: device-preset (AutoEq GraphicEQ) correction curve. See GraphicEqUiState's kdoc.
	private val _graphicEqState = MutableStateFlow(GraphicEqUiState())
	val graphicEqState: StateFlow<GraphicEqUiState> = _graphicEqState.asStateFlow()

	// Continuous slider drags (per-band gain, level, drive, time constant, granularity) call their
	// setX() on every raw drag tick so the label/thumb stay responsive; running the actual native
	// apply*() JNI call on every one of those ticks re-runs a full native effect reconfigure (worst
	// case: the Equalizer's applyEqualizer() resends the whole 15-band array via setMultiEqualizer
	// on every single-band tick) and is the source of drag jank. Same fix as
	// MediaPlayerViewModelAndroid.observePlaybackParameterRequests() applied to playback
	// speed/pitch: the UI state (_equalizerState etc.) still updates synchronously on every tick,
	// but the native write is debounced to ~30ms of drag quiescence via collectLatest+delay, reading
	// whatever the latest UI state is once it actually fires -- so a release mid-window still
	// applies the final dragged value, just ~30ms after the last tick instead of after every tick.
	//
	// Discrete one-shot interactions (enable switches, filter/interpolation/preset/mode pickers,
	// reset buttons, the AutoEq device-preset apply) call their apply*() directly instead of going
	// through these flows -- they're a single deliberate tap each, not a stream of ticks, so
	// debouncing them would only add unwanted latency.
	//
	// One shared debounce flow per *effect* (not per parameter/band) because each effect's native
	// setX() takes its whole parameter set at once regardless of which single field changed (e.g.
	// setMultiEqualizer always takes the full 15-band array) -- so the right granularity to
	// debounce at is "this effect needs re-applying", not "this specific band changed".
	private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
	private val pendingEqualizerApply = MutableStateFlow(0L)
	private val pendingCompanderApply = MutableStateFlow(0L)
	private val pendingStereoEnhancementApply = MutableStateFlow(0L)
	private val pendingVacuumTubeApply = MutableStateFlow(0L)

	init {
		observeDebouncedApplies()
	}

	private fun observeDebouncedApplies() {
		// Ticks are just a counter, not the parameter itself -- apply*() below always reads the
		// current *State.value at the moment it actually fires, so whichever tick is "latest" when
		// the 30ms delay elapses is always fully up to date. 0L is the pre-first-tick sentinel,
		// filtered out so this doesn't fire an extra apply at startup before any drag has happened.
		scope.launch {
			pendingEqualizerApply.filter { it != 0L }.collectLatest {
				delay(30.milliseconds)
				applyEqualizer()
			}
		}
		scope.launch {
			pendingCompanderApply.filter { it != 0L }.collectLatest {
				delay(30.milliseconds)
				applyCompander()
			}
		}
		scope.launch {
			pendingStereoEnhancementApply.filter { it != 0L }.collectLatest {
				delay(30.milliseconds)
				applyStereoEnhancement()
			}
		}
		scope.launch {
			pendingVacuumTubeApply.filter { it != 0L }.collectLatest {
				delay(30.milliseconds)
				applyVacuumTube()
			}
		}
	}

	private fun requestEqualizerApply() { pendingEqualizerApply.update { it + 1 } }
	private fun requestCompanderApply() { pendingCompanderApply.update { it + 1 } }
	private fun requestStereoEnhancementApply() { pendingStereoEnhancementApply.update { it + 1 } }
	private fun requestVacuumTubeApply() { pendingVacuumTubeApply.update { it + 1 } }

	/** Called by [JamesDspAudioProcessor] once its native handle is ready. */
	fun onHandleAllocated(handle: JamesDspHandle) {
		this.handle = handle
		applyEqualizer()
		applyCompander()
		applyReverb()
		applyCrossfeed()
		applyStereoEnhancement()
		applyVacuumTube()
		applyGraphicEq()
	}

	/** Called by [JamesDspAudioProcessor] right before it frees its handle. */
	fun onHandleReleased() {
		handle = 0L
	}

	fun setMasterEnabled(enabled: Boolean) {
		chainEnabled = enabled
		_masterState.update { it.copy(enabled = enabled) }
		Logger.i(TAG, "DSP master chain enabled=$enabled")
	}

	fun setEqualizerEnabled(enabled: Boolean) {
		_equalizerState.update { it.copy(enabled = enabled) }
		applyEqualizer()
	}

	fun setEqualizerFilterType(filterType: Int) {
		_equalizerState.update { it.copy(filterType = filterType) }
		applyEqualizer()
	}

	fun setEqualizerInterpolationMode(interpolationMode: Int) {
		_equalizerState.update { it.copy(interpolationMode = interpolationMode) }
		applyEqualizer()
	}

	fun setEqualizerBandGain(bandIndex: Int, gainDb: Float) {
		_equalizerState.update { state ->
			if (bandIndex !in state.bandGainsDb.indices) return@update state
			state.copy(bandGainsDb = state.bandGainsDb.toMutableList().apply { this[bandIndex] = gainDb })
		}
		// Continuous slider drag -- debounced, see requestEqualizerApply()'s kdoc above.
		requestEqualizerApply()
	}

	fun resetEqualizerBands() {
		_equalizerState.update { it.copy(bandGainsDb = List(EqualizerBandFrequenciesHz.size) { 0f }) }
		applyEqualizer()
	}

	fun setCompanderEnabled(enabled: Boolean) {
		_companderState.update { it.copy(enabled = enabled) }
		applyCompander()
	}

	fun setCompanderTimeConstant(seconds: Float) {
		_companderState.update { it.copy(timeConstantSec = seconds) }
		// Continuous slider drag -- debounced, see requestCompanderApply()'s kdoc above.
		requestCompanderApply()
	}

	fun setCompanderGranularity(granularity: Int) {
		_companderState.update { it.copy(granularity = granularity) }
		// Continuous slider drag (snapped to integer steps, but still dragged) -- debounced.
		requestCompanderApply()
	}

	fun setCompanderTfTransform(tfTransform: Int) {
		_companderState.update { it.copy(tfTransform = tfTransform) }
		applyCompander()
	}

	fun setCompanderBandGain(bandIndex: Int, gainDb: Float) {
		_companderState.update { state ->
			if (bandIndex !in state.bandGainsDb.indices) return@update state
			state.copy(bandGainsDb = state.bandGainsDb.toMutableList().apply { this[bandIndex] = gainDb })
		}
		// Continuous slider drag -- debounced, see requestCompanderApply()'s kdoc above.
		requestCompanderApply()
	}

	fun resetCompanderBands() {
		_companderState.update { it.copy(bandGainsDb = List(CompanderBandFrequenciesHz.size) { 0f }) }
		applyCompander()
	}

	fun setReverbEnabled(enabled: Boolean) {
		_reverbState.update { it.copy(enabled = enabled) }
		applyReverb()
	}

	fun setReverbPreset(preset: Int) {
		_reverbState.update { it.copy(preset = preset) }
		applyReverb()
	}

	fun setCrossfeedEnabled(enabled: Boolean) {
		_crossfeedState.update { it.copy(enabled = enabled) }
		applyCrossfeed()
	}

	fun setCrossfeedMode(mode: Int) {
		_crossfeedState.update { it.copy(mode = mode) }
		applyCrossfeed()
	}

	fun setStereoEnhancementEnabled(enabled: Boolean) {
		_stereoEnhancementState.update { it.copy(enabled = enabled) }
		applyStereoEnhancement()
	}

	fun setStereoEnhancementLevel(level: Float) {
		_stereoEnhancementState.update { it.copy(level = level) }
		// Continuous slider drag -- debounced, see requestStereoEnhancementApply()'s kdoc above.
		requestStereoEnhancementApply()
	}

	fun setVacuumTubeEnabled(enabled: Boolean) {
		_vacuumTubeState.update { it.copy(enabled = enabled) }
		applyVacuumTube()
	}

	fun setVacuumTubeDrive(driveDb: Float) {
		_vacuumTubeState.update { it.copy(driveDb = driveDb) }
		// Continuous slider drag -- debounced, see requestVacuumTubeApply()'s kdoc above.
		requestVacuumTubeApply()
	}

	fun setGraphicEqEnabled(enabled: Boolean) {
		_graphicEqState.update { it.copy(enabled = enabled) }
		applyGraphicEq()
	}

	/**
	 * Applies (and enables) a device correction curve fetched by
	 * [paige.navic.domain.repositories.AutoEqRepository.fetchCurve]. [curveString] is passed to
	 * the native `setGraphicEq` JNI call exactly as fetched -- libjamesdsp's
	 * `ArbitraryEqString2SortedNodes` parser (androidApp/src/main/cpp/libjamesdsp/.../arbEqConv.c
	 * + generalDSP/ArbFIRGen.c) scans the string for numeric freq/gain tokens and skips anything
	 * else character-by-character, so AutoEq's "GraphicEQ: " prefix (and the semicolons) are
	 * harmless noise to it -- no reformatting needed.
	 */
	fun setGraphicEqCurve(deviceName: String, measurementSource: String, curveString: String) {
		_graphicEqState.update {
			it.copy(
				enabled = true,
				deviceName = deviceName,
				measurementSource = measurementSource,
				curveString = curveString
			)
		}
		applyGraphicEq()
	}

	private fun applyEqualizer() {
		val h = handle
		if (h == 0L) return
		val current = _equalizerState.value
		// Native contract (see JamesDspWrapper.cpp's setMultiEqualizer): a flat 30-element array,
		// first 15 = band center frequencies (fixed), last 15 = per-band gains in dB.
		val bands = DoubleArray(30)
		EqualizerBandFrequenciesHz.forEachIndexed { i, hz -> bands[i] = hz }
		current.bandGainsDb.forEachIndexed { i, db -> bands[15 + i] = db.toDouble() }
		val success = JamesDspWrapper.setMultiEqualizer(h, current.enabled, current.filterType, current.interpolationMode, bands)
		Logger.i(
			TAG,
			"setMultiEqualizer(enabled=${current.enabled}, filterType=${current.filterType}, " +
				"interpolationMode=${current.interpolationMode}) -> success=$success"
		)
	}

	private fun applyCompander() {
		val h = handle
		if (h == 0L) return
		val current = _companderState.value
		// Native contract: flat 14-element array, first 7 = band frequencies (fixed), last 7 =
		// per-band target gains in dB.
		val bands = DoubleArray(14)
		CompanderBandFrequenciesHz.forEachIndexed { i, hz -> bands[i] = hz }
		current.bandGainsDb.forEachIndexed { i, db -> bands[7 + i] = db.toDouble() }
		val success = JamesDspWrapper.setCompander(
			h, current.enabled, current.timeConstantSec, current.granularity, current.tfTransform, bands
		)
		Logger.i(
			TAG,
			"setCompander(enabled=${current.enabled}, timeConstant=${current.timeConstantSec}, " +
				"granularity=${current.granularity}, tfTransform=${current.tfTransform}) -> success=$success"
		)
	}

	private fun applyReverb() {
		val h = handle
		if (h == 0L) return
		val current = _reverbState.value
		val success = JamesDspWrapper.setReverb(h, current.enabled, current.preset)
		Logger.i(TAG, "setReverb(enabled=${current.enabled}, preset=${current.preset}) -> success=$success")
	}

	private fun applyCrossfeed() {
		val h = handle
		if (h == 0L) return
		val current = _crossfeedState.value
		// customFcut/customFeed only matter for mode==99 (custom BS2B params), which Navic's UI
		// doesn't expose yet — always pass 0 for the six fixed built-in modes.
		val success = JamesDspWrapper.setCrossfeed(h, current.enabled, current.mode, 0, 0)
		Logger.i(TAG, "setCrossfeed(enabled=${current.enabled}, mode=${current.mode}) -> success=$success")
	}

	private fun applyStereoEnhancement() {
		val h = handle
		if (h == 0L) return
		val current = _stereoEnhancementState.value
		val success = JamesDspWrapper.setStereoEnhancement(h, current.enabled, current.level)
		Logger.i(TAG, "setStereoEnhancement(enabled=${current.enabled}, level=${current.level}) -> success=$success")
	}

	private fun applyVacuumTube() {
		val h = handle
		if (h == 0L) return
		val current = _vacuumTubeState.value
		val success = JamesDspWrapper.setVacuumTube(h, current.enabled, current.driveDb)
		Logger.i(TAG, "setVacuumTube(enabled=${current.enabled}, driveDb=${current.driveDb}) -> success=$success")
	}

	private fun applyGraphicEq() {
		val h = handle
		if (h == 0L) return
		val current = _graphicEqState.value
		val curve = current.curveString
		val success = if (curve.isNullOrBlank()) {
			JamesDspWrapper.setGraphicEq(h, false, "")
		} else {
			JamesDspWrapper.setGraphicEq(h, current.enabled, curve)
		}
		Logger.i(
			TAG,
			"setGraphicEq(enabled=${current.enabled}, device=${current.deviceName}, " +
				"source=${current.measurementSource}) -> success=$success"
		)
	}
}

/**
 * Media3 [AudioProcessor] that routes decoded PCM through the native
 * JamesDSP engine (libjamesdsp via libjamesdsp-wrapper, see
 * androidApp/src/main/cpp) before it reaches the [android.media.AudioTrack].
 *
 * [BassBoostController] (Phase 1) and [SoundEffectsController] (Phase 2) drive the rest of the
 * effect suite; the whole DSP graph runs on every call to [JamesDspWrapper.processFloat]
 * regardless of which individual effects are enabled, so adding more controls doesn't require
 * touching this class's audio-routing logic, just adding more setX calls upstream in those
 * controllers. The one exception is [SoundEffectsController.chainEnabled] (the master switch),
 * which this class checks directly to bypass the native call entirely — see that property's
 * kdoc.
 *
 * ## Why this operates on 16-bit PCM, not float, despite `processFloat()`
 *
 * The natural design — negotiate [C.ENCODING_PCM_FLOAT] end to end via
 * `DefaultRenderersFactory.setEnableAudioFloatOutput(true)` /
 * `DefaultAudioSink.Builder.setEnableFloatOutput(true)`, matching how
 * RootlessJamesDSP's original AudioProcessor is wired — does not work on
 * Media3 1.11.0's actual `DefaultAudioSink`. Its `configure()` only splices
 * a custom `AudioProcessorChain` (set via `Builder.setAudioProcessorChain`)
 * into the pipeline when `shouldUseFloatOutput(pcmEncoding)` is *false*; the
 * moment the decoder produces high-resolution/float PCM (exactly what
 * `setEnableFloatOutput(true)` causes), the whole custom chain — this
 * processor included — is skipped in favor of a bare `ToFloatPcmAudioProcessor`,
 * and `onConfigure()` is simply never called. (Confirmed by reading
 * `DefaultAudioSink.configure()` directly, androidx/media tag `1.11.0`: see
 * the `if (shouldUseFloatOutput(...))` branch around line 756 — the custom
 * chain is only added in the `else` branch.) This is also why
 * `SonicAudioProcessor` — the existing playback-speed/pitch processor — only
 * ever runs against 16-bit PCM in this codebase: per that same file's
 * `shouldApplyAudioProcessorPlaybackParameters()`, "SonicAudioProcessor
 * outputs 16-bit integer PCM" is treated as a hard constraint, not an
 * incidental detail.
 *
 * So this processor stays on the standard (non-float-output) int16 pipeline
 * — same as speed/pitch already does — and instead does its own int16 <->
 * float conversion around the native [JamesDspWrapper.processFloat] call.
 * Input and output [AudioProcessor.AudioFormat] are both
 * [C.ENCODING_PCM_16BIT]; only the bytes flowing between them differ.
 */
@UnstableApi
class JamesDspAudioProcessor : BaseAudioProcessor() {

	// @Volatile is load-bearing, not defensive: start()/release() run on the main thread (from
	// PlaybackService.onCreate()/onDestroy()), while onConfigure()/queueInput() run on ExoPlayer's
	// internal audio-processing thread. Without a memory barrier, the audio thread can observe a
	// stale handle == 0L indefinitely after start() has already set it on the main thread — which
	// is exactly what happened here: onConfigure() and queueInput() were firing the whole time,
	// but every `if (handle != 0L)` branch (and therefore every log line and every native
	// processFloat() call) was silently skipped because of this missing visibility guarantee.
	// BassBoostController's own `handle` field (above) already got this right; this one didn't.
	@Volatile
	private var handle: JamesDspHandle = 0L

	// Reused across queueInput() calls to avoid a per-buffer allocation on the audio thread.
	private var floatInput = FloatArray(0)
	private var floatOutput = FloatArray(0)

	// Logged only once per queueInput() burst (throttled) so this doesn't flood logcat during
	// normal playback while still giving Phase 1 verification something to grep for.
	private var processedBufferCount = 0L

	/** Allocates the native handle. Call once, after the ExoPlayer/AudioSink has been built. */
	fun start() {
		if (handle != 0L) return
		val newHandle = JamesDspWrapper.alloc(NoopJamesDspCallbacks())
		if (newHandle == 0L || !JamesDspWrapper.isHandleValid(newHandle)) {
			Logger.e(TAG, "alloc() failed to produce a valid handle (got $newHandle)")
			return
		}
		handle = newHandle
		Logger.i(TAG, "native handle allocated: $handle (this=$this)")
		BassBoostController.onHandleAllocated(handle)
		SoundEffectsController.onHandleAllocated(handle)
	}

	/** Frees the native handle. Call from the service's onDestroy(). */
	fun release() {
		if (handle == 0L) return
		Logger.i(TAG, "freeing native handle: $handle")
		BassBoostController.onHandleReleased()
		SoundEffectsController.onHandleReleased()
		JamesDspWrapper.free(handle)
		handle = 0L
	}

	@Throws(AudioProcessor.UnhandledAudioFormatException::class)
	override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
		// TEMP diagnostic (Phase 1 verification): unconditionally log every onConfigure() call,
		// before the encoding check, so we can see this fire even if it's about to throw.
		Logger.i(TAG, "onConfigure() called: encoding=${inputAudioFormat.encoding}, sampleRate=${inputAudioFormat.sampleRate}, channels=${inputAudioFormat.channelCount}, this=$this, handle=$handle")
		if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
			// See the class kdoc: this processor deliberately stays on the standard int16 pipeline
			// (same one SonicAudioProcessor/speed-pitch already uses) rather than negotiating float
			// output at the AudioSink level, which silently drops custom AudioProcessorChains on
			// Media3 1.11.0. Refuse anything else rather than silently skipping native processing.
			throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
		}
		if (handle != 0L) {
			Logger.i(TAG, "onConfigure: about to call native setSamplingRate(handle=$handle, rate=${inputAudioFormat.sampleRate})")
			try {
				JamesDspWrapper.setSamplingRate(handle, inputAudioFormat.sampleRate.toFloat(), true)
				Logger.i(TAG, "onConfigure: setSamplingRate(${inputAudioFormat.sampleRate}), channels=${inputAudioFormat.channelCount}")
			} catch (t: Throwable) {
				Logger.e(TAG, "onConfigure: setSamplingRate() threw: $t")
				throw t
			}
		}
		// Same encoding in and out — only the bytes are transformed (int16 -> float -> native DSP
		// -> float -> int16), the rest of the AudioSink pipeline (Sonic, etc.) is unaffected.
		return inputAudioFormat
	}

	override fun queueInput(inputBuffer: ByteBuffer) {
		val remaining = inputBuffer.remaining()
		if (remaining <= 0) return

		val h = handle
		if (h == 0L || !SoundEffectsController.chainEnabled) {
			// Either alloc() hasn't produced a handle yet (or failed), or the master DSP switch is
			// off (see SoundEffectsController.chainEnabled's kdoc) — pass audio through untouched
			// rather than dropping it or running the native chain for nothing.
			val output = replaceOutputBuffer(remaining)
			output.put(inputBuffer)
			output.flip()
			return
		}

		val sampleCount = remaining / 2 // 2 bytes per 16-bit sample
		if (floatInput.size < sampleCount) {
			floatInput = FloatArray(sampleCount)
			floatOutput = FloatArray(sampleCount)
		}

		val inShorts = inputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()
		for (i in 0 until sampleCount) {
			floatInput[i] = inShorts.get(i) / 32768f
		}
		inputBuffer.position(inputBuffer.position() + sampleCount * 2)

		JamesDspWrapper.processFloat(h, floatInput, floatOutput, 0, sampleCount)

		val output = replaceOutputBuffer(sampleCount * 2)
		output.order(ByteOrder.nativeOrder())
		val outShorts = output.asShortBuffer()
		for (i in 0 until sampleCount) {
			val scaled = floatOutput[i] * 32768f
			val clamped = scaled.coerceIn(-32768f, 32767f)
			outShorts.put(i, clamped.toInt().toShort())
		}
		output.position(output.position() + sampleCount * 2)
		output.flip()

		// Rate-limited (Phase 1 verification aid, see report) — confirms every queueInput() burst
		// is actually reaching the native processFloat() call during real playback, without
		// flooding logcat (typical buffer is a few ms of audio, so this fires many times/sec).
		processedBufferCount++
		if (processedBufferCount % 200 == 1L) {
			Logger.i(TAG, "processFloat: buffer #$processedBufferCount, sampleCount=$sampleCount")
		}
	}
}
