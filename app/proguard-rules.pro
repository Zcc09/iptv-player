# Keep kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.zcc09.iptvplayer.core.** {
    *** Companion;
}
-keepclasseswithmembers class com.zcc09.iptvplayer.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}
