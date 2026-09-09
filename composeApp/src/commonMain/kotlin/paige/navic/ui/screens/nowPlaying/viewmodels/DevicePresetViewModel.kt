package paige.navic.ui.screens.nowPlaying.viewmodels

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import paige.navic.domain.models.autoeq.AutoEqDevicePreset
import paige.navic.domain.repositories.AutoEqRepository

// Keeps the results list readable and the LazyColumn cheap to lay out; a query specific enough
// to be useful (e.g. "AirPods Max") narrows well below this anyway. Same instinct as
// SearchScreen's `songs.take(10)` for its inline preview sections.
private const val MaxResults = 150

/**
 * Backs [paige.navic.ui.screens.nowPlaying.DevicePresetScreen] (Phase 3's searchable AutoEq
 * device picker). Owns search-as-you-type over the bundled index and the fetch-on-select flow;
 * deliberately has no dependency on [paige.navic.shared.MediaPlayerViewModel] -- applying a
 * fetched curve to the native DSP handle is the caller's job (see [selectPreset]'s `onApplied`
 * callback), keeping this class testable without a player.
 */
class DevicePresetViewModel(
	private val autoEqRepository: AutoEqRepository
) : ViewModel() {

	sealed interface FetchState {
		data object Idle : FetchState
		data class Loading(val preset: AutoEqDevicePreset) : FetchState
		data class Error(val preset: AutoEqDevicePreset) : FetchState
	}

	val query = TextFieldState()

	val indexReady: StateFlow<Boolean>
		field = MutableStateFlow(false)

	val results: StateFlow<List<AutoEqDevicePreset>>
		field = MutableStateFlow<List<AutoEqDevicePreset>>(emptyList())

	val fetchState: StateFlow<FetchState>
		field = MutableStateFlow<FetchState>(FetchState.Idle)

	private var indexEntries: List<AutoEqDevicePreset> = emptyList()

	init {
		viewModelScope.launch {
			indexEntries = autoEqRepository.loadIndex()
			indexReady.value = true

			snapshotFlow { query.text.toString() }
				.collectLatest { text -> results.value = filterIndex(text) }
		}
	}

	private fun filterIndex(rawQuery: String): List<AutoEqDevicePreset> {
		val q = rawQuery.trim()
		if (q.isEmpty()) return emptyList()
		return indexEntries
			.asSequence()
			.filter { it.deviceName.contains(q, ignoreCase = true) }
			.sortedWith(compareBy({ it.deviceName.lowercase() }, { it.measurementSource }))
			.take(MaxResults)
			.toList()
	}

	/**
	 * Fetches (or reuses a locally cached copy of, see [AutoEqRepository.fetchCurve]) [preset]'s
	 * correction curve, and invokes [onApplied] with the raw curve text on success so the caller
	 * can push it into the native DSP chain.
	 */
	fun selectPreset(preset: AutoEqDevicePreset, onApplied: (curveString: String) -> Unit) {
		viewModelScope.launch {
			fetchState.value = FetchState.Loading(preset)
			autoEqRepository.fetchCurve(preset).fold(
				onSuccess = { curve ->
					fetchState.value = FetchState.Idle
					onApplied(curve)
				},
				onFailure = {
					fetchState.value = FetchState.Error(preset)
				}
			)
		}
	}

	fun retryFetch(onApplied: (curveString: String) -> Unit) {
		val failed = (fetchState.value as? FetchState.Error)?.preset ?: return
		selectPreset(failed, onApplied)
	}

	fun dismissError() {
		fetchState.value = FetchState.Idle
	}
}
