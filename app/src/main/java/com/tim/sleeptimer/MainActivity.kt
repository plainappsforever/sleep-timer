package com.tim.sleeptimer

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.tim.sleeptimer.ui.AccessExplanationScreen
import com.tim.sleeptimer.ui.HomeScreen
import com.tim.sleeptimer.ui.PresetEditorScreen
import com.tim.sleeptimer.ui.SettingsScreen
import com.tim.sleeptimer.ui.theme.SleepTimerTheme
import java.time.LocalDateTime
import kotlinx.coroutines.launch

private enum class Screen { HOME, SETTINGS, PRESETS }

private const val STATE_PENDING_GRANT = "pending_grant"

/** A grant the user was sent to Settings for, and what to turn on once it is allowed. */
private data class PendingGrant(val kind: AccessKind, val presetId: String?, val goodnight: GoodnightFeature? = null) {
    fun encode(): String = kind.name + "|" + presetId.orEmpty() + "|" + (goodnight?.name ?: "")

    companion object {
        fun decode(raw: String?): PendingGrant? {
            if (raw == null) return null
            val parts = raw.split('|')
            val kind = runCatching { AccessKind.valueOf(parts[0]) }.getOrNull() ?: return null
            val feature = parts.getOrNull(2)?.let { f -> runCatching { GoodnightFeature.valueOf(f) }.getOrNull() }
            return PendingGrant(kind, parts.getOrNull(1)?.ifEmpty { null }, feature)
        }
    }
}

class MainActivity : ComponentActivity() {

    private lateinit var repo: TimerRepository
    private lateinit var mediaController: MediaController

    /** Try-it-now run in progress and the volume it must put back (-1 = none). */
    private var tryJob: kotlinx.coroutines.Job? = null
    private var tryOriginalIndex = -1
    /** Sessions Try it now paused through rung 0, resumed when it finishes or is interrupted. */
    private var tryPausedSessions: List<android.media.session.MediaController> = emptyList()

    // ---- special access (V2_PLAN "Special access", D15/D36) ----
    /** Grant state, re-read in onResume. */
    private var access by mutableStateOf(AccessState())
    /** Explanation screen currently shown (kind + what to turn on after the grant), or null. */
    private var explaining by mutableStateOf<PendingGrant?>(null)
    /** Sent to Android's Settings after Agree; checked in onResume. Survives activity recreation. */
    private var pendingGrant: PendingGrant? = null
    /** Reason line under "End of timer" after a refusal or a revoke. */
    private var accessNotice by mutableStateOf<String?>(null)
    /** P5: the same for "Start of timer" (Do Not Disturb access). */
    private var startNotice by mutableStateOf<String?>(null)
    /** P6: the same for "Goodnight". */
    private var goodnightNotice by mutableStateOf<String?>(null)

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Home reflects the current permission state on its own; nothing else to do. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = TimerRepository(applicationContext)
        mediaController = MediaController(applicationContext)
        pendingGrant = PendingGrant.decode(savedInstanceState?.getString(STATE_PENDING_GRANT))
        // Publish the shortcuts and redraw the widget once per launch (first run, upgrade, launcher reset).
        lifecycleScope.launch { TimerUi.settingsChanged(applicationContext) }

