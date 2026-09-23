# R8/ProGuard rules for Nova Player.
#
# The JNI layer calls back into MpvNative.onEvent(String,String) by name via
# GetStaticMethodID. If R8 renames or strips it the engine goes silent with no
# crash — exactly the class of bug the desktop hit with its IPC watchdog.

-keep class com.sadik.novaplayer.core.MpvNative { *; }
-keep interface com.sadik.novaplayer.core.MpvObserver { *; }

# Native methods must keep their names (JNI symbol resolution).
-keepclasseswithmembernames class * {
    native <methods>;
}

# libmpv logs through stderr; keep line numbers for readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
