package com.plainapps.nightcap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.content.ActivityNotFoundException
import android.net.Uri
import android.widget.Toast
import com.plainapps.nightcap.AccessKind
import com.plainapps.nightcap.AppLinks
import com.plainapps.nightcap.AccessState
import com.plainapps.nightcap.EndActions
import com.plainapps.nightcap.R
import com.plainapps.nightcap.ThemeMode
import com.plainapps.nightcap.TimerSettings
import com.plainapps.nightcap.TimerWidgetProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Slider
import com.plainapps.nightcap.Presets
import com.plainapps.nightcap.Schedule
import java.time.ZonedDateTime
import java.time.format.TextStyle
import kotlin.math.roundToInt
import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.app.NotificationManagerCompat

private val FADE_OPTIONS = listOf(0, 10, 30, 60, 120)
private val EXTEND_OPTIONS = listOf(5, 10, 15, 30)

@Composable
fun SettingsScreen(
    settings: TimerSettings,
    onBack: () -> Unit,
    onSettingsChange: (TimerSettings) -> Unit,
    onEditPresets: () -> Unit,
    access: AccessState,
    accessNotice: String?,
    onFeatureToggle: (AccessKind, Boolean) -> Unit,
    startNotice: String? = null,
    onRequestNotificationPermission: () -> Unit = {},
    goodnightNotice: String? = null,
    onGoodnightFeature: (com.plainapps.nightcap.GoodnightFeature, Boolean) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Text("←", style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.height(0.dp))
                Text(
                    stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
            ) {
                SectionLabel(stringResource(R.string.settings_presets))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        settings.presets.joinToString(" · ") { it.label },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onEditPresets) { Text(stringResource(R.string.settings_presets_edit)) }
                }

                Spacer(Modifier.height(20.dp))
                SectionLabel(stringResource(R.string.settings_fade))
                OptionRow(FADE_OPTIONS, settings.fadeSeconds, suffix = "s") {
                    onSettingsChange(settings.copy(fadeSeconds = it))
                }

                Spacer(Modifier.height(20.dp))
                SectionLabel(stringResource(R.string.settings_extend))
                OptionRow(EXTEND_OPTIONS, settings.extendStepMinutes, suffix = "m") {
                    onSettingsChange(settings.copy(extendStepMinutes = it))
                }

                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                SectionLabel(stringResource(R.string.settings_still_awake))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_shake), style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = settings.shakeToExtendEnabled,
                        onCheckedChange = { onSettingsChange(settings.copy(shakeToExtendEnabled = it)) },
                        modifier = Modifier.testTag("shake_switch"),
                    )
                }
                Text(
                    stringResource(R.string.settings_shake_body, settings.extendStepMinutes),
                    style = MaterialTheme.typography.bodySmall,
                )

                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_mute_fallback), style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = settings.muteFallbackEnabled,
                        onCheckedChange = { onSettingsChange(settings.copy(muteFallbackEnabled = it)) },
                    )
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_restore_volume), style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = settings.restoreVolumeAfterPause,
                        onCheckedChange = { onSettingsChange(settings.copy(restoreVolumeAfterPause = it)) },
                    )
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge)
                    Row {
                        FilterChip(
                            selected = settings.themeMode == ThemeMode.SYSTEM,
                            onClick = { onSettingsChange(settings.copy(themeMode = ThemeMode.SYSTEM)) },
                            label = { Text("System") },
                        )
                        Spacer(Modifier.height(0.dp))
                        FilterChip(
                            selected = settings.themeMode == ThemeMode.DARK,
                            onClick = { onSettingsChange(settings.copy(themeMode = ThemeMode.DARK)) },
                            label = { Text("Dark") },
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                StartOfTimerSection(settings, startNotice, onSettingsChange, onFeatureToggle, onRequestNotificationPermission)

                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                EndOfTimerSection(settings, access, accessNotice, onSettingsChange, onFeatureToggle)

                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                GoodnightSection(settings, access, goodnightNotice, onSettingsChange, onGoodnightFeature)

                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                SectionLabel(stringResource(R.string.settings_reliability))
                ReliabilityRow(context, settings)

                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                HomeScreenRow(context)

                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                SectionLabel(stringResource(R.string.settings_about))
                Text(
                    stringResource(R.string.app_tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.settings_about_body), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                AccessLine(R.string.settings_about_access_accessibility, access.accessibility)
                AccessLine(R.string.settings_about_access_listener, access.notificationListener)
                AccessLine(R.string.settings_about_access_write, access.writeSettings)
                AccessLine(R.string.settings_about_access_dnd, access.dndPolicy)
                Text(stringResource(R.string.settings_about_revoke), style = MaterialTheme.typography.bodySmall)
                // v2.1: Play wants the privacy policy reachable inside the app (RELEASE_KIT step 4); opens the browser.
                TextButton(
                    onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppLinks.PRIVACY_POLICY_URL)))
                        } catch (e: ActivityNotFoundException) {
                            Toast.makeText(context, AppLinks.PRIVACY_POLICY_URL, Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.testTag("privacy_policy"),
                ) { Text(stringResource(R.string.settings_privacy_policy)) }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

private fun hhmm(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

/**
 * Start of timer (SPEC F18/F19, D19/D20/D39): Do Not Disturb (asks for DND access only when turned on),
 * bedtime volume, and the nightly automatic start (no special access; may ask for notifications).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StartOfTimerSection(
    settings: TimerSettings,
    startNotice: String?,
    onSettingsChange: (TimerSettings) -> Unit,
    onFeatureToggle: (AccessKind, Boolean) -> Unit,
    onRequestNotificationPermission: () -> Unit,
) {
    var picking by remember { mutableStateOf<String?>(null) } // "dnd" | "schedule"
    SectionLabel(stringResource(R.string.settings_start_of_timer))
    SwitchRow(
        text = stringResource(R.string.settings_dnd),
        checked = settings.dndAtStart,
        tag = "dnd_switch",
    ) { onFeatureToggle(AccessKind.DND_POLICY, it) }
    Text(stringResource(R.string.settings_dnd_body, hhmm(settings.dndUntilMinuteOfDay)), style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { picking = "dnd" }, modifier = Modifier.testTag("dnd_until")) {
        Text(stringResource(R.string.settings_dnd_until, hhmm(settings.dndUntilMinuteOfDay)))
    }
    startNotice?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(8.dp))
    }

    Spacer(Modifier.height(4.dp))
    SwitchRow(
        text = stringResource(R.string.settings_bedtime_volume),
        checked = settings.bedtimeVolumeEnabled,
        tag = "bedtime_switch",
    ) { onSettingsChange(settings.copy(bedtimeVolumeEnabled = it)) }
    var percent by remember(settings.bedtimeVolumePercent) { mutableFloatStateOf(settings.bedtimeVolumePercent.toFloat()) }
    Text(stringResource(R.string.settings_bedtime_volume_body, percent.roundToInt()), style = MaterialTheme.typography.bodySmall)
    if (settings.bedtimeVolumeEnabled) {
        Slider(
            value = percent,
            onValueChange = { percent = it },
            onValueChangeFinished = { onSettingsChange(settings.copy(bedtimeVolumePercent = percent.roundToInt())) },
            valueRange = Presets.MIN_BEDTIME_PERCENT.toFloat()..Presets.MAX_BEDTIME_PERCENT.toFloat(),
            steps = (Presets.MAX_BEDTIME_PERCENT - Presets.MIN_BEDTIME_PERCENT) / 5 - 1,
            modifier = Modifier.testTag("bedtime_slider"),
        )
    }

    Spacer(Modifier.height(12.dp))
    SwitchRow(
        text = stringResource(R.string.settings_schedule),
        checked = settings.scheduleEnabled,
        tag = "schedule_switch",
    ) {
        onSettingsChange(settings.copy(scheduleEnabled = it))
        if (it) onRequestNotificationPermission()
    }
    Text(stringResource(R.string.settings_schedule_body), style = MaterialTheme.typography.bodySmall)
    if (settings.scheduleEnabled) {
        TextButton(onClick = { picking = "schedule" }, modifier = Modifier.testTag("schedule_time")) {
            Text(stringResource(R.string.settings_schedule_time, hhmm(settings.scheduleMinuteOfDay)))
        }
        val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Schedule.DAYS_IN_ORDER.forEach { day ->
                val on = Schedule.isDaySet(settings.scheduleWeekdayMask, day)
                FilterChip(
                    selected = on,
                    onClick = { onSettingsChange(settings.copy(scheduleWeekdayMask = Schedule.withDay(settings.scheduleWeekdayMask, day, !on))) },
                    label = { Text(day.getDisplayName(TextStyle.SHORT, locale)) },
                )
            }
        }
        Text(stringResource(R.string.settings_schedule_preset), style = MaterialTheme.typography.labelLarge)
        val chosen = settings.presets.firstOrNull { it.id == settings.schedulePresetId } ?: settings.presets.first()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            settings.presets.forEach { preset ->
                FilterChip(
                    selected = preset.id == chosen.id,
                    onClick = { onSettingsChange(settings.copy(schedulePresetId = preset.id)) },
                    label = { Text(preset.label) },
                )
            }
        }
        // Same rule as alarm 104 (SystemAlarms.syncSchedule): never an occurrence already handled.
        val next = Schedule.nextToArm(
            ZonedDateTime.now(), settings.scheduleMinuteOfDay, settings.scheduleWeekdayMask,
            settings.scheduleLastHandledMillis, 0L,
        )
        Text(
            if (next == null) {
                stringResource(R.string.settings_schedule_no_days)
            } else {
                stringResource(R.string.settings_schedule_next, untilText(next.toInstant().toEpochMilli()))
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }

    when (picking) {
        "dnd" -> TimePickerDialog(
            settings.dndUntilMinuteOfDay / 60, settings.dndUntilMinuteOfDay % 60,
            onDismiss = { picking = null },
        ) { h, m -> picking = null; onSettingsChange(settings.copy(dndUntilMinuteOfDay = h * 60 + m)) }
        "schedule" -> TimePickerDialog(
            settings.scheduleMinuteOfDay / 60, settings.scheduleMinuteOfDay % 60,
            onDismiss = { picking = null },
        ) { h, m -> picking = null; onSettingsChange(settings.copy(scheduleMinuteOfDay = h * 60 + m)) }
    }
}

/**
 * End of timer (SPEC F16/F17): each switch that needs a special access asks for it only when turned
 * on (MainActivity shows the explanation first); refused/revoked turns it off with [accessNotice].
 */
@Composable
private fun EndOfTimerSection(
    settings: TimerSettings,
    access: AccessState,
    accessNotice: String?,
    onSettingsChange: (TimerSettings) -> Unit,
    onFeatureToggle: (AccessKind, Boolean) -> Unit,
) {
    val lockSupported = Build.VERSION.SDK_INT >= EndActions.LOCK_MIN_SDK
    SectionLabel(stringResource(R.string.settings_end_of_timer))
    SwitchRow(
        text = stringResource(R.string.settings_lock),
        checked = settings.lockAtEnd && lockSupported,
        enabled = lockSupported,
        tag = "lock_switch",
    ) { onFeatureToggle(AccessKind.ACCESSIBILITY, it) }
    Text(
        stringResource(if (lockSupported) R.string.settings_lock_body else R.string.settings_lock_needs_android9),
        style = MaterialTheme.typography.bodySmall,
    )
    if (settings.lockAtEnd && lockSupported) {
        Spacer(Modifier.height(8.dp))
        SwitchRow(
            text = stringResource(R.string.settings_home_first),
            checked = settings.homeBeforeLock,
            tag = "home_first_switch",
        ) { onSettingsChange(settings.copy(homeBeforeLock = it)) }
        Text(stringResource(R.string.settings_home_first_body), style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(12.dp))
    SwitchRow(
        text = stringResource(R.string.settings_screen_off_sooner),
        checked = settings.screenOffSoonerFallback,
        tag = "screen_off_sooner_switch",
    ) { onFeatureToggle(AccessKind.WRITE_SETTINGS, it) }
    Text(stringResource(R.string.settings_screen_off_sooner_body), style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(12.dp))
    SwitchRow(
        text = stringResource(R.string.settings_enhanced),
        checked = settings.enhancedCompatibility,
        tag = "enhanced_switch",
    ) { onFeatureToggle(AccessKind.NOTIFICATION_LISTENER, it) }
    Text(stringResource(R.string.settings_enhanced_body), style = MaterialTheme.typography.bodySmall)
    accessNotice?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
}

/**
 * Goodnight (SPEC F20, D21-D23, D42): main switch (asks for Modify system settings only when neither it nor the
 * accessibility access is allowed), wind-down length, warm tint / blackout / dark layer only (accessibility), and one
 * line saying how it will dim with the accesses allowed right now.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GoodnightSection(
    settings: TimerSettings,
    access: AccessState,
    notice: String?,
    onSettingsChange: (TimerSettings) -> Unit,
    onFeature: (com.plainapps.nightcap.GoodnightFeature, Boolean) -> Unit,
) {
    SectionLabel(stringResource(R.string.settings_goodnight))
    SwitchRow(
        text = stringResource(R.string.settings_goodnight_switch),
        checked = settings.goodnightEnabled,
        tag = "goodnight_switch",
    ) { onFeature(com.plainapps.nightcap.GoodnightFeature.ENABLED, it) }
    Text(stringResource(R.string.settings_goodnight_body), style = MaterialTheme.typography.bodySmall)
    if (settings.goodnightEnabled) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.settings_wind_down), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.plainapps.nightcap.GoodnightPlan.WIND_DOWN_OPTIONS.forEach { m ->
                FilterChip(
                    selected = settings.windDownMinutes == m,
                    onClick = { onSettingsChange(settings.copy(windDownMinutes = m)) },
                    label = { Text("$m min") },
                    modifier = Modifier.testTag("wind_down_$m"),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        SwitchRow(
            text = stringResource(R.string.settings_goodnight_warmth),
            checked = settings.goodnightWarmth,
            tag = "goodnight_warmth_switch",
        ) { onFeature(com.plainapps.nightcap.GoodnightFeature.WARMTH, it) }
        Text(
            stringResource(R.string.settings_goodnight_warmth_body) +
                if (settings.goodnightWarmth && !access.accessibility) "\n" + stringResource(R.string.settings_goodnight_needs_a11y) else "",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        SwitchRow(
            text = stringResource(R.string.settings_goodnight_blackout),
            checked = settings.goodnightBlackout,
            tag = "goodnight_blackout_switch",
        ) { onFeature(com.plainapps.nightcap.GoodnightFeature.BLACKOUT, it) }
        Text(
            stringResource(R.string.settings_goodnight_blackout_body) +
                if (settings.goodnightBlackout && !access.accessibility) "\n" + stringResource(R.string.settings_goodnight_needs_a11y) else "",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.settings_dim_using), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = settings.dimUsing == com.plainapps.nightcap.DimUsing.BOTH,
                onClick = { onSettingsChange(settings.copy(dimUsing = com.plainapps.nightcap.DimUsing.BOTH)) },
                label = { Text(stringResource(R.string.settings_dim_both)) },
                modifier = Modifier.testTag("dim_both"),
            )
            FilterChip(
                selected = settings.dimUsing == com.plainapps.nightcap.DimUsing.OVERLAY_ONLY,
                onClick = { onFeature(com.plainapps.nightcap.GoodnightFeature.OVERLAY_ONLY, true) },
                label = { Text(stringResource(R.string.settings_dim_overlay)) },
                modifier = Modifier.testTag("dim_overlay"),
            )
        }
        Text(stringResource(R.string.settings_dim_overlay_body), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        val method = com.plainapps.nightcap.GoodnightPlan.resolveMethod(settings.dimUsing, access.accessibility, access.writeSettings)
        Text(
            stringResource(
                when {
                    !com.plainapps.nightcap.GoodnightPlan.available(settings.dimUsing, access.accessibility, access.writeSettings) ->
                        R.string.settings_goodnight_now_none
                    method == com.plainapps.nightcap.BacklightMethod.WINDOW -> R.string.settings_goodnight_now_window
                    method == com.plainapps.nightcap.BacklightMethod.SYSTEM -> R.string.settings_goodnight_now_system
                    else -> R.string.settings_goodnight_now_window_only
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("goodnight_status"),
        )
    }
    notice?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, tag: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled, modifier = Modifier.testTag(tag))
    }
}

/** About: one line per special access, what it does, and whether it is currently allowed. */
@Composable
private fun AccessLine(textRes: Int, granted: Boolean) {
    Text(
        stringResource(textRes) + if (granted) " (allowed)" else " (not allowed)",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

/** "Add widget to Home screen" (F14): the system's own pin dialog, no permission involved. */
@Composable
private fun HomeScreenRow(context: Context) {
    val manager = remember { AppWidgetManager.getInstance(context) }
    SectionLabel(stringResource(R.string.settings_home_screen))
    Text(stringResource(R.string.settings_add_widget_body), style = MaterialTheme.typography.bodyMedium)
    if (manager.isRequestPinAppWidgetSupported) {
        TextButton(
            onClick = { manager.requestPinAppWidget(TimerWidgetProvider.componentName(context), null, null) },
            modifier = Modifier.testTag("add_widget"),
        ) { Text(stringResource(R.string.settings_add_widget)) }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun OptionRow(options: List<Int>, selected: Int, suffix: String, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text("$option$suffix") },
            )
        }
    }
}

@Composable
private fun ReliabilityRow(context: android.content.Context, settings: TimerSettings) {
    val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val exactAlarmsAvailable = android.os.Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()

    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.settings_reliability_notifications))
            Text(if (notificationsEnabled) "On" else "Off")
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.settings_reliability_exact_alarm))
            Text(if (exactAlarmsAvailable) "Available" else "Unavailable")
        }
        if (settings.everLateCompletion) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("A recent timer finished late.", style = MaterialTheme.typography.bodySmall)
                androidx.compose.material3.TextButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.fromParts("package", context.packageName, null)),
                    )
                }) { Text(stringResource(R.string.settings_reliability_battery)) }
            }
        }
    }
}
