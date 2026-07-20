package com.simemu.spoof.hooks

import android.app.Application
import android.content.Context
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.simemu.spoof.core.ConfigProvider
import com.simemu.spoof.core.Constants
import com.simemu.spoof.core.ProfileConfig
import com.simemu.spoof.utils.Reflect
import com.simemu.spoof.utils.XLog
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Xposed/LSPosed entry point (declared in assets/xposed_init).
 *
 * Flow per target process:
 *   1. Skip our own settings app.
 *   2. Wait for Application.attach(Context) so we have a usable Context. We need it
 *      to query [ConfigProvider] over binder — the SELinux-safe way to read config
 *      (XSharedPreferences can't read our file cross-app on Android 10+).
 *   3. Read master switch + per-app scope from the provider; bail if off/out-of-scope.
 *   4. Install the TelephonyManager + SubscriptionManager (+ Binder) hooks.
 *
 * Application.attach runs before the app's own code and before any telephony call,
 * so installing hooks there is early enough.
 */
class HookEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        if (pkg == Constants.MODULE_PKG) return

        runCatching { HiddenApiBypass.addHiddenApiExemptions("L") }

        // Hook Application.attach(Context) to obtain a Context, then configure.
        Reflect.hookAllByName(Application::class.java, "attach", object : XC_MethodHook() {
            private var done = false
            override fun afterHookedMethod(param: MethodHookParam) {
                if (done) return
                val ctx = param.args.firstOrNull { it is Context } as? Context ?: return
                done = true
                configureAndInstall(ctx, pkg, lpparam.classLoader)
            }
        })
    }

    private fun configureAndInstall(ctx: Context, pkg: String, cl: ClassLoader) {
        val (master, config) = loadConfig(ctx) ?: return
        if (!master) return
        if (!config.isAppEnabled(pkg)) return
        if (config.activeProfiles().isEmpty()) return

        XLog.d("Activating SIM emulation for $pkg (${config.activeProfiles().size} active SIM(s))")
        try {
            TelephonyManagerHook.install(TelephonyManager::class.java, config)
            SubscriptionManagerHook.install(SubscriptionManager::class.java, config)
            BinderHook.install(cl, config)
            if (config.diagnostics) {
                DiagnosticHook.install(
                    TelephonyManager::class.java, SubscriptionManager::class.java, cl)
            }
        } catch (t: Throwable) {
            XLog.e("Hook install failed for $pkg", t)
        }
    }

    /**
     * Load (masterEnabled, config). Primary channel is Settings.Global (the
     * settings provider is always visible, even to targetSdk 36+ apps that can't
     * resolve our ContentProvider). Falls back to the ContentProvider.
     */
    private fun loadConfig(ctx: Context): Pair<Boolean, ProfileConfig>? {
        readFromSettings(ctx)?.let { return it }
        return readFromProvider(ctx)
    }

    private fun readFromSettings(ctx: Context): Pair<Boolean, ProfileConfig>? = try {
        val json = android.provider.Settings.Global.getString(
            ctx.contentResolver, Constants.SETTINGS_CONFIG)
        if (json.isNullOrBlank()) null
        else {
            val master = android.provider.Settings.Global.getString(
                ctx.contentResolver, Constants.SETTINGS_ENABLED) == "1"
            XLog.d("config via Settings.Global")
            master to ProfileConfig.fromJson(json)
        }
    } catch (t: Throwable) {
        XLog.e("readFromSettings failed", t); null
    }

    private fun readFromProvider(ctx: Context): Pair<Boolean, ProfileConfig>? = try {
        val bundle = ctx.contentResolver.call(
            ConfigProvider.URI, ConfigProvider.METHOD_GET, null, null)
        if (bundle == null) null
        else {
            val master = bundle.getBoolean(ConfigProvider.KEY_MASTER, false)
            XLog.d("config via ContentProvider")
            master to ProfileConfig.fromJson(bundle.getString(ConfigProvider.KEY_JSON))
        }
    } catch (t: Throwable) {
        XLog.e("readFromProvider failed (targetSdk visibility?)", t); null
    }
}
