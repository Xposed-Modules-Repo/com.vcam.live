package com.hazbu.xcam.hooks;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.media.Image;
import android.media.ImageReader;
import android.view.Surface;

import com.hazbu.xcam.XCamModule;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;

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
            module.printLog("Initializing Camera2 API hooks", null);
            hookDiscovery(param);
            hookModernHijack(param);
            hookSurgicalDiverter(param);
            // Removed ImageReader and CaptureSession hooks to prevent hang
            module.printLog("Camera2 API hooks initialized successfully", null);
        } catch (Throwable t) {
            module.printLog("Failed to initialize Camera2 API hooks: " + t.getMessage(), null);
        }
    }

    private void hookDiscovery(XposedModuleInterface.PackageReadyParam param) {
        try {
            Class<?> cameraDeviceClass = param.getClassLoader().loadClass("android.hardware.camera2.impl.CameraDeviceImpl");
            for (Method method : cameraDeviceClass.getDeclaredMethods()) {
                if (method.getName().startsWith("createCaptureSession")) {
                    module.hook(method).intercept(chain -> {
                        module.printLog("[Discovery] CameraDeviceImpl#" + method.getName(), null);
                        module.clearPreviewSurfaces();
                        for (Object arg : chain.getArgs()) {
                            inspectDiscoveryArgument(arg);
                        }
                        return chain.proceed();
                    });
                }
            }
        } catch (Throwable t) {
            module.printLog("Discovery hook failed: " + t.getMessage(), null);
        }
    }

    private void inspectDiscoveryArgument(Object arg) {
        if (arg instanceof Surface) {
            module.registerPreviewSurface((Surface) arg);
        } else if (arg instanceof OutputConfiguration) {
            Surface s = ((OutputConfiguration) arg).getSurface();
            if (s != null) module.registerPreviewSurface(s);
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
                            module.printLog("[Hijack] ACTION: Swapping Preview Surface via OutputConfiguration", null);
                            module.setPreviewSwapped(true);
                            module.handleModernPreview(surface);
                            
                            Object[] newArgs = chain.getArgs().toArray();
                            newArgs[0] = module.getDummySurface();
                            return chain.proceed(newArgs);
                        }
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable t) {
            module.printLog("Modern Hijack hook failed: " + t.getMessage(), null);
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
                        module.printLog("Hooked: addTarget [STILL_CAPTURE]", null);
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
        } catch (Throwable t) {
            module.printLog("Surgical Diverter hook failed: " + t.getMessage(), null);
        }
    }
}

