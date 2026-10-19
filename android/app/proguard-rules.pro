# JNI entry points of :engine are kept by its consumer-rules.pro; nothing else is loaded by name.
-keepattributes *Annotation*
-keep class javax.inject.** { *; }
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
