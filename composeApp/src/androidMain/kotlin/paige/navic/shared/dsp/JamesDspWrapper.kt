package paige.navic.shared.dsp

/**
 * JNI bridge to the native JamesDSP engine (libjamesdsp + libjamesdsp-wrapper,
 * built from androidApp/src/main/cpp).
 *
 * This mirrors the real API surface of timschneeb/RootlessJamesDSP's
 * `me.timschneeberger.rootlessjamesdsp.interop.JamesDspWrapper`, ported to
 * Navic's package. Phase 1 only wires up Bass Boost end-to-end; the other
 * `setX` effect functions are declared because the compiled wrapper .so
 * exports them (JamesDspWrapper.cpp is ported largely unmodified), but
 * nothing in Navic calls them yet — that's Phase 2 scope.
 */
typealias JamesDspHandle = Long

object JamesDspWrapper {
	external fun alloc(callbacks: JamesDspCallbacks): JamesDspHandle
	external fun free(self: JamesDspHandle)
	external fun isHandleValid(self: JamesDspHandle): Boolean

	external fun processFloat(
		self: JamesDspHandle,
		input: FloatArray,
		output: FloatArray,
		offset: Int = -1,
		length: Int = -1
	)

	external fun setSamplingRate(self: JamesDspHandle, sampleRate: Float, forceRefresh: Boolean)
	external fun setBassBoost(self: JamesDspHandle, enable: Boolean, maxGain: Float): Boolean

	// --- Declared for parity with the ported native wrapper; unused in Phase 1. ---
	external fun setStereoEnhancement(self: JamesDspHandle, enable: Boolean, level: Float): Boolean
	external fun setVacuumTube(self: JamesDspHandle, enable: Boolean, level: Float): Boolean
	external fun setPostGain(self: JamesDspHandle, gain: Float): Boolean
	external fun setLimiter(self: JamesDspHandle, threshold: Float, release: Float): Boolean
	external fun setReverb(self: JamesDspHandle, enable: Boolean, preset: Int): Boolean
	external fun setCrossfeed(self: JamesDspHandle, enable: Boolean, mode: Int, customFcut: Int, customFeed: Int): Boolean
	external fun setVdc(self: JamesDspHandle, enable: Boolean, vdcContents: String): Boolean
	external fun setGraphicEq(self: JamesDspHandle, enable: Boolean, graphicEq: String): Boolean
	external fun setMultiEqualizer(
		self: JamesDspHandle,
		enable: Boolean,
		filterType: Int,
		interpolationMode: Int,
		bands: DoubleArray
	): Boolean
	external fun setCompander(
		self: JamesDspHandle,
		enable: Boolean,
		timeConstant: Float,
		granularity: Int,
		tfresolution: Int,
		bands: DoubleArray
	): Boolean
	external fun setConvolver(
		self: JamesDspHandle,
		enable: Boolean,
		impulseResponse: FloatArray,
		irChannels: Int,
		irFrames: Int
	): Boolean
	external fun setLiveprog(self: JamesDspHandle, enable: Boolean, id: String, liveprogContent: String): Boolean

	interface JamesDspCallbacks {
		fun onLiveprogOutput(message: String)
		fun onLiveprogExec(id: String)
		fun onLiveprogResult(resultCode: Int, id: String, errorMessage: String?)
		fun onVdcParseError()

		// Convolver isn't wired up in Phase 1; stub with a no-op default so
		// callers don't need to implement a full error-code sealed type yet.
		fun onConvolverParseError() {}
	}

	init {
		System.loadLibrary("jamesdsp-wrapper")
	}
}

/** No-op implementation — Phase 1 doesn't use Liveprog/VDC/Convolver, only Bass Boost. */
class NoopJamesDspCallbacks : JamesDspWrapper.JamesDspCallbacks {
	override fun onLiveprogOutput(message: String) = Unit
	override fun onLiveprogExec(id: String) = Unit
	override fun onLiveprogResult(resultCode: Int, id: String, errorMessage: String?) = Unit
	override fun onVdcParseError() = Unit
}
