package com.hazbu.xcam.hooks;

import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.view.TextureView;

import androidx.annotation.NonNull;
import com.hazbu.xcam.XCamModule;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * Hooks for UI components that display camera previews (TextureView, SurfaceView).
 * UI Fallback mechanisms for apps that don't use standard Camera APIs for display.
 */
public class UIHook {
    private final XCamModule module;
    private final Handler uiHandler;

    public UIHook(XCamModule module) {
        this.module = module;
        this.uiHandler = new Handler(Looper.getMainLooper());
    }

    public void install(XposedModuleInterface.PackageReadyParam param) {
        try {
            module.printLog("Initializing UI Fallback hooks", null);
            hookTextureView(param);
            hookSurfaceView(param);
        } catch (Throwable t) {
            module.printLog("Failed to initialize UI Fallback hooks: " + t.getMessage(), null);
        }
    }

    private void hookTextureView(XposedModuleInterface.PackageReadyParam param) {
        try {
            Class<?> tvClass = param.getClassLoader().loadClass("android.view.TextureView");
            Method setSt = tvClass.getDeclaredMethod("setSurfaceTexture", SurfaceTexture.class);
            module.hook(setSt).intercept(chain -> {
                SurfaceTexture st = (SurfaceTexture) chain.getArgs().get(0);
                if (st != null) {
                    module.printLog("UI Hook: TextureView setSurfaceTexture", null);
                    module.handleCamera1Preview(st);
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {}
    }

    private void hookSurfaceView(XposedModuleInterface.PackageReadyParam param) {
        try {
            Class<?> svClass = param.getClassLoader().loadClass("android.view.SurfaceView");
            Method getHolder = svClass.getDeclaredMethod("getHolder");
            module.hook(getHolder).intercept(chain -> {
                SurfaceHolder holder = (SurfaceHolder) chain.proceed();
                if (holder != null) {
                    module.printLog("UI Hook: SurfaceView getHolder", null);
                    holder.addCallback(new SurfaceHolder.Callback() {
                        @Override
                        public void surfaceCreated(@NonNull SurfaceHolder h) {
                            module.printLog("UI Hook: SurfaceView Callback -> surfaceCreated", null);
                            uiHandler.postDelayed(() -> module.handleSurfaceViewPreview(h), 500);
                        }

                        @Override
                        public void surfaceChanged(@NonNull SurfaceHolder h, int f, int w, int h2) {
                            module.handleSurfaceViewPreview(h);
                        }

                        @Override
                        public void surfaceDestroyed(@NonNull SurfaceHolder h) {
                            module.stopCamera1Engine();
                        }
                    });
                }
                return holder;
            });
        } catch (Throwable ignored) {}
    }
}
