package com.plainapps.nightcap

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * Special accesses a feature may need (V2_PLAN "Special access", DECISIONS D15). Each is asked for
 * only when its switch is turned on, after one explanation screen, and re-checked whenever the app
 * comes back to the foreground. P5 adds DND_POLICY; P6 reuses ACCESSIBILITY and WRITE_SETTINGS.
 */
enum class AccessKind { ACCESSIBILITY, NOTIFICATION_LISTENER, WRITE_SETTINGS, DND_POLICY }

/**
 * P6 Goodnight switches that may need an access (D42): the main switch needs accessibility OR Modify system settings
 * (it asks for the latter when neither is allowed); warm tint, blackout and "dark layer only" need accessibility.
 */
enum class GoodnightFeature { ENABLED, WARMTH, BLACKOUT, OVERLAY_ONLY }

/** Grant state of every kind, re-read whenever the app comes to the foreground. */
data class AccessState(
    val accessibility: Boolean = false,
    val notificationListener: Boolean = false,
    val writeSettings: Boolean = false,
    val dndPolicy: Boolean = false,
) {
    fun granted(kind: AccessKind): Boolean = when (kind) {
        AccessKind.ACCESSIBILITY -> accessibility
        AccessKind.NOTIFICATION_LISTENER -> notificationListener
        AccessKind.WRITE_SETTINGS -> writeSettings
        AccessKind.DND_POLICY -> dndPolicy
    }
}

object SpecialAccess {

    fun snapshot(context: Context): AccessState = AccessState(
        accessibility = isGranted(context, AccessKind.ACCESSIBILITY),
        notificationListener = isGranted(context, AccessKind.NOTIFICATION_LISTENER),
        writeSettings = isGranted(context, AccessKind.WRITE_SETTINGS),
        dndPolicy = isGranted(context, AccessKind.DND_POLICY),
    )

    fun isGranted(context: Context, kind: AccessKind): Boolean = when (kind) {
        AccessKind.ACCESSIBILITY -> isAccessibilityEnabled(context)
        AccessKind.NOTIFICATION_LISTENER ->
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        AccessKind.WRITE_SETTINGS -> Settings.System.canWrite(context)
        AccessKind.DND_POLICY -> DndController(context).isAccessGranted()
    }

    /** The Android Settings page where the user grants [kind]. */
    fun settingsIntent(context: Context, kind: AccessKind): Intent = when (kind) {
        AccessKind.ACCESSIBILITY -> {
            val component = ComponentName(context, SleepAccessibilityService::class.java).flattenToString()
            // The fragment-args extras make some Settings versions highlight our entry; harmless elsewhere.
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .putExtra(":settings:fragment_args_key", component)
                .putExtra(":settings:show_fragment_args", android.os.Bundle().apply {
                    putString(":settings:fragment_args_key", component)
                })
        }
        AccessKind.NOTIFICATION_LISTENER -> if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(context, MediaListenerService::class.java).flattenToString(),
            )
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
        AccessKind.WRITE_SETTINGS ->
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.fromParts("package", context.packageName, null))
        // No public per-app page: the list of apps with Do Not Disturb access.
        AccessKind.DND_POLICY -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
    }

    /** Opens the grant page, falling back to the general list if the detail page does not exist. */
    fun openSettings(context: Context, kind: AccessKind) {
        val intent = settingsIntent(context, kind).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            val fallback = when (kind) {
                AccessKind.NOTIFICATION_LISTENER -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                AccessKind.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                AccessKind.WRITE_SETTINGS, AccessKind.DND_POLICY -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", context.packageName, null))
            }
            context.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /**
     * The user's switch in Accessibility settings, read from the secure setting itself. Not
     * AccessibilityManager's enabled list: that one only holds bound services and flickers while the
     * service is (re)bound (after returning from Settings, during a `uiautomator` rebind — L14).
     */
    private fun isAccessibilityEnabled(context: Context): Boolean {
        val resolver = context.contentResolver
        val raw = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val enabled = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1) != 0
        return AccessRules.isAccessibilityServiceEnabled(
            raw,
            enabled,
            context.packageName,
            SleepAccessibilityService::class.java.name,
        )
    }
}
