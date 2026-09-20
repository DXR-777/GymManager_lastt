# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# WorkManager: workers are instantiated by class name via reflection
# (androidx.work.WorkerFactory / Class.forName) using the name recorded
# when the WorkRequest was enqueued (e.g. ExpiryCheckWorker, scheduled
# from GymApplication). Without this rule R8 is free to rename or strip
# the class, since nothing in the static call graph calls its
# constructor directly — the app would still build and install fine,
# but WorkManager would silently fail with ClassNotFoundException the
# next time the periodic expiry-check job runs, breaking notifications.
-keep public class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# MPAndroidChart
-keep class com.github.mikephil.charting.** { *; }
-dontwarn com.github.mikephil.charting.**

# Kotlin coroutines
-dontwarn kotlinx.coroutines.**

# Keep annotations (Room and other libraries rely on some of these being
# readable) and keep enough line-number info that release-build crash
# stack traces still point at real source lines instead of "Unknown
# Source" once class/method names are obfuscated.
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
