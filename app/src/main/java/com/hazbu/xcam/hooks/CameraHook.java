package com.hazbu.xcam.hooks;

import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.PictureCallback;
import android.hardware.Camera.ShutterCallback;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.hazbu.xcam.XCamModule;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * Hooks for the original Camera API (android.hardware.Camera).
 * Consolidated all Legacy Camera logic including Renderer hooks.
 */
public class CameraHook {
    private static final long PREVIEW_RESTART_DELAY_MS = 100;

    private final XCamModule module;
    private final Handler mainHandler;
    private Surface viewfinderSurface;

    public CameraHook(XCamModule module) {
        this.module = module;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void install(XposedModuleInterface.PackageReadyParam param) {
        try {
            module.printLog("Initializing Camera API hooks", null);
            hookCameraParameters(param);
            hookViewfinder(param);
            hookTakePicture(param);
            hookLegacyMethod1(param);
        } catch (Throwable t) {
            module.printLog("Failed to initialize Camera API hooks: " + t.getMessage(), null);
        }
    }

    private void hookLegacyMethod1(XposedModuleInterface.PackageReadyParam param) {
        try {
            Class<?> rendererClass = param.getClassLoader().loadClass("android.hardware.camera2.legacy.SurfaceTextureRenderer");
            Method drawFrame = rendererClass.getDeclaredMethod("drawFrame", SurfaceTexture.class, int.class, int.class, int.class);
            module.hook(drawFrame).intercept(chain -> {
                int width = (int) chain.getArgs().get(1);
                int height = (int) chain.getArgs().get(2);
                if (module.handlePreview(width, height)) return null;
                return chain.proceed();
            });
            module.printLog("Legacy: Method 1 (Renderer) Hook OK", null);
        } catch (Throwable ignored) {}

        try {
            Class<?> legacyDeviceClass = param.getClassLoader().loadClass("android.hardware.camera2.legacy.LegacyCameraDevice");
            for (Method method : legacyDeviceClass.getDeclaredMethods()) {
                if (method.getName().equals("produceFrame") && method.getParameterTypes().length == 5) {
                    module.hook(method).intercept(chain -> {
                        int format = (int) chain.getArgs().get(4);
                        if (format == 0x21) { // JPEG
                            int width = (int) chain.getArgs().get(2);
                            int height = (int) chain.getArgs().get(3);
                            byte[] replacement = module.handleCapture(width, height);
                            if (replacement != null) {
                                Object[] newArgs = chain.getArgs().toArray();
                                newArgs[1] = replacement;
                                return chain.proceed(newArgs);
                            }
                        }
                        return chain.proceed();
                    });
                }
            }
        } catch (Throwable ignored) {}
    }

    private void hookViewfinder(XposedModuleInterface.PackageReadyParam param) {
        try {
            Method setPreviewDisplay = Camera.class.getDeclaredMethod("setPreviewDisplay", SurfaceHolder.class);
            module.hook(setPreviewDisplay).intercept(chain -> {
                SurfaceHolder holder = (SurfaceHolder) chain.getArgs().get(0);
                if (holder != null && holder.getSurface() != null) viewfinderSurface = holder.getSurface();
                return chain.proceed();
            });

            Method setPreviewTexture = Camera.class.getDeclaredMethod("setPreviewTexture", SurfaceTexture.class);
            module.hook(setPreviewTexture).intercept(chain -> {
                SurfaceTexture texture = (SurfaceTexture) chain.getArgs().get(0);
                if (texture != null) viewfinderSurface = new Surface(texture);
                return chain.proceed();
            });

            Method startPreview = Camera.class.getDeclaredMethod("startPreview");
            module.hook(startPreview).intercept(chain -> {
                Object result = chain.proceed();
                if (module.getMediaPath() != null && viewfinderSurface != null && viewfinderSurface.isValid()) {
                    module.registerPreviewSurface(viewfinderSurface);
                }
                return result;
            });

            Method stopPreview = Camera.class.getDeclaredMethod("stopPreview");
            module.hook(stopPreview).intercept(chain -> {
                Object result = chain.proceed();
                module.stopCamera1Engine();
                return result;
            });
        } catch (Throwable t) {
            module.printLog("Failed to hook viewfinder: " + t.getMessage(), null);
        }
    }

    private void hookCameraParameters(XposedModuleInterface.PackageReadyParam param) {
        try {
            Class<?> paramsClass = Camera.Parameters.class;
            Method setPreviewSize = paramsClass.getDeclaredMethod("setPreviewSize", int.class, int.class);
            module.hook(setPreviewSize).intercept(chain -> {
                module.printLog("Legacy: setPreviewSize " + chain.getArgs().get(0) + "x" + chain.getArgs().get(1), null);
                return chain.proceed();
            });
        } catch (Throwable t) {
            module.printLog("Failed to hook Camera.Parameters: " + t.getMessage(), null);
        }
    }

    private void hookTakePicture(XposedModuleInterface.PackageReadyParam param) {
        try {
            for (Method m : Camera.class.getDeclaredMethods()) {
                if (m.getName().equals("takePicture") && m.getParameterTypes().length == 4) {
                    module.hook(m).intercept(chain -> {
                        if (module.getMediaPath() == null) return chain.proceed();
                        Camera camera = (Camera) chain.getThisObject();
                        PictureCallback jpegCallback = (PictureCallback) chain.getArgs().get(3);
                        if (jpegCallback == null) return chain.proceed();

                        module.triggerCaptureState();
                        Camera.Parameters params = camera.getParameters();
                        byte[] imageData = module.handleCapture(params.getPictureSize().width, params.getPictureSize().height);

                        if (imageData == null) return chain.proceed();

                        final ShutterCallback shutterCallback = (ShutterCallback) chain.getArgs().get(0);
                        final PictureCallback rawCallback = (PictureCallback) chain.getArgs().get(1);
                        final PictureCallback postviewCallback = (PictureCallback) chain.getArgs().get(2);

                        Runnable deliver = () -> {
                            try {
                                if (shutterCallback != null) shutterCallback.onShutter();
                                if (rawCallback != null) rawCallback.onPictureTaken(null, camera);
                                if (postviewCallback != null) postviewCallback.onPictureTaken(null, camera);
                                jpegCallback.onPictureTaken(imageData, camera);
                                mainHandler.postDelayed(() -> { try { camera.startPreview(); } catch (Throwable ignored) {} }, PREVIEW_RESTART_DELAY_MS);
                            } catch (Throwable ignored) {}
                        };
                        if (Looper.myLooper() == Looper.getMainLooper()) deliver.run(); else mainHandler.post(deliver);
                        return null;
                    });
                }
            }
        } catch (Throwable t) {
            module.printLog("Failed to hook takePicture: " + t.getMessage(), null);
        }
    }
}

