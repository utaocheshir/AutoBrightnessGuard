package com.banoku.oplus.autobrightness

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Handles:
 * 1. Opening the companion UI via secret dialer code (*#*#224#*#* -> ABG) even when the launcher icon is hidden.
 * 2. Migrating pre-v1.5.1 users who hid MainActivity directly so MainActivity stays enabled (accessible
 *    from Vector / LSPosed MODULE_SETTINGS & dialer code) while hiding MainActivityLauncher instead.
 */
class AppEntryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        when (intent.action) {
            ACTION_TELEPHONY_SECRET_CODE, ACTION_ANDROID_SECRET_CODE -> {
                ensureMainActivityEnabled(context)
                val launch = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                try {
                    context.startActivity(launch)
                } catch (_: Throwable) {
                }
            }

            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                migrateLegacyHiddenMainActivity(context)
            }
        }
    }

    companion object {
        const val SECRET_CODE_NUMBER = "224"
        private const val ACTION_TELEPHONY_SECRET_CODE = "android.provider.Telephony.SECRET_CODE"
        private const val ACTION_ANDROID_SECRET_CODE = "android.telephony.action.SECRET_CODE"

        fun launcherAliasComponent(context: Context): ComponentName =
            ComponentName(context.packageName, "${context.packageName}.MainActivityLauncher")

        fun ensureMainActivityEnabled(context: Context) {
            try {
                val pm = context.packageManager
                val mainComp = ComponentName(context, MainActivity::class.java)
                val mainState = pm.getComponentEnabledSetting(mainComp)
                if (mainState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                    mainState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                ) {
                    pm.setComponentEnabledSetting(
                        mainComp,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                    )
                }
            } catch (_: Throwable) {
            }
        }

        fun migrateLegacyHiddenMainActivity(context: Context) {
            try {
                val pm = context.packageManager
                val mainComp = ComponentName(context, MainActivity::class.java)
                val mainState = pm.getComponentEnabledSetting(mainComp)
                if (mainState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                    mainState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                ) {
                    pm.setComponentEnabledSetting(
                        launcherAliasComponent(context),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                    pm.setComponentEnabledSetting(
                        mainComp,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                    )
                }
            } catch (_: Throwable) {
            }
        }
    }
}
