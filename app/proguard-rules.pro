# Keep kotlinx.serialization metadata
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.joe.novelreader.**$$serializer { *; }
-keepclassmembers class com.joe.novelreader.** {
    *** Companion;
}
-keepclasseswithmembers class com.joe.novelreader.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp / jsoup
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.jspecify.annotations.**
-dontwarn com.google.re2j.**
-keep class org.jsoup.** { *; }
