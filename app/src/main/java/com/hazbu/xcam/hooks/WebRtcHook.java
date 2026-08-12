package com.hazbu.xcam.hooks;

import android.graphics.SurfaceTexture;
import android.view.Surface;

import com.hazbu.xcam.XCamModule;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * WebRTC uses the platform Camera1/Camera2 APIs underneath, so camera-frame
 * replacement remains in the normal camera hooks. This hook exposes the
 * SurfaceTexture owned by WebRTC early, allowing the generic surface pipeline
 * to recognise it as a camera sink without a package-specific profile.
 */
public final class WebRtcHook {
    private final XCamModule module;

    public WebRtcHook(XCamModule module) {
        this.module = module;
    }

    public void install(XposedModuleInterface.PackageReadyParam param) {
        try {
            Class<?> helper = param.getClassLoader().loadClass("org.webrtc.SurfaceTextureHelper");
            hookStartListening(helper);
            hookStopListening(helper);
            module.printLog("WebRTC support enabled", null);
        } catch (ClassNotFoundException ignored) {
            // WebRTC is optional; do not make ordinary camera apps fail.
        } catch (Throwable t) {
            module.printLog("WebRTC hook setup failed: " + t.getMessage(), null);
        }
    }

    private void hookStartListening(Class<?> helper) {
        // Do not resolve VideoSink directly: older WebRTC releases used a
        // differently named observer interface.
        for (Method startListening : helper.getDeclaredMethods()) {
            if (!startListening.getName().equals("startListening") || startListening.getParameterTypes().length != 1) continue;
            module.hook(startListening).intercept(chain -> {
                Object result = chain.proceed();
                registerHelperSurface(chain.getThisObject());
                return result;
            });
        }
    }

    private void hookStopListening(Class<?> helper) throws NoSuchMethodException {
        Method stopListening = helper.getDeclaredMethod("stopListening");
        module.hook(stopListening).intercept(chain -> {
            module.printLog("[WebRTC] SurfaceTextureHelper stopped", null);
            return chain.proceed();
        });
    }

    private void registerHelperSurface(Object helper) {
        try {
            Method getter = helper.getClass().getMethod("getSurfaceTexture");
            Object value = getter.invoke(helper);
            if (!(value instanceof SurfaceTexture)) return;
            Surface surface = new Surface((SurfaceTexture) value);
            try {
                module.registerPreviewSurface(surface);
                module.printLog("[WebRTC] Camera frame surface registered", null);
            } finally {
                surface.release();
            }
        } catch (Throwable t) {
            module.printLog("[WebRTC] Could not register frame surface: " + t.getMessage(), null);
        }
    }
}
