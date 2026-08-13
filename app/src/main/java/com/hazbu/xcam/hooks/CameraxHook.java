package com.hazbu.xcam.hooks;

import com.hazbu.xcam.xposed.XCamModule;

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
            module.logHook("[*] Initializing CameraX (Logging only)");
            hookImageCapture(param);
        } catch (Throwable t) {
            module.logHook("[!] Failed to initialize CameraX hooks: " + t.getMessage());
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
                            module.logHook("[*] Activity: CameraX#takePicture detected");
                        }
                        return chain.proceed();
                    });
                    module.logHook("[+] Hooked: CameraX#takePicture");
                }
            }
        } catch (Throwable t) {
            module.logHook("[!] Failed to hook CameraX ImageCapture: " + t.getMessage());
        }
    }
}
