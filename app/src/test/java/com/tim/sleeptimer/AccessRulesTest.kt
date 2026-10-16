package com.tim.sleeptimer

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/** v2 P4: reading Android's accessibility setting, and which media sessions rung 0 pauses. */
class AccessRulesTest {
    private val pkg = "com.tim.sleeptimer"
    private val cls = "com.tim.sleeptimer.SleepAccessibilityService"

    private fun enabled(raw: String?, on: Boolean = true) = AccessRules.isAccessibilityServiceEnabled(raw, on, pkg, cls)

    @Test
    fun `full and short component forms are recognised`() {
        assertTrue(enabled("com.tim.sleeptimer/com.tim.sleeptimer.SleepAccessibilityService"))
        assertTrue(enabled("com.tim.sleeptimer/.SleepAccessibilityService"))
        assertTrue(enabled("com.other/.Svc:com.tim.sleeptimer/.SleepAccessibilityService"))
    }

    @Test
    fun `absent, other apps, empty, or accessibility globally off are not enabled`() {
        assertFalse(enabled(null))
        assertFalse(enabled(""))
        assertFalse(enabled("com.tim.dimmer/.DimmerAccessibilityService"))
        assertFalse(enabled("com.tim.sleeptimer/.OtherService"))
        assertFalse(enabled("garbage"))
        assertFalse(enabled("com.tim.sleeptimer/.SleepAccessibilityService", on = false))
    }

    @Test
    fun `rung 0 pauses every playing session that is not ours`() {
        val sessions = listOf(
            "com.spotify.music" to 3, // PLAYING
            "com.google.android.youtube" to 2, // PAUSED
            "com.podcast" to 6, // BUFFERING
            pkg to 3, // our own (never)
            "com.nostate" to null,
        )
        assertEquals(listOf(0, 2), SessionPlan.toPause(sessions, pkg))
        assertEquals(emptyList(), SessionPlan.toPause(emptyList(), pkg))
    }

    @Test
    fun `try it now names the paused apps`() {
        assertEquals("Spotify", SessionPlan.sessionResultLabel(listOf("Spotify")))
        assertEquals("Spotify + YouTube", SessionPlan.sessionResultLabel(listOf("Spotify", "YouTube", "Spotify")))
        assertEquals("the player", SessionPlan.sessionResultLabel(emptyList()))
    }
}
