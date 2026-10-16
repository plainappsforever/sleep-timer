package com.plainapps.nightcap

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * "Lock screen at end" (SPEC F16, DECISIONS D16/D36). Enabled only by the user in Android's
 * Accessibility settings, after our in-app disclosure. It reads nothing: no event types, no window
 * content (`res/xml/accessibility_service_config.xml`); it only performs two global actions when a
 * timer ends — Home (optional) and lock screen — and, for Goodnight (P6, D22/D42), draws one dark/warm layer
 * ([GoodnightOverlay]) whose window also carries the backlight override.
 *
 * Lifecycle (Dimmer lessons): [onServiceConnected] can run more than once per instance, so it is
 * idempotent; [onUnbind] is the "access gone / unbound" signal, but it also fires when a test tool
 * (`uiautomator dump`) or a force-stop unbinds us, so it only drops the reference — the settings
 * switch is reconciled with the real enabled list when the app is opened (MainActivity.onResume).
 */
class SleepAccessibilityService : AccessibilityService() {

    private val overlay by lazy { GoodnightOverlay(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var screenOffReceiver: BroadcastReceiver? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (instance === this) return
        instance = this
        Log.i(TAG, "connected")
        scope.launch { restoreGoodnightLayer() }
    }

    /**
     * After a process death the layer (and a WINDOW backlight override) is gone. If a Goodnight run of this boot is
     * still dimming, show it again at once at the plan's value for now (never brighter than before: the plan only
     * darkens with time); the service's stepping loop takes over when it is back (alarm 102). After the end, the
     * layer is released at the next screen-off.
     */
    private suspend fun restoreGoodnightLayer() {
        val state = TimerRepository(applicationContext).currentState()
        val run = state.goodnight ?: return
        if (!run.started || run.finished || !run.overlay || run.bootCount != Goodnight.bootCount(this)) return
        if (!state.isActive && state.phase != RunPhase.DONE) return
        val step = Goodnight.applyNow(this, run, System.currentTimeMillis())
        Log.i(TAG, "Goodnight layer restored on connect: argb=${Integer.toHexString(step.overlayArgb)} level=${step.backlight}")
        if (!state.isActive) releaseGoodnightOnScreenOff()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        goodnightHide()
        Log.i(TAG, "unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        goodnightHide()
        scope.cancel()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay.updateBounds() // rotation: cover the whole display again (Dimmer D-V3-20)
    }

    /** Main thread. Shows or updates the Goodnight layer; [windowBrightness] −1 = no backlight override. */
    fun goodnightApply(argb: Int, windowBrightness: Float): Boolean {
        val ok = overlay.apply(argb, windowBrightness)
        if (!ok) Log.w(TAG, "Goodnight layer could not be shown")
        return ok
    }

    /** Main thread. Removes the layer; true if one was showing. */
    fun goodnightHide(): Boolean {
        unregisterScreenOff()
        if (!overlay.isShowing) return false
        overlay.hide()
        return true
    }

    /**
     * D23 hand-over: the run is over but the screen stayed on past the service's 10-minute wait (or the process was
     * restarted after the end). The layer stays until the screen goes off, then it is removed and the brightness debt
     * is paid.
     */
    fun releaseGoodnightOnScreenOff() {
        if (screenOffReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val pending = goAsync()
                scope.launch {
                    try {
                        RestoreManager.applyOwed(applicationContext, "screen off (accessibility)")
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
        ContextCompat.registerReceiver(this, receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        screenOffReceiver = receiver
        Log.i(TAG, "Goodnight layer held until the screen goes off")
    }

    private fun unregisterScreenOff() {
        screenOffReceiver?.let { r -> try { unregisterReceiver(r) } catch (e: IllegalArgumentException) { /* gone */ } }
        screenOffReceiver = null
    }

    /** Locks the screen like the power button does (API 28+). False if unavailable. */
    fun lock(): Boolean {
        if (Build.VERSION.SDK_INT < EndActions.LOCK_MIN_SDK) return false
        val ok = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        Log.i(TAG, "lock -> $ok")
        return ok
    }

    fun goHome(): Boolean {
        val ok = performGlobalAction(GLOBAL_ACTION_HOME)
        Log.i(TAG, "home -> $ok")
        return ok
    }

    companion object {
        private const val TAG = "SleepAccessibility"

        /** Process-local reference to the bound service; null when not bound. */
        @Volatile
        var instance: SleepAccessibilityService? = null
            private set
    }
}
