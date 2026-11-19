# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.mangatranslator.**$$serializer { *; }
-keepclassmembers class com.mangatranslator.** { *** Companion; }
-keepclasseswithmembers class com.mangatranslator.** { kotlinx.serialization.KSerializer serializer(...); }
# Credential Manager / Google ID
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }
