package paige.navic.ui.screens.nowPlaying

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.capsule.ContinuousCapsule
import com.kyant.capsule.ContinuousRoundedRectangle
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_reset
import navic.composeapp.generated.resources.label_pitch_mode_multiplier
import navic.composeapp.generated.resources.label_pitch_mode_semitones
import navic.composeapp.generated.resources.label_playback_pitch
import navic.composeapp.generated.resources.label_playback_speed
import navic.composeapp.generated.resources.option_playback_speed
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Refresh
import paige.navic.shared.MediaPlayerViewModel
import paige.navic.util.ui.rememberDraggableListState
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt

private enum class PitchMode {
	Semitones,
	Multiplier
}

private const val TempoMin = 0.25f
private const val TempoMax = 2f
private const val PitchMin = 0.25f
private const val PitchMax = 2f
private const val FineStep = 0.01f

private fun Float.safeCoerceIn(min: Float, max: Float, fallback: Float): Float {
	val safe = if (this.isFinite()) this else fallback
	return safe.coerceIn(min, max)
}

private fun Float.quantize(step: Float): Float {
	if (step <= 0f) return this
	return (round(this / step) * step).coerceAtLeast(0f)
}

private fun pitchToSemitones(pitch: Float): Int {
	val safePitch = pitch.safeCoerceIn(PitchMin, PitchMax, fallback = 1f).coerceAtLeast(0.0001f)
	return (12f * log2(safePitch)).roundToInt().coerceIn(-12, 12)
}

private fun semitonesToPitch(semitones: Int): Float = 2f.pow(semitones.toFloat() / 12f).coerceIn(PitchMin, PitchMax)

private fun isPitchSemitoneAligned(pitch: Float): Boolean {
	val safePitch = pitch.safeCoerceIn(PitchMin, PitchMax, fallback = 1f).coerceAtLeast(0.0001f)
	val semitones = (12f * log2(safePitch)).roundToInt()
	val reconstructed = 2f.pow(semitones.toFloat() / 12f)
	return abs(reconstructed - pitch) < 0.0015f
}

private fun formatMultiplier(multiplier: Float): String {
	val clamped = multiplier.safeCoerceIn(TempoMin, TempoMax, fallback = 1f)
	val hundredths = round(clamped * 100).toInt()
	val whole = hundredths / 100
	val fraction = (hundredths % 100).let { if (it < 0) -it else it }
	return "$whole.${fraction.toString().padStart(2, '0')}"
}

private fun formatSemitones(semitones: Int): String = if (semitones > 0) "+$semitones" else semitones.toString()

// Non-linear slider mapping so mid-slider positions feel natural despite the wide 0.25x-2x range —
// slider domain is 0f..1f, with 0.5f == 1.0x (unity), curving outward toward the extremes.
private fun sliderToMultiplier(slider: Float): Float {
	val t = slider.coerceIn(0f, 1f)
	val y = (t - 0.5f) * 2f
	val curve = 2.2f
	val absY = abs(y).pow(curve)
	val shaped = when {
		y > 0f -> absY
		y < 0f -> -absY
		else -> 0f
	}
	val exponent = if (y < 0f) 2f * shaped else shaped
	return 2f.pow(exponent).coerceIn(TempoMin, TempoMax)
}

