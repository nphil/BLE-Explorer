# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class dev.nphil.blueshark.**$$serializer { *; }
-keepclassmembers class dev.nphil.blueshark.** { *** Companion; }
-keepclasseswithmembers class dev.nphil.blueshark.** { kotlinx.serialization.KSerializer serializer(...); }
# Shizuku AIDL
-keep class dev.nphil.blueshark.shell.** { *; }
-keep class rikka.shizuku.** { *; }
