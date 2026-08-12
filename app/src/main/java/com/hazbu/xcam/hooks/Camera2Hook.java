package com.hazbu.xcam.hooks;

import android.annotation.SuppressLint;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.view.Surface;

import com.hazbu.xcam.XCamModule;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collection;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * Hooks for the Camera2 API (android.hardware.camera2) and ImageReader.
 * Consolidated all Camera2 logic including Hijacking and Surgical Diversion.
 */
public class Camera2Hook {
    private final XCamModule module;

    public Camera2Hook(XCamModule module) {
        this.module = module;
    }

    public void install(XposedModuleInterface.PackageReadyParam param) {
        try {
            module.logHook("[*] Initializing Camera2 API");
            hookDiscovery(param);
            hookModernHijack(param);
            hookSurgicalDiverter(param);
            module.logHook("[+] Camera2 API hooks installed successfully");
        } catch (Throwable t) {
            module.logHook("[!] Failed to initialize Camera2 API hooks: " + t.getMessage());
        }
    }

    private void hookDiscovery(XposedModuleInterface.PackageReadyParam param) {
        try {
            @SuppressLint("PrivateApi") Class<?> cameraDeviceClass = param.getClassLoader().loadClass("android.hardware.camera2.impl.CameraDeviceImpl");
            for (Method method : cameraDeviceClass.getDeclaredMethods()) {
                if (method.getName().startsWith("createCaptureSession")) {
                    module.hook(method).intercept(chain -> {
                        module.logHook("[*] Activity: CameraDeviceImpl#" + method.getName());
                        module.incrementSessionGeneration();
                        module.clearPreviewSurfaces();
                        for (Object arg : chain.getArgs()) {
                            inspectDiscoveryArgument(arg);
                        }
                        return chain.proceed();
                    });
                    module.logHook("[+] Hooked: CameraDeviceImpl#" + method.getName());
                }
            }
        } catch (Throwable t) {
            module.logHook("[!] Discovery hook failed: " + t.getMessage());
        }
    }

    private void inspectDiscoveryArgument(Object arg) {
        if (arg instanceof Surface) {
            Surface s = (Surface) arg;
            module.logSessionOutput(s);
            module.registerPreviewSurface(s);
        } else if (arg instanceof OutputConfiguration) {
            Surface s = ((OutputConfiguration) arg).getSurface();
            if (s != null) {
                module.logSessionOutput(s);
                module.registerPreviewSurface(s);
            }
        } else if (arg instanceof Collection) {
            for (Object item : (Collection<?>) arg) inspectDiscoveryArgument(item);
        }
    }

    private void hookModernHijack(XposedModuleInterface.PackageReadyParam param) {
        try {
            Class<?> ocClass = param.getClassLoader().loadClass("android.hardware.camera2.params.OutputConfiguration");
            for (Constructor<?> constructor : ocClass.getDeclaredConstructors()) {
                module.hook(constructor).intercept(chain -> {
                    Object firstArg = !chain.getArgs().isEmpty() ? chain.getArgs().get(0) : null;
                    if (firstArg instanceof Surface && ((Surface) firstArg).isValid() && module.getMediaPath() != null) {
                        Surface surface = (Surface) firstArg;
                        module.registerPreviewSurface(surface);
                        String sStr = surface.toString();
                        if (sStr.contains("SurfaceTexture") && !module.getPreviewSwapped()) {
                            module.logHook("[!] Action: Swapping Preview Surface via OutputConfiguration");
                            module.setPreviewSwapped(true);
                            module.handleModernPreview(surface);
                            
                            Object[] newArgs = chain.getArgs().toArray();
                            newArgs[0] = module.getDummySurface();
                            return chain.proceed(newArgs);
                        }
                    }
                    return chain.proceed();
                });
                module.logHook("[+] Hooked: OutputConfiguration Constructor");
            }
        } catch (Throwable t) {
            module.logHook("[!] Modern Hijack hook failed: " + t.getMessage());
        }
    }

    private void hookSurgicalDiverter(XposedModuleInterface.PackageReadyParam param) {
        try {
            Method addTarget = CaptureRequest.Builder.class.getDeclaredMethod("addTarget", Surface.class);
            module.hook(addTarget).intercept(chain -> {
                Surface surface = (Surface) chain.getArgs().get(0);
                if (surface != null && module.getMediaPath() != null) {
                    CaptureRequest.Builder builder = (CaptureRequest.Builder) chain.getThisObject();
                    Integer intent = null;
                    try { intent = builder.get(CaptureRequest.CONTROL_CAPTURE_INTENT); } catch (Throwable ignored) {}

                    if (intent != null && intent == CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE) {
                        module.logHook("[*] Activity: addTarget [STILL_CAPTURE]");
                        module.triggerCaptureState();
                        return chain.proceed();
                    }

                    if (module.isPreviewSurface(surface)) {
                        Object[] newArgs = new Object[] { module.getDummySurface() };
                        return chain.proceed(newArgs);
                    }
                }
                return chain.proceed();
            });
            module.logHook("[+] Hooked: CaptureRequest.Builder#addTarget");
        } catch (Throwable t) {
            module.logHook("[!] Surgical Diverter hook failed: " + t.getMessage());
        }
    }
}

