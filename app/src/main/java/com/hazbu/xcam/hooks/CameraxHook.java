package com.hazbu.xcam.hooks;

import com.hazbu.xcam.XCamModule;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * Hooks for the CameraX API (androidx.camera)
 * CameraX sits on top of Camera2, so our Camera2 ImageReader hooks should
 * handle the data injection.
 * We keep this class primarily for logging.
 * Migrated to libxposed API 101.
 */
public class CameraxHook {
    private final XCamModule module;

    public CameraxHook(XCamModule module) {
        this.module = module;
    }

    public void install(XposedModuleInterface.PackageReadyParam param) {
        try {
            module.printLog("Initializing CameraX API hooks (Logging only)", null);
            hookImageCapture(param);
        } catch (Throwable t) {
            module.printLog("Failed to initialize CameraX API hooks: " + t.getMessage(), null);
        }
    }

    private void hookImageCapture(XposedModuleInterface.PackageReadyParam param) {
        try {
            Class<?> imageCaptureClass;
            try {
                imageCaptureClass = param.getClassLoader().loadClass("androidx.camera.core.ImageCapture");
            } catch (ClassNotFoundException e) {
                return;
            }

            for (Method m : imageCaptureClass.getDeclaredMethods()) {
                if (m.getName().equals("takePicture")) {
                    module.hook(m).intercept(chain -> {
                        if (module.getMediaPath() != null) {
                            module.printLog("CameraX takePicture detected - relying on Camera2 hooks", null);
                        }
                        return chain.proceed();
                    });
                }
            }
        } catch (Throwable t) {
            module.printLog("Failed to hook CameraX ImageCapture: " + t.getMessage(), null);
        }
    }
}
