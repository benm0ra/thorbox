# Keep JNI entry points when an app that depends on :engine is minified.
-keep class com.thorbox.engine.NativeEngine { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
