# kotlinx.serialization
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keep,includedescriptorclasses class com.alara.hermes.**$$serializer { *; }
-keepclassmembers class com.alara.hermes.** {
    *** Companion;
}
-keepclasseswithmembers class com.alara.hermes.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
