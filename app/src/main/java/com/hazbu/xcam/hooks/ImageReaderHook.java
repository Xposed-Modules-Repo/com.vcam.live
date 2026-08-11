package com.hazbu.xcam.hooks;

import android.graphics.ImageFormat;
import android.media.ImageReader;
import com.hazbu.xcam.XCamModule;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Specialized hooks for android.media.ImageReader.
 * Detects when ImageReader is created and when data starts flowing.
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
                    if (result != null) {
                        String key = reader.getWidth() + "x" + reader.getHeight() + "_" + reader.getImageFormat();
                        if (!detectedFlows.contains(key)) {
                            detectedFlows.add(key);
                            String fmt = getFormatName(reader.getImageFormat());
                            module.showToast("Flowing: " + reader.getWidth() + "x" + reader.getHeight() + " (" + fmt + ")");
                            module.printLog("[ImageReader] First image acquired: " + key, null);
                        }
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
                    if (result != null) {
                        String key = reader.getWidth() + "x" + reader.getHeight() + "_" + reader.getImageFormat();
                        if (!detectedFlows.contains(key)) {
                            detectedFlows.add(key);
                            String fmt = getFormatName(reader.getImageFormat());
                            module.showToast("Flowing: " + reader.getWidth() + "x" + reader.getHeight() + " (" + fmt + ")");
                            module.printLog("[ImageReader] First image acquired: " + key, null);
                        }
                    }
                    return result;
                });
            } catch (Throwable ignored) {}
            
        } catch (Throwable t) {
            module.printLog("ImageReader hook failed: " + t.getMessage(), null);
        }
    }
}
