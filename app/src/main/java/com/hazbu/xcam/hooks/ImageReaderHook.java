package com.hazbu.xcam.hooks;

import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import com.hazbu.xcam.XCamModule;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Specialized hooks for android.media.ImageReader.
 * Detects when ImageReader is created and when data starts flowing.
 * Added surgical injection for RGBA_8888 (0x1) format used by Instagram.
 */
public class ImageReaderHook {
    private final XCamModule module;
    private final Set<String> detectedFlows = new HashSet<>();

    public ImageReaderHook(XCamModule module) {
        this.module = module;
    }

    public void install(XposedModuleInterface.PackageReadyParam param) {
        try {
            module.printLog("Initializing ImageReader hooks", null);
            hookImageReader(param);
        } catch (Throwable t) {
            module.printLog("Failed to initialize ImageReader hooks: " + t.getMessage(), null);
        }
    }

    private String getFormatName(int format) {
        switch (format) {
            case ImageFormat.JPEG: return "JPEG";
            case ImageFormat.YUV_420_888: return "YUV_420_888";
            case 0x1: return "RGBA_8888";
            case 0x22: return "PRIVATE";
            case ImageFormat.RAW_SENSOR: return "RAW_SENSOR";
            default: return "Format(0x" + Integer.toHexString(format) + ")";
        }
    }

    private void hookImageReader(XposedModuleInterface.PackageReadyParam param) {
        try {
            // Hook ImageReader.newInstance
            for (Method method : ImageReader.class.getDeclaredMethods()) {
                if (method.getName().equals("newInstance")) {
                    module.hook(method).intercept(chain -> {
                        int w = (int) chain.getArgs().get(0);
                        int h = (int) chain.getArgs().get(1);
                        int format = (int) chain.getArgs().get(2);
                        String fmtName = getFormatName(format);
                        
                        module.printLog("[ImageReader] newInstance: " + w + "x" + h + " (" + fmtName + ")", null);
                        module.showToast(w + "x" + h + " [" + fmtName + "]");
                        return chain.proceed();
                    });
                }
            }

            // Hook acquireLatestImage
            try {
                Method acquireLatest = ImageReader.class.getDeclaredMethod("acquireLatestImage");
                module.hook(acquireLatest).intercept(chain -> {
                    ImageReader reader = (ImageReader) chain.getThisObject();
                    Object result = chain.proceed();
                    if (result instanceof Image) {
                        processAcquiredImage(reader, (Image) result);
                    }
                    return result;
                });
            } catch (Throwable ignored) {}

            // Hook acquireNextImage
            try {
                Method acquireNext = ImageReader.class.getDeclaredMethod("acquireNextImage");
                module.hook(acquireNext).intercept(chain -> {
                    ImageReader reader = (ImageReader) chain.getThisObject();
                    Object result = chain.proceed();
                    if (result instanceof Image) {
                        processAcquiredImage(reader, (Image) result);
                    }
                    return result;
                });
            } catch (Throwable ignored) {}
            
        } catch (Throwable t) {
            module.printLog("ImageReader hook failed: " + t.getMessage(), null);
        }
    }

    private void processAcquiredImage(ImageReader reader, Image image) {
        int w = reader.getWidth();
        int h = reader.getHeight();
        int format = reader.getImageFormat();
        String key = w + "x" + h + "_" + format;

        if (!detectedFlows.contains(key)) {
            detectedFlows.add(key);
            String fmt = getFormatName(format);
            module.showToast(w + "x" + h + " (" + fmt + ")");
            module.printLog("[ImageReader] First image acquired: " + key, null);
        }

        // Surgical Injection ONLY for JPEG (0x100) during Capture
        if (module.isCapturingState() && format == ImageFormat.JPEG) {
            try {
                byte[] replacement = module.handleCapture(w, h);
                if (replacement != null) {
                    Image.Plane[] planes = image.getPlanes();
                    if (planes != null && planes.length > 0) {
                        ByteBuffer buffer = planes[0].getBuffer();
                        if (buffer != null && !buffer.isReadOnly()) {
                            buffer.clear();
                            int toCopy = Math.min(buffer.remaining(), replacement.length);
                            buffer.put(replacement, 0, toCopy);
                            module.printLog("[ImageReader] JPEG replaced successfully", null);
                        }
                    }
                }
            } catch (Throwable t) {
                module.printLog("Failed to inject JPEG: " + t.getMessage(), null);
            }
        }
    }
}
