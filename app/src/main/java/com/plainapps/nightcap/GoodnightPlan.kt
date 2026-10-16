package com.plainapps.nightcap

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/** "Dim using" (SPEC F20): brightness + overlay (default), or the overlay alone (fallback if a jump shows). */
enum class DimUsing { BOTH, OVERLAY_ONLY }

/**
 * How the backlight is lowered (DECISIONS D42): WINDOW = `LayoutParams.screenBrightness` on our own accessibility
 * overlay (no permission, never touches the user's setting, gone with the window); SYSTEM =
 * `Settings.System.SCREEN_BRIGHTNESS` (WRITE_SETTINGS, owed-restore record); NONE = the backlight is left alone.
 */
enum class BacklightMethod { NONE, WINDOW, SYSTEM }

/**
 * A value that rises from [from] at [startMillis] to 1 at [endMillis] and holds [from] before [startMillis].
 * Goodnight's whole timeline is two of these (wind-down progress, blackout), so every output is a function of
 * values that can only go up — the "never brighten" invariant (D23) follows from monotone outputs.
 */
data class Ramp(val startMillis: Long, val endMillis: Long, val from: Float) {
    fun value(nowMillis: Long): Float = when {
        from >= 1f -> 1f
        nowMillis <= startMillis -> from
        nowMillis >= endMillis -> 1f
        else -> from + (1f - from) * (nowMillis - startMillis).toFloat() / (endMillis - startMillis)
    }

    /**
     * The same ramp aimed at a new end (extend, −5, End now, volume-touch auto-extend): it holds its current value
     * and then covers only the rest of [durationMillis] before [newEndMillis]. Never lower than now.
     */
    fun replanned(nowMillis: Long, newEndMillis: Long, durationMillis: Long, minMillis: Long): Ramp {
        val v = value(nowMillis)
        val start = maxOf(nowMillis, newEndMillis - ((1f - v) * durationMillis).toLong())
        return Ramp(start, maxOf(newEndMillis, start + minMillis), v)
    }
}

/**
 * The Goodnight part of one run (SPEC F20), snapshotted at start and completed at wind-down start. Stored as one
 * string in the run state ([GoodnightCodec]).
 */
data class GoodnightRun(
    /** Effective wind-down length (the setting, clamped to the timer). */
    val windDownMillis: Long,
    val warmth: Boolean,
    val blackout: Boolean,
    val dimUsing: DimUsing,
    val progress: Ramp,
    val black: Ramp,
    /** Wind-down has begun: the values below are captured. */
    val started: Boolean = false,
    /** The user's SCREEN_BRIGHTNESS (0..255) and mode at wind-down start. */
    val startBrightness: Int = -1,
    val autoMode: Boolean = false,
    val method: BacklightMethod = BacklightMethod.NONE,
    val overlay: Boolean = false,
    /** The user changed brightness/mode during a SYSTEM run: no more writes this run. */
    val tookOver: Boolean = false,
    /** Overlay removed and brightness put back (or nothing left to do). */
    val finished: Boolean = false,
    /** Settings.Global.BOOT_COUNT when the wind-down began: a run from before a reboot is never resumed. */
    val bootCount: Int = -1,
) {
    /** Something to give back: our layer, or a SYSTEM brightness write (any method; [Goodnight.pending]). */
    val owesScreen: Boolean get() = started && !finished && (overlay || method == BacklightMethod.SYSTEM)

    val windDownMinutesShown: Int get() = ((windDownMillis + 59_999L) / 60_000L).toInt().coerceAtLeast(1)

    fun params(): GoodnightPlan.Params = GoodnightPlan.Params(startBrightness, autoMode, method, overlay, warmth, blackout)

    /** Extend / −5 / End now / auto-extend: the wind-down holds where it is, then continues towards the new deadline. */
    fun replanned(nowMillis: Long, newDeadlineMillis: Long, fadeSeconds: Int): GoodnightRun = copy(
        progress = progress.replanned(nowMillis, newDeadlineMillis - fadeSeconds * 1000L, windDownMillis, GoodnightPlan.MIN_SEGMENT_MILLIS),
    )

    /**
     * Blackout starts only after the pause ladder, when the run is already over (no extend can follow) and a lock or
     * "screen off sooner" will turn the screen off (L13 review: during the fade an extend could undo it).
     */
    fun withBlackoutFrom(nowMillis: Long): GoodnightRun = copy(black = Ramp(nowMillis, nowMillis + GoodnightPlan.BLACKOUT_MILLIS, 0f))
}

