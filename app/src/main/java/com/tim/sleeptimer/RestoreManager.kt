package com.tim.sleeptimer

import android.content.Context
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Puts back phone settings we changed (V2_PLAN rule 8, DECISIONS D23/D36/D39). P4 owns the screen
 * timeout ("Turn screen off sooner"), P5 the bedtime volume and Do Not Disturb, P6 the Goodnight layer and brightness. Called on app open,
 * trampoline start, timer start, and by the running service when the screen goes off. One mutex so
 * read -> write -> clear never interleaves between those callers.
 */
object RestoreManager {
    private const val TAG = "RestoreManager"
    private val mutex = Mutex()

    /**
     * [includeBrightness] false = everything except Goodnight (the service's "wait over, screen still on" path: D23 keeps
     * the screen dim while it is on). [orphanCheck] (app open only): a run whose Goodnight has no stepping loop in this
     * process (force-stop, process death) is treated as over for the brightness — the user is awake and opening the app.
     */
    suspend fun applyOwed(
        context: Context,
        reason: String,
        includeBrightness: Boolean = true,
        orphanCheck: Boolean = false,
    ) = mutex.withLock {
        val app = context.applicationContext
        expireStaleRunLocked(app, reason)
        applyOwedScreenTimeoutLocked(app, reason)
        applyOwedStreamLocked(app, reason)
        applyOwedDndLocked(app, reason)
        if (includeBrightness) applyOwedBrightnessLocked(app, reason, orphanCheck)
    }

    /** P6: Goodnight only (Stop, boot). */
    suspend fun applyOwedBrightness(context: Context, reason: String) = mutex.withLock {
        applyOwedBrightnessLocked(context.applicationContext, reason, orphanCheck = false)
    }

    /**
     * P6 (D23/D42): removes a leftover Goodnight layer and puts the SYSTEM brightness back per [BrightnessRestore]
     * (decided from the current values). Never while a run of this boot is still dimming, unless it is orphaned.
     * Without WRITE_SETTINGS the record is kept (rule 8) and paid once the grant is back.
     */
    private suspend fun applyOwedBrightnessLocked(app: Context, reason: String, orphanCheck: Boolean) {
        val repo = TimerRepository(app)
        val state = repo.currentState()
        val run = state.goodnight
        val otherBoot = run != null && run.started && run.bootCount != Goodnight.bootCount(app)
        if (!RestoreRules.brightnessDue(state.isActive, Goodnight.pending(run), otherBoot, orphanCheck, Goodnight.steppingAlive)) return
        Goodnight.hideOverlay(reason)
        val owed = repo.currentOwedRestore()
        val original = owed.brightness
        if (original != null) {
            val r = app.contentResolver
            val current = Settings.System.getInt(r, Settings.System.SCREEN_BRIGHTNESS, -1)
            val currentMode = Settings.System.getInt(r, Settings.System.SCREEN_BRIGHTNESS_MODE, GoodnightPlan.MODE_MANUAL)
            val plan = BrightnessRestore.plan(
                original, owed.brightnessMode ?: GoodnightPlan.MODE_MANUAL,
                owed.brightnessSet ?: original, owed.brightnessPrevSet ?: original, current, currentMode,
            )
            val ok = writeBrightness(app, plan.brightness, plan.mode)
            Log.i(TAG, "brightness ($reason): current=$current/$currentMode owed=$original/${owed.brightnessMode} set=${owed.brightnessSet} -> $plan ok=$ok")
            if (ok) {
                repo.clearOwedBrightness()
                SystemAlarms.refreshBootReceiver(app)
            }
        }
        if (Goodnight.pending(run)) {
            Goodnight.update(app) { it.copy(finished = true) }
            Log.i(TAG, "Goodnight finished ($reason)")
        }
    }

