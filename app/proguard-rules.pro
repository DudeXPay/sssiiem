# Keep the Xposed entry point — LSPosed loads it by name from assets/xposed_init.
-keep class com.simemu.spoof.hooks.HookEntry { *; }

# Keep all hook classes and their members: they are invoked reflectively and
# their method names must survive shrinking/obfuscation.
-keep class com.simemu.spoof.hooks.** { *; }

# The Xposed API is provided at runtime by the framework; do not warn about it.
-dontwarn de.robv.android.xposed.**
-keep class de.robv.android.xposed.** { *; }

# kotlinx.serialization generated serializers.
-keepclassmembers class com.simemu.spoof.core.** {
    *** Companion;
}
-keepclasseswithmembers class com.simemu.spoof.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}