        setContent {
            val settings by repo.settings.collectAsStateWithLifecycle(initialValue = TimerSettings())
            val state by repo.timerState.collectAsStateWithLifecycle(initialValue = TimerState())
            val owed by repo.owedRestore.collectAsStateWithLifecycle(initialValue = OwedRestore())
            var screen by remember { mutableStateOf(Screen.HOME) }
            var focusPresetId by remember { mutableStateOf<String?>(null) }
            val saveSettings: (TimerSettings) -> Unit = { updated ->
                lifecycleScope.launch {
                    repo.saveSettings(updated)
                    // Presets may have changed: widget buttons and app shortcuts follow (D24/D25).
                    TimerUi.settingsChanged(applicationContext)
                    // P5: the nightly start follows its settings at once (alarm 104, BootReceiver on/off).
                    SystemAlarms.syncSchedule(applicationContext, 0L, "settings")
                }
            }

            BackHandler(enabled = screen != Screen.HOME) {
                screen = if (screen == Screen.PRESETS && focusPresetId == null) Screen.SETTINGS else Screen.HOME
            }

            SleepTimerTheme(themeMode = settings.themeMode) {
                // Settings stays composed under the explanation screen, so its scroll position survives.
                androidx.compose.foundation.layout.Box {
                    when (screen) {
                        Screen.HOME -> HomeScreen(
                            state = state,
                            settings = settings,
                            notificationsEnabled = areNotificationsEnabled(),
                            isMusicActive = { mediaController.isMusicActive() },
                            onStartDuration = { minutes, presetId -> startTimer(minutes, presetId) },
                            onStartUntil = { hour, minute ->
                                startTimer(TimerPlan.minutesUntil(LocalDateTime.now(), hour, minute), null)
                            },
                            onEditPreset = { id -> focusPresetId = id; screen = Screen.PRESETS },
                            onExtend = { sendServiceAction(TimerActionReceiver.ACTION_EXTEND) },
                            onStop = { sendServiceAction(TimerActionReceiver.ACTION_STOP) },
                            onRestoreVolume = { sendServiceAction(TimerActionReceiver.ACTION_RESTORE_VOLUME) },
                            onOpenNotificationSettings = ::openNotificationSettings,
                            onRequestNotificationPermission = ::requestNotificationPermissionIfNeeded,
                            onOpenSettings = { screen = Screen.SETTINGS },
                            onTryItNow = { onResult -> runTryItNow(onResult) },
                            // P5: our DND is on (and really on: the user may have switched it off in the shade).
                            dndUntilMillis = owed.dndUntilMillis.takeIf { owed.dndActive && DndController(this@MainActivity).isDndOn() },
                            onEndDnd = { lifecycleScope.launch { RestoreManager.endDnd(applicationContext, "app") } },
                        )
                        Screen.SETTINGS -> SettingsScreen(
                            settings = settings,
                            onBack = { screen = Screen.HOME },
                            onSettingsChange = saveSettings,
                            onEditPresets = { focusPresetId = null; screen = Screen.PRESETS },
                            access = access,
                            accessNotice = accessNotice,
                            startNotice = startNotice,
                            onFeatureToggle = { kind, on -> onFeatureToggle(kind, on, null) },
                            // P5: turning on the automatic start is like a first Start: it may ask for notifications.
                            onRequestNotificationPermission = ::requestNotificationPermissionIfNeeded,
                            goodnightNotice = goodnightNotice,
                            onGoodnightFeature = { feature, on -> onGoodnightFeature(feature, on, null) },
                        )
                        Screen.PRESETS -> PresetEditorScreen(
                            presets = settings.presets,
                            globalFadeSeconds = settings.fadeSeconds,
                            initiallyExpandedId = focusPresetId,
                            onBack = { screen = if (focusPresetId == null) Screen.SETTINGS else Screen.HOME },
                            onPresetsChange = { updated -> saveSettings(settings.copy(presets = updated)) },
                            lockGranted = access.accessibility,
                            onPresetLockChange = { preset, value ->
                                if (value == true) {
                                    onFeatureToggle(AccessKind.ACCESSIBILITY, true, preset.id)
                                } else {
                                    saveSettings(settings.copy(presets = Presets.update(settings.presets, preset.copy(lockAtEnd = value))))
                                }
                            },
                            dndGranted = access.dndPolicy,
                            onPresetDndChange = { preset, value ->
                                if (value == true) {
                                    onFeatureToggle(AccessKind.DND_POLICY, true, preset.id)
                                } else {
                                    saveSettings(settings.copy(presets = Presets.update(settings.presets, preset.copy(dnd = value))))
                                }
                            },
                            goodnightPossible = GoodnightPlan.available(settings.dimUsing, access.accessibility, access.writeSettings),
                            onPresetGoodnightChange = { preset, value ->
                                if (value == true) {
                                    onGoodnightFeature(GoodnightFeature.ENABLED, true, preset.id)
                                } else {
                                    saveSettings(settings.copy(presets = Presets.update(settings.presets, preset.copy(goodnight = value))))
                                }
                            },
                        )
                    }
                    val explain = explaining
                    if (explain != null) {
                        BackHandler { explaining = null }
                        AccessExplanationScreen(
                            goodnight = explain.goodnight == GoodnightFeature.ENABLED,
                            kind = explain.kind,
                            onAgree = {
                                explaining = null
                                pendingGrant = explain
                                SpecialAccess.openSettings(this@MainActivity, explain.kind)
                            },
                            onCancel = { explaining = null },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        access = SpecialAccess.snapshot(this)
        lifecycleScope.launch {
            // App open: a phone setting still owed from an earlier run goes back (rule 8, D36); P6: also a Goodnight
            // left by a run that no longer has its service (force-stop), since the user is awake and here.
            RestoreManager.applyOwed(applicationContext, "app open", orphanCheck = true)
            // v2.1 (REVIEW_V2 row 1): a run with no service in this process (force-stop, or a death the watchdog has not
            // caught yet) is re-attached: its countdown resumes, or its end sequence completes without a screen action.
            if (TimerRepository(applicationContext).currentState().isActive && !TimerService.alive) {
                startForegroundService(
                    Intent(applicationContext, TimerService::class.java)
                        .setAction(TimerService.ACTION_RECOVER)
                        .putExtra(TimerService.EXTRA_REASON, "app open")
                        .putExtra(TimerService.EXTRA_QUIET_END, true),
                )
            }
            reconcileAccess()
            // P5: re-arm the nightly start (a force-stop clears alarms).
            SystemAlarms.syncSchedule(applicationContext, 0L, "app open")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingGrant?.let { outState.putString(STATE_PENDING_GRANT, it.encode()) }
    }

    /**
     * A switch that needs special access was flipped. Off: saved at once. On: saved at once if the access
     * is already allowed, otherwise the explanation screen comes first (zero-prompt rule, D15).
     * [presetId] non-null = a preset's own "Lock screen at end: On".
     */
    private fun onFeatureToggle(kind: AccessKind, on: Boolean, presetId: String?) {
        accessNotice = null
        startNotice = null
        lifecycleScope.launch {
            if (on && !SpecialAccess.isGranted(this@MainActivity, kind)) {
                explaining = PendingGrant(kind, presetId)
                return@launch
            }
            saveFeature(PendingGrant(kind, presetId), on)
        }
    }

    /**
     * P6 Goodnight switches (D42). On: saved at once if possible with the accesses allowed now; otherwise the
     * explanation for the missing one first — Modify system settings for the main switch (accessibility also works and
     * is not asked for here), accessibility for warm tint / blackout / dark layer only.
     */
    private fun onGoodnightFeature(feature: GoodnightFeature, on: Boolean, presetId: String?) {
        goodnightNotice = null
        lifecycleScope.launch {
            if (on) {
                val s = repo.currentSettings()
                val a11y = SpecialAccess.isGranted(this@MainActivity, AccessKind.ACCESSIBILITY)
                val write = SpecialAccess.isGranted(this@MainActivity, AccessKind.WRITE_SETTINGS)
                val needed = when (feature) {
                    GoodnightFeature.ENABLED -> when {
                        GoodnightPlan.available(s.dimUsing, a11y, write) -> null
                        s.dimUsing == DimUsing.OVERLAY_ONLY -> AccessKind.ACCESSIBILITY
                        else -> AccessKind.WRITE_SETTINGS
                    }
                    else -> if (a11y) null else AccessKind.ACCESSIBILITY
                }
                if (needed != null) {
                    explaining = PendingGrant(needed, presetId, feature)
                    return@launch
                }
            }
            saveGoodnightFeature(feature, on, presetId)
        }
    }

    private suspend fun saveGoodnightFeature(feature: GoodnightFeature, on: Boolean, presetId: String?) {
        val s = repo.currentSettings()
        val updated = if (presetId != null) {
            val preset = s.presets.firstOrNull { it.id == presetId } ?: return
            s.copy(presets = Presets.update(s.presets, preset.copy(goodnight = if (on) true else null)))
        } else {
            when (feature) {
                GoodnightFeature.ENABLED -> s.copy(goodnightEnabled = on)
                GoodnightFeature.WARMTH -> s.copy(goodnightWarmth = on)
                GoodnightFeature.BLACKOUT -> s.copy(goodnightBlackout = on)
                GoodnightFeature.OVERLAY_ONLY -> s.copy(dimUsing = if (on) DimUsing.OVERLAY_ONLY else DimUsing.BOTH)
            }
        }
        repo.saveSettings(updated)
        TimerUi.settingsChanged(applicationContext)
    }

    private suspend fun saveFeature(grant: PendingGrant, on: Boolean) {
        val s = repo.currentSettings()
        val updated = if (grant.presetId != null) {
            val preset = s.presets.firstOrNull { it.id == grant.presetId } ?: return
            val changed = when (grant.kind) {
                AccessKind.DND_POLICY -> preset.copy(dnd = if (on) true else null)
                else -> preset.copy(lockAtEnd = if (on) true else null)
            }
            s.copy(presets = Presets.update(s.presets, changed))
        } else {
            when (grant.kind) {
                AccessKind.ACCESSIBILITY -> s.copy(lockAtEnd = on)
                AccessKind.NOTIFICATION_LISTENER -> s.copy(enhancedCompatibility = on)
                AccessKind.WRITE_SETTINGS -> s.copy(screenOffSoonerFallback = on)
                AccessKind.DND_POLICY -> s.copy(dndAtStart = on)
            }
        }
        repo.saveSettings(updated)
        TimerUi.settingsChanged(applicationContext)
    }

    /**
     * Back from Android's Settings (or any return to the app): a pending grant turns its switch on if
     * it was allowed; switches whose access is gone turn themselves off, with the reason (F16 AC).
     * Presets set to "On" keep that value and show "Not active" in the editor until access is back.
     */
    private suspend fun reconcileAccess() {
        pendingGrant?.let { grant ->
            pendingGrant = null
            val feature = grant.goodnight
            if (feature != null) {
                if (access.granted(grant.kind)) {
                    saveGoodnightFeature(feature, true, grant.presetId)
                } else {
                    goodnightNotice = getString(
                        if (feature == GoodnightFeature.ENABLED) R.string.access_not_granted_goodnight else R.string.access_not_granted_accessibility,
                    )
                }
            } else if (access.granted(grant.kind)) {
                saveFeature(grant, true)
            } else if (grant.kind == AccessKind.DND_POLICY) {
                startNotice = getString(R.string.access_not_granted_dnd)
            } else {
                accessNotice = getString(
                    when (grant.kind) {
                        AccessKind.ACCESSIBILITY -> R.string.access_not_granted_accessibility
                        AccessKind.NOTIFICATION_LISTENER -> R.string.access_not_granted_listener
                        else -> R.string.access_not_granted_write
                    },
                )
            }
        }
        val s = repo.currentSettings()
        var updated = s
        val lost = mutableListOf<Int>()
        if (s.lockAtEnd && !access.accessibility) {
            updated = updated.copy(lockAtEnd = false); lost += R.string.access_lost_accessibility
        }
        if (s.enhancedCompatibility && !access.notificationListener) {
            updated = updated.copy(enhancedCompatibility = false); lost += R.string.access_lost_listener
        }
        if (s.screenOffSoonerFallback && !access.writeSettings) {
            updated = updated.copy(screenOffSoonerFallback = false); lost += R.string.access_lost_write
        }
        if (updated != s) accessNotice = lost.joinToString("\n") { getString(it) }
        if (s.dndAtStart && !access.dndPolicy) {
            updated = updated.copy(dndAtStart = false)
            startNotice = getString(R.string.access_lost_dnd)
        }
        // P6 (D38 pattern): Goodnight needs the accessibility access or Modify system settings ("dark layer only":
        // accessibility). Warm tint / blackout just do nothing without accessibility (the section says so).
        if (s.goodnightEnabled && !GoodnightPlan.available(s.dimUsing, access.accessibility, access.writeSettings)) {
            updated = updated.copy(goodnightEnabled = false)
            goodnightNotice = getString(R.string.access_lost_goodnight)
        }
        if (updated != s) repo.saveSettings(updated)
    }

    private fun startTimer(durationMinutes: Int, presetId: String?) {
        requestNotificationPermissionIfNeeded()
        val intent = Intent(this, TimerService::class.java)
            .setAction(TimerService.ACTION_START)
            .putExtra(TimerService.EXTRA_DURATION_MINUTES, TimerPlan.clampDurationMinutes(durationMinutes))
            .apply { presetId?.let { putExtra(TimerService.EXTRA_PRESET_ID, it) } }
        startForegroundService(intent)
    }

    private fun sendServiceAction(action: String) {
        startForegroundService(Intent(this, TimerService::class.java).setAction(action))
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && !areNotificationsEnabled()) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun areNotificationsEnabled(): Boolean =
        androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        startActivity(intent)
    }

    /**
     * Android 17 background-audio hardening (D27, P2 audit): volume/focus calls are ignored once no
     * activity of ours is visible and no FGS runs. Try it now runs from this Activity, so if the user
     * leaves mid-run, put the volume back here while we are still in the foreground.
     */
    override fun onPause() {
        if (tryJob?.isActive == true) {
            tryJob?.cancel()
            if (tryOriginalIndex >= 0) mediaController.setStreamIndex(tryOriginalIndex)
            mediaController.abandonFocus()
            tryOriginalIndex = -1
            SessionPauser(this).resume(tryPausedSessions)
            tryPausedSessions = emptyList()
        }
        super.onPause()
    }

    /** SPEC F9: dry run of the real end sequence, run directly from the foreground Activity. */
    private fun runTryItNow(onResult: (String) -> Unit) {
        if (tryJob?.isActive == true) return
        tryJob = lifecycleScope.launch {
            val original = mediaController.currentStreamIndex()
            tryOriginalIndex = original
            if (!mediaController.isVolumeFixed()) {
                for (step in TimerPlan.rampSteps(original, TimerPlan.MIN_STREAM_INDEX, durationSeconds = 3)) {
                    kotlinx.coroutines.delay(step.delayMillis)
                    mediaController.setStreamIndex(step.targetIndex)
                }
            }
            // Rung 0 first when "Enhanced compatibility" is on (D17), exactly like the real end sequence.
            val sessionPauser = SessionPauser(this@MainActivity)
            var session = SessionPauser.Result(false, emptyList(), emptyList())
            var activeAfterSession = true
            if (repo.currentSettings().enhancedCompatibility) {
                session = sessionPauser.pausePlaying()
                tryPausedSessions = session.paused
                if (session.tried && session.paused.isNotEmpty()) activeAfterSession = mediaController.isMusicActiveAfter(1_500L)
            }
            var activeAfterMediaKey = false
            var activeAfterAudioFocus = false
            val sessionRungTried = session.tried && session.paused.isNotEmpty()
            if (!sessionRungTried || activeAfterSession) {
                mediaController.sendPause()
                kotlinx.coroutines.delay(1500)
                activeAfterMediaKey = mediaController.isMusicActive()
                activeAfterAudioFocus = activeAfterMediaKey
                if (activeAfterMediaKey) {
                    mediaController.requestFocusGain()
                    kotlinx.coroutines.delay(1500)
                    activeAfterAudioFocus = mediaController.isMusicActive()
                }
            }
            val ladder = TimerPlan.evaluatePauseLadder(sessionRungTried, activeAfterSession, activeAfterMediaKey, activeAfterAudioFocus)
            mediaController.setStreamIndex(original)
            tryOriginalIndex = -1
            mediaController.abandonFocus()
            sessionPauser.resume(session.paused)
            if (ladder.stoppedAtRung != PauseRung.SESSION) mediaController.sendPlay()
            tryPausedSessions = emptyList()
            val resultText = when (ladder.stoppedAtRung) {
                PauseRung.SESSION -> getString(R.string.try_result_session, SessionPlan.sessionResultLabel(session.appLabels))
                PauseRung.MEDIA_KEY -> getString(R.string.try_result_media_key)
                PauseRung.AUDIO_FOCUS -> getString(R.string.try_result_audio_focus)
                PauseRung.MUTE -> getString(R.string.try_result_mute_only)
            }
            onResult(resultText)
        }
    }
}
