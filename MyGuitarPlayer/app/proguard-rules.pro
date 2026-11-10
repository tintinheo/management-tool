# Keep readable stack traces in the crash report (Công cụ shows the last crash after a restart).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Curriculum JSON models (kotlinx.serialization) and Room entities: small, and keeping them whole
# removes any doubt about generated serializers / field names after shrinking.
-keep class com.myguitarplayer.data.** { *; }
-keepclassmembers class com.myguitarplayer.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
