package com.tim.sleeptimer

/**
 * v2.1 (REVIEW_V2 "missing unit tests" 1): what each user command does to the persisted run, as pure functions.
 * [TimerService] reads the facts (now, the current volume, whether something plays), applies the answer to
 * AudioManager / alarms / notifications, and saves the returned state. Tests: `RunTransitionsTest`.
 */
object RunTransitions {

    /** +extend: the new state, and the ramp back up to the fade-start volume when the extend interrupts a fade. */
    data class Extended(val state: TimerState, val rampUp: List<FadeStep>)

    /**
     * +extend (notification, tile, app, shake). Null = nothing to extend (no live run, or a stale one: the caller closes
     * it). During the fade or the pause ladder (phase FADING) the volume ramps back up from the last index we set.
     */
    fun extend(state: TimerState, nowMillis: Long, currentIndex: Int): Extended? {
        if (!state.isActive || TimerPlan.isStale(true, state.deadlineWallClockMillis, nowMillis)) return null
        val rampUp = if (state.phase == RunPhase.FADING && state.originalStreamIndex >= 0) {
            TimerPlan.rampUpSteps(state.lastSetStreamIndex.takeIf { it >= 0 } ?: currentIndex, state.originalStreamIndex)
        } else {
            emptyList()
        }
        val deadline = RestoreRules.extendedDeadline(
            state.startWallClockMillis, state.deadlineWallClockMillis, nowMillis, state.extendStepMinutes * 60_000L,
        )
        return Extended(
            state.copy(
                phase = RunPhase.RUNNING,
                deadlineWallClockMillis = deadline,
                originalStreamIndex = -1,
                lastSetStreamIndex = -1,
                mutedInsteadOfPaused = false,
            ),
            rampUp,
        )
    }

    /** The volume was touched during the fade (the user is awake): like +extend, counted and remembered (D19). */
    fun autoExtend(state: TimerState, nowMillis: Long): TimerState = state.copy(
        phase = RunPhase.RUNNING,
        deadlineWallClockMillis = RestoreRules.extendedDeadline(
            state.startWallClockMillis, state.deadlineWallClockMillis, nowMillis, state.extendStepMinutes * 60_000L,
        ),
        originalStreamIndex = -1,
        lastSetStreamIndex = -1,
        volumeTouchedDuringRun = true,
        autoExtendCount = state.autoExtendCount + 1,
    )

    /** "−5 min": only while counting down (hidden during the fade); null = nothing changes. */
    fun reduce(state: TimerState, nowMillis: Long): TimerState? {
        if (state.phase != RunPhase.RUNNING) return null
        val deadline = TimerPlan.reducedDeadline(nowMillis, state.deadlineWallClockMillis, state.fadeSeconds)
        return if (deadline == state.deadlineWallClockMillis) null else state.copy(deadlineWallClockMillis = deadline)
    }

    /** "End now": the end sequence at once with a fade of at most 10 s; a fade underway keeps its start volume. */
    fun endNow(state: TimerState, nowMillis: Long): TimerState? {
        if (!state.isActive) return null
        val fade = TimerPlan.endNowFadeSeconds(state.fadeSeconds)
        return state.copy(fadeSeconds = fade, deadlineWallClockMillis = nowMillis + fade * 1000L)
    }

    /** Stop: where the volume goes ([TimerPlan.stopRestoreIndex]), whether the bedtime record is settled, and the new state. */
    data class Stopped(val volumeTarget: Int?, val touched: Boolean, val clearOwedStream: Boolean, val state: TimerState)

    fun stop(state: TimerState, currentIndex: Int, isPlaying: Boolean): Stopped {
        val done = TimerState(phase = RunPhase.DONE, doneReason = DoneReason.STOPPED_BY_USER)
        if (!state.isActive) return Stopped(null, false, false, done)
        // v1: a fade goes back to its start; P5 (D39): an untouched bedtime volume goes back while nothing plays.
        val touched = state.volumeTouchedDuringRun ||
            (state.phase == RunPhase.RUNNING && TimerPlan.touchedSinceBedtime(state.bedtimeStreamIndex, currentIndex))
        val target = TimerPlan.stopRestoreIndex(
            fading = state.phase == RunPhase.FADING,
            fadeStartIndex = state.originalStreamIndex,
            preStartIndex = state.preStartStreamIndex,
            touched = touched,
            isPlaying = isPlaying,
        )
        // Stop settles the bedtime volume either way; DND stays on until its morning time (D19).
        return Stopped(target, touched, clearOwedStream = state.preStartStreamIndex >= 0, state = done)
    }
}
