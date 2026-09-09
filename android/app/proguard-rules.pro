# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class dev.nphil.blestudio.**$$serializer { *; }
-keepclassmembers class dev.nphil.blestudio.** { *** Companion; }
-keepclasseswithmembers class dev.nphil.blestudio.** { kotlinx.serialization.KSerializer serializer(...); }
# Shizuku AIDL
-keep class dev.nphil.blestudio.shell.** { *; }
-keep class rikka.shizuku.** { *; }
