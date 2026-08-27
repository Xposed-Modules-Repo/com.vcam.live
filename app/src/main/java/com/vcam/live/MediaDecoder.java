package com.vcam.live;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

// 硬件解码器
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

    // 热替换输出表面
    public synchronized void updateTargetSurface(Surface newSurface) {
        if (newSurface == null || !newSurface.isValid()) {
            return;
        }
        if (this.targetSurface == newSurface) {
            return;
        }
        this.targetSurface = newSurface;
        Log.i(TAG, "Updating target surface to: " + newSurface);

        if (codec != null && isRunning.get()) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    codec.setOutputSurface(newSurface);
                    Log.i(TAG, "setOutputSurface hot-swapped successfully: " + newSurface);
                    return;
                }
            } catch (Throwable t) {
                Log.w(TAG, "setOutputSurface failed, fallback to restart: " + t.getMessage());
            }
            restartDecoder();
        }
    }

    // 喂入视频数据
    public void feedNalu(byte[] data, int offset, int length, long ptsUs) {
        final MediaCodec c = this.codec;
        if (!isRunning.get() || c == null || length <= 0) {
            return;
        }

        int remaining = length;
        int currentOffset = offset;

        while (remaining > 0 && isRunning.get()) {
            try {
                int inputIndex = -1;
                for (int retry = 0; retry < 3 && inputIndex < 0 && isRunning.get(); retry++) {
                    inputIndex = c.dequeueInputBuffer(15000);
                }

                if (inputIndex < 0) {
                    break;
                }

                ByteBuffer inputBuffer = c.getInputBuffer(inputIndex);
                if (inputBuffer == null) {
                    break;
                }

                inputBuffer.clear();
                int chunkSize = Math.min(remaining, inputBuffer.capacity());
                inputBuffer.put(data, currentOffset, chunkSize);

                long presentationTime = ptsUs > 0 ? ptsUs : System.nanoTime() / 1000L;
                c.queueInputBuffer(inputIndex, 0, chunkSize, presentationTime, 0);

                currentOffset += chunkSize;
                remaining -= chunkSize;
            } catch (MediaCodec.CodecException ce) {
                Log.e(TAG, "CodecException in feedNalu: " + ce.getMessage());
                restartDecoder();
                break;
            } catch (IllegalStateException ise) {
                if (isRunning.get()) {
                    Log.w(TAG, "IllegalStateException in feedNalu: " + ise.getMessage());
                }
                break;
            } catch (Throwable t) {
                Log.w(TAG, "feedNalu error: " + t.getMessage());
                break;
            }
        }
    }

    // 循环提取解码帧并直出渲染
    private void renderLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long renderedFrames = 0;
        long lastLog = System.currentTimeMillis();

        while (isRunning.get()) {
            final MediaCodec c = this.codec;
            if (c == null) break;

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
                    Log.e(TAG, "CodecException in renderLoop: " + ce.getMessage());
                    restartDecoder();
                }
                break;
            } catch (IllegalStateException ise) {
                break;
            } catch (Throwable t) {
                if (isRunning.get()) {
                    Log.w(TAG, "renderLoop exception: " + t.getMessage());
                }
                break;
            }
        }
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
        if (outputThread != null) {
            outputThread.interrupt();
            outputThread = null;
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
