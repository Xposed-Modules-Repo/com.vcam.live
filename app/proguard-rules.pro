# R8/ProGuard rules

-dontwarn io.github.libxposed.**
-adaptresourcefilecontents META-INF/xposed/java_init.list

-keep class com.hazbu.xcam.** { *; }

-keep class io.github.libxposed.api.** { *; }
-keep interface io.github.libxposed.api.** { *; }
-keep class io.github.libxposed.service.** { *; }
-keep interface io.github.libxposed.service.** { *; }

-keep public class com.hazbu.xcam.XCamModule {
    public <init>(io.github.libxposed.api.XposedInterface, io.github.libxposed.api.XposedModuleInterface$ModuleLoadedParam);
}

-keepattributes *Annotation*,Signature,EnclosingMethod,InnerClasses,SourceFile,LineNumberTable

-dontoptimize
