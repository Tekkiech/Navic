package paige.navic.domain.manager

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import paige.navic.domain.models.settings.EqualiserConfig
import paige.navic.util.core.Logger
import kotlin.time.Duration.Companion.milliseconds

class EqualiserManager(
	private val preferences: DataStore<Preferences>
) {
	private val json = Json
	private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

	val config = preferenceStateFlow {
		return@preferenceStateFlow try {
			json.decodeFromString<EqualiserConfig>(
				it[KEY_CONFIG] ?: return@preferenceStateFlow EqualiserConfig()
			)
		} catch(ex: SerializationException) {
			Logger.e("EqualiserManager", "failed to deserialise config", ex)
			EqualiserConfig()
		} catch(ex: Exception) {
			Logger.e("EqualiserManager", "failed to read config", ex)
			EqualiserConfig()
		}
	}

	// EqualiserScreen's per-band VerticalSlider drags used to call setConfig() on every raw drag
	// tick. setConfig() isn't a plain in-memory write -- it JSON-encodes the *entire* config
	// (all band levels) and commits it through Jetpack DataStore, which does an atomic
	// write-to-temp-file-then-rename on disk per call. Doing that on every single drag tick was
	// real per-tick file I/O jank, the same class of bug as the native/IPC slider calls fixed
	// elsewhere in this app, just backed by disk instead of JNI/Binder.
	//
	// Fix: dragging updates `_liveConfig` (a plain in-memory StateFlow) so the UI can render the
	// live value instantly, while the actual DataStore write is debounced to ~30ms of drag
	// quiescence via collectLatest+delay. `displayConfig` is what the UI should read: it shows
	// `_liveConfig` while a drag is in flight/pending, and falls back to the persisted `config`
	// once the write completes and the persisted flow catches up (see the collector below, which
	// clears the override so `config` -- the real source of truth -- takes over again).
	private val _liveConfig = MutableStateFlow<EqualiserConfig?>(null)
	private val pendingConfigWrite = MutableStateFlow<EqualiserConfig?>(null)

	val displayConfig: StateFlow<EqualiserConfig> = combine(config, _liveConfig) { persisted, live ->
		live ?: persisted
	}.stateIn(scope = scope, started = SharingStarted.Eagerly, initialValue = EqualiserConfig())

	init {
		scope.launch {
			// Once the persisted config catches up to whatever was last dragged, drop the live
			// override so `config` is the single source of truth again (handles both the
			// debounced write completing and any external change to `config`).
			config.collect { persisted ->
				if (_liveConfig.value == persisted) _liveConfig.value = null
			}
		}
		scope.launch {
			pendingConfigWrite.filterNotNull().collectLatest { value ->
				delay(30.milliseconds)
				setConfig(value)
			}
		}
	}

	/** Continuous slider drag -- updates the live display immediately, debounces the disk write. */
	fun setConfigDebounced(value: EqualiserConfig) {
		_liveConfig.value = value
		pendingConfigWrite.value = value
	}

	suspend fun setConfig(value: EqualiserConfig) {
		try {
			preferences.edit { it[KEY_CONFIG] = json.encodeToString(value) }
		} catch (ex: SerializationException) {
			Logger.e("EqualiserManager", "failed to serialise config", ex)
		} catch (ex: Exception) {
			Logger.e("EqualiserManager", "failed to save config", ex)
		}
	}

	private inline fun <T> preferenceStateFlow(crossinline transform: (Preferences) -> T): StateFlow<T> {
		return preferences.data
			.map(transform)
			.stateIn(
				scope = scope,
				started = SharingStarted.Eagerly,
				initialValue = transform(emptyPreferences())
			)
	}

	private companion object {
		val KEY_CONFIG = stringPreferencesKey("equaliser_config")
	}
}
