package paige.navic.shared

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import paige.navic.domain.manager.ConnectivityManager
import paige.navic.domain.manager.DownloadManager
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.models.DomainExplicitStatus
import paige.navic.domain.models.DomainRadio
import paige.navic.domain.models.DomainSong
import paige.navic.domain.models.DomainSongCollection
import paige.navic.domain.models.settings.ExplicitContentPlayback
import paige.navic.domain.repositories.PlayerStateRepository
import paige.navic.domain.repositories.SongRepository
import paige.navic.shared.dsp.BassBoostUiState
import paige.navic.shared.dsp.CompanderUiState
import paige.navic.shared.dsp.CrossfeedUiState
import paige.navic.shared.dsp.DspMasterUiState
import paige.navic.shared.dsp.EqualizerUiState
import paige.navic.shared.dsp.GraphicEqUiState
import paige.navic.shared.dsp.ReverbUiState
import paige.navic.shared.dsp.StereoEnhancementUiState
import paige.navic.shared.dsp.VacuumTubeUiState
import paige.navic.ui.core.PlayerUiState
import kotlin.time.Duration.Companion.seconds

abstract class MediaPlayerViewModel(
	private val stateRepository: PlayerStateRepository,
	protected val songRepository: SongRepository,
	protected val connectivityManager: ConnectivityManager,
	protected val downloadManager: DownloadManager,
	protected val preferenceManager: PreferenceManager
) : ViewModel() {

	@Suppress("PropertyName")
	protected val _uiState = MutableStateFlow(PlayerUiState())
	val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

	// `progress` ticks every ~200ms during playback (and now on every drag tick -- see seek()'s
	// kdoc on the Android implementation). Screens that call `player.uiState.collectAsState()`
	// but never read `.progress` (album art, title/artist row, play/pause/shuffle/repeat buttons,
	// star/more buttons, technical info) were still recomposing on every one of those ticks,
	// because collectAsState()'s equality check is against the *whole* PlayerUiState data class --
	// a progress-only change still produces a `!=` instance, so every reader is invalidated
	// regardless of which field it actually uses.
	//
	// `uiStateIgnoringProgress` re-emits only when something other than `progress` changes, so
	// composables that don't care about playback position can collect this instead of `uiState`
	// and stop recomposing 5x/sec during ordinary playback. Screens that *do* need progress
	// (the progress bar itself, the elapsed/remaining time labels) should keep using `uiState`.
	val uiStateIgnoringProgress: StateFlow<PlayerUiState> = uiState
		.distinctUntilChangedBy { it.copy(progress = 0f) }
		.stateIn(viewModelScope, SharingStarted.Eagerly, _uiState.value)

	protected fun isExplicit(song: DomainSong): Boolean {
		return song.explicitStatus == DomainExplicitStatus.Explicit
			&& preferenceManager.explicitContentPlayback != ExplicitContentPlayback.Allowed
	}

	init {
		viewModelScope.launch {
			restoreState()
			observeAndSaveState()
		}
	}

	abstract fun addToQueueSingle(song: DomainSong, notify: Boolean = true)
	abstract fun addToQueue(collection: DomainSongCollection, notify: Boolean = true)
	abstract fun addToQueue(songs: List<DomainSong>, notify: Boolean = true)
	abstract fun removeFromQueue(index: Int)
	abstract fun moveQueueItem(fromIndex: Int, toIndex: Int)
	abstract fun clearQueue()
	abstract fun playAt(index: Int)
	abstract fun playNextSingle(song: DomainSong)
	abstract fun playNext(collection: DomainSongCollection)
	abstract fun playRadio(radio: DomainRadio)
	abstract fun pause()
	abstract fun resume()
	abstract fun seek(normalized: Float)
	abstract fun next()
	abstract fun previous()
	abstract fun toggleShuffle()
	abstract fun toggleRepeat()
	abstract fun shufflePlay(collection: DomainSongCollection)
	abstract fun setPlaybackSpeed(value: Float)
	abstract fun setPlaybackPitch(value: Float)

	// Phase 1 proof-of-concept for the native JamesDSP pipeline (see androidApp/src/main/cpp).
	// Android-only for now: iOS keeps the no-op defaults below until the native module grows an
	// iOS target. Deliberately not abstract, so this doesn't block the iOS actual implementation.
	open val bassBoostState: StateFlow<BassBoostUiState> = MutableStateFlow(BassBoostUiState()).asStateFlow()
	open fun setBassBoostEnabled(enabled: Boolean) {}
	open fun setBassBoostGain(gainDb: Float) {}

	// Phase 2: rest of the JamesDSP effect suite (Equalizer, Compander, Reverb, Crossfeed,
	// Stereo Enhancement, Vacuum Tube) plus a master enable switch for the whole chain. Same
	// not-abstract/no-op-default pattern as Bass Boost above, for the same reason (iOS has no
	// native module yet).
	open val dspMasterState: StateFlow<DspMasterUiState> = MutableStateFlow(DspMasterUiState()).asStateFlow()
	open fun setDspMasterEnabled(enabled: Boolean) {}

	open val equalizerState: StateFlow<EqualizerUiState> = MutableStateFlow(EqualizerUiState()).asStateFlow()
	open fun setEqualizerEnabled(enabled: Boolean) {}
	open fun setEqualizerFilterType(filterType: Int) {}
	open fun setEqualizerInterpolationMode(interpolationMode: Int) {}
	open fun setEqualizerBandGain(bandIndex: Int, gainDb: Float) {}
	open fun resetEqualizerBands() {}

	open val companderState: StateFlow<CompanderUiState> = MutableStateFlow(CompanderUiState()).asStateFlow()
	open fun setCompanderEnabled(enabled: Boolean) {}
	open fun setCompanderTimeConstant(seconds: Float) {}
	open fun setCompanderGranularity(granularity: Int) {}
	open fun setCompanderTfTransform(tfTransform: Int) {}
	open fun setCompanderBandGain(bandIndex: Int, gainDb: Float) {}
	open fun resetCompanderBands() {}

	open val reverbState: StateFlow<ReverbUiState> = MutableStateFlow(ReverbUiState()).asStateFlow()
	open fun setReverbEnabled(enabled: Boolean) {}
	open fun setReverbPreset(preset: Int) {}

	open val crossfeedState: StateFlow<CrossfeedUiState> = MutableStateFlow(CrossfeedUiState()).asStateFlow()
	open fun setCrossfeedEnabled(enabled: Boolean) {}
	open fun setCrossfeedMode(mode: Int) {}

	open val stereoEnhancementState: StateFlow<StereoEnhancementUiState> = MutableStateFlow(StereoEnhancementUiState()).asStateFlow()
	open fun setStereoEnhancementEnabled(enabled: Boolean) {}
	open fun setStereoEnhancementLevel(level: Float) {}

	open val vacuumTubeState: StateFlow<VacuumTubeUiState> = MutableStateFlow(VacuumTubeUiState()).asStateFlow()
	open fun setVacuumTubeEnabled(enabled: Boolean) {}
	open fun setVacuumTubeDrive(driveDb: Float) {}

	// Phase 3: device-preset (AutoEq GraphicEQ) correction curve. See GraphicEqUiState's kdoc.
	open val graphicEqState: StateFlow<GraphicEqUiState> = MutableStateFlow(GraphicEqUiState()).asStateFlow()
	open fun setGraphicEqEnabled(enabled: Boolean) {}
	open fun applyGraphicEqCurve(deviceName: String, measurementSource: String, curveString: String) {}

	fun playNow(song: DomainSong) {
		clearQueue()
		addToQueueSingle(song, notify = false)
		playAt(0)
		checkAndAutoFillQueue()
	}

	fun playNow(collection: DomainSongCollection, startIndex: Int = 0) {
		clearQueue()
		addToQueue(collection, notify = false)
		playAt(startIndex)
		checkAndAutoFillQueue()
	}

	fun playNow(songs: List<DomainSong>, startIndex: Int = 0) {
		clearQueue()
		addToQueue(songs, notify = false)
		playAt(startIndex)
		checkAndAutoFillQueue()
	}

	fun togglePlay() {
		if (!uiState.value.isPaused) {
			pause()
		} else {
			resume()
		}
	}

	abstract fun syncPlayerWithState(state: PlayerUiState)

	protected fun checkAndAutoFillQueue() {
		if (!preferenceManager.autoFillQueue) return

		val state = uiState.value
		if (state.queue.isEmpty()) return

		val remainingCount = state.queue.size - state.currentIndex

		if (remainingCount <= 1) {
			viewModelScope.launch {
				val randomSongs = songRepository.getRandomSongs(1)
				addToQueue(randomSongs, notify = false)
			}
		}
	}

	private suspend fun restoreState() {
		val savedState = stateRepository.state
			.filterNotNull()
			.firstOrNull()
			?.copy(isPaused = true, isLoading = false)
		if (savedState != null) {
			_uiState.value = savedState
			syncPlayerWithState(savedState)
			checkAndAutoFillQueue()
		}
	}

	@OptIn(FlowPreview::class)
	private fun observeAndSaveState() {
		viewModelScope.launch {
			uiState
				.distinctUntilChanged { old, new ->
					old.currentIndex == new.currentIndex &&
						old.queue == new.queue &&
						old.isPaused == new.isPaused &&
						old.repeatMode == new.repeatMode &&
						old.isShuffleEnabled == new.isShuffleEnabled
				}
				.collect { state ->
					stateRepository.setState(state)
				}
		}

		viewModelScope.launch {
			uiState
				.debounce(2.seconds)
				.collect { state ->
					stateRepository.setState(state)
				}
		}
	}
}
