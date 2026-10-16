package com.tim.sleeptimer

/**
 * What the home-screen widget shows (SPEC F14, DECISIONS D24). Pure Kotlin, no Android imports: the
 * RemoteViews renderer in `TimerWidgetProvider` only turns this into views.
 */
sealed interface WidgetContent {
    /** No timer: up to [PresetButton]s that start a preset. */
    data class Idle(val buttons: List<PresetButton>) : WidgetContent

    /** A timer runs: "Ends HH:MM", the countdown to [deadlineWallClockMillis], +extend and Stop. */
    data class Running(
        val deadlineWallClockMillis: Long,
        val extendStepMinutes: Int,
        val fading: Boolean,
    ) : WidgetContent
}

/** One widget start button: two-line label ("Nap" / "20 min"), or just "30 min" when unnamed. */
data class PresetButton(val presetId: String, val title: String?, val subtitle: String)

object WidgetPlan {
    const val MAX_BUTTONS = 3

    /** Width one preset button needs, and the widget's outer padding, in dp. */
    private const val BUTTON_WIDTH_DP = 72
    private const val PADDING_DP = 16

    /** A run whose deadline passed this long ago without a DONE state (reboot, killed service) shows idle. */
    private const val STALE_AFTER_DEADLINE_MS = 60_000L

    fun button(preset: Preset): PresetButton =
        if (preset.name.isNullOrBlank()) PresetButton(preset.id, null, "${preset.minutes} min")
        else PresetButton(preset.id, preset.name, "${preset.minutes} min")

    /** How many preset buttons fit a widget [minWidthDp] wide: 1 to [MAX_BUTTONS], never more than there are presets. */
    fun buttonCount(minWidthDp: Int, presetCount: Int): Int =
        ((minWidthDp - PADDING_DP) / BUTTON_WIDTH_DP).coerceIn(1, MAX_BUTTONS).coerceAtMost(presetCount.coerceAtLeast(1))

    fun content(state: TimerState, presets: List<Preset>, nowMillis: Long, minWidthDp: Int = 250): WidgetContent {
        val live = state.isActive && state.deadlineWallClockMillis + STALE_AFTER_DEADLINE_MS > nowMillis
        if (live) {
            return WidgetContent.Running(
                deadlineWallClockMillis = state.deadlineWallClockMillis,
                extendStepMinutes = state.extendStepMinutes,
                fading = state.phase == RunPhase.FADING,
            )
        }
        return WidgetContent.Idle(presets.take(buttonCount(minWidthDp, presets.size)).map(::button))
    }
}