    /**
     * P6 SYSTEM method: records the user's brightness (first time only) and our new value BEFORE writing (rule 8,
     * Dimmer's journal pattern), then writes brightness first and the manual mode second (mode first would briefly
     * show the old manual value — L13 review). False = the user took over (moved the slider or switched auto on after
     * our first write): nothing written, no more writes this run.
     */
    suspend fun writeGoodnightBrightness(context: Context, level: Int, original: Int, originalMode: Int): Boolean = mutex.withLock {
        val app = context.applicationContext
        if (!Settings.System.canWrite(app)) return@withLock true // nothing possible now; not a takeover
        val repo = TimerRepository(app)
        val r = app.contentResolver
        val current = Settings.System.getInt(r, Settings.System.SCREEN_BRIGHTNESS, -1)
        val currentMode = Settings.System.getInt(r, Settings.System.SCREEN_BRIGHTNESS_MODE, GoodnightPlan.MODE_MANUAL)
        val owed = repo.currentOwedRestore()
        val lastSet = owed.brightnessSet
        if (owed.brightness != null && lastSet != null) {
            if (BrightnessRestore.tookOver(lastSet, owed.brightnessPrevSet ?: lastSet, current, currentMode)) return@withLock false
            if (lastSet == level && currentMode == GoodnightPlan.MODE_MANUAL) return@withLock true
        }
        repo.saveOwedBrightness(original, originalMode, level, lastSet ?: current)
        SystemAlarms.refreshBootReceiver(app)
        val ok = writeBrightness(app, level, GoodnightPlan.MODE_MANUAL)
        if (lastSet == null) Log.i(TAG, "Goodnight brightness: $current/$currentMode -> $level (owed $original/$originalMode) ok=$ok")
        true
    }

