package com.tim.sleeptimer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.tim.sleeptimer.DoneReason
import com.tim.sleeptimer.R
import com.tim.sleeptimer.RunPhase
import com.tim.sleeptimer.TimerPlan
import com.tim.sleeptimer.TimerSettings
import com.tim.sleeptimer.TimerState
import com.tim.sleeptimer.ui.theme.NightBackground
import com.tim.sleeptimer.ui.theme.OnNight
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    state: TimerState,
    settings: TimerSettings,
    notificationsEnabled: Boolean,
    isMusicActive: () -> Boolean,
    onStartDuration: (Int, String?) -> Unit,
    onStartUntil: (Int, Int) -> Unit,
    onEditPreset: (String) -> Unit,
    onExtend: () -> Unit,
    onStop: () -> Unit,
    onRestoreVolume: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onTryItNow: ((String) -> Unit) -> Unit,
    /** P5: our Do Not Disturb is on until this time (null = not on). */
    dndUntilMillis: Long? = null,
    onEndDnd: () -> Unit = {},
) {
    // F1 AC: the running screen always uses a dark surface (nightstand glare), regardless of
    // the system/app theme setting.
    val backgroundColor = if (state.isActive) NightBackground else MaterialTheme.colorScheme.background
    val contentColor = if (state.isActive) OnNight else MaterialTheme.colorScheme.onBackground
    Surface(modifier = Modifier.fillMaxSize(), color = backgroundColor, contentColor = contentColor) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringRes(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = onOpenSettings) { Text(stringRes(R.string.link_settings)) }
            }
            Spacer(Modifier.height(16.dp))

            if (dndUntilMillis != null) {
                DndCard(dndUntilMillis, onEndDnd)
                Spacer(Modifier.height(16.dp))
            }

            if (state.isActive) {
                RunningContent(
                    state = state,
                    notificationsEnabled = notificationsEnabled,
                    onExtend = onExtend,
                    onStop = onStop,
                    onOpenNotificationSettings = onOpenNotificationSettings,
                )
            } else {
                if (state.doneReason == DoneReason.COMPLETED_MUTED) {
                    MutedBanner(onRestoreVolume)
                    Spacer(Modifier.height(16.dp))
                }
                IdleContent(
                    settings = settings,
                    isMusicActive = isMusicActive,
                    onStartDuration = onStartDuration,
                    onStartUntil = onStartUntil,
                    onEditPreset = onEditPreset,
                    onRequestNotificationPermission = onRequestNotificationPermission,
                    onTryItNow = onTryItNow,
                )
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text(stringRes(R.string.footer_privacy), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** P5 (D19): "Do Not Disturb on until 07:00 tomorrow" + End Do Not Disturb. */
@Composable
private fun DndCard(untilMillis: Long, onEndDnd: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                androidx.compose.ui.res.stringResource(R.string.dnd_active_until, untilText(untilMillis)),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onEndDnd, modifier = Modifier.testTag("end_dnd")) { Text(stringRes(R.string.dnd_end)) }
        }
    }
}

/** "07:00" today, "07:00 tomorrow", else "Thu 07:00" (L13 review: a nap's DND lasts until the next morning). */
internal fun untilText(millis: Long): String {
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
    val zone = java.time.ZoneId.systemDefault()
    val day = java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    val today = java.time.LocalDate.now(zone)
    return when (day) {
        today -> time
        today.plusDays(1) -> "$time tomorrow"
        else -> SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(millis))
    }
}

@Composable
private fun MutedBanner(onRestoreVolume: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringRes(R.string.notification_muted), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onRestoreVolume) { Text(stringRes(R.string.notification_action_restore)) }
        }
    }
}