private fun multiplierToSlider(multiplier: Float): Float {
	val m = multiplier.coerceIn(TempoMin, TempoMax)
	val log = log2(m)
	val curve = 2.2f
	val shaped = if (m < 1f) (log / 2f) else log
	val absShaped = abs(shaped).pow(1f / curve)
	val y = when {
		shaped > 0f -> absShaped
		shaped < 0f -> -absShaped
		else -> 0f
	}
	return (0.5f + y / 2f).coerceIn(0f, 1f)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PlaybackSpeedScreen() {
	val player = koinInject<MediaPlayerViewModel>()
	val lazyListState = rememberLazyListState()
	val haptic = LocalHapticFeedback.current
	val playerState by player.uiState.collectAsStateWithLifecycle()

	val draggableState = rememberDraggableListState(lazyListState) { from, to ->
		player.moveQueueItem(from, to)
		haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
	}

	val selectedSpeed = playerState.playbackSpeed
	val selectedPitch = playerState.playbackPitch

	// Which display mode starts selected reflects whether the current pitch already lands on a
	// semitone; switching modes afterwards never mutates the underlying pitch value.
	var pitchMode by remember {
		mutableStateOf(if (isPitchSemitoneAligned(selectedPitch)) PitchMode.Semitones else PitchMode.Multiplier)
	}

	val multiplierPresets = remember { listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f) }
	val semitonePresets = remember { listOf(-12, -7, -5, 0, 5, 7, 12) }

	LazyColumn(
		modifier = Modifier
			.padding(horizontal = 12.dp, vertical = 4.dp)
			.fillMaxWidth()
			.clip(ContinuousRoundedRectangle(topStart = 16.dp, topEnd = 16.dp)),
		state = draggableState.listState,
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
				Text(
					text = stringResource(Res.string.option_playback_speed),
					style = MaterialTheme.typography.titleMedium
				)
				IconButton(
					onClick = {
						player.setPlaybackSpeed(1.0f)
						player.setPlaybackPitch(1.0f)
					}
				) {
					Icon(
						imageVector = Icons.Outlined.Refresh,
						contentDescription = stringResource(Res.string.action_reset)
					)
				}
			}
		}

		item {
			PlaybackSpeedSection(
				label = stringResource(Res.string.label_playback_speed),
				value = selectedSpeed,
				presets = multiplierPresets,
				onValueChange = { player.setPlaybackSpeed(it) }
			)
		}

		item {
			Spacer(Modifier.height(4.dp))
			HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
			Spacer(Modifier.height(4.dp))
		}

		item {
			PlaybackPitchSection(
				label = stringResource(Res.string.label_playback_pitch),
				pitch = selectedPitch,
				mode = pitchMode,
				onModeChange = { pitchMode = it },
				multiplierPresets = multiplierPresets,
				semitonePresets = semitonePresets,
				onPitchChange = { player.setPlaybackPitch(it) }
			)
		}
	}
}

