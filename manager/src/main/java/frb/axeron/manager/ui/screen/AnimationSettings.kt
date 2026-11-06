package frb.axeron.manager.ui.screen

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import frb.axeron.manager.R
import frb.axeron.manager.data.SettingsRepository
import frb.axeron.manager.data.SettingsRepository.SettingType
import frb.axeron.manager.ui.component.AxOptionsSheet
import frb.axeron.manager.ui.component.OptionTile
import frb.axeron.manager.ui.component.OptionTileGrid
import frb.axeron.manager.ui.component.SectionHeader
import frb.axeron.manager.ui.component.SettingsGroup
import frb.axeron.manager.ui.component.SettingsRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Animation scales. The three keys are plain `Settings.Global` floats that
 * WindowManager / ActivityThread read on every transition:
 *
 *   window_animation_scale      — app open/close
 *   transition_animation_scale  — activity to activity
 *   animator_duration_scale     — ValueAnimator / ObjectAnimator (most in-app motion)
 *
 * Writing 0 turns the animation off rather than speeding it up; 1.0 is the stock value.
 * Nothing here needs root: the server runs these through `settings put`, and without a
 * server [SettingsRepository.putValue] falls back to the app's own WRITE_SECURE_SETTINGS.
 */
private const val KEY_WINDOW = "window_animation_scale"
private const val KEY_TRANSITION = "transition_animation_scale"
private const val KEY_ANIMATOR = "animator_duration_scale"

/** Values Android itself accepts in Developer options, plus a finer set in between. */
private val ANIMATION_SCALES = listOf(0f, 0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 5f, 10f)

private fun readScale(repository: SettingsRepository, key: String): Float =
    repository.getValue(SettingType.GLOBAL, key)?.toFloatOrNull() ?: 1f

private fun formatScale(value: Float): String {
    val number = if (value % 1f == 0f) value.toInt().toString() else value.toString()
    return "$number×"
}

@Composable
fun AnimationSettingsSection(enabled: Boolean = true) {
    val context = LocalContext.current
    val repository = remember { SettingsRepository(context.contentResolver) }
    val scope = rememberCoroutineScope()

    var windowScale by remember { mutableStateOf(1f) }
    var transitionScale by remember { mutableStateOf(1f) }
    var animatorScale by remember { mutableStateOf(1f) }
    var editing by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        val scales = withContext(Dispatchers.IO) {
            Triple(
                readScale(repository, KEY_WINDOW),
                readScale(repository, KEY_TRANSITION),
                readScale(repository, KEY_ANIMATOR)
            )
        }
        windowScale = scales.first
        transitionScale = scales.second
        animatorScale = scales.third
    }

    suspend fun write(key: String, value: Float) {
        withContext(Dispatchers.IO) {
            repository.putValue(SettingType.GLOBAL, key, value.toString())
        }
        reload()
    }

    LaunchedEffect(Unit) { reload() }

    val allOff = windowScale == 0f && transitionScale == 0f && animatorScale == 0f

    SectionHeader(
        text = stringResource(R.string.settings_section_animation),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
    )
    SettingsGroup {
        SettingsRow(
            iconVector = Icons.Filled.Bolt,
            label = stringResource(R.string.animation_master),
            description = stringResource(R.string.animation_master_desc),
            enabled = enabled,
            checked = !allOff,
            onSwitchChange = { on ->
                scope.launch {
                    val target = if (on) 1f else 0f
                    withContext(Dispatchers.IO) {
                        repository.putValue(SettingType.GLOBAL, KEY_WINDOW, target.toString())
                        repository.putValue(SettingType.GLOBAL, KEY_TRANSITION, target.toString())
                        repository.putValue(SettingType.GLOBAL, KEY_ANIMATOR, target.toString())
                    }
                    reload()
                }
            }
        )
        SettingsRow(
            iconVector = Icons.Filled.PlayArrow,
            label = stringResource(R.string.animation_window),
            description = formatScale(windowScale),
            enabled = enabled,
            onClick = { editing = KEY_WINDOW },
            showDivider = true
        )
        SettingsRow(
            iconVector = Icons.Filled.Refresh,
            label = stringResource(R.string.animation_transition),
            description = formatScale(transitionScale),
            enabled = enabled,
            onClick = { editing = KEY_TRANSITION },
            showDivider = true
        )
        SettingsRow(
            iconVector = Icons.Filled.RestartAlt,
            label = stringResource(R.string.animation_animator),
            description = formatScale(animatorScale),
            enabled = enabled,
            onClick = { editing = KEY_ANIMATOR },
            showDivider = true
        )
    }

    val editingKey = editing
    if (editingKey != null) {
        val current = when (editingKey) {
            KEY_WINDOW -> windowScale
            KEY_TRANSITION -> transitionScale
            else -> animatorScale
        }
        AxOptionsSheet(
            title = when (editingKey) {
                KEY_WINDOW -> stringResource(R.string.animation_window)
                KEY_TRANSITION -> stringResource(R.string.animation_transition)
                else -> stringResource(R.string.animation_animator)
            },
            description = stringResource(R.string.animation_scale_desc),
            onDismissRequest = { editing = null }
        ) {
            OptionTileGrid(items = ANIMATION_SCALES, columns = 3) { value, tileModifier ->
                OptionTile(
                    modifier = tileModifier,
                    icon = Icons.Filled.PlayArrow,
                    label = formatScale(value),
                    selected = value == current,
                    onClick = {
                        editing = null
                        scope.launch { write(editingKey, value) }
                    }
                )
            }
        }
    }
}