    private fun writeBrightness(app: Context, brightness: Int?, mode: Int?): Boolean = try {
        if (!Settings.System.canWrite(app) && (brightness != null || mode != null)) {
            false
        } else {
            brightness?.let { Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, it) }
            mode?.let { Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, it) }
            true
        }
    } catch (e: SecurityException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    }

    /** DND only (BootReceiver, alarm 103): no volume call from the background (Android 17 hardening). */
    suspend fun applyOwedDnd(context: Context, reason: String) = mutex.withLock {
        applyOwedDndLocked(context.applicationContext, reason)
    }

    /**
     * A run still RUNNING/FADING long after its deadline (the phone rebooted or the process was killed with
     * no alarm left, D10) is closed, so its debts are paid and a new or scheduled start is not blocked
     * (L13 review). v2.1 (REVIEW_V2 row 1): a fade it had started becomes an owed volume record first
     * ([RestoreRules.interruptedRunVolume]), so the volume comes back (once nothing plays) instead of the
     * record being dropped as "changed by the user".
     */
    private suspend fun expireStaleRunLocked(app: Context, reason: String) {
        val repo = TimerRepository(app)
        val state = repo.currentState()
        if (!TimerPlan.isStale(state.isActive, state.deadlineWallClockMillis, System.currentTimeMillis())) return
        closeInterruptedRunLocked(app, state, "stale run closed ($reason)")
    }

    private suspend fun closeInterruptedRunLocked(app: Context, state: TimerState, what: String) {
        val repo = TimerRepository(app)
        val volume = RestoreRules.interruptedRunVolume(
            fading = state.phase == RunPhase.FADING,
            fadeStartIndex = state.originalStreamIndex,
            lastSetIndex = state.lastSetStreamIndex,
            preStartIndex = state.preStartStreamIndex,
            touched = state.volumeTouchedDuringRun,
            restoreEnabled = state.restoreVolumeAfterPause,
        )
        when (volume) {
            is InterruptedVolume.Owe -> repo.saveOwedStreamIndex(volume.original, volume.set)
            InterruptedVolume.Clear -> repo.clearOwedStreamIndex()
            InterruptedVolume.Keep -> Unit
        }
        Log.i(TAG, "$what: deadline ${state.deadlineWallClockMillis} phase=${state.phase} volume=$volume")
        repo.saveState(state.copy(phase = RunPhase.DONE, doneReason = DoneReason.NONE))
        SystemAlarms.refreshBootReceiver(app)
        TimerUi.refresh(app)
    }

    /** Receivers (schedule): close a stale run without touching any phone setting. */
    suspend fun expireStaleRun(context: Context, reason: String) = mutex.withLock {
        expireStaleRunLocked(context.applicationContext, reason)
    }

    private suspend fun applyOwedScreenTimeoutLocked(app: Context, reason: String) {
        val repo = TimerRepository(app)
        val owed = repo.currentOwedRestore()
        if (owed.screenOffTimeout == null) return
        val current = readTimeout(app)
        val decision = EndActions.restoreDecision(owed.screenOffTimeout, owed.screenOffTimeoutSet, current, Settings.System.canWrite(app))
        var written = false
        if (decision.write != null) written = writeTimeout(app, decision.write)
        Log.i(TAG, "screen timeout ($reason): current=$current owed=${owed.screenOffTimeout} write=${decision.write} ok=$written clear=${decision.clearRecord}")
        // A failed write keeps the record (rule 8): the debt is paid on the next call.
        if (decision.clearRecord && (decision.write == null || written)) repo.clearOwedScreenOffTimeout(owed)
    }

    /**
     * P5: a bedtime volume still owed with no run active (the run died before its end sequence): back to
     * the pre-start index if the volume is still the one we set; the user's own change is kept. Called
     * only from the service or a visible activity (Android 17 audio hardening).
     */
    private suspend fun applyOwedStreamLocked(app: Context, reason: String) {
        val repo = TimerRepository(app)
        val owed = repo.currentOwedRestore()
        val original = owed.streamIndex ?: return
        if (repo.currentState().isActive) return // the run pays it at its end or stop
        val media = MediaController(app)
        val current = media.currentStreamIndex()
        // v2.1: never paid while something plays (it would get louder in the dark; P5 had this for a mute debt
        // only). The record stays for the next call (rule 8); a volume the user changed drops it.
        val action = RestoreRules.streamDebt(owed.streamIndexSet, current, media.isMusicActive())
        Log.i(TAG, "stream index ($reason): current=$current set=${owed.streamIndexSet} original=$original -> $action")
        when (action) {
            StreamDebtAction.KEEP -> return
            StreamDebtAction.RESTORE -> media.setStreamIndex(original)
            StreamDebtAction.DROP -> Unit
        }
        repo.clearOwedStreamIndex(owed)
    }

    /**
     * P5: our DND past its until-time ends now (missed alarm: reboot, force-stop); a future one gets its
     * alarm 103 back; without Do Not Disturb access the record is dropped (Android removes a revoked
     * app's rules itself).
     */
    private suspend fun applyOwedDndLocked(app: Context, reason: String) {
        val repo = TimerRepository(app)
        val owed = repo.currentOwedRestore()
        if (!owed.dndActive) return
        val dnd = DndController(app)
        when (RestoreRules.dndDebt(owed.dndActive, dnd.isAccessGranted(), owed.dndUntilMillis, System.currentTimeMillis())) {
            DndDebtAction.NONE -> Unit
            DndDebtAction.DROP_NO_ACCESS -> {
                Log.i(TAG, "DND ($reason): access gone, record dropped")
                repo.clearOwedDnd(owed.dndUntilMillis)
                SystemAlarms.cancelDndOff(app)
            }
            DndDebtAction.END_NOW -> {
                val ok = dnd.end(repo.dndRuleId())
                Log.i(TAG, "DND ($reason): until ${owed.dndUntilMillis} passed, ended ok=$ok")
                repo.clearOwedDnd(owed.dndUntilMillis)
                SystemAlarms.cancelDndOff(app)
            }
            DndDebtAction.ARM_ALARM -> SystemAlarms.armDndOff(app, owed.dndUntilMillis)
        }
    }

    /**
     * P5: records the DND debt, then turns our DND on until [untilMillis] and arms alarm 103. False (and
     * nothing owed) without access or when the call fails.
     */
    suspend fun startDnd(context: Context, untilMillis: Long): Boolean = mutex.withLock {
        val app = context.applicationContext
        val repo = TimerRepository(app)
        val dnd = DndController(app)
        if (!dnd.isAccessGranted()) {
            Log.i(TAG, "DND at start skipped: no Do Not Disturb access")
            return@withLock false
        }
        val prior = repo.currentOwedRestore()
        repo.saveOwedDnd(untilMillis)
        val ruleId = dnd.activate(repo.dndRuleId())
        if (ruleId == null) {
            if (prior.dndActive) repo.saveOwedDnd(prior.dndUntilMillis) else repo.clearOwedDnd(untilMillis)
            return@withLock false
        }
        if (ruleId.isNotEmpty()) repo.saveDndRuleId(ruleId)
        SystemAlarms.armDndOff(app, untilMillis)
        true
    }

    /** P5: "End Do Not Disturb" (app, result notification): off now, record and alarm 103 cleared. */
    suspend fun endDnd(context: Context, reason: String) = mutex.withLock {
        val app = context.applicationContext
        val repo = TimerRepository(app)
        val owed = repo.currentOwedRestore()
        if (!owed.dndActive) return@withLock
        val ok = DndController(app).end(repo.dndRuleId())
        Log.i(TAG, "DND ended ($reason) ok=$ok")
        repo.clearOwedDnd(owed.dndUntilMillis)
        SystemAlarms.cancelDndOff(app)
    }

    /** P5: records the pre-start volume, then sets the bedtime index (rule 8). */
    suspend fun applyBedtimeVolume(context: Context, media: MediaController, current: Int, target: Int) = mutex.withLock {
        val repo = TimerRepository(context.applicationContext)
        // A record still there (not paid, e.g. a muted end with the player still playing) keeps its original.
        val original = repo.currentOwedRestore().streamIndex ?: current
        repo.saveOwedStreamIndex(original, target)
        media.setStreamIndex(target)
        Log.i(TAG, "bedtime volume: $current -> $target (owed $original)")
    }

    /**
     * Records the user's timeout, then sets [EndActions.SCREEN_OFF_SOONER_TIMEOUT_MS]. False (and nothing
     * new owed) when the grant is missing, the user's timeout is already that short, or the write fails.
     */
    suspend fun applyScreenOffSooner(context: Context): Boolean = mutex.withLock {
        val app = context.applicationContext
        if (!Settings.System.canWrite(app)) return@withLock false
        val repo = TimerRepository(app)
        val current = readTimeout(app) ?: return@withLock false
        val prior = repo.currentOwedRestore()
        // An existing record keeps its original: a second end before the restore must not record our own 15 s.
        val original = EndActions.originalToRecord(prior.screenOffTimeout, current)
        if (!EndActions.screenOffSoonerUseful(original)) {
            Log.i(TAG, "screen off sooner skipped: user timeout $original ms is not longer")
            return@withLock false
        }
        repo.saveOwedScreenOffTimeout(original, EndActions.SCREEN_OFF_SOONER_TIMEOUT_MS)
        val ok = writeTimeout(app, EndActions.SCREEN_OFF_SOONER_TIMEOUT_MS)
        if (!ok && prior.isEmpty) {
            repo.clearOwedScreenOffTimeout(OwedRestore(original, EndActions.SCREEN_OFF_SOONER_TIMEOUT_MS))
        }
        Log.i(TAG, "screen off sooner: $current -> ${EndActions.SCREEN_OFF_SOONER_TIMEOUT_MS} (owed $original) ok=$ok")
        ok
    }

    /** Synchronous last resort for TimerService.onDestroy during a wait (no coroutine left to run in). */
    fun restoreNowBlocking(context: Context, original: Int) {
        val app = context.applicationContext
        if (readTimeout(app) == EndActions.SCREEN_OFF_SOONER_TIMEOUT_MS && Settings.System.canWrite(app)) {
            Log.i(TAG, "screen timeout restored in onDestroy -> $original ok=${writeTimeout(app, original)}")
        }
    }

    private fun readTimeout(app: Context): Int? = try {
        Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
    } catch (e: Settings.SettingNotFoundException) {
        null
    }

    private fun writeTimeout(app: Context, value: Int): Boolean = try {
        Settings.System.canWrite(app) && Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, value)
    } catch (e: SecurityException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    }
}
