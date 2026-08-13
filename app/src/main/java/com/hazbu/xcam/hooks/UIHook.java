package com.hazbu.xcam.hooks;

import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;

import androidx.annotation.NonNull;
import com.hazbu.xcam.xposed.XCamModule;

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
            module.logHook("[*] Initializing UI Fallback Hooks");
            hookTextureView(param);
            hookSurfaceView(param);
        } catch (Throwable t) {
            module.logHook("[!] Failed to initialize UI Fallback hooks: " + t.getMessage());
        }
    }

    private void hookTextureView(XposedModuleInterface.PackageReadyParam param) {
        try {
            Class<?> tvClass = param.getClassLoader().loadClass("android.view.TextureView");
            Method setSt = tvClass.getDeclaredMethod("setSurfaceTexture", SurfaceTexture.class);
            module.hook(setSt).intercept(chain -> {
                SurfaceTexture st = (SurfaceTexture) chain.getArgs().get(0);
                if (st != null) {
                    module.logHook("[*] Activity: TextureView#setSurfaceTexture detected");
                    module.handleCamera1Preview(st);
                }
                return chain.proceed();
            });
            module.logHook("[+] Hooked: TextureView#setSurfaceTexture");
        } catch (Throwable ignored) {}
    }

    private void hookSurfaceView(XposedModuleInterface.PackageReadyParam param) {
        try {
            Class<?> svClass = param.getClassLoader().loadClass("android.view.SurfaceView");
            Method getHolder = svClass.getDeclaredMethod("getHolder");
            module.hook(getHolder).intercept(chain -> {
                SurfaceHolder holder = (SurfaceHolder) chain.proceed();
                if (holder != null) {
                    module.logHook("[*] Activity: SurfaceView#getHolder detected");
                    holder.addCallback(new SurfaceHolder.Callback() {
                        @Override
                        public void surfaceCreated(@NonNull SurfaceHolder h) {
                            module.logHook("[*] Activity: SurfaceView Callback -> surfaceCreated");
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
            module.logHook("[+] Hooked: SurfaceView#getHolder");
        } catch (Throwable ignored) {}
    }
}
