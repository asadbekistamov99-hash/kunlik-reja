# ---- Jarvis Ultra R8 configuration ----
# Keep line numbers for readable crash reports; mapping.txt is attached to every release.
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# ONNX Runtime (openWakeWord) uses JNI and reflection into its Java API.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Picovoice Porcupine: JNI-bound classes.
-keep class ai.picovoice.** { *; }
-dontwarn ai.picovoice.**

# Vosk + JNA: native bindings resolved by name at runtime.
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# SQLCipher: JNI callbacks into Java classes.
-keep class net.zetetic.database.** { *; }
-dontwarn net.zetetic.database.**

# Room entities are accessed through generated code; keep their fields for backup serialization safety.
-keep class com.example.data.** { *; }

# Google Identity / Play services ship their own consumer rules; silence optional deps.
-dontwarn com.google.android.gms.**

# OkHttp optional platform integrations.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
