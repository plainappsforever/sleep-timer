package com.plainapps.nightcap

import android.content.Context
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Android side of the Goodnight sequence (SPEC F20, D21-D23, D42), shared by [TimerService] (stepping),
 * [SleepAccessibilityService] (overlay recovery / release at screen-off) and [RestoreManager] (restore).
 *
 * - The run's [GoodnightRun] is only ever changed through [update] (one mutex), so the stepping loop and the
 *   service's extend/stop handlers never overwrite each other's changes (L13 review).
 * - Overlay calls run on the main thread, one at a time; [hideOverlay] bumps a generation so a step computed
 *   before the hide can never show the layer again.
 * - A runtime guard never applies a higher backlight or a lighter overlay than the last one applied in this process.
 */
object Goodnight {
    private const val TAG = "Goodnight"
    private val mutex = Mutex()

    /** True while [TimerService]'s stepping loop runs in this process (app-open orphan check, RestoreManager). */
    @Volatile
    var steppingAlive: Boolean = false

    @Volatile private var generation = 0
    @Volatile private var lastLevel: Int? = null
    @Volatile private var lastArgb: Int = 0

    fun bootCount(context: Context): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    /** Read-modify-write of the run's Goodnight part under one lock; null result clears it. */
    suspend fun update(context: Context, transform: (GoodnightRun) -> GoodnightRun?): GoodnightRun? = mutex.withLock {
        val repo = TimerRepository(context.applicationContext)
        val run = repo.currentState().goodnight ?: return@withLock null
        val next = transform(run)
        if (next != run) repo.saveGoodnight(next)
        next
    }

    /**
     * Wind-down start: capture the user's brightness and mode, fix the method for the whole run (from the grants,
     * with the same 2 s rebind grace as the lock; never switched mid-run — L13 review).
     */
    suspend fun begin(context: Context): GoodnightRun? {
        val app = context.applicationContext
        if (SleepAccessibilityService.instance == null && SpecialAccess.isGranted(app, AccessKind.ACCESSIBILITY)) {
            var waited = 0L
            while (SleepAccessibilityService.instance == null && waited < 2_000L) {
                delay(200); waited += 200
            }
        }
        val overlay = SleepAccessibilityService.instance != null
        val canWrite = Settings.System.canWrite(app)
        val resolver = app.contentResolver
        val brightness = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 255)
        val mode = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, GoodnightPlan.MODE_MANUAL)
        // A brightness record still owed (should have been paid at start) holds the user's real values.
        val owed = TimerRepository(app).currentOwedRestore()
        val original = owed.brightness ?: brightness
        val originalMode = owed.brightnessMode ?: mode
        lastLevel = null
        lastArgb = 0
        val boot = bootCount(app)
        val run = update(app) { r ->
            if (r.started) {
                r
            } else {
                r.copy(
                    started = true,
                    startBrightness = original,
                    autoMode = originalMode != GoodnightPlan.MODE_MANUAL,
                    method = GoodnightPlan.resolveMethod(r.dimUsing, overlay, canWrite),
                    overlay = overlay,
                    bootCount = boot,
                )
            }
        }
        Log.i(TAG, "wind-down start: brightness=$original mode=$originalMode overlay=$overlay canWrite=$canWrite -> ${run?.method} ($run)")
        return run
    }

    /** Applies the plan for [nowMillis]. Returns the step actually applied (after the guard), for logging. */
    suspend fun applyNow(context: Context, run: GoodnightRun, nowMillis: Long): GoodnightPlan.Step {
        val app = context.applicationContext
        val step = GoodnightPlan.step(run.progress.value(nowMillis), run.black.value(nowMillis), run.params())
        var level = step.backlight
        val prev = lastLevel
        if (level != null && prev != null) level = minOf(level, prev)
        if (level != null) lastLevel = level
        var argb = step.overlayArgb
        if (((argb ushr 24) and 0xFF) < ((lastArgb ushr 24) and 0xFF)) argb = lastArgb else lastArgb = argb

        if (run.method == BacklightMethod.SYSTEM && level != null && !run.tookOver) {
            if (!RestoreManager.writeGoodnightBrightness(app, level, run.startBrightness, if (run.autoMode) GoodnightPlan.MODE_AUTOMATIC else GoodnightPlan.MODE_MANUAL)) {
                update(app) { it.copy(tookOver = true) }
                Log.i(TAG, "user took over the brightness: no more writes this run")
            }
        }
        if (run.overlay) {
            val windowValue = if (run.method == BacklightMethod.WINDOW) GoodnightPlan.windowBrightness(level) else -1f
            showOverlay(argb, windowValue)
        }
        return GoodnightPlan.Step(level, argb)
    }

    /** Shows/updates the layer unless a hide happened since this generation was read. */
    suspend fun showOverlay(argb: Int, windowBrightness: Float) {
        val gen = generation
        withContext(Dispatchers.Main) {
            if (gen == generation) SleepAccessibilityService.instance?.goodnightApply(argb, windowBrightness)
        }
    }

    /** Removes the layer (and with it a WINDOW backlight override) now. */
    suspend fun hideOverlay(reason: String) {
        generation++
        lastArgb = 0
        lastLevel = null
        withContext(Dispatchers.Main) {
            val shown = SleepAccessibilityService.instance?.goodnightHide() ?: false
            if (shown) Log.i(TAG, "overlay removed ($reason)")
        }
    }

    /** Whether this run still owes the user a restore (layer shown or a SYSTEM brightness change). */
    fun pending(run: GoodnightRun?): Boolean = run?.owesScreen == true
}
