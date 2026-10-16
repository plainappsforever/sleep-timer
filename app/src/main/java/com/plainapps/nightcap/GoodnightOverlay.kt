package com.plainapps.nightcap

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * The Goodnight layer (SPEC F20, D22/D42): one plain, non-touchable, non-focusable TYPE_ACCESSIBILITY_OVERLAY view
 * owned by [SleepAccessibilityService]. It carries the warm/dark colour AND (WINDOW method) the backlight override
 * `screenBrightness`, so both appear in the same frame and both vanish with the window (crash/force-stop safe).
 *
 * Built from Dimmer's measured lessons (own code, not a copy): explicit real-display size anchored top-left,
 * `fitInsetsTypes = 0` and cutout mode ALWAYS (API 30+; SHORT_EDGES on 28-29) so the status-bar area is covered too;
 * re-sized after rotation ([updateBounds], Dimmer D-V3-20); brightness set before `addView`; `updateViewLayout` only
 * when a layout value changed. Never FLAG_KEEP_SCREEN_ON (Dimmer D-V3-19): the screen must be free to turn off.
 * Main thread only.
 */
class GoodnightOverlay(private val context: Context) {

    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var color: Int = 0

    private val windowManager: WindowManager
        get() = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    val isShowing: Boolean get() = view != null

    /** Shows the layer or updates it in place. False if it could not be added. */
    fun apply(argb: Int, brightness: Float): Boolean {
        val existing = view
        val p = params
        if (existing != null && p != null) {
            if (argb != color) {
                existing.setBackgroundColor(argb)
                color = argb
            }
            if (p.screenBrightness != brightness) {
                p.screenBrightness = brightness
                relayout()
            }
            return true
        }
        val v = View(context).apply { setBackgroundColor(argb) }
        val (w, h) = realDisplaySize()
        @Suppress("DEPRECATION")
        val lp = WindowManager.LayoutParams(
            w,
            h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0
        lp.y = 0
        lp.title = "SleepTimerGoodnight"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lp.fitInsetsTypes = 0
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        // Before addView: backlight and layer change in the same frame.
        lp.screenBrightness = brightness
        return try {
            windowManager.addView(v, lp)
            view = v
            params = lp
            color = argb
            true
        } catch (e: RuntimeException) { // BadTokenException, SecurityException, IllegalArgumentException/StateException
            false
        }
    }

    /** After rotation / fold: cover the whole display again. */
    fun updateBounds() {
        val p = params ?: return
        val (w, h) = realDisplaySize()
        if (p.width == w && p.height == h) return
        p.width = w
        p.height = h
        relayout()
    }

    fun hide() {
        val v = view ?: return
        view = null
        params = null
        color = 0
        try {
            windowManager.removeView(v)
        } catch (e: IllegalArgumentException) {
            // Already detached (token died).
        }
    }

    private fun relayout() {
        val v = view ?: return
        val p = params ?: return
        try {
            windowManager.updateViewLayout(v, p)
        } catch (e: IllegalArgumentException) {
            // Detached; hide() cleans up.
        }
    }

    private fun realDisplaySize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.currentWindowMetrics.bounds
            return b.width() to b.height()
        }
        val point = Point()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealSize(point)
        if (point.x > 0 && point.y > 0) return point.x to point.y
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }
}
