package com.tim.sleeptimer

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** v2 P4: screen action at the end (SPEC F16, D16/D36), owed screen-timeout restore, rung 0 helpers. */
class EndActionsTest {

    private fun choose(
        lock: Boolean = false,
        home: Boolean = false,
        sooner: Boolean = false,
        sdk: Int = 36,
        bound: Boolean = true,
        canWrite: Boolean = true,
        interactive: Boolean = true,
    ) = EndActions.choose(lock, home, sooner, sdk, bound, canWrite, interactive)

    @Test
    fun `lock when the switch is on, the service bound and the screen on`() {
        assertEquals(ScreenAction.LOCK, choose(lock = true))
        assertEquals(ScreenAction.HOME_THEN_LOCK, choose(lock = true, home = true))
        assertEquals(ScreenAction.LOCK, choose(lock = true, sdk = 28))
    }

    @Test
    fun `nothing at all when the screen is already off`() {
        assertEquals(ScreenAction.NONE, choose(lock = true, interactive = false))
        assertEquals(ScreenAction.NONE, choose(sooner = true, interactive = false))
        assertEquals(ScreenAction.NONE, choose(lock = true, sooner = true, interactive = false))
    }

    @Test
    fun `lock unavailable falls back to screen off sooner only if that switch is on and granted`() {
        assertEquals(ScreenAction.SCREEN_OFF_SOONER, choose(lock = true, sooner = true, bound = false))
        assertEquals(ScreenAction.SCREEN_OFF_SOONER, choose(lock = true, sooner = true, sdk = 27))
        assertEquals(ScreenAction.SCREEN_OFF_SOONER, choose(lock = true, sooner = true, sdk = 26, home = true))
        assertEquals(ScreenAction.NONE, choose(lock = true, sooner = true, bound = false, canWrite = false))
        assertEquals(ScreenAction.NONE, choose(lock = true, bound = false))
        assertEquals(ScreenAction.NONE, choose(lock = true, sdk = 27))
    }

    @Test
    fun `screen off sooner alone, and nothing with every switch off`() {
        assertEquals(ScreenAction.SCREEN_OFF_SOONER, choose(sooner = true))
        assertEquals(ScreenAction.NONE, choose(sooner = true, canWrite = false))
        assertEquals(ScreenAction.NONE, choose())
        assertEquals(ScreenAction.NONE, choose(home = true))
    }

    @Test
    fun `preset choice wins over the global switch`() {
        assertTrue(EndActions.effectiveLock(presetLock = true, globalLock = false))
        assertFalse(EndActions.effectiveLock(presetLock = false, globalLock = true))
        assertTrue(EndActions.effectiveLock(presetLock = null, globalLock = true))
        assertFalse(EndActions.effectiveLock(presetLock = null, globalLock = false))
        // Preset "Off" with the global switch on: nothing happens at the end.
        assertEquals(ScreenAction.NONE, choose(lock = EndActions.effectiveLock(false, true)))
    }

    @Test
    fun `planned action for the notification follows switches and grants, not binding`() {
        assertEquals(ScreenAction.LOCK, EndActions.planned(true, false, false, 36, lockGranted = true, writeSettingsGranted = false))
        assertEquals(ScreenAction.HOME_THEN_LOCK, EndActions.planned(true, true, false, 36, true, false))
        assertEquals(ScreenAction.SCREEN_OFF_SOONER, EndActions.planned(true, false, true, 36, false, true))
        assertEquals(ScreenAction.SCREEN_OFF_SOONER, EndActions.planned(true, false, true, 27, true, true))
        assertEquals(ScreenAction.NONE, EndActions.planned(true, false, true, 36, false, false))
        assertEquals(ScreenAction.NONE, EndActions.planned(false, true, false, 36, true, true))
    }

    @Test
    fun `summary suffix and full notification line`() {
        assertEquals(" → lock", EndActions.summarySuffix(ScreenAction.LOCK))
        assertEquals(" → lock", EndActions.summarySuffix(ScreenAction.HOME_THEN_LOCK))
        assertEquals(" → screen off", EndActions.summarySuffix(ScreenAction.SCREEN_OFF_SOONER))
        assertEquals("", EndActions.summarySuffix(ScreenAction.NONE))
        assertEquals("Fade 30 s → pause → lock", TimerPlan.endSequenceSummary(30, ScreenAction.LOCK))
        assertEquals("Pause → screen off", TimerPlan.endSequenceSummary(0, ScreenAction.SCREEN_OFF_SOONER))
    }

    // ---- owed screen timeout (rule 8) ----

    @Test
    fun `restore writes the user's value back while ours is still set`() {
        val d = EndActions.restoreDecision(owedOriginal = 120_000, valueWeSet = 15_000, current = 15_000, canWrite = true)
        assertEquals(120_000, d.write)
        assertTrue(d.clearRecord)
    }

