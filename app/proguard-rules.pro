# FlightTrackAR ProGuard / R8 Rules for Google Play Release

# Retain clean stack traces for Play Console crash deobfuscation (mapping.txt is auto-bundled in AAB)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep data models & JSON DTOs
-keep class com.flighttrackar.model.** { *; }
-keep class com.flighttrackar.data.** { *; }

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.flighttrackar.**$$serializer { *; }
-keepclassmembers class com.flighttrackar.** {
    *** Companion;
}
-keepclasseswithmembers class com.flighttrackar.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp / Okio optional platform annotations
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
