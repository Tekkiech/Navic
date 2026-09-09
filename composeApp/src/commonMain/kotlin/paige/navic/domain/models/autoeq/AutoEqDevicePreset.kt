package paige.navic.domain.models.autoeq

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * One entry from Navic's bundled AutoEq (jaakkopasanen/AutoEq on GitHub, MIT licensed) device
 * index -- see `composeApp/src/commonMain/composeResources/files/autoeq_index.json`, generated
 * by `scripts/generate_autoeq_index.py`.
 *
 * The bundled index only carries these three fields (deliberately -- 8,830 devices' worth of
 * actual correction curves would be tens of MB, not worth shipping). [path] is fetched on
 * demand from AutoEq's raw GitHub content when a user selects a device; see
 * [paige.navic.domain.repositories.AutoEqRepository.fetchCurve].
 */
@Immutable
@Serializable
data class AutoEqDevicePreset(
	val deviceName: String,
	val measurementSource: String,
	/**
	 * Repo-relative path to the raw "<device name> GraphicEQ.txt" file, e.g.
	 * `results/Rtings/over-ear/Sony WH-1000XM4/Sony WH-1000XM4 GraphicEQ.txt`. Combine with
	 * AutoEq's raw-content base URL to fetch the actual curve text.
	 */
	val path: String
)
