package paige.navic.ui.screens.nowPlaying

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.capsule.ContinuousRoundedRectangle
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_reset
import navic.composeapp.generated.resources.compander_tf_multires_wavelet
import navic.composeapp.generated.resources.compander_tf_pseudo_time_domain
import navic.composeapp.generated.resources.compander_tf_pseudo_undersampling
import navic.composeapp.generated.resources.compander_tf_uniform
import navic.composeapp.generated.resources.crossfeed_mode_bs2b_strong
import navic.composeapp.generated.resources.crossfeed_mode_bs2b_weak
import navic.composeapp.generated.resources.crossfeed_mode_out_of_head
import navic.composeapp.generated.resources.crossfeed_mode_realistic_surround
import navic.composeapp.generated.resources.crossfeed_mode_surround_1
import navic.composeapp.generated.resources.crossfeed_mode_surround_2
import navic.composeapp.generated.resources.eq_filter_type_fir_minimum
import navic.composeapp.generated.resources.eq_filter_type_iir_10
import navic.composeapp.generated.resources.eq_filter_type_iir_12
import navic.composeapp.generated.resources.eq_filter_type_iir_4
import navic.composeapp.generated.resources.eq_filter_type_iir_6
import navic.composeapp.generated.resources.eq_filter_type_iir_8
import navic.composeapp.generated.resources.eq_interpolation_akima
import navic.composeapp.generated.resources.eq_interpolation_pchip
import navic.composeapp.generated.resources.label_compander
import navic.composeapp.generated.resources.label_compander_bands
import navic.composeapp.generated.resources.label_compander_granularity
import navic.composeapp.generated.resources.label_compander_resolution
import navic.composeapp.generated.resources.label_compander_time_constant
import navic.composeapp.generated.resources.label_crossfeed
import navic.composeapp.generated.resources.label_crossfeed_mode
import navic.composeapp.generated.resources.label_device_preset_change
import navic.composeapp.generated.resources.label_device_preset_none_selected
import navic.composeapp.generated.resources.label_dsp_master_enable
import navic.composeapp.generated.resources.label_eq_bands
import navic.composeapp.generated.resources.label_eq_filter_type
import navic.composeapp.generated.resources.label_eq_interpolation
import navic.composeapp.generated.resources.label_equalizer
import navic.composeapp.generated.resources.label_reverb
import navic.composeapp.generated.resources.label_reverb_room_type
import navic.composeapp.generated.resources.label_stereo_enhancement
import navic.composeapp.generated.resources.label_stereo_enhancement_level
import navic.composeapp.generated.resources.label_vacuum_tube
import navic.composeapp.generated.resources.label_vacuum_tube_drive
import navic.composeapp.generated.resources.option_device_preset
import navic.composeapp.generated.resources.option_sound_effects
import navic.composeapp.generated.resources.reverb_preset_default
import navic.composeapp.generated.resources.reverb_preset_large_hall
import navic.composeapp.generated.resources.reverb_preset_large_room
import navic.composeapp.generated.resources.reverb_preset_long_reverb_1
import navic.composeapp.generated.resources.reverb_preset_long_reverb_2
import navic.composeapp.generated.resources.reverb_preset_medium_hall
import navic.composeapp.generated.resources.reverb_preset_medium_room
import navic.composeapp.generated.resources.reverb_preset_plate_high
import navic.composeapp.generated.resources.reverb_preset_plate_low
import navic.composeapp.generated.resources.reverb_preset_small_hall_1
import navic.composeapp.generated.resources.reverb_preset_small_hall_2
import navic.composeapp.generated.resources.reverb_preset_small_room_1
import navic.composeapp.generated.resources.reverb_preset_small_room_2
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.LocalNavStack
import paige.navic.icons.Icons
import paige.navic.icons.outlined.ChevronForward
import paige.navic.icons.outlined.Refresh
import paige.navic.shared.MediaPlayerViewModel
import paige.navic.shared.dsp.CompanderBandFrequenciesHz
import paige.navic.shared.dsp.CompanderUiState
import paige.navic.shared.dsp.CrossfeedUiState
import paige.navic.shared.dsp.EqualizerBandFrequenciesHz
import paige.navic.shared.dsp.EqualizerUiState
import paige.navic.shared.dsp.GraphicEqUiState
import paige.navic.shared.dsp.ReverbUiState
import paige.navic.shared.dsp.StereoEnhancementUiState
import paige.navic.shared.dsp.VacuumTubeUiState
import paige.navic.ui.navigation.Screen
import kotlin.math.roundToInt

