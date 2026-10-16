package com.tim.sleeptimer

/**
 * What happens to the screen after the pause (SPEC F16, DECISIONS D16/D36). Pure Kotlin, no Android
 * imports: the service passes in the facts (API level, whether our accessibility service is bound,
 * whether "Modify system settings" is granted, whether the screen is on) and gets one decision back.
 */
enum class ScreenAction { NONE, LOCK, HOME_THEN_LOCK, SCREEN_OFF_SOONER }

/** v2.1: one tick of the service's wait after the end (screen timeout and Goodnight given back). */
enum class ScreenWaitStep { WAIT, RESTORE_TIMEOUT, RESTORE_ALL, DONE }

object EndActions {
    /** GLOBAL_ACTION_LOCK_SCREEN exists from API 28 (Android 9). */
    const val LOCK_MIN_SDK = 28

    /** "Turn screen off sooner" sets the system screen timeout to this for the few seconds after the pause. */
    const val SCREEN_OFF_SOONER_TIMEOUT_MS = 15_000

    /** Longest the service waits for the screen to go off before putting the user's timeout back anyway. */
    const val SCREEN_OFF_WAIT_MS = 10 * 60_000L

    /**
     * v2.1 (REVIEW_V2 row 2): the dimmed screen (any Goodnight method) is given back at the next screen-off, or at the
     * latest this long after the end even if the screen stays on (a 30-minute timeout, "stay awake while charging", an
     * app keeping the screen on). Before v2.1 a SYSTEM brightness then stayed at the floor until the app was opened.
     */
    const val GOODNIGHT_MAX_WAIT_MS = 30 * 60_000L

    /**
     * The wait after the end, once a second. Screen off -> everything back. Screen on: the timeout comes back after
     * [SCREEN_OFF_WAIT_MS] (the user is awake) while a dimmed screen stays dim, at most until [GOODNIGHT_MAX_WAIT_MS].
     * [timeoutOwed] / [goodnightOwed]: something of that kind is still to give back (a timeout already handled by
     * RESTORE_TIMEOUT counts as not owed, even if its write failed and the record stays for the next app open).
     */
    fun screenWaitStep(elapsedMillis: Long, interactive: Boolean, timeoutOwed: Boolean, goodnightOwed: Boolean): ScreenWaitStep = when {
        !timeoutOwed && !goodnightOwed -> ScreenWaitStep.DONE
        !interactive -> ScreenWaitStep.RESTORE_ALL
        goodnightOwed && elapsedMillis >= GOODNIGHT_MAX_WAIT_MS -> ScreenWaitStep.RESTORE_ALL
        timeoutOwed && elapsedMillis >= SCREEN_OFF_WAIT_MS -> ScreenWaitStep.RESTORE_TIMEOUT
        else -> ScreenWaitStep.WAIT
    }

    /** Pause between Home and lock so the launcher is drawn before the screen goes off. */
    const val HOME_BEFORE_LOCK_DELAY_MS = 600L

    /** A preset's own lock choice wins; null = the global switch. */
    fun effectiveLock(presetLock: Boolean?, globalLock: Boolean): Boolean = presetLock ?: globalLock

    /**
     * What the run intends to do, for the notification line: from the switches and the grants (not
     * from whether the service happens to be bound right now). Locking wins over "screen off sooner",
     * which is its fallback.
     */
    fun planned(
        lock: Boolean,
        homeFirst: Boolean,
        screenOffSooner: Boolean,
        sdkInt: Int,
        lockGranted: Boolean,
        writeSettingsGranted: Boolean,
    ): ScreenAction = when {
        lock && sdkInt >= LOCK_MIN_SDK && lockGranted -> if (homeFirst) ScreenAction.HOME_THEN_LOCK else ScreenAction.LOCK
        screenOffSooner && writeSettingsGranted -> ScreenAction.SCREEN_OFF_SOONER
        else -> ScreenAction.NONE
    }

    /**
     * What the end sequence actually does, given the facts at that moment. Nothing when the screen is
     * already off (the user is asleep: the phone's own lock policy applies, and changing the timeout
     * would only be a change to undo). Lock needs API 28+ and the bound service; otherwise the fallback
     * runs if its switch is on and "Modify system settings" is granted; otherwise nothing (system timeout).
     */
    fun choose(
        lock: Boolean,
        homeFirst: Boolean,
        screenOffSooner: Boolean,
        sdkInt: Int,
        lockServiceBound: Boolean,
        canWriteSettings: Boolean,
        screenInteractive: Boolean,
    ): ScreenAction = when {
        !screenInteractive -> ScreenAction.NONE
        lock && sdkInt >= LOCK_MIN_SDK && lockServiceBound ->
            if (homeFirst) ScreenAction.HOME_THEN_LOCK else ScreenAction.LOCK
        screenOffSooner && canWriteSettings -> ScreenAction.SCREEN_OFF_SOONER
        else -> ScreenAction.NONE
    }

