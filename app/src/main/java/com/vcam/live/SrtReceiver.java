package com.vcam.live;

import android.util.Log;
import android.util.Pair;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.thibaultbee.srtdroid.core.models.SrtSocket;

// 网络收流器
public final class SrtReceiver {

    private static final String TAG = "vcam::srt-rx";
    private static final int PORT = 9999;
    private static final int RCV_BUF_SIZE = 4 * 1024 * 1024;

    public interface StreamCallback {
        void onTsData(byte[] buffer, int offset, int length);
    }

    private final StreamCallback callback;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private Thread serverThread;
    private SrtSocket serverSocket;

    public SrtReceiver(StreamCallback callback) {
        this.callback = callback;
    }

    // 启动监听线程
    public synchronized void start() {
        if (isRunning.get()) {
            return;
        }
        isRunning.set(true);
        serverThread = new Thread(this::listenLoop, "vcam-srt-receiver");
        serverThread.start();
    }

    // 监听与接收数据循环
    private void listenLoop() {
        try {
            Class.forName("io.github.thibaultbee.srtdroid.core.Srt");
        } catch (Throwable t) {
            Log.e(TAG, "Cannot load srtdroid", t);
            isRunning.set(false);
            return;
        }

        while (isRunning.get()) {
            try {
                serverSocket = new SrtSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.setReceiveBufferSize(RCV_BUF_SIZE);
                serverSocket.bind("0.0.0.0", PORT);
                serverSocket.listen(64);
                Log.i(TAG, "SrtReceiver listening on 0.0.0.0:" + PORT);

                while (isRunning.get()) {
                    Pair<SrtSocket, InetSocketAddress> acceptRes = serverSocket.accept();
                    SrtSocket client = acceptRes.first;
                    InetSocketAddress peer = acceptRes.second;
                    Log.i(TAG, "Sender connected from: " + peer);

                    try (client) {
                        InputStream in = client.getInputStream(null);
                        byte[] buf = new byte[188 * 14];
                        int readLen;

                        while (isRunning.get() && (readLen = in.read(buf)) > 0) {
                            callback.onTsData(buf, 0, readLen);
                        }
                    } catch (Throwable e) {
                        Log.i(TAG, "Sender disconnected: " + e.getMessage());
                    }
                }
            } catch (Throwable t) {
                if (isRunning.get()) {
                    Log.w(TAG, "SrtReceiver socket retry in 1s: " + t.getMessage());
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ignored) {
                    }
                }
            } finally {
                if (serverSocket != null) {
                    try { serverSocket.close(); } catch (Throwable ignored) {}
                    serverSocket = null;
                }
            }
        }
    }

    // 停止接收器并关闭套接字
    public synchronized void stop() {
        isRunning.set(false);
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (Throwable ignored) {}
            serverSocket = null;
        }
        if (serverThread != null) {
            serverThread.interrupt();
            serverThread = null;
        }
    }
}
