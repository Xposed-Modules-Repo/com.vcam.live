-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list

-keep public class com.hazbu.xcam.XCamModule {
    public <init>(io.github.libxposed.api.XposedInterface, io.github.libxposed.api.XposedModuleInterface$ModuleLoadedParam);
}

-keep class com.hazbu.xcam.MainActivity {
    boolean checkSelfActive();
    java.util.List getOfficialScope();
}

-keep class com.hazbu.xcam.SettingsProvider { *; }

-keep interface io.github.libxposed.api.** { *; }
-keep interface io.github.libxposed.service.** { *; }

-keep class com.hazbu.xcam.** { *; }

-keepattributes *Annotation*,Signature,EnclosingMethod,InnerClasses
