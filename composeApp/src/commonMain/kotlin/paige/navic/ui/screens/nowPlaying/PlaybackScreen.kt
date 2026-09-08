package paige.navic.ui.screens.nowPlaying

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import navic.composeapp.generated.resources.label_playback_pitch
import navic.composeapp.generated.resources.label_playback_speed
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.shared.MediaPlayerViewModel
import paige.navic.util.ui.rememberDraggableListState
import kotlin.math.round

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
	val presets = listOf(
		1.0f,
		1.25f,
		1.5f,
		1.75f,
		2.0f
	)

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
			PlaybackMultiplierSection(
				label = stringResource(Res.string.label_playback_speed),
				value = selectedSpeed,
				presets = presets,
				onValueChange = { player.setPlaybackSpeed(it) }
			)
		}

		item {
			Spacer(Modifier.height(4.dp))
			HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
			Spacer(Modifier.height(4.dp))
		}

		item {
			PlaybackMultiplierSection(
				label = stringResource(Res.string.label_playback_pitch),
				value = selectedPitch,
				presets = presets,
				onValueChange = { player.setPlaybackPitch(it) }
			)
		}
	}
}

@Composable
private fun PlaybackMultiplierSection(
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

	Row(
		modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		Slider(
			value = value,
			onValueChange = { newValue ->
				val snappedValue = round(newValue * 100) / 100f
				onValueChange(snappedValue)
			},
			valueRange = 0.5f..2.0f,
			modifier = Modifier.weight(1f),
			colors = SliderDefaults.colors(
				thumbColor = MaterialTheme.colorScheme.primary,
				activeTrackColor = MaterialTheme.colorScheme.primary,
				inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
			)
		)
	}

	Spacer(Modifier.height(8.dp))

	Column(
		modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.Center
	) {
		Text(
			text = "${value}x",
			style = MaterialTheme.typography.titleMedium,
			color = MaterialTheme.colorScheme.primary
		)

		Row(
			modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.Center
		) {
			presets.forEach { preset ->
				val isSelected = preset == value
				SurfaceButton(
					modifier = Modifier.weight(1f),
					onClick = { onValueChange(preset) },
					text = "$preset",
					isSelected = isSelected
				)
			}
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
