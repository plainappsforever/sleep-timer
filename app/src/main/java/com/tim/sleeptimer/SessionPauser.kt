package com.tim.sleeptimer

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.session.MediaController as SessionController
import android.media.session.MediaSessionManager
import android.util.Log
import kotlinx.coroutines.delay

/**
 * Rung 0 of the pause ladder (SPEC F17, DECISIONS D17): with "Enhanced compatibility" on and
 * Notification access granted to [MediaListenerService], pause every playing media session directly.
 * Reads only each session's package and playback state — never titles, artwork or notifications.
 * Every call is guarded: a revoked grant throws SecurityException, which just means "rung 0 skipped".
 */
class SessionPauser(context: Context) {
    private val app = context.applicationContext
    private val listener = ComponentName(app, MediaListenerService::class.java)

    /** Sessions paused by the last [pausePlaying], so "Try it now" can resume exactly those. */
    data class Result(val tried: Boolean, val paused: List<SessionController>, val appLabels: List<String>)

    /** Pauses every playing session that is not ours, then waits up to [waitMillis] for them to leave "playing". */
    suspend fun pausePlaying(waitMillis: Long = POLL_WINDOW_MS): Result {
        if (!SpecialAccess.isGranted(app, AccessKind.NOTIFICATION_LISTENER)) {
            Log.i(TAG, "rung 0 skipped: notification access not granted")
            return Result(false, emptyList(), emptyList())
        }
        val sessions = try {
            val msm = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            msm.getActiveSessions(listener)
        } catch (e: SecurityException) {
            Log.w(TAG, "rung 0 skipped: ${e.message}")
            return Result(false, emptyList(), emptyList())
        }
        val described = sessions.map { it.packageName to it.playbackState?.state }
        Log.i(TAG, "sessions: " + described.joinToString { "${it.first} [${label(it.first)}]=${it.second}" })
        val targets = SessionPlan.toPause(described, app.packageName).map { sessions[it] }
        if (targets.isEmpty()) {
            Log.i(TAG, "rung 0: no playing session")
            return Result(true, emptyList(), emptyList())
        }
        for (c in targets) {
            try {
                c.transportControls.pause()
                Log.i(TAG, "rung 0: pause sent to ${c.packageName}")
            } catch (e: RuntimeException) {
                Log.w(TAG, "pause failed for ${c.packageName}: ${e.message}")
            }
        }
        var waited = 0L
        while (waited < waitMillis && targets.any { SessionPlan.isPlaying(it.playbackState?.state) }) {
            delay(STEP_MS)
            waited += STEP_MS
        }
        Log.i(TAG, "rung 0 after ${waited}ms: " + targets.joinToString { "${it.packageName}=${it.playbackState?.state}" })
        return Result(true, targets, targets.map { label(it.packageName) })
    }

    /** "Try it now" puts back what it paused. */
    fun resume(paused: List<SessionController>) {
        for (c in paused) {
            try {
                c.transportControls.play()
            } catch (e: RuntimeException) {
                Log.w(TAG, "resume failed for ${c.packageName}: ${e.message}")
            }
        }
    }

    private fun label(pkg: String): String = try {
        val pm = app.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    companion object {
        private const val TAG = "SessionPauser"
        private const val POLL_WINDOW_MS = 1_500L
        private const val STEP_MS = 100L
    }
}
