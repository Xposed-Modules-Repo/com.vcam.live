-keep class com.hazbu.xcam.XCamModule { *; }
-keep class com.hazbu.xcam.SettingsProvider { *; }
-keep class de.robv.android.xposed.** { *; }
-keep interface de.robv.android.xposed.** { *; }
-keep class com.hazbu.xcam.** { *; }
-keepclassmembers class * {
    @de.robv.android.xposed.callbacks.XC_LoadPackage$LoadPackageParam *;
}
-dontwarn de.robv.android.xposed.**
