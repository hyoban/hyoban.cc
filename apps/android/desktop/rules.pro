# ProGuard for the Mac's release build (`packageReleaseDmg`): it only takes away what nothing
# reaches, neither renaming nor rewriting, so stack traces read as they are and what is found by
# name at run time still is. What is reached by name, by JNI or by ServiceLoader is kept whole.

-dontobfuscate
-dontoptimize
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions,SourceFile,LineNumberTable

# The app itself: its records are read and written by kotlinx.serialization and Gson, its words by
# Compose resources' generated accessors.
-keep class cc.hyoban.moments.** { *; }

# kotlinx.serialization: each @Serializable's generated serializer, found from its companion.
-keep class kotlinx.serialization.** { *; }
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class **$$serializer { *; }

# Native code reached through JNI or a foreign function interface.
-keep class androidx.sqlite.** { *; }
-keep class org.maplibre.** { *; }
-keep class org.lwjgl.** { *; }

# Libraries that find their parts by name or by ServiceLoader.
-keep class coil3.** { *; }
-keep class com.google.gson.** { *; }
-keep class com.ibm.icu.** { *; }
-keep class com.drew.** { *; }
-keep class net.lingala.zip4j.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class dev.chrisbanes.haze.** { *; }
-keep class me.saket.telephoto.** { *; }
-keep class org.jetbrains.compose.resources.** { *; }
-keep class * implements java.util.ServiceLoader$Provider { *; }

# What these libraries name but the app never uses (Android, other TLS stacks, optional parts).
-dontwarn android.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.graalvm.**
-dontwarn com.oracle.svm.**
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlinx.serialization.**
-dontwarn org.slf4j.**
-dontwarn com.adobe.xmp.**
-dontnote **

# Left out of the Mac's packaging (desktop/build.gradle.kts): MapLibre's other backends and
# Compose's Material, which only paths the Mac never takes reach.
-dontwarn org.lwjgl.vulkan.**
-dontwarn org.lwjgl.opengl.**
-dontwarn org.lwjgl.opengles.**
-dontwarn org.lwjgl.egl.**
-dontwarn androidx.compose.material.**

# MethodHandle's invoke and invokeExact take any signature, which ProGuard reads as missing methods.
-dontwarn java.lang.invoke.MethodHandle
