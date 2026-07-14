package com.simemu.spoof.core

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * Cross-process config bridge.
 *
 * WHY A PROVIDER (not XSharedPreferences): on Android 10+ every app's files carry
 * per-app SELinux categories (e.g. app_data_file:s0:c512,c768), so a hook running
 * inside a *different* app cannot read our SharedPreferences file — SELinux denies
 * it even when the DAC bits are world-readable. Binder calls to an exported
 * ContentProvider, however, are always permitted (untrusted_app → provider). So the
 * hook fetches config by calling this provider instead of reading a file.
 *
 * The provider runs in the module's own process, reads its own prefs (same uid, no
 * SELinux issue), and returns the config over binder.
 *
 * SECURITY: exported with no permission, so any app can read the (non-sensitive)
 * fake-SIM config. That's acceptable for a testing tool; do not store secrets here.
 */
class ConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    /** The hook calls resolver.call(uri, METHOD_GET, null, null). */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_GET) return null
        val ctx = context ?: return null
        val store = ConfigStore.get(ctx)
        return Bundle().apply {
            putBoolean(KEY_MASTER, store.masterEnabled)
            putString(KEY_JSON, store.load().toJson())
        }
    }

    // Unused CRUD surface.
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.simemu.spoof.config"
        const val METHOD_GET = "get"
        const val KEY_MASTER = "master"
        const val KEY_JSON = "json"
        val URI: Uri = Uri.parse("content://$AUTHORITY/config")
    }
}
