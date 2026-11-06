# JSch loads cipher/kex classes reflectively by name.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# Room
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.neomaker.neoserver.** {
    *** Companion;
}
-keepclasseswithmembers class com.neomaker.neoserver.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Compose
-dontwarn androidx.compose.**
