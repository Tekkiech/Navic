package paige.navic.domain.repositories

import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLPath
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import navic.composeapp.generated.resources.Res
import paige.navic.domain.models.autoeq.AutoEqDevicePreset
import paige.navic.util.core.Logger

private const val TAG = "AutoEqRepository"

// AutoEq (jaakkopasanen/AutoEq on GitHub, MIT licensed -- compatible with Navic's GPL-3.0) is
// the community-standard source for device correction curves. Only the raw text is fetched
// here; the bundled index (see AutoEqDevicePreset's kdoc) is what makes search possible without
// shipping all ~8,830 curve files.
private const val RAW_BASE_URL = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/"
private const val INDEX_RESOURCE_PATH = "files/autoeq_index.json"
private const val CACHE_KEY_PREFIX = "autoeq_curve:"

/**
 * Search + fetch layer for Navic's AutoEq device-preset picker (Phase 3). Follows
 * [LyricsRepository]'s pattern: its own [HttpClient] for a small number of one-off GETs against
 * a public API, and [Settings] (same key-value store [paige.navic.domain.manager.SessionManager]
 * uses for credentials) as a persistent cache, rather than pulling in Room for what's fundamentally
 * a handful of small string blobs keyed by path.
 *
 * There's no native/DSP code here -- this repository only knows how to search the bundled index
 * and fetch+cache raw GraphicEQ curve text. Applying a curve to the native JamesDSP handle is
 * [paige.navic.shared.MediaPlayerViewModel.applyGraphicEqCurve]'s job (see
 * `SoundEffectsController` on androidMain for where that ends up).
 */
class AutoEqRepository(
	private val settings: Settings
) {
	private val client = HttpClient {
		install(HttpTimeout) {
			requestTimeoutMillis = 20000
			connectTimeoutMillis = 20000
			socketTimeoutMillis = 20000
		}
	}
	private val json = Json { ignoreUnknownKeys = true }

	private val indexMutex = Mutex()
	private var indexCache: List<AutoEqDevicePreset>? = null

	/**
	 * Loads (and memoizes for the process lifetime) the bundled device index. Cheap enough
	 * after the first call that the UI can hold the full list in memory and filter it
	 * reactively on every keystroke -- see the kdoc on [AutoEqDevicePreset].
	 */
	suspend fun loadIndex(): List<AutoEqDevicePreset> {
		indexCache?.let { return it }
		return indexMutex.withLock {
			indexCache?.let { return it }
			val bytes = Res.readBytes(INDEX_RESOURCE_PATH)
			val parsed = json.decodeFromString<List<AutoEqDevicePreset>>(bytes.decodeToString())
			indexCache = parsed
			Logger.i(TAG, "loaded AutoEq index: ${parsed.size} entries")
			parsed
		}
	}

	/**
	 * Fetches the raw "<device> GraphicEQ.txt" contents for [preset], preferring a previously
	 * cached copy (see [cacheKeyFor]) so re-selecting a device is instant and works offline.
	 * The returned string is exactly what upstream serves -- including its "GraphicEQ: " prefix
	 * -- since libjamesdsp's parser is prefix-agnostic (see setGraphicEqCurve's call site kdoc
	 * for why passing it through unmodified is safe).
	 */
	suspend fun fetchCurve(preset: AutoEqDevicePreset): Result<String> {
		val cacheKey = cacheKeyFor(preset)
		settings.getStringOrNull(cacheKey)?.let { cached ->
			Logger.i(TAG, "fetchCurve: cache hit for ${preset.deviceName} (${preset.measurementSource})")
			return Result.success(cached)
		}

		return try {
			val url = RAW_BASE_URL + preset.path.encodeURLPath(encodeSlash = false)
			val response = client.get(url)
			if (!response.status.isSuccess()) {
				return Result.failure(Exception("unsuccessful status code ${response.status.value} fetching $url"))
			}
			val body = response.bodyAsText()
			if (body.isBlank()) {
				return Result.failure(Exception("empty response body fetching $url"))
			}
			settings.putString(cacheKey, body)
			Logger.i(TAG, "fetchCurve: fetched + cached ${preset.deviceName} (${preset.measurementSource})")
			Result.success(body)
		} catch (ex: Exception) {
			Logger.w(TAG, "fetchCurve: failed for ${preset.deviceName}", ex)
			Result.failure(ex)
		}
	}

	/** True if [preset]'s curve has already been fetched and cached locally. */
	fun isCached(preset: AutoEqDevicePreset): Boolean =
		settings.hasKey(cacheKeyFor(preset))

	private fun cacheKeyFor(preset: AutoEqDevicePreset): String = CACHE_KEY_PREFIX + preset.path
}
