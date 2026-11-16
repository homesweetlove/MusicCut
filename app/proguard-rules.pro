# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keep,includedescriptorclasses class com.musiccut.core.**$$serializer { *; }
-keepclassmembers class com.musiccut.core.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# jump3r: only the encoder core is used; its javax.sound wrapper classes are never loaded
-dontwarn javax.sound.**
-keep class de.sciss.jump3r.** { *; }
# ONNX Runtime JNI
-keep class ai.onnxruntime.** { *; }
