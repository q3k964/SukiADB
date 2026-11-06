package frb.axeron.manager.adb

import android.content.Context
import android.os.Build

/**
 * The single owner of the keep-alive preference.
 *
 * Kept out of [KeepAliveService] so callers (the Settings switch, the activity) never start or
 * stop the service directly and the stored preference can't drift away from what is running.
 * On by default: the whole point is that a drop should heal itself without the user noticing.
 */
object KeepAlive {
    private const val PREFS = "settings"
    private const val KEY_ENABLED = "keep_alive_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        sync(context)
    }

    /** Makes the running service match the stored preference. Cheap and idempotent. */
    fun sync(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (isEnabled(context)) KeepAliveService.start(context) else KeepAliveService.stop(context)
    }
}
