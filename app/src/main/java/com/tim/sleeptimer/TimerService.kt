package com.tim.sleeptimer

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Foreground service (type specialUse) that owns the whole countdown: scheduling the exact alarm,
 * the ongoing notification, the fade → pause-ladder → restore end sequence, extend and stop.
 * Started only from a visible Activity (MainActivity Start button or StartTrampolineActivity),
 * per DECISIONS D3. Never uses shortService.
 */
class TimerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var repo: TimerRepository
    private lateinit var mediaController: MediaController
    private lateinit var alarmManager: AlarmManager

    private var endSequenceJob: Job? = null
    private var countdownTickerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    /** Fallback A only (no exact alarm): held for the whole countdown, renewed on every new deadline. */
    private var countdownLock: PowerManager.WakeLock? = null
    private val endSequenceStarted = AtomicBoolean(false)
    private lateinit var shakeMonitor: ShakeMonitor
    private val shakeMutex = Mutex()
    /** Start commands run one at a time (L13 review: a scheduled start and a tap at the same moment). */
    private val startMutex = Mutex()
    /**
     * v2.1 (REVIEW_V2 row 9): every command runs one at a time (+15 +15, +15 and End now, a scheduled start and a tap,
     * a watchdog and a tile tap after a reboot). Long work (the end sequence, waits) runs in its own jobs, never here.
     */
    private val commandMutex = Mutex()
    /** Latest onStartCommand id: [stopIfIdle] stops only if no newer start is queued (REVIEW_V2 row 9). */
    @Volatile private var lastStartId = 0
    private val shakeExtendInProgress = AtomicBoolean(false)
    /** Last real running notification, re-posted instead of a placeholder on repeat start commands. */
    @Volatile private var lastNotifiedState: TimerState? = null
    /** P4: end-of-run work that must keep the service alive after the run is DONE. */
    @Volatile private var focusHoldPending = false
    @Volatile private var screenOffWaitJob: Job? = null
    private var screenOffReceiver: BroadcastReceiver? = null
    /** P6: the Goodnight stepping coroutine (one per process; see [steppingLoop]). */
    @Volatile private var steppingJob: Job? = null
    @Volatile private var waitForGoodnight = false
    /** v2.1 (REVIEW_V2 row 1): re-arms alarm 105 while the end sequence or the screen-off wait runs. */
    @Volatile private var watchdogJob: Job? = null
    /** v2.1 (REVIEW_V2 row 7): listens for sound for 20 s after the restore. */
    @Volatile private var postRestoreWatchJob: Job? = null
    private val endSequenceRunning = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        alive = true
        repo = TimerRepository(applicationContext)
        mediaController = MediaController(applicationContext)
        alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        TimerNotifications.ensureChannels(this)
        shakeMonitor = ShakeMonitor(this) { scope.launch { commandMutex.withLock { onShakeDetected() } } }
        // Turning "Shake to extend" on/off mid-run takes effect at once (D33).
        scope.launch {
            repo.settings.map { it.shakeToExtendEnabled }.distinctUntilChanged().drop(1)
                .collect { updateShakeMonitoring("setting changed") }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForegroundPlaceholder()
        lastStartId = startId
        val action = intent?.action
        val capturedIntent = intent
        scope.launch {
            commandMutex.withLock { handleCommand(action, capturedIntent) }
        }
        return START_NOT_STICKY
    }

    private suspend fun handleCommand(action: String?, capturedIntent: Intent?) {
        when (action) {
            ACTION_START -> handleStart(capturedIntent)
            TimerActionReceiver.ACTION_STOP -> {
                endSequenceJob?.cancelAndJoin()
                countdownTickerJob?.cancel()
                handleStop()
            }
            TimerActionReceiver.ACTION_EXTEND -> extendNow()
            // Never replace a running end sequence's job (alarm 100 + watchdog + recovery can meet; REVIEW_V2 row 9).
            ACTION_ALARM_FIRED -> if (endSequenceJob?.isActive != true) endSequenceJob = scope.launch { runAlarmFired() }
            TimerActionReceiver.ACTION_REDUCE -> handleReduce()
            TimerActionReceiver.ACTION_END_NOW -> handleEndNow()
            TimerActionReceiver.ACTION_RESTORE_VOLUME -> handleRestoreVolume()
            // P6: alarm 102 (wind-down start, and the 30 s watchdog while stepping).
            ACTION_WIND_DOWN -> if (lastNotifiedState?.isActive == true) armGoodnight("alarm 102") else handleRecovery("alarm 102")
            // Debug builds only: behave exactly as if the accelerometer had reported a shake.
            ACTION_DEBUG_SHAKE -> if (BuildConfig.DEBUG_TIMER) onShakeDetected() else Unit
            // v2.1: alarm 105. In a live process with the end sequence or the wait running it is only a late
            // re-arm; in a fresh process (the old one died) it re-attaches or completes the run.
            ACTION_WATCHDOG -> if (endSequenceRunning.get() || screenOffWaitJob != null) {
                Log.i(TAG, "Watchdog: alive")
            } else {
                handleRecovery("watchdog")
            }
            // v2.1: app open (orphaned run after a force-stop) and BOOT_COMPLETED / update (BootReceiver).
            ACTION_RECOVER -> handleRecovery(
                capturedIntent?.getStringExtra(EXTRA_REASON) ?: "recover",
                quietEnd = capturedIntent?.getBooleanExtra(EXTRA_QUIET_END, false) == true,
            )
            // ACTION_SHAKE_WINDOW (alarm 101) and cold restarts: recovery re-evaluates the window.
            else -> handleRecovery(action ?: "restart")
        }
    }

    override fun onDestroy() {
        // Destroyed during a screen-off wait (should not happen: stopIfIdle waits for it): pay the debt now.
        if (screenOffWaitJob != null) {
            unregisterScreenOffReceiver()
            val owed = kotlinx.coroutines.runBlocking { repo.currentOwedRestore() }
            owed.screenOffTimeout?.let { RestoreManager.restoreNowBlocking(this, it) }
            // P6 (D23): a Goodnight layer still up stays until the screen goes off; the accessibility service owns that.
            SleepAccessibilityService.instance?.let { a11y -> android.os.Handler(mainLooper).post { a11y.releaseGoodnightOnScreenOff() } }
        }
        steppingJob?.cancel()
        alive = false
        shakeMonitor.stop("service destroyed")
        // Safety net: never leave audio focus held after the service is gone (V2_LOG P2 audit).
        mediaController.abandonFocus()
        releaseWakeLockIfHeld()
        releaseCountdownLock()
        scope.cancel()
        super.onDestroy()
    }

    /** Persist the run state, then redraw the widget and tile (TimerUi); the notification is [updateNotification]. */
    private suspend fun saveAndRefresh(state: TimerState) {
        repo.saveState(state)
        TimerUi.refresh(this, state)
    }

    // ---- action handlers ----

    private suspend fun handleStart(intent: Intent?) = startMutex.withLock {
        // Owed phone settings from an earlier run go back first (rule 8, D36/D39/D42); a run left RUNNING by
        // a reboot is closed there too.
        RestoreManager.applyOwed(this, "timer start")
        if (repo.currentState().isActive) {
            // A live run: resume it in this instance (notification, alarms) instead of a stuck placeholder.
            handleRecovery("start while running")
            return@withLock
        }
        screenOffWaitJob?.cancel()
        stopStepping()
        val settings = repo.currentSettings()
        val preset = intent?.getStringExtra(EXTRA_PRESET_ID)?.let { id -> settings.presets.firstOrNull { it.id == id } }
        var durationMinutes = intent?.getIntExtra(EXTRA_DURATION_MINUTES, -1) ?: -1
        if (durationMinutes <= 0 && preset != null) durationMinutes = preset.minutes
        if (durationMinutes <= 0) {
            releaseForegroundOrShowWait()
            stopIfIdle()
            return@withLock
        }
        endSequenceStarted.set(false)
        val now = System.currentTimeMillis()
        // Debug builds only: a seconds-resolution run for emulator tests (DebugCommandReceiver).
        val debugSeconds = if (BuildConfig.DEBUG_TIMER) intent?.getIntExtra(EXTRA_DEBUG_SECONDS, -1) ?: -1 else -1
        val deadline = if (debugSeconds > 0) {
            now + debugSeconds * 1000L
        } else {
            now + TimerPlan.clampDurationMinutes(durationMinutes) * 60_000L
        }
        val scheduled = intent?.getBooleanExtra(EXTRA_SCHEDULED, false) == true
        // P5 (D19/D39): effective start actions of this run; bedtime volume only ever lowers.
        val dndWanted = preset?.effectiveDnd(settings.dndAtStart) ?: settings.dndAtStart
        val bedtimePercent = Presets.effectiveBedtimePercent(preset?.bedtimeVolumePercent, settings.bedtimeVolumeEnabled, settings.bedtimeVolumePercent)
        val preStartIndex = mediaController.currentStreamIndex()
        val bedtimeTarget = if (mediaController.isVolumeFixed()) {
            null
        } else {
            TimerPlan.bedtimeTarget(preStartIndex, bedtimePercent, mediaController.maxStreamIndex())
        }
        // P6 (F20, D42): Goodnight for this run if wanted and possible with the current grants; else a normal timer.
        val goodnightWanted = preset?.effectiveGoodnight(settings.goodnightEnabled) ?: settings.goodnightEnabled
        val goodnightPossible = GoodnightPlan.available(
            settings.dimUsing,
            SpecialAccess.isGranted(this, AccessKind.ACCESSIBILITY),
            SpecialAccess.isGranted(this, AccessKind.WRITE_SETTINGS),
        )
        val fadeForRun = preset?.effectiveFadeSeconds(settings.fadeSeconds) ?: settings.fadeSeconds
        val goodnight = if (goodnightWanted && goodnightPossible) {
            GoodnightPlan.newRun(now, deadline, fadeForRun, settings.windDownMinutes, settings.goodnightWarmth, settings.goodnightBlackout, settings.dimUsing)
        } else {
            null
        }
        // The Goodnight part is only written through its own key (Goodnight.update); the old run's is replaced here.
        repo.saveGoodnight(goodnight)
        var newState = TimerState(
            phase = RunPhase.RUNNING,
            doneReason = DoneReason.NONE,
            startWallClockMillis = now,
            deadlineWallClockMillis = deadline,
            originalStreamIndex = -1,
            lastSetStreamIndex = -1,
            mutedInsteadOfPaused = false,
            fadeSeconds = fadeForRun,
            extendStepMinutes = settings.extendStepMinutes,
            presetId = preset?.id,
            presetLabel = preset?.name,
            restoreVolumeAfterPause = settings.restoreVolumeAfterPause,
            lockAtEnd = EndActions.effectiveLock(preset?.lockAtEnd, settings.lockAtEnd),
            homeBeforeLock = settings.homeBeforeLock,
            screenOffSooner = settings.screenOffSoonerFallback,
            preStartStreamIndex = if (bedtimeTarget != null) preStartIndex else -1,
            bedtimeStreamIndex = bedtimeTarget ?: -1,
            startedBySchedule = scheduled,
            goodnight = goodnight,
        )
        // RUNNING is saved before any phone setting changes, so an app-open applyOwed in between leaves
        // the new run's bedtime volume alone (L13 review).
        saveAndRefresh(newState)
        SystemAlarms.refreshBootReceiver(this) // v2.1: a reboot mid-run hands the run back to this service
        repo.saveSettings(
            settings.copy(
                lastUsedDurationMinutes = TimerPlan.clampDurationMinutes(durationMinutes),
                lastUsedPresetId = preset?.id,
            ),
        )
        if (bedtimeTarget != null) RestoreManager.applyBedtimeVolume(this, mediaController, preStartIndex, bedtimeTarget)
        if (dndWanted) {
            // v2.1 owner decision: until the morning time or 9 hours after the start, whichever is earlier.
            val until = Schedule.dndUntil(ZonedDateTime.now(), settings.dndUntilMinuteOfDay)
            if (RestoreManager.startDnd(this, until.toInstant().toEpochMilli())) {
                newState = newState.copy(dnd = true)
                saveAndRefresh(newState)
            }
        }
        Log.i(
            TAG,
            "Start: minutes=$durationMinutes preset=${preset?.id} fade=${newState.fadeSeconds}s " +
                "lock=${newState.lockAtEnd} home=${newState.homeBeforeLock} screenOffSooner=${newState.screenOffSooner} " +
                "bedtime=${bedtimePercent}% ${preStartIndex}->${bedtimeTarget ?: "-"} dnd=${newState.dnd}/$dndWanted scheduled=$scheduled " +
                "goodnight=${goodnight != null}/$goodnightWanted" +
                (goodnight?.let { " windDown=${it.progress.startMillis}..${it.progress.endMillis} dim=${it.dimUsing} warm=${it.warmth} black=${it.blackout}" } ?: ""),
        )
        updateNotification(newState)
        scheduleDeadlineAlarm(newState.deadlineWallClockMillis, newState.fadeSeconds)
        updateShakeMonitoring("start")
        armGoodnight("start")
    }

    private suspend fun handleStop() {
        stopStepping()
        val state = repo.currentState()
        cancelAlarm()
        cancelWatchdog()
        val stopped = RunTransitions.stop(state, mediaController.currentStreamIndex(), mediaController.isMusicActive())
        if (state.isActive) {
            stopped.volumeTarget?.let { mediaController.setStreamIndex(it) }
            Log.i(TAG, "Stop: volume -> ${stopped.volumeTarget ?: "unchanged"} (touched=${stopped.touched})")
            if (stopped.clearOwedStream) repo.clearOwedStreamIndex()
        }
        releaseWakeLockIfHeld()
        endShakeWindow("stopped")
        lastNotifiedState = null
        saveAndRefresh(stopped.state)
        SystemAlarms.refreshBootReceiver(this)
        // P6 (D23): the user's own Stop puts the screen back at once (layer off, brightness back).
        RestoreManager.applyOwedBrightness(this, "stop")
        releaseForegroundOrShowWait()
        stopIfIdle()
    }

    /** +extend from the notification, tile, app or a shake: one path (V2_PLAN "Pipelines"). */
    private suspend fun extendNow() {
        val wasFading = repo.currentState().phase == RunPhase.FADING
        endSequenceJob?.cancelAndJoin()
        countdownTickerJob?.cancel()
        stopStepping()
        handleExtend(wasFading)
        armGoodnight("extended") // also after a no-op extend: the stepping loop must come back
    }

    private suspend fun handleExtend(wasFading: Boolean) {
        val current = repo.currentState()
        if (!current.isActive) return
        val extended = RunTransitions.extend(current, System.currentTimeMillis(), mediaController.currentStreamIndex())
        if (extended == null) {
            // v2.1 (REVIEW_V2 row 5): a stale run has nothing to extend; it is closed and its debts paid instead.
            Log.i(TAG, "Extend: stale run, closed instead")
            handleRecovery("extend of a stale run")
            return
        }
        if (wasFading) {
            for (step in extended.rampUp) {
                delay(step.delayMillis)
                mediaController.setStreamIndex(step.targetIndex)
            }
        }
        endSequenceStarted.set(false)
        val state = extended.state
        val newDeadline = state.deadlineWallClockMillis
        Log.i(TAG, "Extend: deadline -> $newDeadline")
        saveAndRefresh(state)
        replanGoodnight(newDeadline, state.fadeSeconds)
        scheduleDeadlineAlarm(newDeadline, state.fadeSeconds)
        updateNotification(state)
        notifyExtended(newDeadline)
        updateShakeMonitoring("extended")
    }

    /** Notification "-5 min": pull the deadline in, never closer than fade + 60 s (TimerPlan.reducedDeadline). */
    private suspend fun handleReduce() {
        val state = repo.currentState()
        // hidden while fading; stale taps are ignored
        val updated = RunTransitions.reduce(state, System.currentTimeMillis()) ?: return
        val newDeadline = updated.deadlineWallClockMillis
        endSequenceJob?.cancelAndJoin()
        countdownTickerJob?.cancel()
        stopStepping()
        endSequenceStarted.set(false)
        saveAndRefresh(updated)
        replanGoodnight(newDeadline, updated.fadeSeconds)
        scheduleDeadlineAlarm(newDeadline, updated.fadeSeconds)
        updateNotification(updated)
        Log.i(TAG, "Reduce: deadline ${state.deadlineWallClockMillis} -> $newDeadline")
        updateShakeMonitoring("reduced")
        armGoodnight("reduced")
    }

    /** Notification "End now": run the end sequence immediately with fade = min(fade, 10 s). */
    private suspend fun handleEndNow() {
        val updated = RunTransitions.endNow(repo.currentState(), System.currentTimeMillis()) ?: return
        val fade = updated.fadeSeconds
        endSequenceJob?.cancelAndJoin()
        countdownTickerJob?.cancel()
        cancelAlarm()
        stopStepping()
        saveAndRefresh(updated)
        replanGoodnight(updated.deadlineWallClockMillis, fade)
        endSequenceStarted.set(false)
        Log.i(TAG, "End now: fade=${fade}s")
        updateShakeMonitoring("end now")
        endSequenceJob = scope.launch { runAlarmFired() }
    }

    private suspend fun handleRestoreVolume() {
        val state = repo.currentState()
        if (state.mutedInsteadOfPaused) {
            mutedRestoreIndex(state)?.let { mediaController.setStreamIndex(it) }
            repo.clearOwedStreamIndex()
            saveAndRefresh(state.copy(mutedInsteadOfPaused = false))
        }
        NotificationManagerCompat.from(this).cancel(TimerNotifications.NOTIF_ID_RESULT)
        if (!repo.currentState().isActive) {
            releaseForegroundOrShowWait()
            stopIfIdle()
        } else {
            // A new run started meanwhile: RESTORE must not stop it (P2 audit).
            lastNotifiedState?.let { updateNotification(it) }
        }
    }

    /** Where "Restore volume" after a mute goes: the pre-start index of an untouched bedtime run, else the fade start. */
    private fun mutedRestoreIndex(state: TimerState): Int? =
        TimerPlan.restoreTargetIndex(false, true, true, state.originalStreamIndex, state.preStartStreamIndex, state.volumeTouchedDuringRun)

    /**
     * Cold start via alarm hand-off (100 / watchdog 105), app open, boot, or any restart without a specific action:
     * resume the countdown, complete the end sequence, close a stale run, or finish the end-of-run work (v2.1,
     * REVIEW_V2 row 1). [quietEnd] (app open, boot): if the end sequence has to run now, it pauses and restores
     * but does no screen action — the user is awake and using the phone.
     */
    private suspend fun handleRecovery(reason: String, quietEnd: Boolean = false) {
        var state = repo.currentState()
        var action = RestoreRules.recovery(state.isActive, state.deadlineWallClockMillis, state.fadeSeconds, System.currentTimeMillis())
        Log.i(TAG, "Recovery ($reason): $action phase=${state.phase} quiet=$quietEnd")
        if (action == RecoveryAction.CLOSE_STALE) {
            // Long past its deadline: closed and its debts paid (the fade's volume becomes a record, paid once quiet).
            RestoreManager.applyOwed(this, "recovery: stale run")
            state = repo.currentState()
            action = RestoreRules.recovery(state.isActive, state.deadlineWallClockMillis, state.fadeSeconds, System.currentTimeMillis())
        }
        if (action == RecoveryAction.IDLE) {
            resumeEndOfRunWork(reason)
            return
        }
        updateNotification(state)
        TimerUi.refresh(this, state)
        if (action == RecoveryAction.FINISH_END_SEQUENCE) {
            if (endSequenceJob?.isActive != true) endSequenceJob = scope.launch { runAlarmFired(quiet = quietEnd) }
        } else {
            scheduleDeadlineAlarm(state.deadlineWallClockMillis, state.fadeSeconds)
        }
        updateShakeMonitoring("recovery")
        armGoodnight("recovery")
    }

    /**
     * No run active in a (re)started service: a screen-off timeout or Goodnight still owed while the screen is on
     * and the run ended recently -> the screen-off wait again (the process died during it); otherwise pay what is
     * owed now (rule 8) and stop.
     */
    private suspend fun resumeEndOfRunWork(reason: String) {
        if (screenOffWaitJob == null) {
            val state = repo.currentState()
            val owed = repo.currentOwedRestore()
            val goodnightOwed = Goodnight.pending(state.goodnight) || owed.brightness != null
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val endedAt = state.deadlineWallClockMillis
            if (pm.isInteractive && (owed.screenOffTimeout != null || goodnightOwed) && endedAt > 0L &&
                System.currentTimeMillis() < endedAt + EndActions.GOODNIGHT_MAX_WAIT_MS
            ) {
                Log.i(TAG, "Recovery ($reason): screen-off wait resumed")
                startScreenOffWait(goodnight = goodnightOwed && owed.screenOffTimeout == null, startedAt = endedAt)
                return
            }
            if (!owed.isEmpty || Goodnight.pending(state.goodnight)) RestoreManager.applyOwed(this, "recovery ($reason)")
        }
        releaseForegroundOrShowWait()
        stopIfIdle()
    }

    // ---- the end sequence ----

    private suspend fun runAlarmFired(quiet: Boolean = false) {
        var state = repo.currentState()
        if (!state.isActive) return
        val fadeStartAt = state.deadlineWallClockMillis - state.fadeSeconds * 1000L
        val waitMs = fadeStartAt - System.currentTimeMillis()
        if (waitMs > 0) {
            // REVIEW_V2 (checked, not raised): hold the CPU through the ~5 s gap too; with nothing playing it can sleep
            // there. Only for that short gap: a longer wait (a re-planned deadline) relies on alarm 100 instead.
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val gapLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SleepTimerTrial:end-gap").apply {
                setReferenceCounted(false)
                if (waitMs <= END_GAP_LOCK_MAX_MS) acquire(waitMs + 10_000L)
            }
            try {
                delay(waitMs)
            } finally {
                if (gapLock.isHeld) gapLock.release()
            }
        }
        val lateByMillis = System.currentTimeMillis() - fadeStartAt
        if (lateByMillis > 60_000L) scope.launch { repo.markLateCompletion() }

        state = repo.currentState()
        if (!state.isActive) return
        if (!endSequenceStarted.compareAndSet(false, true)) return

        acquireWakeLock("end-sequence", (state.fadeSeconds * 1000L) + 90_000L)
        endSequenceRunning.set(true)
        startWatchdog("end sequence")
        try {
            runEndSequence(state, quiet)
        } finally {
            endSequenceRunning.set(false)
            releaseWakeLockIfHeld()
        }
    }

    private suspend fun runEndSequence(initialState: TimerState, quiet: Boolean = false) {
        var state = initialState
        armGoodnight("end sequence") // a lost alarm 102 (short timer, End now) still gets its wind-down
        if (!mediaController.isVolumeFixed()) {
            // A fade that is already underway (End now mid-fade, or a restart after process death)
            // keeps its captured original index and continues from the current, lowered index.
            val startIndex = mediaController.currentStreamIndex()
            val original = if (state.phase == RunPhase.FADING && state.originalStreamIndex >= 0) {
                state.originalStreamIndex
            } else {
                startIndex
            }
            // P5: the volume is no longer the bedtime index we set -> the user changed it during the run.
            val touched = state.volumeTouchedDuringRun ||
                (state.phase != RunPhase.FADING && TimerPlan.touchedSinceBedtime(state.bedtimeStreamIndex, startIndex))
            state = state.copy(
                phase = RunPhase.FADING,
                originalStreamIndex = original,
                lastSetStreamIndex = startIndex,
                volumeTouchedDuringRun = touched,
            )
            saveAndRefresh(state)
            updateNotification(state)
            Log.i(TAG, "Fade start: from=$startIndex original=$original over ${state.fadeSeconds}s")

            var spentInEchoWindow = 0L
            for (step in TimerPlan.fadeSteps(startIndex, state.fadeSeconds)) {
                delay((step.delayMillis - spentInEchoWindow).coerceAtLeast(0L))
                spentInEchoWindow = 0L
                val fresh = repo.currentState()
                if (fresh.phase != RunPhase.FADING) return // stopped/extended concurrently

                val actualIndex = mediaController.currentStreamIndex()
                val verdict = TimerPlan.touchVerdict(state.lastSetStreamIndex, actualIndex, millisSinceOurWrite = Long.MAX_VALUE)
                if (verdict == TouchVerdict.TOUCHED && !TimerPlan.autoExtendAllowed(state.autoExtendCount)) {
                    Log.i(TAG, "Volume touched ($actualIndex, we set ${state.lastSetStreamIndex}) but ${TimerPlan.MAX_AUTO_EXTENDS} automatic extends already: fade continues")
                } else if (verdict == TouchVerdict.TOUCHED) {
                    Log.i(TAG, "Volume touched: $actualIndex, we set ${state.lastSetStreamIndex} -> extend ${state.autoExtendCount + 1}/${TimerPlan.MAX_AUTO_EXTENDS}")
                    state = RunTransitions.autoExtend(state, System.currentTimeMillis())
                    val newDeadline = state.deadlineWallClockMillis
                    endSequenceStarted.set(false)
                    saveAndRefresh(state)
                    stopStepping()
                    replanGoodnight(newDeadline, state.fadeSeconds)
                    armGoodnight("volume touched")
                    scheduleDeadlineAlarm(newDeadline, state.fadeSeconds)
                    updateNotification(state)
                    notifyExtended(newDeadline)
                    updateShakeMonitoring("volume touched")
                    return
                }
                mediaController.setStreamIndex(step.targetIndex)
                state = state.copy(lastSetStreamIndex = step.targetIndex)
                saveAndRefresh(state)
                // v2.1 echo window (REVIEW_V2 row 4): a Bluetooth headset reporting its own level back one step off is
                // adopted as our value, not counted as a touch. Bounded to half a step so the fade keeps its timing.
                val window = minOf(TimerPlan.ECHO_WINDOW_MS, step.delayMillis / 2)
                while (spentInEchoWindow + TimerPlan.ECHO_POLL_MS <= window) {
                    delay(TimerPlan.ECHO_POLL_MS)
                    spentInEchoWindow += TimerPlan.ECHO_POLL_MS
                    val reported = mediaController.currentStreamIndex()
                    val echo = TimerPlan.touchVerdict(step.targetIndex, reported, spentInEchoWindow)
                    if (echo == TouchVerdict.NONE) continue
                    if (echo == TouchVerdict.ECHO) {
                        Log.i(TAG, "Volume echo: set ${step.targetIndex}, reported $reported after ${spentInEchoWindow} ms -> adopted")
                        state = state.copy(lastSetStreamIndex = reported)
                        saveAndRefresh(state)
                    }
                    break // a real touch is judged at the next step
                }
            }
        }

        // Pause ladder (DECISIONS D4), with rung 0 when "Enhanced compatibility" is on (D17).
        Log.i(TAG, "Fade end: pause ladder")
        val settings = repo.currentSettings()
        var sessionRungTried = false
        var activeAfterSession = true
        if (settings.enhancedCompatibility) {
            val session = SessionPauser(this).pausePlaying()
            // Rung 0 counts only if it actually paused something; otherwise the v1 rungs decide.
            sessionRungTried = session.tried && session.paused.isNotEmpty()
            if (sessionRungTried) activeAfterSession = mediaController.isMusicActiveAfter(1_500L)
            Log.i(TAG, "Rung 0 (session): tried=${session.tried} paused=${session.appLabels} activeAfter=$activeAfterSession")
        }
        var activeAfterMediaKey = false
        var activeAfterAudioFocus = false
        if (!sessionRungTried || activeAfterSession) {
            mediaController.sendPause()
            delay(POLL_DELAY_MS)
            activeAfterMediaKey = mediaController.isMusicActive()
            activeAfterAudioFocus = activeAfterMediaKey
            if (activeAfterMediaKey) {
                mediaController.requestFocusGain()
                delay(POLL_DELAY_MS)
                activeAfterAudioFocus = mediaController.isMusicActive()
            }
        }
        val ladder = TimerPlan.evaluatePauseLadder(sessionRungTried, activeAfterSession, activeAfterMediaKey, activeAfterAudioFocus)
        var muted = false
        if (ladder.muted && settings.muteFallbackEnabled) {
            mediaController.setStreamIndex(TimerPlan.MUTED_STREAM_INDEX)
            muted = true
        }
        val isQuietNow = !mediaController.isMusicActive()
        // Restore rule (D19/D39): pre-start index for an untouched bedtime run, else the fade-start index.
        val restoreTo = TimerPlan.restoreTargetIndex(
            muted, isQuietNow, state.restoreVolumeAfterPause,
            state.originalStreamIndex, state.preStartStreamIndex, state.volumeTouchedDuringRun,
        )
        restoreTo?.let { mediaController.setStreamIndex(it) }
        if (muted) {
            // A mute is still owed (L13 review, rule 8): paid by "Restore volume", or on the next app open /
            // start once nothing plays any more (RestoreManager).
            mutedRestoreIndex(state)?.let { repo.saveOwedStreamIndex(it, TimerPlan.MUTED_STREAM_INDEX) }
        } else {
            repo.clearOwedStreamIndex()
        }
        val doneReason = if (muted) DoneReason.COMPLETED_MUTED else DoneReason.COMPLETED_PAUSED
        Log.i(
            TAG,
            "Done: rung=${ladder.stoppedAtRung} muted=$muted restoreSwitch=${state.restoreVolumeAfterPause} " +
                "restoredTo=${restoreTo ?: "-"} (preStart=${state.preStartStreamIndex} fadeStart=${state.originalStreamIndex} touched=${state.volumeTouchedDuringRun})",
        )
        val finalState = state.copy(phase = RunPhase.DONE, doneReason = doneReason, mutedInsteadOfPaused = muted)
        saveAndRefresh(finalState)
        SystemAlarms.refreshBootReceiver(this)
        endShakeWindow("timer ended")
        lastNotifiedState = null
        postCompletionNotification(muted, initialState.deadlineWallClockMillis, repo.currentOwedRestore().dndActive)
        cancelAlarm()
        // (row 8) the muted result says when the volume comes back by itself: see armOwedVolumeRetry.
        if (restoreTo != null && restoreTo > TimerPlan.MIN_STREAM_INDEX) {
            postRestoreWatchJob = scope.launch { watchAfterRestore(restoreTo, finalState, settings.muteFallbackEnabled) }
        }

        // SPEC F3: hold the audio focus 10 s more so the player settles into "paused", then abandon.
        // Its own job (P4 review): never stretched by the screen-off wait below; the service stays
        // started (not foreground) for those 10 s, and stops afterwards only if nothing else runs.
        focusHoldPending = true
        scope.launch {
            delay(FOCUS_HOLD_AFTER_END_MS)
            postRestoreWatchJob?.join() // v2.1: a re-pause may hold the focus again; given back after the watch
            mediaController.abandonFocus()
            focusHoldPending = false
            stopIfIdle()
        }

        stopStepping()
        if (quiet) {
            // v2.1 recovery from app open / boot: the user is using the phone. No blackout, no lock; the screen back now.
            RestoreManager.applyOwedBrightness(this, "quiet end")
            Log.i(TAG, "Quiet end: no screen action")
            if (!repo.currentState().isActive) stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }
        // P6: the wind-down's last values (and the optional blackout) before the screen action.
        goodnightEnd(state)

        // Screen action after the pause (SPEC F16, D16/D36): [Home] -> lock, or the fallback.
        val action = performScreenAction(state)
        // P6 (D23): a dimmed screen is put back only once it is off, so Goodnight waits for SCREEN_OFF too.
        val goodnightPending = Goodnight.pending(repo.currentState().goodnight)
        if (action == ScreenAction.SCREEN_OFF_SOONER || goodnightPending) {
            startScreenOffWait(goodnight = goodnightPending && action != ScreenAction.SCREEN_OFF_SOONER)
        } else if (!repo.currentState().isActive) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    /**
     * v2.1 (REVIEW_V2 row 7): one quiet sample decided "paused". For [TimerPlan.POST_RESTORE_WATCH_MS] after the restore
     * the service keeps listening; sound while the volume is still [restoredTo] -> back to the faded level, pause
     * again (media key, then focus), and then [TimerPlan.afterRepause]: restore once more, or the mute rung with the
     * volume owed (rule 8; paid by "Restore volume" or once nothing plays). Runs in the started service like the
     * 10 s focus hold.
     */
    private suspend fun watchAfterRestore(restoredTo: Int, doneState: TimerState, muteFallbackEnabled: Boolean) {
        var repauses = 0
        var waited = 0L
        while (waited < TimerPlan.POST_RESTORE_WATCH_MS) {
            delay(TimerPlan.POST_RESTORE_POLL_MS)
            waited += TimerPlan.POST_RESTORE_POLL_MS
            if (repo.currentState().isActive) return // a new run owns the volume now
            if (!TimerPlan.repauseNeeded(mediaController.isMusicActive(), mediaController.currentStreamIndex(), restoredTo)) continue
            Log.i(TAG, "Sound after the restore ($waited ms): quiet again and pause again (${repauses + 1})")
            mediaController.setStreamIndex(TimerPlan.MIN_STREAM_INDEX)
            mediaController.sendPause()
            delay(POLL_DELAY_MS)
            var stillPlaying = mediaController.isMusicActive()
            if (stillPlaying) {
                mediaController.requestFocusGain()
                delay(POLL_DELAY_MS)
                stillPlaying = mediaController.isMusicActive()
            }
            when (TimerPlan.afterRepause(stillPlaying, repauses, muteFallbackEnabled)) {
                RepauseOutcome.RESTORE_AGAIN -> {
                    mediaController.setStreamIndex(restoredTo)
                    repauses++
                    Log.i(TAG, "Re-pause worked: volume back to $restoredTo, still listening")
                }
                RepauseOutcome.STAY_QUIET -> {
                    repo.saveOwedStreamIndex(restoredTo, TimerPlan.MIN_STREAM_INDEX)
                    Log.i(TAG, "Re-pause: volume stays at ${TimerPlan.MIN_STREAM_INDEX}, $restoredTo owed")
                    return
                }
                RepauseOutcome.MUTE -> {
                    mediaController.setStreamIndex(TimerPlan.MUTED_STREAM_INDEX)
                    repo.saveOwedStreamIndex(restoredTo, TimerPlan.MUTED_STREAM_INDEX)
                    saveAndRefresh(doneState.copy(doneReason = DoneReason.COMPLETED_MUTED, mutedInsteadOfPaused = true))
                    postCompletionNotification(true, doneState.deadlineWallClockMillis, repo.currentOwedRestore().dndActive)
                    Log.i(TAG, "Re-pause failed: muted, $restoredTo owed")
                    return
                }
            }
        }
        Log.i(TAG, "Quiet for ${TimerPlan.POST_RESTORE_WATCH_MS / 1000} s after the restore")
    }

    // ---- Goodnight (SPEC F20, D21-D23, D42) ----

    /** Starts stepping now, or arms alarm 102 for the wind-down start. Idempotent. */
    private suspend fun armGoodnight(reason: String) {
        val state = repo.currentState()
        val run = state.goodnight
        if (!state.isActive || run == null || run.finished) {
            cancelWindDownAlarm()
            return
        }
        if (run.started || System.currentTimeMillis() >= run.progress.startMillis) {
            if (steppingJob?.isActive != true) {
                Log.i(TAG, "Goodnight stepping ($reason)")
                steppingJob = scope.launch { steppingLoop() }
            }
        } else {
            armWindDownAlarm(run.progress.startMillis)
            Log.i(TAG, "Goodnight wind-down at ${run.progress.startMillis} ($reason)")
        }
    }

    private suspend fun stopStepping() {
        steppingJob?.cancelAndJoin()
        steppingJob = null
    }

    /** Extend / −5 / End now / auto-extend: the wind-down holds, then continues to the new end (never brighter). */
    private suspend fun replanGoodnight(newDeadline: Long, fadeSeconds: Int) {
        Goodnight.update(this) { it.replanned(System.currentTimeMillis(), newDeadline, fadeSeconds) }
    }

    /**
     * Applies the plan every [GoodnightPlan.STEP_MILLIS] from wind-down start until the run is over. No wake lock
     * of its own: with the screen off nothing needs to change until it is on again, and the next step then only
     * jumps darker. While stepping, alarm 102 is re-armed as a watchdog, so a killed process comes back (the
     * accessibility service shows the layer again on its own as soon as it is rebound).
     */
    private suspend fun steppingLoop() {
        Goodnight.steppingAlive = true
        var lastWatchdog = 0L
        var lastLog = 0L
        try {
            while (true) {
                val state = repo.currentState()
                var run = state.goodnight
                if (!state.isActive || run == null || run.finished) break
                val now = System.currentTimeMillis()
                if (!run.started) {
                    if (now < run.progress.startMillis) {
                        armWindDownAlarm(run.progress.startMillis)
                        break
                    }
                    run = Goodnight.begin(this) ?: break
                } else if (run.bootCount != Goodnight.bootCount(this)) {
                    Log.i(TAG, "Goodnight: started before a reboot, not resumed")
                    break
                }
                val step = Goodnight.applyNow(this, run, now)
                if (now - lastLog >= 10_000L) {
                    Log.i(TAG, "Goodnight step: p=${"%.3f".format(run.progress.value(now))} level=${step.backlight ?: "-"} alpha=${step.overlayAlpha} argb=${Integer.toHexString(step.overlayArgb)} method=${run.method} overlay=${run.overlay}")
                    lastLog = now
                }
                if (now - lastWatchdog >= WIND_DOWN_WATCHDOG_MS) {
                    armWindDownAlarm(now + WIND_DOWN_WATCHDOG_MS + 5_000L)
                    lastWatchdog = now
                }
                delay(GoodnightPlan.STEP_MILLIS)
            }
        } finally {
            Goodnight.steppingAlive = false
        }
    }

    /** After the pause: the final wind-down values, then the blackout if on and a lock / screen-off follows. */
    private suspend fun goodnightEnd(state: TimerState) {
        var run = repo.currentState().goodnight ?: return
        if (!Goodnight.pending(run)) return
        Goodnight.applyNow(this, run, System.currentTimeMillis())
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (run.overlay && run.blackout && (state.lockAtEnd || state.screenOffSooner) && pm.isInteractive) {
            val start = System.currentTimeMillis()
            run = Goodnight.update(this) { it.withBlackoutFrom(start) } ?: return
            while (true) {
                val now = System.currentTimeMillis()
                Goodnight.applyNow(this, run, now)
                if (now >= start + GoodnightPlan.BLACKOUT_MILLIS) break
                delay(250L)
            }
            Log.i(TAG, "Goodnight blackout done")
        }
    }

    private fun windDownPendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        this,
        REQUEST_CODE_WIND_DOWN,
        Intent(this, TimerAlarmReceiver::class.java).setAction(ACTION_WIND_DOWN),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun armWindDownAlarm(atMillis: Long) {
        val pi = windDownPendingIntent()
        val exactAvailable = Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()
        try {
            if (exactAvailable) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
                return
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm 102 denied: ${e.message}")
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
    }

    private fun cancelWindDownAlarm() {
        alarmManager.cancel(windDownPendingIntent())
    }

    /**
     * Stops the service unless a run is active or end-of-run work is still pending (focus hold,
     * screen-off wait). Every "nothing left to do" path uses this, so a Restore tap or a recovery
     * command can't kill the screen-off wait (P4 review).
     */
    private suspend fun stopIfIdle() {
        if (repo.currentState().isActive || focusHoldPending || screenOffWaitJob != null || steppingJob?.isActive == true) return
        if (endSequenceRunning.get()) return
        cancelWatchdog()
        armOwedVolumeRetry()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (!stopSelfResult(lastStartId)) Log.i(TAG, "Stop skipped: a newer command is queued")
    }

    /**
     * v2.1 (REVIEW_V2 row 8): a media volume still owed when the service stops (a muted end, a re-pause that stayed
     * quiet, an interrupted run while something played) is tried again 10 min before the morning time (the DND
     * "until" setting, default 07:00 -> 06:50) through alarm 105: a fresh service pays it if nothing plays then
     * (RestoreRules.streamDebt), else tries again the next morning. A morning alarm app that uses the media volume is
     * then not silent.
     */
    private suspend fun armOwedVolumeRetry() {
        if (repo.currentOwedRestore().streamIndex == null) return
        val at = Schedule.nextOccurrence(ZonedDateTime.now(), Schedule.owedVolumeRetryMinuteOfDay(repo.currentSettings().dndUntilMinuteOfDay), Schedule.ALL_DAYS)
            ?: return
        armWatchdog(at.toInstant().toEpochMilli())
        Log.i(TAG, "Volume still owed: retry at $at")
    }

    /** Decides and performs the screen action with the facts of this moment (EndActions.choose). */
    private suspend fun performScreenAction(state: TimerState): ScreenAction {
        if (!state.lockAtEnd && !state.screenOffSooner) return ScreenAction.NONE
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        // The system rebinds an enabled accessibility service after a process restart; give it a moment.
        if (state.lockAtEnd && SleepAccessibilityService.instance == null &&
            SpecialAccess.isGranted(this, AccessKind.ACCESSIBILITY)
        ) {
            var waited = 0L
            while (SleepAccessibilityService.instance == null && waited < SERVICE_REBIND_WAIT_MS) {
                delay(200); waited += 200
            }
        }
        val service = SleepAccessibilityService.instance
        val canWrite = SpecialAccess.isGranted(this, AccessKind.WRITE_SETTINGS)
        val action = EndActions.choose(
            lock = state.lockAtEnd,
            homeFirst = state.homeBeforeLock,
            screenOffSooner = state.screenOffSooner,
            sdkInt = Build.VERSION.SDK_INT,
            lockServiceBound = service != null,
            canWriteSettings = canWrite,
            screenInteractive = pm.isInteractive,
        )
        Log.i(TAG, "Screen action: $action (lock=${state.lockAtEnd} bound=${service != null} interactive=${pm.isInteractive})")
        when (action) {
            ScreenAction.NONE -> Unit
            ScreenAction.LOCK, ScreenAction.HOME_THEN_LOCK -> {
                withContext(Dispatchers.Main) {
                    if (action == ScreenAction.HOME_THEN_LOCK) {
                        service?.goHome()
                        delay(EndActions.HOME_BEFORE_LOCK_DELAY_MS)
                    }
                    service?.lock()
                }
                delay(LOCK_CHECK_DELAY_MS)
                val stillOn = pm.isInteractive
                Log.i(TAG, "Lock result: screen ${if (stillOn) "still on" else "off"}")
                // The lock did not take (e.g. access revoked a moment ago): the fallback, if the user has it.
                if (stillOn && state.screenOffSooner && canWrite && RestoreManager.applyScreenOffSooner(this)) {
                    return ScreenAction.SCREEN_OFF_SOONER
                }
            }
            ScreenAction.SCREEN_OFF_SOONER -> if (!RestoreManager.applyScreenOffSooner(this)) return ScreenAction.NONE
        }
        return action
    }

    /**
     * Keeps the service (foreground, with a no-action notification) and its SCREEN_OFF receiver alive
     * until the screen goes off; the steps are [EndActions.screenWaitStep]: the user's timeout comes back
     * after [EndActions.SCREEN_OFF_WAIT_MS] with the screen still on (the user is awake), a dimmed screen
     * (v2.1, REVIEW_V2 row 2) at the latest after [EndActions.GOODNIGHT_MAX_WAIT_MS]. Its own job and wake
     * lock for the first 10 minutes (P4 review; afterwards the lit screen keeps the CPU awake): a new run
     * cancels it after its own applyOwed(). [startedAt] = the end; a wait resumed after process death counts from it.
     */
    private fun startScreenOffWait(goodnight: Boolean = false, startedAt: Long = System.currentTimeMillis()) {
        waitForGoodnight = goodnight
        startForeground(TimerNotifications.NOTIF_ID_TIMER, TimerNotifications.buildScreenOffWaitNotification(this, goodnight))
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val waitLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SleepTimerTrial:screen-off-wait").apply {
            setReferenceCounted(false)
            acquire(EndActions.SCREEN_OFF_WAIT_MS + 30_000L)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val pending = goAsync()
                scope.launch {
                    try {
                        RestoreManager.applyOwed(this@TimerService, "screen off")
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
        ContextCompat.registerReceiver(this, receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        screenOffReceiver = receiver
        screenOffWaitJob = scope.launch {
            var completed = false
            try {
                var timeoutHandled = false
                while (true) {
                    val owed = repo.currentOwedRestore()
                    val goodnightOwed = Goodnight.pending(repo.currentState().goodnight) || owed.brightness != null
                    val step = EndActions.screenWaitStep(
                        elapsedMillis = System.currentTimeMillis() - startedAt,
                        interactive = pm.isInteractive,
                        timeoutOwed = owed.screenOffTimeout != null && !timeoutHandled,
                        goodnightOwed = goodnightOwed,
                    )
                    when (step) {
                        ScreenWaitStep.WAIT -> delay(1_000L)
                        ScreenWaitStep.RESTORE_TIMEOUT -> {
                            // D23: the user is awake, the timeout comes back; the dim screen stays a while longer.
                            RestoreManager.applyOwed(this@TimerService, "wait over, screen still on", includeBrightness = false)
                            timeoutHandled = true
                            if (goodnightOwed) {
                                withContext(Dispatchers.Main) { SleepAccessibilityService.instance?.releaseGoodnightOnScreenOff() }
                                Log.i(TAG, "Goodnight: screen still on after the wait, dim until it goes off (at most ${EndActions.GOODNIGHT_MAX_WAIT_MS / 60_000} min after the end)")
                            }
                        }
                        ScreenWaitStep.RESTORE_ALL -> {
                            RestoreManager.applyOwed(
                                this@TimerService,
                                if (pm.isInteractive) "Goodnight limit, screen still on" else "screen off (poll)",
                            )
                            break
                        }
                        ScreenWaitStep.DONE -> break
                    }
                }
                completed = true
            } finally {
                unregisterScreenOffReceiver()
                if (waitLock.isHeld) waitLock.release()
                screenOffWaitJob = null
            }
            if (completed) stopIfIdle()
        }
        startWatchdog("screen-off wait")
    }

    /** No run active: drop the running notification, or show the wait notification if a wait is on. */
    private fun releaseForegroundOrShowWait() {
        if (screenOffWaitJob != null) {
            startForeground(TimerNotifications.NOTIF_ID_TIMER, TimerNotifications.buildScreenOffWaitNotification(this, waitForGoodnight))
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun unregisterScreenOffReceiver() {
        screenOffReceiver?.let { r ->
            try { unregisterReceiver(r) } catch (e: IllegalArgumentException) { /* already gone */ }
        }
        screenOffReceiver = null
    }

    // ---- alarm scheduling ----

    private fun alarmPendingIntent(): PendingIntent {
        val intent = Intent(this, TimerAlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            this,
            REQUEST_CODE_ALARM,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun scheduleDeadlineAlarm(deadlineWallClockMillis: Long, fadeSeconds: Int) {
        val fireAt = deadlineWallClockMillis - fadeSeconds * 1000L - ALARM_BUFFER_MS
        val pendingIntent = alarmPendingIntent()
        // v2.1: every run has a watchdog after its deadline; the end sequence re-arms it while it runs.
        armWatchdog(deadlineWallClockMillis + RestoreRules.WATCHDOG_AFTER_DEADLINE_MS)
        val exactAvailable = Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()
        if (exactAvailable) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pendingIntent)
                countdownTickerJob?.cancel()
                releaseCountdownLock()
                return
            } catch (e: SecurityException) {
                Log.w(TAG, "Exact alarm denied at schedule time, falling back: ${e.message}")
            }
        }
        // Fallback A (DECISIONS D6): inexact watchdog + in-process ticker holding a wake lock
        // for the whole remaining countdown.
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pendingIntent)
        armCountdownTicker(deadlineWallClockMillis, fadeSeconds)
    }

    private fun armCountdownTicker(deadlineWallClockMillis: Long, fadeSeconds: Int) {
        val fireAt = deadlineWallClockMillis - fadeSeconds * 1000L - ALARM_BUFFER_MS
        val holdMillis = (deadlineWallClockMillis - System.currentTimeMillis()).coerceAtLeast(0L) + 120_000L
        // v2.1 (REVIEW_V2 row 6): its own lock, renewed for every new deadline (extend) instead of keeping the old
        // timeout, and never released by the end sequence's lock handling.
        releaseCountdownLock()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        countdownLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SleepTimerTrial:countdown").apply {
            setReferenceCounted(false)
            acquire(holdMillis)
        }
        countdownTickerJob?.cancel()
        countdownTickerJob = scope.launch {
            while (isActive) {
                val remaining = fireAt - System.currentTimeMillis()
                if (remaining <= 0) break
                delay(minOf(remaining, 1_000L))
            }
            if (isActive && endSequenceJob?.isActive != true) {
                endSequenceJob = launch {
                    try { runAlarmFired() } finally { releaseCountdownLock() }
                }
            }
        }
    }

    private fun releaseCountdownLock() {
        countdownLock?.let { if (it.isHeld) it.release() }
        countdownLock = null
    }

    private fun cancelAlarm() {
        alarmManager.cancel(alarmPendingIntent())
        countdownTickerJob?.cancel()
        releaseCountdownLock()
        cancelWindDownAlarm()
    }

    // ---- watchdog, alarm 105 (v2.1, REVIEW_V2 row 1) ----

    private fun watchdogPendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        this,
        REQUEST_CODE_WATCHDOG,
        Intent(this, TimerAlarmReceiver::class.java).setAction(ACTION_WATCHDOG),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Exact and allowed while idle, so a fresh process may start the foreground service (alarm FGS exemption). */
    private fun armWatchdog(atMillis: Long) {
        val pi = watchdogPendingIntent()
        val exactAvailable = Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()
        try {
            if (exactAvailable) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
                return
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm 105 denied: ${e.message}")
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
    }

    private fun cancelWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
        alarmManager.cancel(watchdogPendingIntent())
    }

    /**
     * While the end sequence or the screen-off wait runs, alarm 105 always points [RestoreRules.WATCHDOG_LEAD_MS]
     * ahead: if this process dies (crash, update, low memory, kill), a fresh one is started at most that long later and
     * [handleRecovery] continues the fade / pause / restore or the wait. Afterwards the countdown's own watchdog
     * (deadline + 60 s) is put back for an active run; an idle service cancels it in [stopIfIdle].
     */
    private fun startWatchdog(reason: String) {
        if (watchdogJob?.isActive == true) return
        Log.i(TAG, "Watchdog on ($reason)")
        watchdogJob = scope.launch {
            while (endSequenceRunning.get() || screenOffWaitJob != null) {
                armWatchdog(System.currentTimeMillis() + RestoreRules.WATCHDOG_LEAD_MS)
                delay(RestoreRules.WATCHDOG_REARM_MS)
            }
            val state = repo.currentState()
            if (state.isActive) armWatchdog(state.deadlineWallClockMillis + RestoreRules.WATCHDOG_AFTER_DEADLINE_MS)
        }
    }

    // ---- shake to extend (SPEC F12, DECISIONS D18/D33) ----

    private fun shakeWindowPendingIntent(): PendingIntent {
        val intent = Intent(this, TimerAlarmReceiver::class.java).setAction(ACTION_SHAKE_WINDOW)
        return PendingIntent.getBroadcast(
            this,
            REQUEST_CODE_SHAKE_WINDOW,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * Listens only from deadline − fade − 5 min until the run ends; before that, alarm 101 is armed
     * for the window start. Re-evaluated after every deadline change and when the setting flips.
     */
    private suspend fun updateShakeMonitoring(reason: String) = shakeMutex.withLock {
        val state = repo.currentState()
        val enabled = repo.currentSettings().shakeToExtendEnabled
        if (!enabled || !state.isActive) {
            alarmManager.cancel(shakeWindowPendingIntent())
            shakeMonitor.stop(reason)
            return@withLock
        }
        val now = System.currentTimeMillis()
        val windowStart = ShakeDetector.windowStartMillis(state.deadlineWallClockMillis, state.fadeSeconds)
        if (now >= windowStart) {
            alarmManager.cancel(shakeWindowPendingIntent())
            // Wake-lock safety timeout: rest of the countdown + the end sequence's pause ladder.
            shakeMonitor.start(state.deadlineWallClockMillis - now + SHAKE_WAKE_LOCK_TAIL_MS)
        } else {
            shakeMonitor.stop(reason)
            val pi = shakeWindowPendingIntent()
            val exactAvailable = Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()
            try {
                if (exactAvailable) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, windowStart, pi)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, windowStart, pi)
                }
            } catch (e: SecurityException) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, windowStart, pi)
            }
            Log.i(TAG, "Shake window at $windowStart ($reason)")
        }
    }

    private fun endShakeWindow(reason: String) {
        alarmManager.cancel(shakeWindowPendingIntent())
        shakeMonitor.stop(reason)
    }

    /** A detected shake (or the debug SHAKE command) = +extend, confirmed by one short vibration. */
    private suspend fun onShakeDetected() {
        if (!shakeMonitor.isListening) {
            Log.i(TAG, "Shake ignored: outside the shake window")
            return
        }
        if (!shakeExtendInProgress.compareAndSet(false, true)) return
        try {
            if (!repo.currentState().isActive) return
            Log.i(TAG, "Shake: extending")
            shakeMonitor.vibrate()
            extendNow()
        } finally {
            shakeExtendInProgress.set(false)
        }
    }

    // ---- notifications ----

    /**
     * Every start command must call startForeground() before anything else — and before any volume
     * or focus call (Android 17 background-audio hardening, D27). Re-posts the real notification when
     * this instance already has one, so a repeat command (alarm, shake window) doesn't flash a placeholder.
     */
    private fun ensureForegroundPlaceholder() {
        lastNotifiedState?.let { if (it.isActive) { updateNotification(it); return } }
        val notif = TimerNotifications.buildRunningNotification(this, System.currentTimeMillis(), 15, false, 30)
        startForeground(TimerNotifications.NOTIF_ID_TIMER, notif)
    }

    private fun updateNotification(state: TimerState) {
        lastNotifiedState = state
        val notif = TimerNotifications.buildRunningNotification(
            this,
            state.deadlineWallClockMillis,
            state.extendStepMinutes,
            state.phase == RunPhase.FADING,
            state.fadeSeconds,
            EndActions.planned(
                state.lockAtEnd,
                state.homeBeforeLock,
                state.screenOffSooner,
                Build.VERSION.SDK_INT,
                SpecialAccess.isGranted(this, AccessKind.ACCESSIBILITY),
                SpecialAccess.isGranted(this, AccessKind.WRITE_SETTINGS),
            ),
            state.startedBySchedule,
            state.goodnight?.takeIf { !it.finished }?.windDownMinutesShown,
        )
        startForeground(TimerNotifications.NOTIF_ID_TIMER, notif)
    }

    private suspend fun postCompletionNotification(muted: Boolean, deadlineWallClockMillis: Long, dndActive: Boolean) {
        val retry = Schedule.owedVolumeRetryMinuteOfDay(repo.currentSettings().dndUntilMinuteOfDay)
        try {
            val notif = TimerNotifications.buildResultNotification(
                this, muted, deadlineWallClockMillis, dndActive, "%02d:%02d".format(retry / 60, retry % 60),
            )
            NotificationManagerCompat.from(this).notify(TimerNotifications.NOTIF_ID_RESULT, notif)
        } catch (e: SecurityException) {
            Log.w(TAG, "No POST_NOTIFICATIONS permission: ${e.message}")
        }
    }

    private fun notifyExtended(newDeadlineWallClockMillis: Long) {
        try {
            val notif = TimerNotifications.buildExtendedNotification(this, newDeadlineWallClockMillis)
            NotificationManagerCompat.from(this).notify(TimerNotifications.NOTIF_ID_RESULT, notif)
        } catch (e: SecurityException) {
            Log.w(TAG, "No POST_NOTIFICATIONS permission: ${e.message}")
        }
    }

    // ---- wake lock ----

    private fun acquireWakeLock(tag: String, timeoutMillis: Long) {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SleepTimerTrial:$tag").apply {
            setReferenceCounted(false)
            acquire(timeoutMillis.coerceAtLeast(1_000L))
        }
    }

    private fun releaseWakeLockIfHeld() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        const val ACTION_START = "com.tim.sleeptimer.action.START"
        const val ACTION_ALARM_FIRED = "com.tim.sleeptimer.action.ALARM_FIRED"
        const val ACTION_SHAKE_WINDOW = "com.tim.sleeptimer.action.SHAKE_WINDOW"
        const val ACTION_DEBUG_SHAKE = "com.tim.sleeptimer.action.SHAKE"
        /** P6: alarm 102, Goodnight wind-down start and stepping watchdog. */
        const val ACTION_WIND_DOWN = "com.tim.sleeptimer.action.WIND_DOWN"
        /** v2.1: alarm 105, the watchdog of every run (REVIEW_V2 row 1). */
        const val ACTION_WATCHDOG = "com.tim.sleeptimer.action.WATCHDOG"
        /** v2.1: resume or complete a persisted run (app open after a force-stop, boot, update). */
        const val ACTION_RECOVER = "com.tim.sleeptimer.action.RECOVER"
        const val EXTRA_QUIET_END = "quiet_end"
        const val EXTRA_REASON = "reason"
        const val EXTRA_DURATION_MINUTES = "duration_minutes"
        const val EXTRA_PRESET_ID = "preset_id"
        const val EXTRA_DEBUG_SECONDS = "debug_seconds"
        /** P5: started by the nightly schedule (ScheduleReceiver); the notification says so. */
        const val EXTRA_SCHEDULED = "scheduled"
        private const val REQUEST_CODE_ALARM = 100
        private const val REQUEST_CODE_SHAKE_WINDOW = 101
        private const val REQUEST_CODE_WIND_DOWN = 102
        private const val REQUEST_CODE_WATCHDOG = 105
        private const val WIND_DOWN_WATCHDOG_MS = 30_000L
        private const val SHAKE_WAKE_LOCK_TAIL_MS = 120_000L
        private const val FOCUS_HOLD_AFTER_END_MS = 10_000L
        private const val ALARM_BUFFER_MS = 5_000L
        private const val END_GAP_LOCK_MAX_MS = 60_000L
        private const val POLL_DELAY_MS = 1_500L
        private const val SERVICE_REBIND_WAIT_MS = 2_000L
        private const val LOCK_CHECK_DELAY_MS = 1_000L
        private const val TAG = "TimerService"

        /** True while this process has a TimerService instance (app open: a run without one is orphaned). */
        @Volatile
        var alive: Boolean = false
            private set
    }
}