/**
 * Pure Goodnight maths (SPEC F20, DECISIONS D21-D23, D42). No Android imports; `GoodnightPlanTest`.
 *
 * Backlight (Settings 1..255 scale; null = leave the backlight alone):
 * - manual mode: the user's value down to [FLOOR] along a perceptual curve (linear in level^(1/2.2));
 * - automatic mode: untouched (auto keeps working) until the wind-down is complete, then [FLOOR], which is below any
 *   auto level — it can only darken, and with the overlay it happens under the darkest layer (D42). The plan's
 *   min(saved, 64) start ([AUTO_START_CAP]) could be brighter than a dark room's auto level, so it is not used.
 * Overlay (ARGB; only with the accessibility overlay): warm colour from black to Dimmer's dark "Candle" red, an alpha
 * that only rises (tint first, dimming in the last third — or over the whole wind-down when the overlay does the dimming),
 * Dimmer's alpha compensation for the tint's own light, and an optional blackout ramp after the pause, before the lock.
 */
object GoodnightPlan {
    const val FLOOR = 1
    const val AUTO_START_CAP = 64
    const val MODE_MANUAL = 0
    const val MODE_AUTOMATIC = 1
    const val STEP_MILLIS = 2_000L
    const val BLACKOUT_MILLIS = 3_000L
    /** "Not scheduled" for the blackout ramp: its value stays 0. */
    val NO_BLACKOUT = Ramp(Long.MAX_VALUE, Long.MAX_VALUE, 0f)
    const val MIN_SEGMENT_MILLIS = 5_000L
    const val DEFAULT_WIND_DOWN_MINUTES = 15
    val WIND_DOWN_OPTIONS = listOf(5, 10, 15, 30)

    /**
     * D42: with the overlay available, the backlight is lowered through the overlay window's brightness override
     * (Dimmer D-V3-16) rather than the system setting. Flip to false if the owner's phone shows the override does not
     * dim the real panel: the SYSTEM method is then used whenever "Modify system settings" is granted.
     */
    const val PREFER_WINDOW = true

    const val WARM_R = 70
    const val WARM_G = 15
    const val WARM_B = 0
    const val TINT_MAX_ALPHA = 0.30f
    const val LATE_DIM_MAX_ALPHA = 0.50f
    const val OVERLAY_DIM_MAX_ALPHA = 0.75f
    const val TINT_COMPENSATION = 0.3f
    const val MAX_ALPHA = 0.975f
    const val BLACKOUT_ALPHA = 0.98f
    private const val GAMMA = 2.2

    data class Params(
        val startBrightness: Int,
        val autoMode: Boolean,
        val method: BacklightMethod,
        val overlay: Boolean,
        val warmth: Boolean,
        val blackout: Boolean,
    )

    /** One applied state: backlight level (null = untouched) and overlay colour (0 = no overlay). */
    data class Step(val backlight: Int?, val overlayArgb: Int) {
        val overlayAlpha: Int get() = (overlayArgb ushr 24) and 0xFF
    }

    /** The run's Goodnight at start: wind-down ends where the audio fade starts (clamped to the timer). */
    fun newRun(
        runStartMillis: Long,
        deadlineMillis: Long,
        fadeSeconds: Int,
        windDownMinutes: Int,
        warmth: Boolean,
        blackout: Boolean,
        dimUsing: DimUsing,
    ): GoodnightRun {
        val end = deadlineMillis - fadeSeconds * 1000L
        val w = minOf(windDownMinutes.coerceAtLeast(1) * 60_000L, (end - runStartMillis).coerceAtLeast(0L))
        return GoodnightRun(
            windDownMillis = w,
            warmth = warmth,
            blackout = blackout,
            dimUsing = dimUsing,
            progress = Ramp(end - w, end, 0f),
            black = NO_BLACKOUT,
        )
    }

