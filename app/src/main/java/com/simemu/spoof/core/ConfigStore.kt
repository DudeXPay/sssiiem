package com.simemu.spoof.core

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings

/**
 * App-side (settings UI) reader/writer of the configuration.
 *
 * CROSS-PROCESS DELIVERY (two channels, written together):
 *  1. Settings.Global — the PRIMARY channel. The settings provider is a framework
 *     provider always visible to every app regardless of package visibility or
 *     target SDK, so hooks in targetSdk 36+ apps can always read it. Writing needs
 *     WRITE_SECURE_SETTINGS; on a rooted device we fall back to `su settings put`.
 *  2. [ConfigProvider] (backed by these SharedPreferences) — FALLBACK for apps
 *     where Settings is unavailable. Works up to ~targetSdk 35.
 *
 * (XSharedPreferences was tried first but can't read cross-app on Android 10+ due
 * to per-app SELinux categories — see git history.)
 */
class ConfigStore private constructor(
    private val appContext: Context,
    private val prefs: SharedPreferences
) {

    var masterEnabled: Boolean
        get() = prefs.getBoolean(Constants.KEY_MASTER_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(Constants.KEY_MASTER_ENABLED, value).apply()
            writeSettings(Constants.SETTINGS_ENABLED, if (value) "1" else "0")
        }

    fun load(): ProfileConfig =
        ProfileConfig.fromJson(prefs.getString(Constants.KEY_CONFIG_JSON, null))

    fun save(config: ProfileConfig) {
        val json = config.toJson()
        prefs.edit().putString(Constants.KEY_CONFIG_JSON, json).commit()
        // Mirror to Settings.Global so targetSdk 36+ hooks can read it.
        writeSettings(Constants.SETTINGS_CONFIG, json)
        writeSettings(Constants.SETTINGS_ENABLED, if (masterEnabled) "1" else "0")
    }

    /**
     * Write a Settings.Global key. Tries the framework API (needs
     * WRITE_SECURE_SETTINGS, grantable via adb on a test device); if that's denied,
     * falls back to `su settings put global` since this is a rooted tool.
     */
    private fun writeSettings(key: String, value: String) {
        try {
            Settings.Global.putString(appContext.contentResolver, key, value)
            return
        } catch (_: Throwable) { /* fall through to root */ }
        try {
            // Base64 to avoid shell-quoting issues with JSON.
            val b64 = android.util.Base64.encodeToString(
                value.toByteArray(), android.util.Base64.NO_WRAP)
            val cmd = "echo $b64 | base64 -d | xargs -0 -I{} settings put global $key {}"
            Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor()
        } catch (_: Throwable) { /* best effort */ }
    }

    companion object {
        @Suppress("WorldReadableFiles", "DEPRECATION")
        fun get(context: Context): ConfigStore {
            val prefs = try {
                context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_WORLD_READABLE)
            } catch (_: SecurityException) {
                context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            }
            return ConfigStore(context.applicationContext, prefs)
        }
    }
}
