package paige.navic.ui.screens.nowPlaying

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_clear_search
import navic.composeapp.generated.resources.action_retry
import navic.composeapp.generated.resources.info_device_preset_fetch_failed
import navic.composeapp.generated.resources.info_no_search_results
import navic.composeapp.generated.resources.label_device_preset_search_hint
import navic.composeapp.generated.resources.option_device_preset
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import paige.navic.LocalNavStack
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Close
import paige.navic.icons.outlined.NoSearchResults
import paige.navic.icons.outlined.Refresh
import paige.navic.icons.outlined.Search
import paige.navic.shared.MediaPlayerViewModel
import paige.navic.ui.components.common.ContentUnavailable
import paige.navic.ui.components.layouts.NestedTopBar
import paige.navic.ui.screens.nowPlaying.viewmodels.DevicePresetViewModel

/**
 * Phase 3: searchable picker over Navic's bundled AutoEq device index (8,830 devices --
 * see `composeApp/src/commonMain/composeResources/files/autoeq_index.json`). Selecting a
 * result fetches (or reuses a cached copy of) its correction curve and applies it via
 * [MediaPlayerViewModel.applyGraphicEqCurve], then returns to the previous screen -- the
 * applied device is shown persistently there (see SoundEffectsScreen's device-preset row)
 * rather than as a transient confirmation here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicePresetScreen() {
	val viewModel = koinViewModel<DevicePresetViewModel>()
	val player = koinInject<MediaPlayerViewModel>()
	val backStack = LocalNavStack.current

	val results by viewModel.results.collectAsStateWithLifecycle()
	val fetchState by viewModel.fetchState.collectAsStateWithLifecycle()

	val focusManager = LocalFocusManager.current
	val focusRequester = remember { FocusRequester() }
	LaunchedEffect(Unit) { focusRequester.requestFocus() }

	Scaffold(
		topBar = { NestedTopBar({ Text(stringResource(Res.string.option_device_preset)) }) }
	) { contentPadding ->
		Column(
			modifier = Modifier.fillMaxSize().padding(contentPadding)
		) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Icon(
					Icons.Outlined.Search,
					contentDescription = null,
					modifier = Modifier.padding(start = 18.dp),
					tint = MaterialTheme.colorScheme.onSurfaceVariant
				)
				BasicTextField(
					state = viewModel.query,
					modifier = Modifier
						.weight(1f)
						.height(56.dp)
						.padding(start = 12.dp)
						.focusRequester(focusRequester),
					lineLimits = TextFieldLineLimits.SingleLine,
					keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
					onKeyboardAction = { focusManager.clearFocus() },
					textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
					cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
					decorator = { innerTextField ->
						Box(contentAlignment = Alignment.CenterStart) {
							if (viewModel.query.text.isEmpty()) {
								Text(
									text = stringResource(Res.string.label_device_preset_search_hint),
									color = MaterialTheme.colorScheme.onSurfaceVariant
								)
							}
							innerTextField()
						}
					}
				)
				if (viewModel.query.text.isNotEmpty()) {
					IconButton(onClick = { viewModel.query.clearText() }) {
						Icon(
							Icons.Outlined.Close,
							contentDescription = stringResource(Res.string.action_clear_search)
						)
					}
				}
			}

			val fetchStateSnapshot = fetchState
			if (fetchStateSnapshot is DevicePresetViewModel.FetchState.Error) {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 16.dp, vertical = 8.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.SpaceBetween
				) {
					Text(
						text = stringResource(Res.string.info_device_preset_fetch_failed),
						color = MaterialTheme.colorScheme.error,
						style = MaterialTheme.typography.bodyMedium,
						modifier = Modifier.weight(1f)
					)
					IconButton(onClick = {
						viewModel.retryFetch { curve ->
							val preset = fetchStateSnapshot.preset
							player.applyGraphicEqCurve(preset.deviceName, preset.measurementSource, curve)
							if (backStack.size > 1) backStack.removeLastOrNull()
						}
					}) {
						Icon(Icons.Outlined.Refresh, contentDescription = stringResource(Res.string.action_retry))
					}
				}
			}

			if (viewModel.query.text.isEmpty()) {
				ContentUnavailable(
					icon = Icons.Outlined.Search,
					label = stringResource(Res.string.label_device_preset_search_hint)
				)
			} else if (results.isEmpty()) {
				ContentUnavailable(
					icon = Icons.Outlined.NoSearchResults,
					label = stringResource(Res.string.info_no_search_results)
				)
			} else {
				val isFetching = fetchStateSnapshot is DevicePresetViewModel.FetchState.Loading
				LazyColumn(
					modifier = Modifier.fillMaxWidth(),
					contentPadding = PaddingValues(bottom = 24.dp)
				) {
					items(results, key = { it.path }) { preset ->
						val isLoadingThis =
							(fetchStateSnapshot as? DevicePresetViewModel.FetchState.Loading)?.preset == preset
						ListItem(
							modifier = Modifier.clickable(enabled = !isFetching) {
								viewModel.selectPreset(preset) { curve ->
									player.applyGraphicEqCurve(
										preset.deviceName,
										preset.measurementSource,
										curve
									)
									if (backStack.size > 1) backStack.removeLastOrNull()
								}
							},
							headlineContent = { Text(preset.deviceName) },
							supportingContent = { Text(preset.measurementSource) },
							trailingContent = {
								if (isLoadingThis) {
									CircularProgressIndicator(modifier = Modifier.size(20.dp))
								}
							}
						)
					}
				}
			}
		}
	}
}