    /** Whether Goodnight can do anything with these grants (else the switch is unavailable, the run is a normal timer). */
    fun available(dimUsing: DimUsing, overlayPossible: Boolean, canWriteSettings: Boolean): Boolean = when (dimUsing) {
        DimUsing.BOTH -> overlayPossible || canWriteSettings
        DimUsing.OVERLAY_ONLY -> overlayPossible
    }

    fun resolveMethod(
        dimUsing: DimUsing,
        overlayAvailable: Boolean,
        canWriteSettings: Boolean,
        preferWindow: Boolean = PREFER_WINDOW,
    ): BacklightMethod = when {
        dimUsing == DimUsing.OVERLAY_ONLY -> BacklightMethod.NONE
        overlayAvailable && (preferWindow || !canWriteSettings) -> BacklightMethod.WINDOW
        canWriteSettings -> BacklightMethod.SYSTEM
        else -> BacklightMethod.NONE
    }

    fun step(progress: Float, blackoutProgress: Float, params: Params): Step =
        Step(backlight(progress, params), overlayArgb(progress, blackoutProgress, params))

    fun backlight(progress: Float, params: Params): Int? {
        if (params.method == BacklightMethod.NONE) return null
        val p = progress.coerceIn(0f, 1f)
        val start = params.startBrightness.coerceIn(FLOOR, 255)
        // Automatic mode: the panel's real level is unknown, so any start value could be brighter (D21 risk). The
        // backlight is left to auto until the wind-down is complete, then set to the floor, which is below any auto
        // level (D42; the plan's min(saved, 64) start is not used — L11 row).
        if (params.autoMode) return if (p >= 1f) FLOOR else null
        if (p <= 0f) return start
        val a = start.toDouble().pow(1.0 / GAMMA)
        val b = FLOOR.toDouble().pow(1.0 / GAMMA)
        return (a + (b - a) * p).pow(GAMMA).roundToInt().coerceIn(FLOOR, start)
    }

    fun overlayArgb(progress: Float, blackoutProgress: Float, params: Params): Int {
        if (!params.overlay) return 0
        val p = progress.coerceIn(0f, 1f)
        val q = if (params.blackout) blackoutProgress.coerceIn(0f, 1f) else 0f
        val w = if (params.warmth) p else 0f
        // The backlight does the main dimming only in manual mode; otherwise the overlay does it over the whole wind-down.
        val backlightDims = params.method != BacklightMethod.NONE && !params.autoMode
        val dim = if (backlightDims) LATE_DIM_MAX_ALPHA * ((p - 2f / 3f) * 3f).coerceIn(0f, 1f) else OVERLAY_DIM_MAX_ALPHA * p
        val base = maxOf(dim, TINT_MAX_ALPHA * w)
        var alpha = minOf(MAX_ALPHA, base * (1f + TINT_COMPENSATION * w))
        var r = WARM_R * w
        var g = WARM_G * w
        var b = WARM_B * w
        if (q > 0f) {
            alpha += (BLACKOUT_ALPHA - alpha) * q
            r *= 1f - q
            g *= 1f - q
            b *= 1f - q
        }
        return argb((alpha * 255f).roundToInt(), r.roundToInt(), g.roundToInt(), b.roundToInt())
    }

    /**
     * Window brightness override for a Settings level: AOSP maps int n to float (n − 1) / 254 (measured 200 → 0.78346,
     * Dimmer D-V3-16). Half a step lower than that, because the int the user's slider stored may itself be rounded up
     * from the real float (L13 review): the first override is then never above the user's brightness. [FLOOR] → 0.0 =
     * panel minimum (Dimmer D-V3-17); null → −1 = no override.
     */
    fun windowBrightness(level: Int?): Float = when {
        level == null -> -1f
        level <= FLOOR -> 0f
        else -> ((level.coerceAtMost(255) - 1.5f) / 254f)
    }

