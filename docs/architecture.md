# vcam — 设计文档（v2，从零重写）

> Linux 桌面实时画面+声音 → 局域网一条流 → 手机 hook 相机/麦克风 → 国内直播 App 开播。
> 本文件是唯一权威设计依据。旧版 xCam 代码已废弃，仅保留本设计要点。

## 目标

在国内直播 App 不做 OBS/RTMP 推流入口的情况下，用手机作为「最后一跳」:手机 hook 相机/麦克风,
把 Linux 推送的画面+声音喂给 App 的直播。

首选目标:抖音 `com.ss.android.ugc.aweme`。

**原则**:只用于直播自己的实时内容(非录播);全程小号测试;音画同流、天然同步;
尽可能避免二次编码;推流即开关(流到=注入,断流=恢复)。

## 已验证的事实(不要再怀疑)

- 测试机:小米 9 SE (grus), SD710, Android 14 / API 34, Magisk kitsune root, Vector/LibXposed 寄生框架。
- **抖音 Camera2 预览 Surface 是横屏 1280×720**,抖音 UI 将其旋转 90° 显示为竖屏。
- 16:9 横屏内容直接塞进该 Surface → 被拉伸/旋转,比例错误。
- **9:16 (720×1280) 竖屏内容推过去 → 抖音正常满屏显示**。
- xCam(LibXposed 模块)能 hook 抖音 Camera2 的 createCaptureSession,用假 Surface 替换预览 Surface —— 注入链路已验证可行。
- 系统 `MediaCodec` 硬解 H264 ✓;多媒体帧能画到任意 Surface ✓。
- Linux ffmpeg n9.0.1 支持 UDP/RTMP/SRT + tee;gsr 输出容器仅 mp4/mkv/flv/webm。

## 关键教训(为什么重写)

1. **xCam 用 ExoPlayer/media3,在抖音进程内加载 GLSL shader 会走抖音的 MiraResourcesWrapper → Resources$NotFoundException → 黑屏。** 必须避开 Media3 的资源加载。
2. **系统 MediaPlayer 稳定不黑屏,但不支持 `udp://`。**
3. IJKPlayer/VLC 支持 `udp://` 且原生 ffmpeg,但要集成 .so。
4. **最优解:用系统 MediaCodec 解码(零外部依赖、不黑屏、可控),自己写 UDP 收流 + EGL 渲染 + hook。**
5. 比例问题:16:9 桌面必须在手机端/推流端变成 9:16 画布,否则抖音必然变形。手机端 EGL 做「旋转90°+FIT」最干净(不二次编码、推流端不变)。

## 目标架构(从零重写,零第三方依赖)

```
com.vcam.live
├── VcamXposed          # LibXposed API 101 入口 (xposed_init)
├── DouyinCameraHook    # hook 抖音 Camera2 createCaptureSession → 拿预览 Surface
├── UdpReceiver         # UDP socket 收 mpegts 字节流 (自写)
├── MediaDecoder        # MediaExtractor(解TS) + MediaCodec(解H264/AAC) [系统内置]
├── EglRenderer         # 自建 EGL: 旋转90°+FIT(保比例/黑边) → 预览 Surface (自写)
├── AudioRoute          # MediaCodec 解 AAC → AudioTrack / 后续 hook AudioRecord 注入
└── VcamPrefs           # 配置: 推流地址 / 模式(横竖屏) / 旋转 / 镜像 / enabled
```

### 数据流

```
Linux gsr 编码 → ffmpeg tee → udp://<手机IP>:9999 (mpegts, 推流端不变)
   ↓ (只写/用的是下面这些)
UdpReceiver 收 TS 字节流
   → MediaExtractor 解出 H264 + AAC track
   → MediaCodec 硬解 H264 → 输出到 OES SurfaceTexture
   → EglRenderer(自建EGL, 旋转90°, FIT保比例加黑边) → 抖音预览 Surface
   → AudioRoute(MediaCodec 解 AAC) → AudioTrack 播放
```

### 为什么用 MediaCodec 而非手写协议栈

- MediaExtractor 免费完成 TS 解复用 + H264 annexb 分帧 + PTS 提取(系统内置,稳定)。
- MediaCodec 免费完成硬解码(系统内置,不黑屏)。
- **自己写**:UDP 收流(简单)、EGL 渲染旋转/FIT(核心价值)、hook 抖音(参考 xCam)。
- 手写完整 TS demux / H264 分帧 / A/V 同步是几千行深水区,易出关键帧花屏/PTS 错,不必要。

## hook 参考要点(来自 xCam,已验证)

- Hook `android.hardware.camera2.impl.CameraDeviceImpl#createCaptureSession(SessionConfiguration)`。
- 在 `beforeHookedMethod` 里,把 `sessionConfiguration.outputConfigurations` 的 surface 替换成自己的假 Surface(`SurfaceTexture` 的 Surface),把抖音要的预览 surface 记录为 targetSurface。
- 抖音以为在拍相机,实际 surface 收不到;我们把自己 EGL 渲染到 targetSurface。
- 参考 xCam `Camera2Hook.java` 的 `hookModernHijack` / `hookSurgicalDiverter`(OutputConfiguration 置换)。
- 注意:hook 只活在抖音进程内存,杀抖音进程=彻底还原,零残留(满足「取消 hook 恢复」)。

## 推流端(Linux,现有脚本,不改)

- `~/Code/arch-post-install/dotfiles/.local/bin/livestream-service` — gsr 一次编码 → tee 扇出。
- 手机目标:`{"name":"local","server":"udp://192.168.10.212:9999","key":""}`。
- `enabled` 过滤已在 service 实现:`platforms_from_items` 跳过 `enabled:false`。
- **推流端不改**(不加 pad/scale)——比例由手机端 EGL 处理,避免二次编码。

## 测试环境

- Linux:ffmpeg n9.0.1(--enable-libsrt), gpu-screen-recorder 6.0.1。
- Android:JDK 21 / Gradle 9.7 / compileSdk 36,37。
- 手机:adb (298a3bbc / 192.168.10.212)。
- 推流冒烟测试(测试流):
  `ffmpeg -re -stream_loop -1 -i test.mp4 -c copy -f mpegts "udp://192.168.10.212:9999?pkt_size=1316"`
  (注意:本工具环境杀后台进程,推流需在用户自己的终端/脚本跑。)

## 构建

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.ss.android.ugc.aweme
```

## 里程碑

- [ ] M1: 干净模块骨架 + LibXposed 入口 + hook 抖音拿预览 Surface(能替换成假 Surface)。
- [ ] M2: UdpReceiver + MediaExtractor + MediaCodec 解出 H264,输出到 SurfaceTexture。
- [ ] M3: EglRenderer 把帧画到抖音预览 Surface(先不转,验证不黑屏)。
- [ ] M4: 旋转90°+FIT 比例正确(16:9 桌面 → 竖屏黑边)。
- [ ] M5: 音频(AAC → AudioTrack 播放)。
- [ ] M6: 断流自动恢复 + UI(开始/停止 + 横竖屏模式)。
