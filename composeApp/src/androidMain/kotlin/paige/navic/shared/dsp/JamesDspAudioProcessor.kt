package paige.navic.shared.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import paige.navic.util.core.Logger
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
		apply()
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
 * Media3 [AudioProcessor] that routes decoded PCM through the native
 * JamesDSP engine (libjamesdsp via libjamesdsp-wrapper, see
 * androidApp/src/main/cpp) before it reaches the [android.media.AudioTrack].
 *
 * Phase 1 only drives Bass Boost through [BassBoostController]; the ported
 * JamesDspWrapper.cpp already exports many more setX effect entry points
 * (setMultiEqualizer, setCompander, setReverb, setConvolver, ...) for Phase 2
 * to wire up here — the whole DSP graph runs on every call to
 * [JamesDspWrapper.processFloat] regardless of which individual effects are
 * enabled, so adding more controls doesn't require touching this class's
 * audio-routing logic, just adding more setX calls somewhere upstream.
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
	}

	/** Frees the native handle. Call from the service's onDestroy(). */
	fun release() {
		if (handle == 0L) return
		Logger.i(TAG, "freeing native handle: $handle")
		BassBoostController.onHandleReleased()
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
		if (h == 0L) {
			// alloc() hasn't produced a handle yet (or failed) — pass audio through untouched
			// rather than dropping it.
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
