# vcam ProGuard rules.
#
# The libxposed module entry classes are referenced by fully-qualified name in
# META-INF/xposed/java_init.list, so they must keep their names across
# R8/minification.
-keep class com.vcam.live.VcamXposed { *; }
-keep class com.vcam.live.CameraSurfaceHijack { *; }
