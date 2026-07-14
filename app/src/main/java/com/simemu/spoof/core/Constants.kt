package com.simemu.spoof.core

/**
 * Shared identifiers used by BOTH sides of the module:
 *  - the settings app process (writer), and
 *  - the hooked target-app process (reader, via XSharedPreferences).
 *
 * They must be byte-for-byte identical, so they live in one place.
 */
object Constants {
    const val MODULE_PKG = "com.simemu.spoof"

    /** SharedPreferences file name. Read from the hook via XSharedPreferences. */
    const val PREFS_NAME = "sim_emulator_prefs"

    /** Master on/off switch. */
    const val KEY_MASTER_ENABLED = "master_enabled"

    /** JSON blob: the full ProfileConfig (profiles + per-app scope + defaults). */
    const val KEY_CONFIG_JSON = "config_json"

    /** Settings.Global keys — the visibility-proof cross-process channel. */
    const val SETTINGS_CONFIG = "sim_emulator_config"
    const val SETTINGS_ENABLED = "sim_emulator_enabled"

    const val LOG_TAG = "SimEmulator"
}
