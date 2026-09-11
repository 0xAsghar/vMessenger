# vMessenger release rules: shrink + optimise, but do NOT obfuscate — this is an
# open-source app and readable stack traces matter more than a few hundred KB.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*,InnerClasses,EnclosingMethod

# JNA + Lazysodium bind native symbols by (reflective) name.
-keep class com.sun.jna.** { *; }
-keep interface com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class com.goterl.lazysodium.** { *; }
-dontwarn java.awt.**

# protobuf-javalite resolves generated message classes and their fields reflectively.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
-dontwarn com.google.protobuf.**

# SQLCipher (JNI) and MapLibre (JNI + JSON style parsing).
-keep class net.zetetic.database.** { *; }
-dontwarn net.zetetic.**
-keep class org.maplibre.android.** { *; }
-dontwarn org.maplibre.**

# Room, Hilt, OkHttp/Okio, CameraX, ML Kit, DataStore and coroutines ship their
# own consumer rules. NetworkPathTracker matches JDK exception class names
# (java.security.cert.*) — platform classes are never renamed by R8.
