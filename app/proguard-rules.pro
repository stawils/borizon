# Borizon ProGuard rules

# Keep Room entities
-keep class com.borizon.app.data.models.** { *; }

# Keep Kotlin serialization classes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.borizon.app.data.models.**$$serializer { *; }
-keepclassmembers class com.borizon.app.data.models.** {
    *** Companion;
}
-keepclasseswithmembers class com.borizon.app.data.models.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep LiteRT classes
-keep class com.google.ai.edge.litert.** { *; }
-keep class com.google.ai.edge.litertlm.** { *; }
-keep @interface com.google.ai.edge.litertlm.Tool { *; }
-keepclassmembers class * {
    @com.google.ai.edge.litertlm.Tool *;
}

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Remove verbose/debug logging in release, keep info/warn/error for diagnostics
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}

# SQLCipher — keep JNI entry points
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.** { *; }

# OkHttp — keep for reflection-based TLS and connection selection
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Hilt generated classes
-keep class dagger.hilt.** { *; }

# Markdown richtext renderer
-keep class com.halilibo.compose.** { *; }

# Protobuf — prevent R8 from stripping proto-generated fields accessed via reflection
-keep class com.borizon.app.proto.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessage { *; }
