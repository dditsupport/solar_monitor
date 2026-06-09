# Keep Kotlinx Serialization metadata.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keep,includedescriptorclasses class com.dangeedums.solar.**$$serializer { *; }
-keepclassmembers class com.dangeedums.solar.** {
    *** Companion;
}
-keepclasseswithmembers class com.dangeedums.solar.** {
    kotlinx.serialization.KSerializer serializer(...);
}
