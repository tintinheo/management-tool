# My Audio Hub release rules.
# App classes are kept whole: Room DAOs/entities, WorkManager workers, Glance receivers and the
# Media3 service are instantiated by name. Libraries ship their own consumer rules and get shrunk.
-keep class com.audiohub.** { *; }

# Optional dependencies referenced by OkHttp / Coil that aren't on the classpath.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.internal.platform.**