    /** What to do with an owed screen timeout: write [write] back (if not null), and/or drop the record. */
    data class TimeoutRestore(val write: Int?, val clearRecord: Boolean)

    /**
     * The owed screen-timeout restore (rule 8). Write the user's value back only while the setting still
     * holds exactly what we wrote; if the user changed it meanwhile, their newer choice wins and the
     * record is dropped. Without the grant (revoked) the record is KEPT, so the debt stays visible and
     * is paid as soon as the grant is back or the user changes the value themselves.
     */
    fun restoreDecision(owedOriginal: Int?, valueWeSet: Int?, current: Int?, canWrite: Boolean): TimeoutRestore = when {
        owedOriginal == null -> TimeoutRestore(null, clearRecord = false)
        valueWeSet == null -> TimeoutRestore(null, clearRecord = true)
        current == null -> TimeoutRestore(null, clearRecord = false)
        current != valueWeSet -> TimeoutRestore(null, clearRecord = true)
        owedOriginal == valueWeSet -> TimeoutRestore(null, clearRecord = true)
        !canWrite -> TimeoutRestore(null, clearRecord = false)
        else -> TimeoutRestore(owedOriginal, clearRecord = true)
    }

    /** Shortening only makes sense when the user's timeout is longer than ours. */
    fun screenOffSoonerUseful(userTimeoutMillis: Int): Boolean = userTimeoutMillis > SCREEN_OFF_SOONER_TIMEOUT_MS

    /**
     * The original to remember before writing [SCREEN_OFF_SOONER_TIMEOUT_MS]. An existing owed record
     * keeps its original (a second end sequence before the restore must not record our own 15 s as
     * "the user's value").
     */
    fun originalToRecord(existingOwedOriginal: Int?, current: Int): Int = existingOwedOriginal ?: current

    /** Suffix of the notification's sequence line: "→ lock", "→ screen off" or nothing. */
    fun summarySuffix(action: ScreenAction): String = when (action) {
        ScreenAction.LOCK, ScreenAction.HOME_THEN_LOCK -> " → lock"
        ScreenAction.SCREEN_OFF_SOONER -> " → screen off"
        ScreenAction.NONE -> ""
    }
}

/**
 * Enhanced compatibility (SPEC F17, D17): which media sessions rung 0 pauses. Pure; the Android side
 * passes each session's package and `PlaybackState.getState()` value.
 */
object SessionPlan {
    /** `PlaybackState.STATE_PLAYING`, `STATE_FAST_FORWARDING`, `STATE_REWINDING`, `STATE_BUFFERING` (stable platform values). */
    private val AUDIBLE_STATES = setOf(3, 4, 5, 6)

    fun isPlaying(playbackState: Int?): Boolean = playbackState != null && playbackState in AUDIBLE_STATES

    /** Indices of the sessions to pause: every playing one that is not ours. */
    fun toPause(sessions: List<Pair<String, Int?>>, ownPackage: String): List<Int> =
        sessions.indices.filter { i -> sessions[i].first != ownPackage && isPlaying(sessions[i].second) }

    /** "Try it now" wording for rung 0: "Paused Spotify ✓ (session)", several apps joined with " + ". */
    fun sessionResultLabel(appLabels: List<String>): String =
        appLabels.distinct().joinToString(" + ").ifBlank { "the player" }
}

/** Parsing of Android's special-access settings strings (pure, unit-tested). */
object AccessRules {
    /**
     * Whether `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` ([raw], colon-separated component names,
     * full `pkg/pkg.Cls` or short `pkg/.Cls`) contains our service. This is the user's switch; unlike
     * AccessibilityManager's enabled list it does not flicker while the service is being (re)bound.
     */
    fun isAccessibilityServiceEnabled(raw: String?, accessibilityEnabled: Boolean, pkg: String, cls: String): Boolean {
        if (!accessibilityEnabled || raw.isNullOrBlank()) return false
        return raw.split(':').any { entry ->
            val slash = entry.indexOf('/')
            if (slash <= 0) return@any false
            val p = entry.substring(0, slash).trim()
            var c = entry.substring(slash + 1).trim()
            if (c.startsWith(".")) c = p + c
            p == pkg && c == cls
        }
    }
}
