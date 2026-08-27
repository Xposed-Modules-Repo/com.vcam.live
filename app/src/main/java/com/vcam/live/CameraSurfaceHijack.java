package com.vcam.live;

import android.annotation.SuppressLint;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

// 通用相机预览劫持与直通注入引擎
public final class CameraSurfaceHijack {

    private static final String TAG = "vcam::cam-hijack";

    private static final Set<Surface> sFakeSurfaces =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    private static HandlerThread sDrainThread;
    private static Handler sDrainHandler;

    private CameraSurfaceHijack() {
    }

    public static void install(
            XposedInterface xposed, XposedModuleInterface.PackageReadyParam param) {
        install(xposed, param.getClassLoader());
    }

    @SuppressLint("DiscouragedApi")
    public static void install(XposedInterface xposed, ClassLoader cl) {
        hookCameraResolutionBoost(xposed, cl);
        hookCamera2(xposed, cl);
        hookCamera1(xposed, cl);
    }

    // 提升相机物理分辨率
    private static void hookCameraResolutionBoost(XposedInterface xposed, ClassLoader cl) {
        try {
            Method setBufferSize = SurfaceTexture.class.getDeclaredMethod("setDefaultBufferSize", int.class, int.class);
            setBufferSize.setAccessible(true);
            xposed.hook(setBufferSize)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(chain -> {
                        if (!VcamPrefs.isEnabled()) {
                            return chain.proceed();
                        }
                        int w = (int) chain.getArg(0);
                        int h = (int) chain.getArg(1);
                        if (w < 1920 && h < 1920 && w > 0 && h > 0) {
                            int targetW = (w >= h) ? 1920 : 1080;
                            int targetH = (w >= h) ? 1080 : 1920;
                            Log.i(TAG, "Boosting SurfaceTexture buffer: " + w + "x" + h + " -> " + targetW + "x" + targetH);
                            return chain.proceed(new Object[]{ targetW, targetH });
                        }
                        return chain.proceed();
                    });
            Log.i(TAG, "Hooked SurfaceTexture.setDefaultBufferSize");
        } catch (Throwable t) {
            Log.w(TAG, "setDefaultBufferSize hook failed: " + t.getMessage());
        }

        try {
            Class<?> mapClass = Class.forName("android.hardware.camera2.params.StreamConfigurationMap", false, cl);
            for (Method m : mapClass.getDeclaredMethods()) {
                if (m.getName().equals("getOutputSizes")) {
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 1) {
                        m.setAccessible(true);
                        xposed.hook(m)
                                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                                .intercept(chain -> {
                                    Object result = chain.proceed();
                                    if (!VcamPrefs.isEnabled()) {
                                        return result;
                                    }
                                    if (result instanceof Size[] sizes && sizes.length > 0) {
                                        List<Size> list = new ArrayList<>(List.of(sizes));
                                        list.sort((s1, s2) -> Integer.compare(s2.getWidth() * s2.getHeight(), s1.getWidth() * s1.getHeight()));
                                        return list.toArray(new Size[0]);
                                    }
                                    return result;
                                });
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    // 拦截 Camera2 会话创建
    private static void hookCamera2(XposedInterface xposed, ClassLoader cl) {
        try {
            Class<?> deviceImpl = Class.forName("android.hardware.camera2.impl.CameraDeviceImpl", false, cl);

            for (Method m : deviceImpl.getDeclaredMethods()) {
                String name = m.getName();
                if (!name.startsWith("create") || !name.contains("Session")) {
                    continue;
                }

                Class<?>[] params = m.getParameterTypes();
                if (params.length == 0) continue;

                if (params[0] == SessionConfiguration.class) {
                    m.setAccessible(true);
                    xposed.hook(m)
                            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                            .intercept(chain -> {
                                if (!VcamPrefs.isEnabled()) {
                                    return chain.proceed();
                                }
                                Object config = chain.getArg(0);
                                if (config != null) {
                                    swapSessionConfiguration(config);
                                }
                                return chain.proceed();
                            });
                    Log.i(TAG, "Hooked Camera2 SessionConfiguration: " + m);
                } else if (List.class.isAssignableFrom(params[0])) {
                    m.setAccessible(true);
                    xposed.hook(m)
                            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                            .intercept(chain -> {
                                if (!VcamPrefs.isEnabled()) {
                                    return chain.proceed();
                                }
                                Object firstArg = chain.getArg(0);
                                if (firstArg instanceof List<?> list && !list.isEmpty()) {
                                    Object elem = list.get(0);
                                    if (elem instanceof Surface) {
                                        swapSurfaceList((List<Surface>) list);
                                    } else if (elem instanceof OutputConfiguration) {
                                        swapOutputConfigurationList((List<OutputConfiguration>) list);
                                    }
                                }
                                return chain.proceed();
                            });
                    Log.i(TAG, "Hooked Camera2 List: " + m);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "hookCamera2 error", t);
        }
    }

    // 拦截 Camera1 预览接口
    private static void hookCamera1(XposedInterface xposed, ClassLoader cl) {
        try {
            Class<?> cameraClass = Class.forName("android.hardware.Camera", false, cl);

            try {
                Method setTexture = cameraClass.getDeclaredMethod("setPreviewTexture", SurfaceTexture.class);
                setTexture.setAccessible(true);
                xposed.hook(setTexture)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept(chain -> {
                            if (!VcamPrefs.isEnabled()) {
                                return chain.proceed();
                            }
                            SurfaceTexture realTexture = (SurfaceTexture) chain.getArg(0);
                            if (realTexture != null) {
                                Surface realSurface = new Surface(realTexture);
                                Log.i(TAG, "Hooked Camera1 setPreviewTexture: " + realSurface);
                                RenderedStream.accept(realSurface);
                            }
                            return chain.proceed();
                        });
            } catch (Throwable ignored) {}

            try {
                Method setDisplay = cameraClass.getDeclaredMethod("setPreviewDisplay", SurfaceHolder.class);
                setDisplay.setAccessible(true);
                xposed.hook(setDisplay)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept(chain -> {
                            if (!VcamPrefs.isEnabled()) {
                                return chain.proceed();
                            }
                            SurfaceHolder holder = (SurfaceHolder) chain.getArg(0);
                            if (holder != null && holder.getSurface() != null && holder.getSurface().isValid()) {
                                Surface realSurface = holder.getSurface();
                                Log.i(TAG, "Hooked Camera1 setPreviewDisplay: " + realSurface);
                                RenderedStream.accept(realSurface);
                            }
                            return chain.proceed();
                        });
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            Log.w(TAG, "hookCamera1 skipped");
        }
    }

    @SuppressWarnings("unchecked")
    private static void swapSessionConfiguration(Object sessionConfig) {
        try {
            Method getOutputs = SessionConfiguration.class.getDeclaredMethod("getOutputConfigurations");
            getOutputs.setAccessible(true);
            List<OutputConfiguration> outputs = (List<OutputConfiguration>) getOutputs.invoke(sessionConfig);
            if (outputs != null) {
                swapOutputConfigurationList(outputs);
            }
        } catch (Exception e) {
            Log.e(TAG, "swapSessionConfiguration failed", e);
        }
    }

    private static void swapOutputConfigurationList(List<OutputConfiguration> outputs) {
        if (outputs == null || outputs.isEmpty()) return;

        Surface chosenReal = null;
        for (int i = 0; i < outputs.size(); i++) {
            OutputConfiguration cfg = outputs.get(i);
            Surface current = firstSurface(cfg);
            if (current == null || !current.isValid()) continue;

            if (sFakeSurfaces.contains(current)) {
                continue;
            }

            Log.i(TAG, "Camera2 OutputConfiguration real target: " + current);
            if (chosenReal == null) {
                chosenReal = current;
            }

            Surface fake = createUniqueFakeSurface(i);
            replaceSurfaceDirect(cfg, fake);
        }

        if (chosenReal != null) {
            Log.i(TAG, "Camera2 ACCEPT target surface: " + chosenReal);
            RenderedStream.accept(chosenReal);
        }
    }

    private static void swapSurfaceList(List<Surface> surfaces) {
        if (surfaces == null || surfaces.isEmpty()) return;

        Surface chosenReal = null;

        for (int i = 0; i < surfaces.size(); i++) {
            Surface s = surfaces.get(i);
            if (s == null || !s.isValid() || sFakeSurfaces.contains(s)) continue;

            Log.i(TAG, "Camera2 raw Surface real target: " + s);
            if (chosenReal == null) {
                chosenReal = s;
            }

            Surface fake = createUniqueFakeSurface(i);
            try {
                surfaces.set(i, fake);
            } catch (Throwable ignored) {}
        }

        if (chosenReal != null) {
            Log.i(TAG, "Camera2 List Surface ACCEPT target: " + chosenReal);
            RenderedStream.accept(chosenReal);
        }
    }

    @SuppressWarnings("unchecked")
    private static void replaceSurfaceDirect(OutputConfiguration cfg, Surface fake) {
        try {
            Field surfacesField = findField(OutputConfiguration.class, "mSurfaces");
            if (surfacesField != null) {
                surfacesField.setAccessible(true);
                Object obj = surfacesField.get(cfg);
                if (obj instanceof List) {
                    List<Surface> list = (List<Surface>) obj;
                    list.clear();
                    list.add(fake);
                    return;
                }
            }

            Field surfaceField = findField(OutputConfiguration.class, "mSurface");
            if (surfaceField != null) {
                surfaceField.setAccessible(true);
                surfaceField.set(cfg, fake);
                return;
            }
        } catch (Throwable t) {
            Log.w(TAG, "replaceSurfaceDirect fallback: " + t.getMessage());
        }

        try {
            cfg.addSurface(fake);
        } catch (Throwable ignored) {}
    }

    private static Field findField(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Surface firstSurface(OutputConfiguration cfg) {
        try {
            Surface s = cfg.getSurface();
            if (s != null) return s;
        } catch (Throwable ignored) {}

        try {
            List<Surface> list = cfg.getSurfaces();
            if (list != null && !list.isEmpty()) {
                return list.get(0);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // 确保假表面缓冲消费线程就绪
    private static synchronized void ensureDrainThread() {
        if (sDrainThread == null) {
            sDrainThread = new HandlerThread("vcam-fake-drain");
            sDrainThread.start();
            sDrainHandler = new Handler(sDrainThread.getLooper());
        }
    }

    // 创建具有自动消费机制的唯一假表面
    private static synchronized Surface createUniqueFakeSurface(int index) {
        ensureDrainThread();
        SurfaceTexture st = new SurfaceTexture(1001 + index);
        st.setDefaultBufferSize(1920, 1080);
        st.setOnFrameAvailableListener(surfaceTexture -> {
            try {
                surfaceTexture.updateTexImage();
            } catch (Throwable ignored) {}
        }, sDrainHandler);
        Surface s = new Surface(st);
        sFakeSurfaces.add(s);
        return s;
    }
}
