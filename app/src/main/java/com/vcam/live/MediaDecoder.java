package com.vcam.live;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

// 视频硬件解码器
public final class MediaDecoder {

    private static final String TAG = "vcam::decoder";
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;

    public interface FormatCallback {
        void onFormatChanged(int width, int height);
    }

    private MediaCodec codec;
    private Surface targetSurface;
    private final FormatCallback formatCallback;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private Thread outputThread;

    private int currentWidth = 1920;
    private int currentHeight = 1080;

    public MediaDecoder(Surface targetSurface, FormatCallback formatCallback) {
        this.targetSurface = targetSurface;
        this.formatCallback = formatCallback;
    }

    // 启动解码器并绑定目标表面
    public synchronized void start(int width, int height) {
        if (isRunning.get()) {
            return;
        }

        this.currentWidth = width;
        this.currentHeight = height;

        if (targetSurface == null || !targetSurface.isValid()) {
            Log.w(TAG, "Cannot start MediaDecoder: target surface is invalid");
            return;
        }

        try {
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            }
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);

            codec = MediaCodec.createDecoderByType(MIME_TYPE);
            codec.configure(format, targetSurface, null, 0);
            codec.start();

            isRunning.set(true);

            outputThread = new Thread(this::renderLoop, "vcam-decoder-out");
            outputThread.start();

            Log.i(TAG, "MediaCodec started directly for Surface: " + targetSurface + " " + width + "x" + height);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start MediaCodec", t);
            stop();
        }
    }

    // 热替换输出表面 (高通平台 setOutputSurface 存在原生 buffer slot 丢失问题，直接重启编解码器最为干净稳定)
    public synchronized void updateTargetSurface(Surface newSurface) {
        if (newSurface == null || !newSurface.isValid()) {
            return;
        }
        if (this.targetSurface == newSurface) {
            return;
        }
        this.targetSurface = newSurface;
        Log.i(TAG, "Updating target surface to: " + newSurface);

        if (isRunning.get()) {
            stop();
            start(currentWidth, currentHeight);
        }
    }

    // 喂入视频数据
    public void feedNalu(byte[] data, int offset, int length, long ptsUs) {
        if (!isRunning.get() || length <= 0) {
            return;
        }

        final MediaCodec c;
        synchronized (this) {
            c = this.codec;
        }
        if (c == null || !isRunning.get()) {
            return;
        }

        try {
            int inputIndex = -1;
            for (int retry = 0; retry < 10 && inputIndex < 0 && isRunning.get(); retry++) {
                inputIndex = c.dequeueInputBuffer(20000);
            }

            if (inputIndex < 0) {
                Log.w(TAG, "MediaCodec input buffer busy, skipping packet");
                return;
            }

            ByteBuffer inputBuffer = c.getInputBuffer(inputIndex);
            if (inputBuffer == null) {
                return;
            }

            inputBuffer.clear();
            int toWrite = Math.min(length, inputBuffer.capacity());
            inputBuffer.put(data, offset, toWrite);

            long presentationTime = ptsUs > 0 ? ptsUs : (System.nanoTime() / 1000L);
            c.queueInputBuffer(inputIndex, 0, toWrite, presentationTime, 0);
        } catch (MediaCodec.CodecException ce) {
            if (isRunning.get()) {
                Log.w(TAG, "CodecException in feedNalu: " + ce.getMessage());
                asyncRestartDecoder();
            }
        } catch (IllegalStateException ise) {
            if (isRunning.get()) {
                Log.w(TAG, "IllegalStateException in feedNalu: " + ise.getMessage());
                asyncRestartDecoder();
            }
        } catch (Throwable t) {
            Log.w(TAG, "feedNalu error: " + t.getMessage());
        }
    }

    // 循环提取解码帧并直出渲染
    private void renderLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long renderedFrames = 0;
        long lastLog = System.currentTimeMillis();

        while (isRunning.get()) {
            final MediaCodec c;
            synchronized (this) {
                c = this.codec;
            }
            if (c == null || !isRunning.get()) {
                break;
            }

            try {
                int outIndex = c.dequeueOutputBuffer(info, 15000);
                if (outIndex >= 0) {
                    c.releaseOutputBuffer(outIndex, true);
                    renderedFrames++;

                    long now = System.currentTimeMillis();
                    if (now - lastLog >= 5000) {
                        Log.i(TAG, "Hardware decoded and rendered frames: " + renderedFrames);
                        lastLog = now;
                    }
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = c.getOutputFormat();
                    int w = newFormat.getInteger(MediaFormat.KEY_WIDTH);
                    int h = newFormat.getInteger(MediaFormat.KEY_HEIGHT);
                    Log.i(TAG, "Decoder output resolution detected: " + w + "x" + h);
                    if (formatCallback != null) {
                        formatCallback.onFormatChanged(w, h);
                    }
                }
            } catch (MediaCodec.CodecException ce) {
                if (isRunning.get()) {
                    Log.w(TAG, "CodecException in renderLoop: " + ce.getMessage());
                    asyncRestartDecoder();
                }
                break;
            } catch (IllegalStateException ise) {
                if (isRunning.get()) {
                    Log.w(TAG, "IllegalStateException in renderLoop: " + ise.getMessage());
                    asyncRestartDecoder();
                }
                break;
            } catch (Throwable t) {
                if (isRunning.get()) {
                    Log.w(TAG, "renderLoop exception: " + t.getMessage());
                }
                break;
            }
        }
    }

    // 异步重启解码器，避免在 outputThread 内部自我阻塞死锁
    private void asyncRestartDecoder() {
        new Thread(() -> {
            synchronized (this) {
                if (!isRunning.get()) return;
                Log.w(TAG, "Auto-restarting MediaDecoder asynchronously...");
                stop();
                start(currentWidth, currentHeight);
            }
        }, "vcam-decoder-restart").start();
    }

    // 重启解码器
    private synchronized void restartDecoder() {
        if (!isRunning.get()) return;
        Log.w(TAG, "Auto-restarting MediaDecoder...");
        stop();
        start(currentWidth, currentHeight);
    }

    // 停止解码器并释放资源
    public synchronized void stop() {
        isRunning.set(false);
        Thread t = this.outputThread;
        if (t != null) {
            this.outputThread = null;
            if (Thread.currentThread() != t) {
                t.interrupt();
                try {
                    t.join(300);
                } catch (InterruptedException ignored) {}
            }
        }
        if (codec != null) {
            try {
                codec.stop();
                codec.release();
            } catch (Throwable ignored) {
            }
            codec = null;
        }
    }
}
