package com.plainapps.nightcap

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

/**
 * Thin wrapper around AudioManager: fade steps (index only), media-key pause/play, audio-focus
 * gain/abandon, mute fallback and isMusicActive/isVolumeFixed checks. Every call is wrapped
 * against SecurityException (DND policy can reject volume changes) per DECISIONS D5.
 */
class MediaController(context: Context) {
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    fun isMusicActive(): Boolean = safe(false) { audioManager.isMusicActive }

    /**
     * Polls [isMusicActive] until quiet or [timeoutMs] passed; returns whether music is still active.
     * The audio output stops a moment after a session reports PAUSED (P4 emulator finding), so rung 0
     * is judged on this, not on one immediate sample.
     */
    suspend fun isMusicActiveAfter(timeoutMs: Long): Boolean {
        var waited = 0L
        while (isMusicActive()) {
            if (waited >= timeoutMs) return true
            kotlinx.coroutines.delay(100)
            waited += 100
        }
        return false
    }

    fun isVolumeFixed(): Boolean = safe(true) { audioManager.isVolumeFixed }

    fun currentStreamIndex(): Int =
        safe(TimerPlan.MIN_STREAM_INDEX) { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) }

    fun maxStreamIndex(): Int = safe(0) { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    fun setStreamIndex(index: Int) {
        safe(Unit) { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0) }
    }

    private fun sendMediaKey(keyCode: Int) {
        safe(Unit) {
            val eventTime = SystemClock.uptimeMillis()
            audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0))
            audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0))
        }
    }

    fun sendPause() = sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
    fun sendPlay() = sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)

    /** Requests and holds AUDIOFOCUS_GAIN so a well-behaved player pauses itself. */
    fun requestFocusGain(): Boolean = safe(false) {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .build()
        focusRequest = request
        audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun abandonFocus() {
        safe(Unit) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        }
    }

    private inline fun <T> safe(default: T, block: () -> T): T = try {
        block()
    } catch (e: SecurityException) {
        Log.w(TAG, "SecurityException: ${e.message}")
        default
    }

    companion object {
        private const val TAG = "MediaController"
    }
}
