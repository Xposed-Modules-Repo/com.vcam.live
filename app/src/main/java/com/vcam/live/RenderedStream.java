package com.vcam.live;

import android.util.Log;
import android.view.Surface;

// 视频推流与硬解编排器
public final class RenderedStream {

    private static final String TAG = "vcam::render";
    private static final RenderedStream INSTANCE = new RenderedStream();

    private Surface currentTarget;
    private SrtReceiver receiver;
    private MpegTsDemuxer demuxer;
    private MediaDecoder videoDecoder;

    private RenderedStream() {
    }

    public static RenderedStream get() {
        return INSTANCE;
    }

    // 接收目标预览表面并启动硬解直出管道
    public static synchronized void accept(Surface target) {
        if (!VcamPrefs.isEnabled()) {
            Log.i(TAG, "vcam disabled, pass-through to native camera");
            return;
        }

        if (target == null || !target.isValid()) {
            return;
        }

        if (INSTANCE.currentTarget == target && INSTANCE.videoDecoder != null) {
            return;
        }

        INSTANCE.currentTarget = target;

        if (INSTANCE.videoDecoder != null) {
            Log.i(TAG, "Hot-swapping target surface: " + target);
            INSTANCE.videoDecoder.updateTargetSurface(target);
            return;
        }

        Log.i(TAG, "Starting direct MediaCodec pipeline for target: " + target);

        INSTANCE.videoDecoder = new MediaDecoder(target, null);
        INSTANCE.videoDecoder.start(1920, 1080);

        INSTANCE.demuxer = new MpegTsDemuxer((naluData, offset, length, ptsUs) -> {
            if (INSTANCE.videoDecoder != null) {
                INSTANCE.videoDecoder.feedNalu(naluData, offset, length, ptsUs);
            }
        });

        INSTANCE.receiver = new SrtReceiver((buffer, offset, length) -> {
            if (INSTANCE.demuxer != null) {
                INSTANCE.demuxer.feed(buffer, offset, length);
            }
        });
        INSTANCE.receiver.start();

        Log.i(TAG, "Direct video decoding pipeline started");
    }

    // 停止并释放所有硬件与网络资源
    public synchronized void stop() {
        if (receiver != null) {
            receiver.stop();
            receiver = null;
        }
        if (videoDecoder != null) {
            videoDecoder.stop();
            videoDecoder = null;
        }
        demuxer = null;
        currentTarget = null;
        Log.i(TAG, "Pipeline stopped and resources released");
    }
}