@Composable
private fun IdleContent(
    settings: TimerSettings,
    isMusicActive: () -> Boolean,
    onStartDuration: (Int, String?) -> Unit,
    onStartUntil: (Int, Int) -> Unit,
    onEditPreset: (String) -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onTryItNow: ((String) -> Unit) -> Unit,
) {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    var untilMode by rememberSaveable { mutableStateOf(false) }
    var selectedMinutes by rememberSaveable { mutableIntStateOf(settings.lastUsedDurationMinutes) }
    // The preset whose settings (own fade) the run uses; null = custom duration with the global fade.
    val lastUsedPresetId = settings.lastUsedPresetId
        ?: settings.presets.firstOrNull { it.minutes == settings.lastUsedDurationMinutes }?.id
    var selectedPresetId by rememberSaveable { mutableStateOf(lastUsedPresetId) }
    // Mirror the persisted last-used choice once DataStore has loaded (first frame shows defaults).
    LaunchedEffect(settings.lastUsedDurationMinutes, lastUsedPresetId) {
        selectedMinutes = settings.lastUsedDurationMinutes
        selectedPresetId = lastUsedPresetId
    }
    // Stepper / debug chip: pick the first preset with exactly these minutes, else a custom run.
    val selectMinutes: (Int) -> Unit = { minutes ->
        selectedMinutes = minutes
        selectedPresetId = settings.presets.firstOrNull { it.minutes == minutes }?.id
    }
    var showTimePicker by remember { mutableStateOf(false) }
    var untilHour by rememberSaveable { mutableIntStateOf(23) }
    var untilMinute by rememberSaveable { mutableIntStateOf(30) }
    var tryResult by remember { mutableStateOf<String?>(null) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringRes(R.string.until_label), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = untilMode, onCheckedChange = { untilMode = it })
    }
    Spacer(Modifier.height(16.dp))

    if (untilMode) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = String.format(locale, "%02d:%02d", untilHour, untilMinute),
                style = MaterialTheme.typography.displayMedium,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showTimePicker = true }) { Text("Change time") }
        }
    } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(text = "$selectedMinutes", style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
            Text(text = "minutes", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { selectMinutes(TimerPlan.clampDurationMinutes(selectedMinutes - 5)) }) { Text("−5") }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = { selectMinutes(TimerPlan.clampDurationMinutes(selectedMinutes + 5)) }) { Text("+5") }
            }
            Spacer(Modifier.height(16.dp))
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                settings.presets.forEach { preset ->
                    PresetChip(
                        label = preset.label,
                        selected = preset.id == selectedPresetId,
                        onClick = { selectedMinutes = preset.minutes; selectedPresetId = preset.id },
                        onLongClick = { onEditPreset(preset.id) },
                    )
                }
                if (com.tim.sleeptimer.BuildConfig.DEBUG_TIMER) {
                    FilterChip(
                        selected = selectedMinutes == 1 && selectedPresetId == null,
                        onClick = { selectedMinutes = 1; selectedPresetId = null },
                        label = { Text("1 (debug)") },
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(24.dp))
    Button(
        onClick = {
            onRequestNotificationPermission()
            if (untilMode) onStartUntil(untilHour, untilMinute) else onStartDuration(selectedMinutes, selectedPresetId)
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringRes(R.string.start_button))
    }

    if (isMusicActive()) {
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(
            onClick = { onTryItNow { result -> tryResult = result } },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringRes(R.string.try_it_now))
        }
        tryResult?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
    }

    if (showTimePicker) {
        TimePickerDialog(
            initialHour = untilHour,
            initialMinute = untilMinute,
            onDismiss = { showTimePicker = false },
            onConfirm = { h, m -> untilHour = h; untilMinute = m; showTimePicker = false },
        )
    }
}

/** Preset chip; a long press opens the preset editor (FilterChip cannot take a long-click). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetChip(label: String, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.clip(shape).combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = shape,
        color = if (selected) colors.secondaryContainer else Color.Transparent,
        contentColor = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
        border = BorderStroke(1.dp, if (selected) Color.Transparent else colors.outlineVariant),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit,
) {
    val state = rememberTimePickerState(initialHour = initialHour, initialMinute = initialMinute, is24Hour = true)
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                TimePicker(state = state)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onConfirm(state.hour, state.minute) }) { Text("OK") }
                }
            }
        }
    }
}

@Composable
private fun RunningContent(
    state: TimerState,
    notificationsEnabled: Boolean,
    onExtend: () -> Unit,
    onStop: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    var remainingMillis by remember { mutableLongStateOf(state.deadlineWallClockMillis - System.currentTimeMillis()) }

    LaunchedEffect(state.deadlineWallClockMillis) {
        while (true) {
            remainingMillis = state.deadlineWallClockMillis - System.currentTimeMillis()
            delay(1000)
        }
    }

    val totalSeconds = (remainingMillis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val endTimeText = SimpleDateFormat("HH:mm", locale).format(Date(state.deadlineWallClockMillis))
    val statusText = if (state.phase == RunPhase.FADING) {
        stringRes(R.string.notification_status_fading)
    } else {
        stringResource(
            R.string.notification_status_running,
            endTimeText,
            TimerPlan.endSequenceSummary(
                state.fadeSeconds,
                com.tim.sleeptimer.EndActions.planned(
                    state.lockAtEnd,
                    state.homeBeforeLock,
                    state.screenOffSooner,
                    android.os.Build.VERSION.SDK_INT,
                    com.tim.sleeptimer.SpecialAccess.isGranted(androidx.compose.ui.platform.LocalContext.current, com.tim.sleeptimer.AccessKind.ACCESSIBILITY),
                    com.tim.sleeptimer.SpecialAccess.isGranted(androidx.compose.ui.platform.LocalContext.current, com.tim.sleeptimer.AccessKind.WRITE_SETTINGS),
                ),
                state.goodnight?.takeIf { !it.finished }?.windDownMinutesShown,
            ),
        )
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = String.format(locale, "%02d:%02d", minutes, seconds),
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(text = statusText, style = MaterialTheme.typography.bodyMedium)
        state.presetLabel?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onExtend) {
                Text(stringResource(R.string.notification_action_extend, state.extendStepMinutes))
            }
            OutlinedButton(onClick = onStop) { Text(stringRes(R.string.stop_button)) }
        }
        if (!notificationsEnabled) {
            Spacer(Modifier.height(16.dp))
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringRes(R.string.permission_notifications_denied), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onOpenNotificationSettings) { Text(stringRes(R.string.permission_open_settings)) }
                }
            }
        }
    }
}

@Composable
private fun stringRes(id: Int): String = androidx.compose.ui.res.stringResource(id)