@Composable
private fun PlaybackSpeedSection(
	label: String,
	value: Float,
	presets: List<Float>,
	onValueChange: (Float) -> Unit
) {
	Text(
		text = label,
		style = MaterialTheme.typography.labelLarge,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
		modifier = Modifier.padding(horizontal = 16.dp)
	)

	Spacer(Modifier.height(4.dp))

	Text(
		text = "x${formatMultiplier(value)}",
		style = MaterialTheme.typography.titleMedium,
		color = MaterialTheme.colorScheme.primary,
		modifier = Modifier.padding(horizontal = 16.dp)
	)

	Spacer(Modifier.height(8.dp))

	MultiplierStepperSlider(value = value, onValueChange = onValueChange)

	Spacer(Modifier.height(8.dp))

	MultiplierPresetsRow(presets = presets, selected = value, onSelect = onValueChange)

	Spacer(Modifier.height(16.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackPitchSection(
	label: String,
	pitch: Float,
	mode: PitchMode,
	onModeChange: (PitchMode) -> Unit,
	multiplierPresets: List<Float>,
	semitonePresets: List<Int>,
	onPitchChange: (Float) -> Unit
) {
	val semitones = pitchToSemitones(pitch)

	Text(
		text = label,
		style = MaterialTheme.typography.labelLarge,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
		modifier = Modifier.padding(horizontal = 16.dp)
	)

	Spacer(Modifier.height(4.dp))

	Text(
		text = when (mode) {
			PitchMode.Semitones -> formatSemitones(semitones)
			PitchMode.Multiplier -> "x${formatMultiplier(pitch)}"
		},
		style = MaterialTheme.typography.titleMedium,
		color = MaterialTheme.colorScheme.primary,
		modifier = Modifier.padding(horizontal = 16.dp)
	)

	Spacer(Modifier.height(8.dp))

	SingleChoiceSegmentedButtonRow(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp)
	) {
		PitchMode.entries.forEachIndexed { index, entry ->
			SegmentedButton(
				shape = SegmentedButtonDefaults.itemShape(
					index = index,
					count = PitchMode.entries.size
				),
				onClick = { onModeChange(entry) },
				selected = mode == entry,
				label = {
					Text(
						stringResource(
							when (entry) {
								PitchMode.Semitones -> Res.string.label_pitch_mode_semitones
								PitchMode.Multiplier -> Res.string.label_pitch_mode_multiplier
							}
						)
					)
				}
			)
		}
	}

	Spacer(Modifier.height(8.dp))

	when (mode) {
		PitchMode.Semitones -> {
			SemitoneSlider(
				semitones = semitones,
				onSemitoneChange = { onPitchChange(semitonesToPitch(it)) }
			)
			Spacer(Modifier.height(8.dp))
			SemitonePresetsRow(
				presets = semitonePresets,
				selected = semitones,
				onSelect = { onPitchChange(semitonesToPitch(it)) }
			)
		}
		PitchMode.Multiplier -> {
			MultiplierStepperSlider(value = pitch, onValueChange = onPitchChange)
			Spacer(Modifier.height(8.dp))
			MultiplierPresetsRow(presets = multiplierPresets, selected = pitch, onSelect = onPitchChange)
		}
	}

	Spacer(Modifier.height(16.dp))
}

@Composable
private fun MultiplierStepperSlider(value: Float, onValueChange: (Float) -> Unit) {
	Row(
		modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		StepperButton(
			text = "−",
			onClick = { onValueChange((value - FineStep).quantize(FineStep).coerceIn(TempoMin, TempoMax)) }
		)

		Slider(
			value = multiplierToSlider(value),
			onValueChange = { slider -> onValueChange(sliderToMultiplier(slider)) },
			valueRange = 0f..1f,
			modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
			colors = SliderDefaults.colors(
				thumbColor = MaterialTheme.colorScheme.primary,
				activeTrackColor = MaterialTheme.colorScheme.primary,
				inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
			)
		)

		StepperButton(
			text = "+",
			onClick = { onValueChange((value + FineStep).quantize(FineStep).coerceIn(TempoMin, TempoMax)) }
		)
	}
}

@Composable
private fun SemitoneSlider(semitones: Int, onSemitoneChange: (Int) -> Unit) {
	Slider(
		value = semitones.toFloat(),
		onValueChange = { newValue -> onSemitoneChange(newValue.roundToInt().coerceIn(-12, 12)) },
		valueRange = -12f..12f,
		steps = 23,
		modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
		colors = SliderDefaults.colors(
			thumbColor = MaterialTheme.colorScheme.primary,
			activeTrackColor = MaterialTheme.colorScheme.primary,
			inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
		)
	)
}

@Composable
private fun MultiplierPresetsRow(presets: List<Float>, selected: Float, onSelect: (Float) -> Unit) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.horizontalScroll(rememberScrollState())
			.padding(horizontal = 8.dp),
		horizontalArrangement = Arrangement.Center
	) {
		// Explicit leading/trailing spacers, rather than relying on the Row's own padding, so the
		// last chip always has clear space past it once scrolled fully to the end instead of
		// sitting flush against (and getting clipped by) the sheet edge.
		Spacer(Modifier.width(8.dp))
		presets.forEach { preset ->
			SurfaceButton(
				modifier = Modifier,
				onClick = { onSelect(preset) },
				text = formatMultiplier(preset),
				isSelected = preset == selected
			)
		}
		Spacer(Modifier.width(8.dp))
	}
}

@Composable
private fun SemitonePresetsRow(presets: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.horizontalScroll(rememberScrollState())
			.padding(horizontal = 8.dp),
		horizontalArrangement = Arrangement.Center
	) {
		Spacer(Modifier.width(8.dp))
		presets.forEach { preset ->
			SurfaceButton(
				modifier = Modifier,
				onClick = { onSelect(preset) },
				text = formatSemitones(preset),
				isSelected = preset == selected
			)
		}
		Spacer(Modifier.width(8.dp))
	}
}

@Composable
private fun StepperButton(text: String, onClick: () -> Unit) {
	Surface(
		modifier = Modifier.size(36.dp),
		shape = ContinuousCapsule,
		onClick = onClick,
		color = MaterialTheme.colorScheme.surfaceContainerHigh,
		contentColor = MaterialTheme.colorScheme.onSurface
	) {
		Box(contentAlignment = Alignment.Center) {
			Text(text, style = MaterialTheme.typography.titleMedium)
		}
	}
}

@Composable
fun SurfaceButton(
	modifier: Modifier,
	onClick: () -> Unit,
	text: String,
	isSelected: Boolean = false
) {
	Surface(
		modifier = modifier.padding(4.dp),
		shape = ContinuousCapsule,
		onClick = onClick,
		color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
		contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
	) {
		Column(
			modifier = Modifier.padding(8.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.Center
		) {
			Text(text)
		}
	}
}
