# vcam 设计文档

Linux 与 PC 桌面实时画面通过局域网 SRT 推送至手机，手机 hook 相机预览 Surface 实现直播 App 开播。
画面排版、缩放、裁剪与旋转由电脑端全权负责，手机端零自定义渲染，直通硬解直出。
音频由手机原生物理麦克风直接拾音，纯净无干扰。

## 目标

在国内直播 App 不做 OBS 或 RTMP 推流入口的情况下，用手机作为最后一跳，将电脑端推送的画面直接喂给 App 的直播。

首选目标: 抖音、快手、小红书、微信等。

核心原则:
1. 画面安排由电脑端处理：分辨率、旋转角度、比例适配均在电脑端完成，手机端不做多余变换。
2. 手机端零自定义渲染：完全去除 OpenGL ES 与 EGL 着色器重采样层，使用系统 MediaCodec 硬解直出至相机预览 Surface，实现零拷贝、原生画质与零额外渲染延迟。
3. 麦克风原生拾音：音频完全交给手机物理麦克风直接拾音，避免多重音频驱动冲突与回声问题。
4. 极低延迟与高容错：采用 SRT 传输，推流即开播，断流自动重连恢复。
5. 动态旁路开关：提供 START 与 STOP 总控开关，STOP 时完全放行系统原生相机，扫码与直播无缝切换。

## 系统架构

com.vcam.live
├── VcamXposed            # LibXposed API 102 入口
├── CameraSurfaceHijack   # 通用 hook 相机会话与 1080p 提质
├── SrtReceiver           # SRT 收流服务
├── MpegTsDemuxer         # 传输流视频解复用器
├── MediaDecoder          # 视频硬件解码器直接绑定目标表面
├── RenderedStream        # 视频解码与推流管道编排器
├── ControlActivity       # 极简控制中心界面
└── VcamPrefs             # 跨进程配置中心

## 构建与测试

编译 APK:
./gradlew :app:assembleDebug

安装到手机:
adb install -r app/build/outputs/apk/debug/app-debug.apk
