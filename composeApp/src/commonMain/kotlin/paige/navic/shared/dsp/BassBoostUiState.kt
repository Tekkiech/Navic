package paige.navic.shared.dsp

/**
 * Phase 1 proof-of-concept state for the native JamesDSP Bass Boost effect.
 * Lives in commonMain (unlike the native wrapper itself) so the shared
 * [paige.navic.shared.MediaPlayerViewModel] contract and the Compose UI in
 * commonMain can reference it without depending on androidMain.
 */
data class BassBoostUiState(
	val enabled: Boolean = false,
	val gainDb: Float = 6f
)
