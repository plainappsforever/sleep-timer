package com.tim.sleeptimer

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * v2.1 (REVIEW_V2 row 3b): every start of the app's process — app open, the Quick Settings tile being bound when
 * the shade opens, the widget, any alarm — first settles our Do Not Disturb: past its until-time it ends, otherwise
 * alarm 103 is armed again. A force-stop cancels alarm 103, so this is the "next start" path after one. DND only:
 * no volume call outside the service or a visible activity (Android 17 hardening, D34).
 */
class SleepTimerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                RestoreManager.applyOwedDnd(this@SleepTimerApp, "process start")
            } catch (e: Exception) {
                android.util.Log.w("SleepTimerApp", "DND check failed: ${e.message}")
            }
        }
    }
}
