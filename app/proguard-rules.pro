# SPDX-License-Identifier: GPL-3.0-or-later
# R8 rules for release builds.

# JNI: native method names are bound by class + method name, so neither may be renamed.
-keep class com.thorbox.engine.NativeEngine { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# Strip verbose/debug logging from release builds (defence in depth: we already gate on BuildConfig.DEBUG).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
