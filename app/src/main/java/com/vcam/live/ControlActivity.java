package com.vcam.live;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.vcam.live.databinding.ActivityControlBinding;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

// 主控界面
public final class ControlActivity extends AppCompatActivity {

    private ActivityControlBinding binding;
    private VcamPrefs.Config config;
    private String streamUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        binding = ActivityControlBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        config = VcamPrefs.loadConfig(this);

        initStreamUrl();
        updateUiState();
        setupListeners();
    }

    @Override
    protected void onResume() {
        super.onResume();
        config = VcamPrefs.loadConfig(this);
        initStreamUrl();
        updateUiState();
    }

    // 初始化推流地址
    private void initStreamUrl() {
        String ip = getLocalIpAddress();
        streamUrl = "srt://" + ip + ":" + config.port;
        binding.tvStreamUrl.setText(streamUrl);
    }

    // 更新界面状态
    private void updateUiState() {
        if (config.enabled) {
            binding.tvStatus.setText(R.string.status_running);
            binding.btnToggle.setText(R.string.action_stop);
            binding.btnToggle.setIconResource(R.drawable.ic_stop);

            int colorPrimary = MaterialColors.getColor(
                    binding.getRoot(),
                    com.google.android.material.R.attr.colorPrimary);
            binding.statusDot.setBackgroundTintList(ColorStateList.valueOf(colorPrimary));
        } else {
            binding.tvStatus.setText(R.string.status_stopped);
            binding.btnToggle.setText(R.string.action_start);
            binding.btnToggle.setIconResource(R.drawable.ic_play);

            int colorOutline = MaterialColors.getColor(
                    binding.getRoot(),
                    com.google.android.material.R.attr.colorOutline);
            binding.statusDot.setBackgroundTintList(ColorStateList.valueOf(colorOutline));
        }
    }

    // 设置事件监听
    private void setupListeners() {
        binding.btnToggle.setOnClickListener(v -> {
            config.enabled = !config.enabled;
            VcamPrefs.saveConfig(this, config);

            if (!config.enabled) {
                RenderedStream.stop();
            }

            updateUiState();
        });

        binding.btnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                ClipData clip = ClipData.newPlainText("stream_url", streamUrl);
                cm.setPrimaryClip(clip);
                Toast.makeText(this, R.string.copied_toast, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // 获取本机局域网地址
    private String getLocalIpAddress() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                LinkProperties lp = cm.getLinkProperties(cm.getActiveNetwork());
                if (lp != null) {
                    for (LinkAddress la : lp.getLinkAddresses()) {
                        InetAddress addr = la.getAddress();
                        if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {}

        return "127.0.0.1";
    }
}
