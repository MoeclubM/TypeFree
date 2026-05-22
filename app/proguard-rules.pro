# TypeFree ProGuard Rules

# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.typefree.ime.data.**$$serializer { *; }
-keepclassmembers class com.typefree.ime.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.typefree.ime.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Keep AndroidX Security Crypto
-keep class androidx.security.crypto.** { *; }

# Keep Compose
-keep class androidx.compose.** { *; }

# Keep InputMethodService entry
-keep class com.typefree.ime.TypeFreeIME { *; }

# Ignore missing ErrorProne annotations referenced by Tink
-dontwarn com.google.errorprone.annotations.**
