package paige.navic.shared.dsp

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import paige.navic.util.core.Logger

private const val TAG = "JamesDsp"

/**
 * Builds ExoPlayer's audio sink with [audioProcessor] spliced into the
 * sink's processor chain, so decoded audio is routed through the native
 * JamesDSP engine before reaching the [android.media.AudioTrack].
 * Everything else (video, text, etc.) is left at [DefaultRenderersFactory]
 * defaults.
 *
 * Deliberately does NOT call `setEnableAudioFloatOutput(true)` — see the
 * kdoc on [JamesDspAudioProcessor] for why forcing float output at the sink
 * level would actually cause Media3 1.11.0's `DefaultAudioSink` to *skip*
 * this whole custom processor chain (including this app's existing
 * SonicAudioProcessor-based playback speed/pitch feature). Standard int16
 * PCM output — [DefaultRenderersFactory]'s default — is what both speed/
 * pitch and this DSP processor actually run against; [audioProcessor] does
 * its own int16 <-> float conversion around the native call instead.
 */
@UnstableApi
class JamesDspRenderersFactory(
	context: Context,
	private val audioProcessor: JamesDspAudioProcessor
) : DefaultRenderersFactory(context) {

	override fun buildAudioSink(
		context: Context,
		enableFloatOutput: Boolean,
		enableAudioTrackPlaybackParams: Boolean
	): AudioSink {
		// TEMP diagnostic (Phase 1 verification): confirm this override actually fires and see
		// what enableFloatOutput arrives as.
		Logger.i(TAG, "buildAudioSink() called, enableFloatOutput=$enableFloatOutput, audioProcessor=$audioProcessor")
		return DefaultAudioSink.Builder(context)
			.setAudioProcessorChain(DefaultAudioSink.DefaultAudioProcessorChain(audioProcessor))
			.setEnableFloatOutput(enableFloatOutput)
			.setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
			.build()
	}
}
