package com.hazbu.xcam.hooks;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import com.hazbu.xcam.XCamModule;
import com.hazbu.xcam.utils.SystemUtils;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Specialized hooks for android.media.ImageReader.
 * Detects when ImageReader is created and when data starts flowing.
 * Added surgical injection for RGBA_8888 (0x1) format used by Instagram.
 */
public class ImageReaderHook {
    private final XCamModule module;
    private final Set<String> detectedFlows = new HashSet<>();
    private final Set<String> injectedFlows = new HashSet<>();
    private final Map<ImageReader, ReaderMetadata> readerTracker = new WeakHashMap<>();
    private YuvFrame cachedYuvFrame;

    private static class ReaderMetadata {
        final int width;
        final int height;
        final int format;
        final String formatName;
        final long surfaceId;

        ReaderMetadata(int width, int height, int format, String formatName, long surfaceId) {
            this.width = width;
            this.height = height;
            this.format = format;
            this.formatName = formatName;
            this.surfaceId = surfaceId;
        }

        @Override
        public String toString() {
            return width + "x" + height + " " + formatName + " (ID: " + surfaceId + ")";
        }
    }

    public ImageReaderHook(XCamModule module) {
        this.module = module;
    }

    public void install(XposedModuleInterface.PackageReadyParam param) {
        try {
            module.logHook("[*] Initializing ImageReader Hooks");
            hookImageReader(param);
        } catch (Throwable t) {
            module.logHook("[!] Failed to initialize ImageReader hooks: " + t.getMessage());
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
                        
                        Object result = chain.proceed();
                        if (result instanceof ImageReader) {
                            ImageReader reader = (ImageReader) result;
                            long surfaceId = SystemUtils.INSTANCE.getSurfaceId(reader.getSurface());
                            readerTracker.put(reader, new ReaderMetadata(w, h, format, fmtName, surfaceId));
                            module.registerImageReaderSurface(reader.getSurface(), format, w, h);
                            module.logHook("[+] ImageReader.newInstance: " + w + "x" + h + " " + fmtName + " | ID: " + surfaceId);
                        }
                        return result;
                    });
                }
            }

            // Hook setOnImageAvailableListener
            try {
                Method setListener = ImageReader.class.getDeclaredMethod("setOnImageAvailableListener", ImageReader.OnImageAvailableListener.class, android.os.Handler.class);
                module.hook(setListener).intercept(chain -> {
                    ImageReader reader = (ImageReader) chain.getThisObject();
                    ReaderMetadata meta = readerTracker.get(reader);
                    module.logHook("[*] ImageReader.setOnImageAvailableListener | Reader: " + (meta != null ? meta.toString() : "unknown") + " | Thread: " + Thread.currentThread().getName());
                    return chain.proceed();
                });
                module.logHook("[+] Hooked: ImageReader#setOnImageAvailableListener");
            } catch (Throwable ignored) {}

            // Hook acquireLatestImage
            try {
                Method acquireLatest = ImageReader.class.getDeclaredMethod("acquireLatestImage");
                module.hook(acquireLatest).intercept(chain -> {
                    ImageReader reader = (ImageReader) chain.getThisObject();
                    ReaderMetadata meta = readerTracker.get(reader);
                    if (meta != null && meta.width == 960 && meta.format == ImageFormat.YUV_420_888) {
                         module.logHook("[*] ImageReader.acquireLatestImage | Reader: " + meta + " | Thread: " + Thread.currentThread().getName());
                    }
                    Object result = chain.proceed();
                    if (result instanceof Image) {
                        processAcquiredImage(reader, (Image) result);
                    }
                    return result;
                });
                module.logHook("[+] Hooked: ImageReader#acquireLatestImage");
            } catch (Throwable ignored) {}

            // Hook acquireNextImage
            try {
                Method acquireNext = ImageReader.class.getDeclaredMethod("acquireNextImage");
                module.hook(acquireNext).intercept(chain -> {
                    ImageReader reader = (ImageReader) chain.getThisObject();
                    ReaderMetadata meta = readerTracker.get(reader);
                    if (meta != null && meta.width == 960 && meta.format == ImageFormat.YUV_420_888) {
                        module.logHook("[*] ImageReader.acquireNextImage | Reader: " + meta + " | Thread: " + Thread.currentThread().getName());
                    }
                    Object result = chain.proceed();
                    if (result instanceof Image) {
                        processAcquiredImage(reader, (Image) result);
                    }
                    return result;
                });
                module.logHook("[+] Hooked: ImageReader#acquireNextImage");
            } catch (Throwable ignored) {}

            // Hook close
            try {
                Method close = ImageReader.class.getDeclaredMethod("close");
                module.hook(close).intercept(chain -> {
                    ImageReader reader = (ImageReader) chain.getThisObject();
                    ReaderMetadata meta = readerTracker.get(reader);
                    module.logHook("[*] ImageReader.close | Reader: " + (meta != null ? meta.toString() : "unknown"));
                    return chain.proceed();
                });
                module.logHook("[+] Hooked: ImageReader#close");
            } catch (Throwable ignored) {}
            
        } catch (Throwable t) {
            module.logHook("[!] ImageReader hook failed: " + t.getMessage());
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
            module.logHook("[*] Activity: ImageReader first image acquired: " + key);
        }

        if (module.getMediaPath() != null && format == ImageFormat.YUV_420_888) {
            injectYuvFrame(image, w, h);
        } else if (module.isCapturingState() && format == ImageFormat.JPEG) {
            try {
                byte[] replacement = module.handleCapture(w, h);
                if (replacement != null) {
                    Image.Plane[] planes = image.getPlanes();
                    if (planes != null && planes.length > 0) {
                        ByteBuffer buffer = planes[0].getBuffer();
                        if (buffer != null && !buffer.isReadOnly()) {
                            buffer.clear();
                            if (buffer.remaining() < replacement.length) {
                                module.logHook("[!] Activity: ImageReader JPEG replacement skipped: buffer too small");
                                return;
                            }
                            buffer.put(replacement);
                            module.logHook("[+] Activity: ImageReader JPEG replaced successfully (" + replacement.length + " bytes)");
                        }
                    }
                }
            } catch (Throwable t) {
                module.logHook("[!] Failed to inject JPEG: " + t.getMessage());
            }
        }
    }

    /** Replaces Camera2/WebRTC YUV frames for consumers backed by ImageReader. */
    private void injectYuvFrame(Image image, int width, int height) {
        try {
            byte[] jpeg = module.handleStreamFrame(width, height);
            if (jpeg == null) return;

            if (cachedYuvFrame == null || !cachedYuvFrame.matches(jpeg, width, height)) {
                cachedYuvFrame = YuvFrame.fromJpeg(jpeg, width, height);
            }
            Image.Plane[] planes = image.getPlanes();
            if (planes == null || planes.length < 3) return;
            cachedYuvFrame.writeTo(planes);
            String key = width + "x" + height;
            if (injectedFlows.add(key)) {
                module.logHook("[+] Activity: ImageReader YUV replacement active: " + key);
            }
        } catch (Throwable t) {
            module.logHook("[!] Activity: ImageReader YUV replacement failed: " + t.getMessage());
        }
    }

    private static final class YuvFrame {
        private final byte[] source;
        private final int width;
        private final int height;
        private final byte[] y;
        private final byte[] u;
        private final byte[] v;

        private YuvFrame(byte[] source, int width, int height, byte[] y, byte[] u, byte[] v) {
            this.source = source;
            this.width = width;
            this.height = height;
            this.y = y;
            this.u = u;
            this.v = v;
        }

        static YuvFrame fromJpeg(byte[] jpeg, int width, int height) {
            Bitmap decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (decoded == null) throw new IllegalStateException("Could not decode virtual frame");
            Bitmap bitmap = decoded.getWidth() == width && decoded.getHeight() == height
                    ? decoded : Bitmap.createScaledBitmap(decoded, width, height, true);
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            byte[] y = new byte[width * height];
            int chromaWidth = (width + 1) / 2;
            int chromaHeight = (height + 1) / 2;
            byte[] u = new byte[chromaWidth * chromaHeight];
            byte[] v = new byte[chromaWidth * chromaHeight];

            for (int row = 0; row < height; row++) {
                for (int col = 0; col < width; col++) {
                    int color = pixels[row * width + col];
                    y[row * width + col] = (byte) clamp((77 * ((color >> 16) & 0xff) +
                            150 * ((color >> 8) & 0xff) + 29 * (color & 0xff)) >> 8);
                }
            }
            for (int row = 0; row < chromaHeight; row++) {
                for (int col = 0; col < chromaWidth; col++) {
                    int x = Math.min(col * 2, width - 1);
                    int yPos = Math.min(row * 2, height - 1);
                    int color = pixels[yPos * width + x];
                    int r = (color >> 16) & 0xff;
                    int g = (color >> 8) & 0xff;
                    int b = color & 0xff;
                    int index = row * chromaWidth + col;
                    u[index] = (byte) clamp(((-43 * r - 85 * g + 128 * b) >> 8) + 128);
                    v[index] = (byte) clamp(((128 * r - 107 * g - 21 * b) >> 8) + 128);
                }
            }
            if (bitmap != decoded) bitmap.recycle();
            decoded.recycle();
            return new YuvFrame(jpeg, width, height, y, u, v);
        }

        boolean matches(byte[] other, int otherWidth, int otherHeight) {
            return source == other && width == otherWidth && height == otherHeight;
        }

        void writeTo(Image.Plane[] planes) {
            writePlane(planes[0], y, width, height);
            writePlane(planes[1], u, (width + 1) / 2, (height + 1) / 2);
            writePlane(planes[2], v, (width + 1) / 2, (height + 1) / 2);
        }

        private static void writePlane(Image.Plane plane, byte[] values, int width, int height) {
            ByteBuffer buffer = plane.getBuffer();
            if (buffer == null || buffer.isReadOnly()) throw new IllegalStateException("Image plane is read-only");
            int start = buffer.position();
            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            int lastIndex = start + (height - 1) * rowStride + (width - 1) * pixelStride;
            if (lastIndex >= buffer.limit()) throw new IllegalStateException("Image plane capacity is too small");
            for (int row = 0; row < height; row++) {
                int dst = start + row * rowStride;
                int src = row * width;
                for (int col = 0; col < width; col++) buffer.put(dst + col * pixelStride, values[src + col]);
            }
        }

        private static int clamp(int value) {
            return Math.max(0, Math.min(255, value));
        }
    }
}
