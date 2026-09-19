# M0: minimal. Native surface is tiny; keep default Android optimizations.
# Expanded in later milestones as the JNI surface + foreground service grow.

# Keep the JNI entry class and its native method names.
-keep class com.cressu.screendock.bridge.NativeBridge { *; }
-keep class com.cressu.screendock.bridge.RaopCallbackHandler { *; }
