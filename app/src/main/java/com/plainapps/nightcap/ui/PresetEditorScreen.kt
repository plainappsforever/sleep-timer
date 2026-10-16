package com.plainapps.nightcap.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import android.os.Build
import com.plainapps.nightcap.EndActions
import com.plainapps.nightcap.Preset
import com.plainapps.nightcap.Presets
import com.plainapps.nightcap.R
import com.plainapps.nightcap.Shortcuts

/**
 * Preset editor (SPEC F10): add (max 8), rename, minutes (1-480), fade (Default/0/10/30/60/120 s),
 * delete (min 1), reorder with up/down buttons. Every change is saved immediately.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun PresetEditorScreen(
    presets: List<Preset>,
    globalFadeSeconds: Int,
    initiallyExpandedId: String?,
    onBack: () -> Unit,
    onPresetsChange: (List<Preset>) -> Unit,
    lockGranted: Boolean = false,
    /** P4: On asks for accessibility first when it is not granted (MainActivity); Default/Off save directly. */
    onPresetLockChange: (Preset, Boolean?) -> Unit = { p, v -> onPresetsChange(Presets.update(presets, p.copy(lockAtEnd = v))) },
    dndGranted: Boolean = false,
    /** P5: On asks for Do Not Disturb access first when it is not granted (MainActivity); Default/Off save directly. */
    onPresetDndChange: (Preset, Boolean?) -> Unit = { p, v -> onPresetsChange(Presets.update(presets, p.copy(dnd = v))) },
    goodnightPossible: Boolean = false,
    /** P6: On asks for the needed access first when Goodnight is not possible (MainActivity); Default/Off save directly. */
    onPresetGoodnightChange: (Preset, Boolean?) -> Unit = { p, v -> onPresetsChange(Presets.update(presets, p.copy(goodnight = v))) },
) {
    var expandedId by rememberSaveable { mutableStateOf(initiallyExpandedId) }
    val context = LocalContext.current
    val pinSupported = remember { Shortcuts.isPinSupported(context) }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) }
                Text("Presets", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                presets.forEachIndexed { index, preset ->
                    val expanded = preset.id == expandedId
                    Card(modifier = Modifier.fillMaxWidth().testTag("preset_${preset.id}")) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { expandedId = if (expanded) null else preset.id },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(preset.label, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    preset.fadeSeconds?.let { "Fade $it s" } ?: "Default fade ($globalFadeSeconds s)",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (expanded) {
                                Spacer(Modifier.height(12.dp))
                                PresetFields(
                                    preset = preset,
                                    onChange = { onPresetsChange(Presets.update(presets, it)) },
                                )
                                Spacer(Modifier.height(8.dp))
                                PresetDndRow(preset, dndGranted) { onPresetDndChange(preset, it) }
                                Spacer(Modifier.height(8.dp))
                                PresetBedtimeRow(preset) { onPresetsChange(Presets.update(presets, preset.copy(bedtimeVolumePercent = it))) }
                                if (Build.VERSION.SDK_INT >= EndActions.LOCK_MIN_SDK) {
                                    Spacer(Modifier.height(8.dp))
                                    PresetLockRow(preset, lockGranted) { onPresetLockChange(preset, it) }
                                }
                                Spacer(Modifier.height(8.dp))
                                PresetGoodnightRow(preset, goodnightPossible) { onPresetGoodnightChange(preset, it) }
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        enabled = index > 0,
                                        onClick = { onPresetsChange(Presets.move(presets, preset.id, -1)) },
                                        modifier = Modifier.testTag("up_${preset.id}"),
                                    ) { Text("Up") }
                                    OutlinedButton(
                                        enabled = index < presets.lastIndex,
                                        onClick = { onPresetsChange(Presets.move(presets, preset.id, +1)) },
                                        modifier = Modifier.testTag("down_${preset.id}"),
                                    ) { Text("Down") }
                                    TextButton(
                                        enabled = presets.size > Presets.MIN_PRESETS,
                                        onClick = { onPresetsChange(Presets.remove(presets, preset.id)) },
                                        modifier = Modifier.testTag("delete_${preset.id}"),
                                    ) { Text("Delete") }
                                }
                                if (pinSupported) {
                                    // Pinned shortcut that starts this preset (F10/F15); the launcher shows its own confirmation.
                                    TextButton(
                                        onClick = { Shortcuts.requestPin(context, preset) },
                                        modifier = Modifier.testTag("pin_${preset.id}"),
                                    ) { Text(stringResource(R.string.preset_add_to_home)) }
                                }
                            }
                        }
                    }
                }
                OutlinedButton(
                    enabled = presets.size < Presets.MAX_PRESETS,
                    onClick = {
                        val added = Presets.add(presets)
                        onPresetsChange(added)
                        expandedId = added.last().id
                    },
                    modifier = Modifier.fillMaxWidth().testTag("add_preset"),
                ) { Text(if (presets.size < Presets.MAX_PRESETS) "Add preset" else "Add preset (max ${Presets.MAX_PRESETS})") }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** P5 (SPEC F18): "Do Not Disturb at start" for this preset: Default (the global switch) / On / Off. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetDndRow(preset: Preset, dndGranted: Boolean, onSelect: (Boolean?) -> Unit) {
    Text(stringResource(R.string.preset_dnd), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf<Pair<String, Boolean?>>("Default" to null, "On" to true, "Off" to false).forEach { (label, value) ->
            FilterChip(
                selected = preset.dnd == value,
                onClick = { onSelect(value) },
                label = { Text(label) },
                modifier = Modifier.testTag("dnd_${label.lowercase()}_${preset.id}"),
            )
        }
    }
    if (preset.dnd == true && !dndGranted) {
        Text(stringResource(R.string.preset_dnd_needs_access), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

/** P6 (SPEC F20): Goodnight for this preset: Default (the global switch) / On / Off. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetGoodnightRow(preset: Preset, possible: Boolean, onSelect: (Boolean?) -> Unit) {
    Text(stringResource(R.string.preset_goodnight), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf<Pair<String, Boolean?>>("Default" to null, "On" to true, "Off" to false).forEach { (label, value) ->
            FilterChip(
                selected = preset.goodnight == value,
                onClick = { onSelect(value) },
                label = { Text(label) },
                modifier = Modifier.testTag("goodnight_${label.lowercase()}_${preset.id}"),
            )
        }
    }
    if (preset.goodnight == true && !possible) {
        Text(stringResource(R.string.preset_goodnight_needs_access), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

/** P5: bedtime volume for this preset: Default (the global setting) / Off / 20 / 30 / 50 %. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetBedtimeRow(preset: Preset, onSelect: (Int?) -> Unit) {
    Text(stringResource(R.string.preset_bedtime_volume), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val options = listOf<Pair<String, Int?>>("Default" to null, "Off" to 0) + Presets.BEDTIME_OPTIONS.map { "$it %" to it }
        options.forEach { (label, value) ->
            FilterChip(
                selected = preset.bedtimeVolumePercent == value,
                onClick = { onSelect(value) },
                label = { Text(label) },
                modifier = Modifier.testTag("vol_${value ?: "default"}_${preset.id}"),
            )
        }
    }
}

/** "Lock screen at end" for this preset: Default (the global switch) / On / Off (SPEC F16). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetLockRow(preset: Preset, lockGranted: Boolean, onSelect: (Boolean?) -> Unit) {
    Text(stringResource(R.string.preset_lock), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf<Pair<String, Boolean?>>("Default" to null, "On" to true, "Off" to false).forEach { (label, value) ->
            FilterChip(
                selected = preset.lockAtEnd == value,
                onClick = { onSelect(value) },
                label = { Text(label) },
                modifier = Modifier.testTag("lock_${label.lowercase()}_${preset.id}"),
            )
        }
    }
    if (preset.lockAtEnd == true && !lockGranted) {
        Text(stringResource(R.string.preset_lock_needs_access), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetFields(preset: Preset, onChange: (Preset) -> Unit) {
    // Local text state so typing "4" on the way to "45" is not rewritten while editing.
    var nameText by remember(preset.id) { mutableStateOf(preset.name.orEmpty()) }
    var minutesText by remember(preset.id) { mutableStateOf(preset.minutes.toString()) }
    val minutesValid = minutesText.toIntOrNull()?.let { it in Presets.MIN_MINUTES..Presets.MAX_MINUTES } == true

    OutlinedTextField(
        value = nameText,
        onValueChange = {
            nameText = it.take(Presets.MAX_NAME_LENGTH)
            onChange(preset.copy(name = nameText.ifBlank { null }))
        },
        label = { Text("Name (optional)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("name_${preset.id}"),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = minutesText,
        onValueChange = { text ->
            minutesText = text.filter { it.isDigit() }.take(3)
            minutesText.toIntOrNull()?.takeIf { it in Presets.MIN_MINUTES..Presets.MAX_MINUTES }
                ?.let { onChange(preset.copy(minutes = it)) }
        },
        label = { Text("Minutes (${Presets.MIN_MINUTES}–${Presets.MAX_MINUTES})") },
        isError = !minutesValid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag("minutes_${preset.id}"),
    )
    Spacer(Modifier.height(8.dp))
    Text("Fade", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = preset.fadeSeconds == null,
            onClick = { onChange(preset.copy(fadeSeconds = null)) },
            label = { Text("Default") },
            modifier = Modifier.testTag("fade_default_${preset.id}"),
        )
        Presets.FADE_OPTIONS.forEach { seconds ->
            FilterChip(
                selected = preset.fadeSeconds == seconds,
                onClick = { onChange(preset.copy(fadeSeconds = seconds)) },
                label = { Text("$seconds s") },
                modifier = Modifier.testTag("fade_${seconds}_${preset.id}"),
            )
        }
    }
}
