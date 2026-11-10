# ScreenDock R8 rules.
# Native code (android_raop_callbacks.c) resolves callback methods BY NAME via
# GetMethodID, and the JNI exports are bound by mangled name — R8 cannot see
# either, so everything the native side touches must be kept verbatim.

# JNI entry class: its `native` methods are bound by name.
-keep class com.cressu.screendock.bridge.NativeBridge { *; }
-keepclasseswithmembernames class * { native <methods>; }

# Callback interface + EVERY implementer (AirPlayService, test doubles, ...).
# GetMethodID runs on the implementer's runtime class, so its overrides must keep names.
-keep interface com.cressu.screendock.bridge.RaopCallbackHandler { *; }
# Only the callback methods JNI looks up by name must survive; the rest of each
# implementer (e.g. AirPlayService) stays optimizable by R8.
-keepclassmembers class * implements com.cressu.screendock.bridge.RaopCallbackHandler {
    public void on*(...);
    public boolean onClientRequest(...);
    public float[] getHlsPlaybackInfo();
}

# Play Billing / Play Review ship their own consumer rules.

# Play review-ktx references a compile-only annotation
-dontwarn com.google.android.gms.common.annotation.NoNullnessRewrite

# Media3/ExoPlayer on API 24-30: classes such as android.media.metrics.LogSessionId do not exist
# there. R8 can merge/inline references to them into code that loads on older devices ->
# NoClassDefFoundError at runtime (crash when AirPlay video starts). Keep Media3's class
# structure intact and ignore the missing API 31+ platform classes.
# (Adapted from jqssun/android-airplay-server PR #95, GPL-3.0.)
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
-dontwarn android.media.metrics.**
-keep class android.media.metrics.** { *; }