// Band gain range shared by the Equalizer and Compander per-band sliders. Not sourced from
// upstream (its band-gain UI is a freeform 2D drag surface, not a slider), but +/-12dB is a
// standard, conservative graphic-EQ range that matches the granularity of a slider control.
private const val BandGainMinDb = -12f
private const val BandGainMaxDb = 12f

/**
 * Phase 2 "Sound Effects" screen: the rest of the native JamesDSP effect suite (Equalizer,
 * Compander, Reverb, Crossfeed, Stereo Enhancement, Vacuum Tube), plus a master switch for the
 * whole chain. Bass Boost stays on the Playback Speed sheet where Phase 1 put it; everything
 * here is new. Structure and parameter ranges/defaults mirror timschneeb/RootlessJamesDSP's own
 * settings screens (see the state models in shared/dsp for the exact sourcing).
 *
 * Every control applies immediately — no separate "apply" action — consistent with how Bass
 * Boost and playback speed/pitch already behave.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SoundEffectsScreen() {
	val player = koinInject<MediaPlayerViewModel>()
	val backStack = LocalNavStack.current
	val lazyListState = rememberLazyListState()

	val masterState by player.dspMasterState.collectAsStateWithLifecycle()
	val eqState by player.equalizerState.collectAsStateWithLifecycle()
	val companderState by player.companderState.collectAsStateWithLifecycle()
	val reverbState by player.reverbState.collectAsStateWithLifecycle()
	val crossfeedState by player.crossfeedState.collectAsStateWithLifecycle()
	val stereoState by player.stereoEnhancementState.collectAsStateWithLifecycle()
	val tubeState by player.vacuumTubeState.collectAsStateWithLifecycle()
	val graphicEqState by player.graphicEqState.collectAsStateWithLifecycle()

	val masterOn = masterState.enabled

	LazyColumn(
		modifier = Modifier
			.padding(horizontal = 12.dp, vertical = 4.dp)
			.fillMaxWidth()
			.clip(ContinuousRoundedRectangle(topStart = 16.dp, topEnd = 16.dp)),
		state = lazyListState,
		contentPadding = WindowInsets.systemBars
			.only(WindowInsetsSides.Bottom)
			.asPaddingValues()
	) {
		item {
			Row(
				modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically
			) {
				Column {
					Text(
						text = stringResource(Res.string.option_sound_effects),
						style = MaterialTheme.typography.titleMedium
					)
					Text(
						text = stringResource(Res.string.label_dsp_master_enable),
						style = MaterialTheme.typography.labelMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant
					)
				}
				Switch(checked = masterOn, onCheckedChange = { player.setDspMasterEnabled(it) })
			}
		}

		item { SectionDivider() }

		item {
			DevicePresetSection(
				state = graphicEqState,
				masterOn = masterOn,
				onEnabledChange = { player.setGraphicEqEnabled(it) },
				onOpenPicker = { backStack.add(Screen.DevicePreset) }
			)
		}

		item { SectionDivider() }

		item {
			EqualizerSection(
				state = eqState,
				masterOn = masterOn,
				onEnabledChange = { player.setEqualizerEnabled(it) },
				onFilterTypeChange = { player.setEqualizerFilterType(it) },
				onInterpolationModeChange = { player.setEqualizerInterpolationMode(it) },
				onBandGainChange = { index, gain -> player.setEqualizerBandGain(index, gain) },
				onReset = { player.resetEqualizerBands() }
			)
		}

		item { SectionDivider() }

		item {
			CompanderSection(
				state = companderState,
				masterOn = masterOn,
				onEnabledChange = { player.setCompanderEnabled(it) },
				onTimeConstantChange = { player.setCompanderTimeConstant(it) },
				onGranularityChange = { player.setCompanderGranularity(it) },
				onTfTransformChange = { player.setCompanderTfTransform(it) },
				onBandGainChange = { index, gain -> player.setCompanderBandGain(index, gain) },
				onReset = { player.resetCompanderBands() }
			)
		}

		item { SectionDivider() }

		item {
			ReverbSection(
				state = reverbState,
				masterOn = masterOn,
				onEnabledChange = { player.setReverbEnabled(it) },
				onPresetChange = { player.setReverbPreset(it) }
			)
		}

		item { SectionDivider() }

		item {
			CrossfeedSection(
				state = crossfeedState,
				masterOn = masterOn,
				onEnabledChange = { player.setCrossfeedEnabled(it) },
				onModeChange = { player.setCrossfeedMode(it) }
			)
		}

		item { SectionDivider() }

		item {
			StereoEnhancementSection(
				state = stereoState,
				masterOn = masterOn,
				onEnabledChange = { player.setStereoEnhancementEnabled(it) },
				onLevelChange = { player.setStereoEnhancementLevel(it) }
			)
		}

		item { SectionDivider() }

		item {
			VacuumTubeSection(
				state = tubeState,
				masterOn = masterOn,
				onEnabledChange = { player.setVacuumTubeEnabled(it) },
				onDriveChange = { player.setVacuumTubeDrive(it) }
			)
		}
	}
}

@Composable
private fun SectionDivider() {
	Spacer(Modifier.height(4.dp))
	HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
	Spacer(Modifier.height(4.dp))
}

@Composable
private fun EffectHeader(title: String, enabled: Boolean, controlsEnabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
	Row(
		modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
		horizontalArrangement = Arrangement.SpaceBetween,
		verticalAlignment = Alignment.CenterVertically
	) {
		Text(
			text = title,
			style = MaterialTheme.typography.titleSmall,
			color = MaterialTheme.colorScheme.onSurface
		)
		Switch(checked = enabled, onCheckedChange = onEnabledChange, enabled = controlsEnabled)
	}
	Spacer(Modifier.height(8.dp))
}

// Phase 3: device-specific correction curve (AutoEq GraphicEQ), applied via the native
// setGraphicEq call. Unlike the other sections above, this one has no adjustable parameters --
// its only control is which device is currently selected, chosen from DevicePresetScreen's
// searchable picker -- so the header switch just toggles the already-fetched curve on/off, and
// is disabled until a device has actually been picked (state.curveString != null).
@Composable
private fun DevicePresetSection(
	state: GraphicEqUiState,
	masterOn: Boolean,
	onEnabledChange: (Boolean) -> Unit,
	onOpenPicker: () -> Unit
) {
	val hasCurve = state.curveString != null

	EffectHeader(
		title = stringResource(Res.string.option_device_preset),
		enabled = state.enabled,
		controlsEnabled = masterOn && hasCurve,
		onEnabledChange = onEnabledChange
	)

	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onOpenPicker)
			.padding(horizontal = 16.dp, vertical = 4.dp),
		horizontalArrangement = Arrangement.SpaceBetween,
		verticalAlignment = Alignment.CenterVertically
	) {
		Column {
			Text(
				text = state.deviceName ?: stringResource(Res.string.label_device_preset_none_selected),
				style = MaterialTheme.typography.bodyLarge,
				color = MaterialTheme.colorScheme.onSurface
			)
			if (state.measurementSource != null) {
				Text(
					text = state.measurementSource,
					style = MaterialTheme.typography.labelMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
			}
		}
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text(
				text = stringResource(Res.string.label_device_preset_change),
				style = MaterialTheme.typography.labelLarge,
				color = MaterialTheme.colorScheme.primary
			)
			Icon(
				imageVector = Icons.Outlined.ChevronForward,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.primary
			)
		}
	}

	Spacer(Modifier.height(16.dp))
}

@Composable
private fun LabeledSlider(
	label: String,
	valueText: String,
	value: Float,
	onValueChange: (Float) -> Unit,
	valueRange: ClosedFloatingPointRange<Float>,
	enabled: Boolean,
	steps: Int = 0
) {
	Row(
		modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
		horizontalArrangement = Arrangement.SpaceBetween
	) {
		Text(
			text = label,
			style = MaterialTheme.typography.labelLarge,
			color = MaterialTheme.colorScheme.onSurfaceVariant
		)
		Text(
			text = valueText,
			style = MaterialTheme.typography.labelLarge,
			color = MaterialTheme.colorScheme.primary
		)
	}
	Slider(
		value = value,
		onValueChange = onValueChange,
		valueRange = valueRange,
		steps = steps,
		enabled = enabled,
		modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
		colors = SliderDefaults.colors(
			thumbColor = MaterialTheme.colorScheme.primary,
			activeTrackColor = MaterialTheme.colorScheme.primary,
			inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
		)
	)
}

@Composable
private fun <T> OptionsRow(
	options: List<Pair<T, String>>,
	selected: T,
	enabled: Boolean,
	onSelect: (T) -> Unit
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.horizontalScroll(rememberScrollState())
			.alpha(if (enabled) 1f else 0.4f)
			.padding(horizontal = 8.dp),
		horizontalArrangement = Arrangement.Center
	) {
		Spacer(Modifier.width(8.dp))
		options.forEach { (value, label) ->
			SurfaceButton(
				modifier = Modifier,
				onClick = { if (enabled) onSelect(value) },
				text = label,
				isSelected = value == selected
			)
		}
		Spacer(Modifier.width(8.dp))
	}
}

@Composable
private fun BandSlidersSection(
	title: String,
	frequenciesHz: List<Double>,
	gainsDb: List<Float>,
	enabled: Boolean,
	onBandGainChange: (Int, Float) -> Unit,
	onReset: () -> Unit
) {
	Row(
		modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
		horizontalArrangement = Arrangement.SpaceBetween,
		verticalAlignment = Alignment.CenterVertically
	) {
		Text(
			text = title,
			style = MaterialTheme.typography.labelLarge,
			color = MaterialTheme.colorScheme.onSurfaceVariant
		)
		IconButton(onClick = onReset, enabled = enabled) {
			Icon(
				imageVector = Icons.Outlined.Refresh,
				contentDescription = stringResource(Res.string.action_reset)
			)
		}
	}

	frequenciesHz.forEachIndexed { index, hz ->
		val gain = gainsDb.getOrElse(index) { 0f }
		LabeledSlider(
			label = formatBandFrequency(hz),
			valueText = formatDb(gain),
			value = gain,
			onValueChange = { onBandGainChange(index, it) },
			valueRange = BandGainMinDb..BandGainMaxDb,
			enabled = enabled
		)
	}

	Spacer(Modifier.height(8.dp))
}

// Manual formatting rather than String.format/DecimalFormat: this file lives in commonMain
// (shared with the iOS target), and java.text/String.format aren't available there — same
// reason PlaybackScreen.kt's formatMultiplier() does its own digit math instead.
private fun formatBandFrequency(hz: Double): String {
	if (hz < 1000.0) return "${hz.toInt()} Hz"
	val tenthsOfKhz = (hz / 100.0).roundToInt()
	val whole = tenthsOfKhz / 10
	val fraction = tenthsOfKhz % 10
	return if (fraction == 0) "$whole kHz" else "$whole.$fraction kHz"
}

private fun formatDb(value: Float): String {
	val rounded = (value * 10f).roundToInt() / 10f
	val sign = if (rounded > 0f) "+" else ""
	return "$sign$rounded dB"
}

@Composable
private fun EqualizerSection(
	state: EqualizerUiState,
	masterOn: Boolean,
	onEnabledChange: (Boolean) -> Unit,
	onFilterTypeChange: (Int) -> Unit,
	onInterpolationModeChange: (Int) -> Unit,
	onBandGainChange: (Int, Float) -> Unit,
	onReset: () -> Unit
) {
	val controlsEnabled = masterOn && state.enabled

	EffectHeader(
		title = stringResource(Res.string.label_equalizer),
		enabled = state.enabled,
		controlsEnabled = masterOn,
		onEnabledChange = onEnabledChange
	)

	Text(
		text = stringResource(Res.string.label_eq_filter_type),
		style = MaterialTheme.typography.labelLarge,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
		modifier = Modifier.padding(horizontal = 16.dp)
	)
	Spacer(Modifier.height(4.dp))
	OptionsRow(
		options = listOf(
			0 to stringResource(Res.string.eq_filter_type_fir_minimum),
			1 to stringResource(Res.string.eq_filter_type_iir_4),
			2 to stringResource(Res.string.eq_filter_type_iir_6),
			3 to stringResource(Res.string.eq_filter_type_iir_8),
			4 to stringResource(Res.string.eq_filter_type_iir_10),
			5 to stringResource(Res.string.eq_filter_type_iir_12)
		),
		selected = state.filterType,
		enabled = controlsEnabled,
		onSelect = onFilterTypeChange
	)

	Spacer(Modifier.height(8.dp))

	Text(
		text = stringResource(Res.string.label_eq_interpolation),
		style = MaterialTheme.typography.labelLarge,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
		modifier = Modifier.padding(horizontal = 16.dp)
	)
	Spacer(Modifier.height(4.dp))
	OptionsRow(
		options = listOf(
			0 to stringResource(Res.string.eq_interpolation_pchip),
			1 to stringResource(Res.string.eq_interpolation_akima)
		),
		selected = state.interpolationMode,
		enabled = controlsEnabled,
		onSelect = onInterpolationModeChange
	)

	Spacer(Modifier.height(12.dp))

	BandSlidersSection(
		title = stringResource(Res.string.label_eq_bands),
		frequenciesHz = EqualizerBandFrequenciesHz,
		gainsDb = state.bandGainsDb,
		enabled = controlsEnabled,
		onBandGainChange = onBandGainChange,
		onReset = onReset
	)
}

@Composable
private fun CompanderSection(
	state: CompanderUiState,
	masterOn: Boolean,
	onEnabledChange: (Boolean) -> Unit,
	onTimeConstantChange: (Float) -> Unit,
	onGranularityChange: (Int) -> Unit,
	onTfTransformChange: (Int) -> Unit,
	onBandGainChange: (Int, Float) -> Unit,
	onReset: () -> Unit
) {
	val controlsEnabled = masterOn && state.enabled

	EffectHeader(
		title = stringResource(Res.string.label_compander),
		enabled = state.enabled,
		controlsEnabled = masterOn,
		onEnabledChange = onEnabledChange
	)

	LabeledSlider(
		label = stringResource(Res.string.label_compander_time_constant),
		valueText = "${(state.timeConstantSec * 1000f).roundToInt()} ms",
		value = state.timeConstantSec,
		onValueChange = onTimeConstantChange,
		valueRange = 0.06f..0.3f,
		enabled = controlsEnabled
	)

	Spacer(Modifier.height(8.dp))

	LabeledSlider(
		label = stringResource(Res.string.label_compander_granularity),
		valueText = state.granularity.toString(),
		value = state.granularity.toFloat(),
		onValueChange = { onGranularityChange(it.roundToInt().coerceIn(0, 4)) },
		valueRange = 0f..4f,
		steps = 3,
		enabled = controlsEnabled
	)

	Spacer(Modifier.height(8.dp))

	Text(
		text = stringResource(Res.string.label_compander_resolution),
		style = MaterialTheme.typography.labelLarge,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
		modifier = Modifier.padding(horizontal = 16.dp)
	)
	Spacer(Modifier.height(4.dp))
	OptionsRow(
		options = listOf(
			0 to stringResource(Res.string.compander_tf_uniform),
			1 to stringResource(Res.string.compander_tf_multires_wavelet),
			2 to stringResource(Res.string.compander_tf_pseudo_undersampling),
			3 to stringResource(Res.string.compander_tf_pseudo_time_domain)
		),
		selected = state.tfTransform,
		enabled = controlsEnabled,
		onSelect = onTfTransformChange
	)

	Spacer(Modifier.height(12.dp))

	BandSlidersSection(
		title = stringResource(Res.string.label_compander_bands),
		frequenciesHz = CompanderBandFrequenciesHz,
		gainsDb = state.bandGainsDb,
		enabled = controlsEnabled,
		onBandGainChange = onBandGainChange,
		onReset = onReset
	)
}

@Composable
private fun ReverbSection(
	state: ReverbUiState,
	masterOn: Boolean,
	onEnabledChange: (Boolean) -> Unit,
	onPresetChange: (Int) -> Unit
) {
	val controlsEnabled = masterOn && state.enabled

	EffectHeader(
		title = stringResource(Res.string.label_reverb),
		enabled = state.enabled,
		controlsEnabled = masterOn,
		onEnabledChange = onEnabledChange
	)

	Text(
		text = stringResource(Res.string.label_reverb_room_type),
		style = MaterialTheme.typography.labelLarge,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
		modifier = Modifier.padding(horizontal = 16.dp)
	)
	Spacer(Modifier.height(4.dp))
	OptionsRow(
		options = listOf(
			0 to stringResource(Res.string.reverb_preset_default),
			1 to stringResource(Res.string.reverb_preset_small_hall_1),
			2 to stringResource(Res.string.reverb_preset_small_hall_2),
			4 to stringResource(Res.string.reverb_preset_medium_hall),
			5 to stringResource(Res.string.reverb_preset_large_hall),
			7 to stringResource(Res.string.reverb_preset_small_room_1),
			8 to stringResource(Res.string.reverb_preset_small_room_2),
			9 to stringResource(Res.string.reverb_preset_medium_room),
			11 to stringResource(Res.string.reverb_preset_large_room),
			15 to stringResource(Res.string.reverb_preset_plate_high),
			16 to stringResource(Res.string.reverb_preset_plate_low),
			17 to stringResource(Res.string.reverb_preset_long_reverb_1),
			18 to stringResource(Res.string.reverb_preset_long_reverb_2)
		),
		selected = state.preset,
		enabled = controlsEnabled,
		onSelect = onPresetChange
	)

	Spacer(Modifier.height(16.dp))
}

@Composable
private fun CrossfeedSection(
	state: CrossfeedUiState,
	masterOn: Boolean,
	onEnabledChange: (Boolean) -> Unit,
	onModeChange: (Int) -> Unit
) {
	val controlsEnabled = masterOn && state.enabled

	EffectHeader(
		title = stringResource(Res.string.label_crossfeed),
		enabled = state.enabled,
		controlsEnabled = masterOn,
		onEnabledChange = onEnabledChange
	)

	Text(
		text = stringResource(Res.string.label_crossfeed_mode),
		style = MaterialTheme.typography.labelLarge,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
		modifier = Modifier.padding(horizontal = 16.dp)
	)
	Spacer(Modifier.height(4.dp))
	OptionsRow(
		options = listOf(
			0 to stringResource(Res.string.crossfeed_mode_bs2b_weak),
			1 to stringResource(Res.string.crossfeed_mode_bs2b_strong),
			2 to stringResource(Res.string.crossfeed_mode_out_of_head),
			3 to stringResource(Res.string.crossfeed_mode_surround_1),
			4 to stringResource(Res.string.crossfeed_mode_surround_2),
			5 to stringResource(Res.string.crossfeed_mode_realistic_surround)
		),
		selected = state.mode,
		enabled = controlsEnabled,
		onSelect = onModeChange
	)

	Spacer(Modifier.height(16.dp))
}

@Composable
private fun StereoEnhancementSection(
	state: StereoEnhancementUiState,
	masterOn: Boolean,
	onEnabledChange: (Boolean) -> Unit,
	onLevelChange: (Float) -> Unit
) {
	val controlsEnabled = masterOn && state.enabled

	EffectHeader(
		title = stringResource(Res.string.label_stereo_enhancement),
		enabled = state.enabled,
		controlsEnabled = masterOn,
		onEnabledChange = onEnabledChange
	)

	LabeledSlider(
		label = stringResource(Res.string.label_stereo_enhancement_level),
		valueText = state.level.roundToInt().toString(),
		value = state.level,
		onValueChange = onLevelChange,
		valueRange = 30f..75f,
		enabled = controlsEnabled
	)

	Spacer(Modifier.height(16.dp))
}

@Composable
private fun VacuumTubeSection(
	state: VacuumTubeUiState,
	masterOn: Boolean,
	onEnabledChange: (Boolean) -> Unit,
	onDriveChange: (Float) -> Unit
) {
	val controlsEnabled = masterOn && state.enabled

	EffectHeader(
		title = stringResource(Res.string.label_vacuum_tube),
		enabled = state.enabled,
		controlsEnabled = masterOn,
		onEnabledChange = onEnabledChange
	)

	LabeledSlider(
		label = stringResource(Res.string.label_vacuum_tube_drive),
		valueText = formatDb(state.driveDb),
		value = state.driveDb,
		onValueChange = onDriveChange,
		valueRange = -3f..12f,
		enabled = controlsEnabled
	)

	Spacer(Modifier.height(16.dp))
}
