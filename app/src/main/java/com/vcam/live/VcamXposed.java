package com.vcam.live;

import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

// 模块入口
public final class VcamXposed extends XposedModule {

    private static final String TAG = "vcam::entry";

    public VcamXposed() {
        super();
    }

    // 模块载入回调
    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        if (param.isSystemServer()) {
            detach();
            return;
        }
        Log.i(TAG, "module loaded in process: " + param.getProcessName());
    }

    // 目标包准备完毕回调
    @Override
    public void onPackageReady(PackageReadyParam param) {
        String pkg = param.getPackageName();
        Log.i(TAG, "hooking package: " + pkg);
        CameraSurfaceHijack.install(this, param);
    }
}
