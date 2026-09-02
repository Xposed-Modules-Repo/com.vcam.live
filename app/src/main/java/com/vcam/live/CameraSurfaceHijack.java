package com.vcam.live;

import android.annotation.SuppressLint;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

// 通用相机预览劫持与直通注入引擎
@SuppressLint({"DiscouragedApi", "PrivateApi", "NewApi"})
public final class CameraSurfaceHijack {

    private static final String TAG = "vcam::cam-hijack";

    // 记录由本模块创建的无头安全消费表面
    private static final Set<Surface> sDrainSurfaces =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    // 真实应用目标表面 -> 底层相机消费表面的映射 (用于 CaptureRequest 自动重定向)
    private static final Map<Surface, Surface> sRealToDrainMap =
            Collections.synchronizedMap(new IdentityHashMap<>());
    // 持有 ImageReader 引用，防止被 GC 释放
    private static final List<ImageReader> sActiveReaders = Collections.synchronizedList(new ArrayList<>());

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
        hookCamera2(xposed, cl);
        hookCaptureRequest(xposed, cl);
        hookCamera1(xposed, cl);
    }

    // 拦截 Camera2 会话创建 (支持 SessionConfiguration 与 传统 List 模式)
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
                    Log.i(TAG, "Hooked Camera2 SessionConfiguration: " + m.getName());
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
                    Log.i(TAG, "Hooked Camera2 List: " + m.getName());
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "hookCamera2 error", t);
        }
    }

    // 拦截 CaptureRequest 构建与提交，解决 "CaptureRequest contains unconfigured Input/Output Surface" 异常
    private static void hookCaptureRequest(XposedInterface xposed, ClassLoader cl) {
        try {
            Class<?> builderClass = Class.forName("android.hardware.camera2.CaptureRequest$Builder", false, cl);

            try {
                Method addTarget = builderClass.getDeclaredMethod("addTarget", Surface.class);
                addTarget.setAccessible(true);
                xposed.hook(addTarget)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept(chain -> {
                            if (!VcamPrefs.isEnabled()) {
                                return chain.proceed();
                            }
                            Surface target = (Surface) chain.getArg(0);
                            if (target != null) {
                                Surface drain = sRealToDrainMap.get(target);
                                if (drain != null) {
                                    Log.i(TAG, "Remapping CaptureRequest.Builder.addTarget: " + target + " -> " + drain);
                                    return chain.proceed(new Object[]{ drain });
                                }
                            }
                            return chain.proceed();
                        });
                Log.i(TAG, "Hooked CaptureRequest.Builder.addTarget");
            } catch (Throwable ignored) {}

            try {
                Method removeTarget = builderClass.getDeclaredMethod("removeTarget", Surface.class);
                removeTarget.setAccessible(true);
                xposed.hook(removeTarget)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept(chain -> {
                            if (!VcamPrefs.isEnabled()) {
                                return chain.proceed();
                            }
                            Surface target = (Surface) chain.getArg(0);
                            if (target != null) {
                                Surface drain = sRealToDrainMap.get(target);
                                if (drain != null) {
                                    return chain.proceed(new Object[]{ drain });
                                }
                            }
                            return chain.proceed();
                        });
                Log.i(TAG, "Hooked CaptureRequest.Builder.removeTarget");
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            Log.w(TAG, "hookCaptureRequest builder failed: " + t.getMessage());
        }

        try {
            Class<?> deviceImpl = Class.forName("android.hardware.camera2.impl.CameraDeviceImpl", false, cl);
            for (Method m : deviceImpl.getDeclaredMethods()) {
                String name = m.getName();
                if (name.equals("submitCaptureRequest") || name.equals("setRepeatingRequest") ||
                    name.equals("capture") || name.equals("setRepeatingBurst") || name.equals("captureBurst")) {
                    m.setAccessible(true);
                    xposed.hook(m)
                            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                            .intercept(chain -> {
                                if (VcamPrefs.isEnabled()) {
                                    List<Object> args = chain.getArgs();
                                    if (args != null) {
                                        for (Object arg : args) {
                                            if (arg instanceof CaptureRequest) {
                                                remapRequestSurfaces((CaptureRequest) arg);
                                            } else if (arg instanceof List) {
                                                for (Object item : (List<?>) arg) {
                                                    if (item instanceof CaptureRequest) {
                                                        remapRequestSurfaces((CaptureRequest) item);
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                return chain.proceed();
                            });
                }
            }
            Log.i(TAG, "Hooked CameraDeviceImpl submitCaptureRequest methods");
        } catch (Throwable t) {
            Log.w(TAG, "hookCaptureRequest CameraDeviceImpl failed: " + t.getMessage());
        }
    }

    // 纠正 CaptureRequest 中包含的 Surface
    @SuppressWarnings("unchecked")
    private static void remapRequestSurfaces(CaptureRequest request) {
        if (request == null) return;
        try {
            Field surfaceSetField = findField(CaptureRequest.class, "mSurfaceSet");
            if (surfaceSetField != null) {
                surfaceSetField.setAccessible(true);
                Object obj = surfaceSetField.get(request);
                if (obj instanceof Set) {
                    Set<Surface> set = (Set<Surface>) obj;
                    List<Surface> toReplace = new ArrayList<>();
                    for (Surface s : set) {
                        if (sRealToDrainMap.containsKey(s)) {
                            toReplace.add(s);
                        }
                    }
                    for (Surface real : toReplace) {
                        Surface drain = sRealToDrainMap.get(real);
                        if (drain != null) {
                            set.remove(real);
                            set.add(drain);
                            Log.i(TAG, "Remapped submitted CaptureRequest surface: " + real + " -> " + drain);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "remapRequestSurfaces error: " + t.getMessage());
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
            if (current == null || !current.isValid() || sDrainSurfaces.contains(current)) continue;

            Log.i(TAG, "Camera2 OutputConfiguration real target: " + current);
            String name = current.toString();
            if (name.contains("SurfaceTexture") || name.contains("SurfaceView")) {
                chosenReal = current;
            } else if (chosenReal == null) {
                chosenReal = current;
            }

            Surface drain = createSafeDrainSurface(640, 480);
            sRealToDrainMap.put(current, drain);
            replaceSurfaceDirect(cfg, drain);
        }

        if (chosenReal != null) {
            Log.i(TAG, "Camera2 ACCEPT target surface: " + chosenReal);
            RenderedStream.accept(chosenReal);
        }
    }

    @SuppressWarnings("unchecked")
    private static void swapSurfaceList(List<Surface> surfaces) {
        if (surfaces == null || surfaces.isEmpty()) return;

        Surface chosenReal = null;

        for (int i = 0; i < surfaces.size(); i++) {
            Surface s = surfaces.get(i);
            if (s == null || !s.isValid() || sDrainSurfaces.contains(s)) continue;

            Log.i(TAG, "Camera2 raw Surface real target: " + s);
            String name = s.toString();
            if (name.contains("SurfaceTexture") || name.contains("SurfaceView")) {
                chosenReal = s;
            } else if (chosenReal == null) {
                chosenReal = s;
            }

            Surface drain = createSafeDrainSurface(640, 480);
            sRealToDrainMap.put(s, drain);
            try {
                surfaces.set(i, drain);
            } catch (Throwable ignored) {}
        }

        if (chosenReal != null) {
            Log.i(TAG, "Camera2 List Surface ACCEPT target: " + chosenReal);
            RenderedStream.accept(chosenReal);
        }
    }

    @SuppressWarnings("unchecked")
    private static void replaceSurfaceDirect(OutputConfiguration cfg, Surface drain) {
        try {
            Field surfacesField = findField(OutputConfiguration.class, "mSurfaces");
            if (surfacesField != null) {
                surfacesField.setAccessible(true);
                Object obj = surfacesField.get(cfg);
                if (obj instanceof List) {
                    List<Surface> list = (List<Surface>) obj;
                    list.clear();
                    list.add(drain);
                    return;
                }
            }

            Field surfaceField = findField(OutputConfiguration.class, "mSurface");
            if (surfaceField != null) {
                surfaceField.setAccessible(true);
                surfaceField.set(cfg, drain);
                return;
            }
        } catch (Throwable t) {
            Log.w(TAG, "replaceSurfaceDirect fallback: " + t.getMessage());
        }

        try {
            cfg.addSurface(drain);
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

    // 确保后台消费线程就绪
    private static synchronized void ensureDrainThread() {
        if (sDrainThread == null) {
            sDrainThread = new HandlerThread("vcam-safe-drain");
            sDrainThread.start();
            sDrainHandler = new Handler(sDrainThread.getLooper());
        }
    }

    // 使用原生 ImageReader 创建安全无头的消费表面 (无需任何 OpenGL/EGL 上下文，彻底杜绝死锁与 Watchdog 重启)
    private static synchronized Surface createSafeDrainSurface(int width, int height) {
        ensureDrainThread();
        int w = width > 0 ? width : 640;
        int h = height > 0 ? height : 480;

        ImageReader reader = ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 2);
        reader.setOnImageAvailableListener(r -> {
            try {
                Image img = r.acquireLatestImage();
                if (img != null) {
                    img.close();
                }
            } catch (Throwable ignored) {}
        }, sDrainHandler);

        // 保持适度缓存量，避免内存占用累积
        if (sActiveReaders.size() > 8) {
            ImageReader old = sActiveReaders.remove(0);
            try {
                old.close();
            } catch (Throwable ignored) {}
        }
        sActiveReaders.add(reader);

        Surface s = reader.getSurface();
        sDrainSurfaces.add(s);
        return s;
    }
}
