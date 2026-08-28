package com.vcam.live;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import org.json.JSONObject;

// 跨进程配置中心
public final class VcamPrefs {

    private static final String TAG = "vcam::prefs";
    private static final String PREFS_NAME = "vcam_prefs";
    private static final String IPC_FILE = "/data/local/tmp/vcam_config.json";

    private static volatile boolean cachedEnabled = false;
    private static volatile long lastCheckTime = 0;

    public static final class Config {
        public boolean enabled = false;
        public int port = 9999;
    }

    private VcamPrefs() {
    }

    // 保存配置到持久化存储与跨进程文件
    @SuppressLint({"SetWorldReadable", "SetWorldWritable"})
    public static void saveConfig(Context context, Config config) {
        cachedEnabled = config.enabled;
        try {
            if (context != null) {
                SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                sp.edit()
                        .putBoolean("enabled", config.enabled)
                        .putInt("port", config.port)
                        .apply();
            }

            JSONObject json = new JSONObject();
            json.put("enabled", config.enabled);
            json.put("port", config.port);

            File f = new File(IPC_FILE);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(bytes);
                fos.flush();
            }
            f.setReadable(true, false);
            f.setWritable(true, false);
            f.setExecutable(true, false);
            Log.i(TAG, "Config saved: " + json);
        } catch (Throwable t) {
            Log.w(TAG, "saveConfig error: " + t.getMessage());
        }
    }

    // 读取跨进程配置
    public static Config loadConfig(Context context) {
        Config cfg = new Config();
        try {
            File f = new File(IPC_FILE);
            if (f.exists() && f.canRead()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                int n;
                try (FileInputStream fis = new FileInputStream(f)) {
                    while ((n = fis.read(buf)) > 0) {
                        bos.write(buf, 0, n);
                    }
                }
                String content = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                JSONObject json = new JSONObject(content);
                cfg.enabled = json.optBoolean("enabled", false);
                cfg.port = json.optInt("port", 9999);
                cachedEnabled = cfg.enabled;
                return cfg;
            }
        } catch (Throwable ignored) {
        }

        if (context != null) {
            try {
                SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                cfg.enabled = sp.getBoolean("enabled", false);
                cfg.port = sp.getInt("port", 9999);
                cachedEnabled = cfg.enabled;
            } catch (Throwable ignored) {
            }
        }
        return cfg;
    }

    // 高频轻量检查当前开关状态
    public static boolean isEnabled() {
        long now = System.currentTimeMillis();
        if (now - lastCheckTime > 300) {
            loadConfig(null);
            lastCheckTime = now;
        }
        return cachedEnabled;
    }
}