    @Test
    fun `a value the user changed meanwhile wins and the record is dropped`() {
        val d = EndActions.restoreDecision(120_000, 15_000, current = 60_000, canWrite = true)
        assertNull(d.write)
        assertTrue(d.clearRecord)
    }

    @Test
    fun `without the grant the record is kept so the debt stays visible`() {
        val d = EndActions.restoreDecision(120_000, 15_000, current = 15_000, canWrite = false)
        assertNull(d.write)
        assertFalse(d.clearRecord)
        // ...but if the user fixed it themselves, it is settled even without the grant.
        assertTrue(EndActions.restoreDecision(120_000, 15_000, current = 120_000, canWrite = false).clearRecord)
    }

    @Test
    fun `nothing owed means nothing to do, unreadable current keeps the record`() {
        val none = EndActions.restoreDecision(null, null, 15_000, true)
        assertNull(none.write); assertFalse(none.clearRecord)
        val unreadable = EndActions.restoreDecision(120_000, 15_000, null, true)
        assertNull(unreadable.write); assertFalse(unreadable.clearRecord)
    }

    @Test
    fun `a second end before the restore keeps the first original`() {
        assertEquals(120_000, EndActions.originalToRecord(existingOwedOriginal = 120_000, current = 15_000))
        assertEquals(60_000, EndActions.originalToRecord(existingOwedOriginal = null, current = 60_000))
    }

    @Test
    fun `screen off sooner only when the user's timeout is longer than 15 s`() {
        assertTrue(EndActions.screenOffSoonerUseful(30_000))
        assertFalse(EndActions.screenOffSoonerUseful(15_000))
        assertFalse(EndActions.screenOffSoonerUseful(10_000))
    }

    // ---- v2.1 (REVIEW_V2 row 2, "missing unit tests" 5): the wait after the end, for each Goodnight method ----

    private fun startedRun(method: BacklightMethod, overlay: Boolean): GoodnightRun =
        GoodnightPlan.newRun(0L, 3_600_000L, 30, 15, warmth = false, blackout = false, dimUsing = DimUsing.BOTH)
            .copy(started = true, method = method, overlay = overlay, startBrightness = 180)

    private val min = 60_000L

    @Test
    fun `every Goodnight method owes the screen once started, none after finished or before the start`() {
        assertTrue(startedRun(BacklightMethod.WINDOW, overlay = true).owesScreen)
        assertTrue(startedRun(BacklightMethod.SYSTEM, overlay = false).owesScreen) // Modify system settings only
        assertTrue(startedRun(BacklightMethod.SYSTEM, overlay = true).owesScreen)
        assertTrue(startedRun(BacklightMethod.NONE, overlay = true).owesScreen) // "Dark layer only"
        assertFalse(startedRun(BacklightMethod.SYSTEM, overlay = false).copy(finished = true).owesScreen)
        assertFalse(startedRun(BacklightMethod.SYSTEM, overlay = false).copy(started = false).owesScreen)
    }

    @Test
    fun `screen off gives everything back at once, for every method`() {
        for (run in listOf(startedRun(BacklightMethod.WINDOW, true), startedRun(BacklightMethod.SYSTEM, false), startedRun(BacklightMethod.NONE, true))) {
            assertEquals(ScreenWaitStep.RESTORE_ALL, EndActions.screenWaitStep(5_000L, interactive = false, timeoutOwed = false, goodnightOwed = run.owesScreen))
        }
    }

    @Test
    fun `screen still on - timeout back at 10 min, a dim screen stays dim until 30 min, then back regardless`() {
        for (run in listOf(startedRun(BacklightMethod.WINDOW, true), startedRun(BacklightMethod.SYSTEM, false), startedRun(BacklightMethod.NONE, true))) {
            val g = run.owesScreen
            assertEquals(ScreenWaitStep.WAIT, EndActions.screenWaitStep(9 * min, true, timeoutOwed = true, goodnightOwed = g))
            assertEquals(ScreenWaitStep.RESTORE_TIMEOUT, EndActions.screenWaitStep(10 * min, true, timeoutOwed = true, goodnightOwed = g))
            assertEquals(ScreenWaitStep.WAIT, EndActions.screenWaitStep(29 * min, true, timeoutOwed = false, goodnightOwed = g))
            assertEquals(ScreenWaitStep.RESTORE_ALL, EndActions.screenWaitStep(30 * min, true, timeoutOwed = false, goodnightOwed = g))
        }
    }

    @Test
    fun `screen-off-sooner without Goodnight ends at 10 min, nothing owed ends at once`() {
        assertEquals(ScreenWaitStep.RESTORE_TIMEOUT, EndActions.screenWaitStep(10 * min, true, timeoutOwed = true, goodnightOwed = false))
        assertEquals(ScreenWaitStep.DONE, EndActions.screenWaitStep(10 * min, true, timeoutOwed = false, goodnightOwed = false))
        assertEquals(ScreenWaitStep.DONE, EndActions.screenWaitStep(0L, false, timeoutOwed = false, goodnightOwed = false))
    }
}