    fun argb(alpha: Int, r: Int, g: Int, b: Int): Int =
        (alpha.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** Rec. 601 luma (0..255) of [argb] composited over an opaque pixel of luma-equivalent grey/colour. */
    fun compositeLuma(argb: Int, r: Int, g: Int, b: Int): Float {
        val a = ((argb ushr 24) and 0xFF) / 255f
        val or = (argb ushr 16) and 0xFF
        val og = (argb ushr 8) and 0xFF
        val ob = argb and 0xFF
        return 0.299f * (or * a + r * (1 - a)) + 0.587f * (og * a + g * (1 - a)) + 0.114f * (ob * a + b * (1 - a))
    }
}

/**
 * What to put back of a SYSTEM brightness change (Dimmer's RestoreRules, D-V3-18, ported): decided from the CURRENT
 * values at restore time, so a user's own change is never overwritten and our floor is never left behind.
 */
object BrightnessRestore {
    const val TOLERANCE = 2

    data class Plan(val brightness: Int?, val mode: Int?)

    fun isOurs(set: Int, previousSet: Int, current: Int): Boolean =
        abs(current - set) <= TOLERANCE || abs(current - previousSet) <= TOLERANCE

    fun plan(original: Int, originalMode: Int, set: Int, previousSet: Int, current: Int, currentMode: Int): Plan = when {
        // Manual and still our value: both back. Manual but moved by the user: keep theirs, give back only the mode.
        currentMode == GoodnightPlan.MODE_MANUAL ->
            if (isOurs(set, previousSet, current)) Plan(original, originalMode) else Plan(null, originalMode)
        // Automatic now (the user switched it on, or we never got to switch it off): the stored value is invisible,
        // so ours goes back quietly; anything else is theirs.
        else -> if (isOurs(set, previousSet, current)) Plan(original, null) else Plan(null, null)
    }

    /** SYSTEM writes stop for the run once the user moved the slider or switched auto on after our first write. */
    fun tookOver(set: Int, previousSet: Int, current: Int, currentMode: Int): Boolean =
        currentMode != GoodnightPlan.MODE_MANUAL || !isOurs(set, previousSet, current)
}

/** One-string codec for [GoodnightRun] (`v1|wd=…,…`); null for anything unreadable. Pure, tested. */
object GoodnightCodec {
    private const val PREFIX = "v1|"

    fun encode(r: GoodnightRun): String = PREFIX + listOf(
        "wd=${r.windDownMillis}", "warm=${b(r.warmth)}", "black=${b(r.blackout)}", "dim=${r.dimUsing.name}",
        "ps=${r.progress.startMillis}", "pe=${r.progress.endMillis}", "pf=${r.progress.from}",
        "bs=${r.black.startMillis}", "be=${r.black.endMillis}", "bf=${r.black.from}",
        "st=${b(r.started)}", "sb=${r.startBrightness}", "auto=${b(r.autoMode)}", "m=${r.method.name}",
        "ov=${b(r.overlay)}", "to=${b(r.tookOver)}", "fin=${b(r.finished)}", "boot=${r.bootCount}",
    ).joinToString(",")

    fun decode(raw: String?): GoodnightRun? {
        if (raw == null || !raw.startsWith(PREFIX)) return null
        val f = raw.removePrefix(PREFIX).split(",").mapNotNull { e ->
            val i = e.indexOf('=')
            if (i <= 0) null else e.substring(0, i) to e.substring(i + 1)
        }.toMap()
        return try {
            GoodnightRun(
                windDownMillis = f.getValue("wd").toLong(),
                warmth = f["warm"] == "1",
                blackout = f["black"] == "1",
                dimUsing = DimUsing.valueOf(f.getValue("dim")),
                progress = Ramp(f.getValue("ps").toLong(), f.getValue("pe").toLong(), f.getValue("pf").toFloat()),
                black = Ramp(f.getValue("bs").toLong(), f.getValue("be").toLong(), f.getValue("bf").toFloat()),
                started = f["st"] == "1",
                startBrightness = f["sb"]?.toIntOrNull() ?: -1,
                autoMode = f["auto"] == "1",
                method = f["m"]?.let { runCatching { BacklightMethod.valueOf(it) }.getOrNull() } ?: BacklightMethod.NONE,
                overlay = f["ov"] == "1",
                tookOver = f["to"] == "1",
                finished = f["fin"] == "1",
                bootCount = f["boot"]?.toIntOrNull() ?: -1,
            )
        } catch (e: IllegalArgumentException) { // NumberFormatException is a subclass
            null
        } catch (e: NoSuchElementException) {
            null
        }
    }

    private fun b(v: Boolean) = if (v) "1" else "0"
}
