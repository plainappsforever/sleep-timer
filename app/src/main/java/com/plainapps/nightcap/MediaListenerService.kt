package com.plainapps.nightcap

import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * Enhanced compatibility (SPEC F17, DECISIONS D17). Deliberately empty: Android only lets an app see
 * other apps' media sessions (`MediaSessionManager.getActiveSessions`) when one of its components is
 * an enabled notification listener. This class is that component. It never reads, stores or reacts to
 * notifications; the base class callbacks are not overridden.
 */
class MediaListenerService : NotificationListenerService() {
    /**
     * v2.1 (REVIEW_V2 row 10): `getActiveSessions` needs this listener *enabled*, not connected. So it asks Android
     * to unbind at once: it is not kept running all day and is handed no notifications. The access stays granted.
     */
    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i("MediaListenerService", "connected -> requestUnbind")
        requestUnbind()
    }
}
