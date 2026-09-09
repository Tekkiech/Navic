package paige.navic.shared.dsp

/**
 * Phase 2 UI state models for the native JamesDSP effect chain, one per effect (mirrors
 * [BassBoostUiState] from Phase 1). Parameter ranges/defaults below are taken directly from
 * timschneeb/RootlessJamesDSP's own preference definitions (`dsp_*_preferences.xml`,
 * `arrays.xml`, `Constants.kt`) so Navic's UI matches the real app's conventions rather than
 * inventing arbitrary values.
 *
 * Lives in commonMain (unlike the native wrapper itself) so the shared
 * [paige.navic.shared.MediaPlayerViewModel] contract and the Compose UI in commonMain can
 * reference it without depending on androidMain.
 */

/** Whether the entire native DSP chain is active. When false, audio is passed through untouched. */
data class DspMasterUiState(
	val enabled: Boolean = true
)

// 15-band graphic-style multi-equalizer. Frequencies are fixed (matches upstream's default EQ
// band layout); only gains are user-adjustable. filterType/interpolationMode map to
// eq_filter_types/eq_interpolators in upstream's arrays.xml.
val EqualizerBandFrequenciesHz: List<Double> = listOf(
	25.0, 40.0, 63.0, 100.0, 160.0, 250.0, 400.0, 630.0,
	1000.0, 1600.0, 2500.0, 4000.0, 6300.0, 10000.0, 16000.0
)

data class EqualizerUiState(
	val enabled: Boolean = false,
	/** 0=FIR Minimum phase, 1=IIR 4th order, 2=IIR 6th order, 3=IIR 8th order, 4=IIR 10th order, 5=IIR 12th order */
	val filterType: Int = 0,
	/** 0=PCHIP (Piecewise Cubic Hermite), 1=Modified Hiroshi Akima spline */
	val interpolationMode: Int = 0,
	val bandGainsDb: List<Float> = List(EqualizerBandFrequenciesHz.size) { 0f }
)

// 7-band compander (dynamic range compressor/expander). Frequencies are fixed; gains are
// per-band target levels in dB.
val CompanderBandFrequenciesHz: List<Double> = listOf(95.0, 200.0, 400.0, 800.0, 1600.0, 3400.0, 7500.0)

data class CompanderUiState(
	val enabled: Boolean = false,
	val timeConstantSec: Float = 0.22f,
	/** Integer 0..4, higher = finer-grained (slower) time/frequency resolution tradeoff. */
	val granularity: Int = 0,
	/** 0=Uniform (STFT), 1=Multiresolution (wavelet), 2=Pseudo multires (undersampling), 3=Pseudo multires (time domain) */
	val tfTransform: Int = 0,
	val bandGainsDb: List<Float> = List(CompanderBandFrequenciesHz.size) { 0f }
)

data class ReverbUiState(
	val enabled: Boolean = false,
	/** Native preset index (not contiguous — matches upstream's reverb_presets_values). Default 15 = "Plate high". */
	val preset: Int = 15
)

data class CrossfeedUiState(
	val enabled: Boolean = false,
	/** 0=BS2B Weak, 1=BS2B Strong, 2=Out of head, 3=Surround 1, 4=Surround 2, 5=Realistic surround */
	val mode: Int = 5
)

data class StereoEnhancementUiState(
	val enabled: Boolean = false,
	/** 30..75, upstream's "wideness" range. */
	val level: Float = 60f
)

data class VacuumTubeUiState(
	val enabled: Boolean = false,
	/** -3..12 dB drive. */
	val driveDb: Float = 2f
)

/**
 * Phase 3: device-specific frequency-response correction, applied via the native
 * `setGraphicEq` JNI call using a curve fetched on demand from the AutoEq project (see
 * [paige.navic.domain.repositories.AutoEqRepository]). Unlike the other effects above, this one
 * has no fixed parameter set -- [curveString] is arbitrary "freq gain; freq gain; ..." text,
 * verbatim from whichever AutoEq file the user picked.
 */
data class GraphicEqUiState(
	val enabled: Boolean = false,
	val deviceName: String? = null,
	val measurementSource: String? = null,
	val curveString: String? = null
)
