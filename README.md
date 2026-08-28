# vcam

> Linux 与 PC 桌面实时画面 → 局域网 SRT 传输 → 手机硬件直通 Camera2 预览 Surface → 抖音、快手、微信等直播 App 开播。

[![Release](https://img.shields.io/github/v/release/xifan2333/vcam?style=flat-square)](https://github.com/xifan2333/vcam/releases)
[![License](https://img.shields.io/badge/license-MIT-blue.svg?style=flat-square)](LICENSE)

---

## 核心特性

- **零渲染直通（Zero Render Overhead）**
  - 画面排版、缩放、裁剪与旋转由电脑端（OBS / ffmpeg / gpu-screen-recorder）完成；
  - 手机端 `MediaCodec` 硬件解码器将 H.264 帧直接直出写入相机预览 Surface，实现**零拷贝、1:1 原画质与超低延迟**。

- **动态旁路开关（START / STOP 一键切换）**
  - 控制中心提供一键 **START / STOP** 切换；
  - 处于 **STOP** 状态时，Hook 层完整透明放行系统原生物理相机，日常扫码、人脸识别直接可用，无需在框架管理器中反复切换作用域。

- **低延迟可靠传输（SRT / MPEG-TS）**
  - 采用 SRT 工业级低延迟协议传输，在网络波动中保持稳定流畅；
  - 手机端常驻监听 9999 端口，电脑端推流即开播，连接自动稳定维持。

- **极简 Material 3 控制中心**
  - 自动识别当前手机局域网 IP 地址，直接生成推流地址并支持一键复制。

---

## 系统架构与数据流

```
电脑端 (OBS / ffmpeg / gpu-screen-recorder 完成画面排版)
   ↓ SRT (MPEG-TS 封装 H.264 视频流)
手机端 SrtReceiver (监听 0.0.0.0:9999)
   ↓ TS 字节流
MpegTsDemuxer (提取 H.264 NALU 帧)
   ↓ H.264 Annex-B
MediaCodec 硬件解码器
   ↓ (直接直出，无中间 GL 纹理重绘)
目标相机预览 Surface (抖音 / 微信 / 快手等 App 实体画面)
```

---

## 电脑端推流指南

### 1. 使用 ffmpeg 推流
```bash
# 推送视频文件或测试流
ffmpeg -re -stream_loop -1 -i video.mp4 -c:v copy -f mpegts "srt://<手机IP>:9999"

# 捕获 Linux 桌面并直推
ffmpeg -f kmsgrab -i - -vf 'hwmap=derive_device=vaapi,scale_vaapi=w=1080:h=1920' -c:v h264_vaapi -b:v 6000k -f mpegts "srt://<手机IP>:9999"
```

### 2. 使用 gpu-screen-recorder + ffmpeg (超低延迟)
```bash
gpu-screen-recorder -w screen -c flv -f 60 -q 6000 -k h264 -o - | \
ffmpeg -hide_banner -loglevel warning -i - -c:v copy -f mpegts "srt://<手机IP>:9999"
```

### 3. 使用 OBS Studio 推流
- **输出模式**：自定义流媒体服务器
- **URL**：`srt://<手机IP>:9999`
- **视频编码器**：H.264 / AVC (NVENC 或 QuickSync 或 x264)
- **画布与输出分辨率**：根据直播需求设置（如竖屏 1080x1920 或横屏 1920x1080）

---

## 手机端使用方法

1. 在手机上安装并激活 **Vector** 或 **LSPosed** 框架；
2. 安装 `vcam.apk`，在框架作用域列表中勾选需要开播的 App（如抖音 `com.ss.android.ugc.aweme`、微信 `com.tencent.mm` 等）；
3. 打开 **vcam** 控制中心，点击 **START** 启动服务；
4. 点击「复制地址」，获取 `srt://<手机IP>:9999`；
5. 在电脑端启动推流；
6. 打开直播 App 开播，画面将被电脑端画面完整覆盖；
7. 当需要使用物理相机（如微信扫码）时，回到 vcam 控制中心点击 **STOP** 即可，无需重启 App。

---

## 如何收录至 Vector / LSPosed 在线模块仓库？

本模块已收录至官方在线仓库 [Xposed-Modules-Repo/com.vcam.live](https://github.com/Xposed-Modules-Repo/com.vcam.live)，可在 Vector / LSPosed 客户端「在线仓库」中直接搜索 `vcam` 并一键安装、自动更新。

> 模块已上架至 [modules.lsposed.org](https://modules.lsposed.org/module/com.vcam.live/)，请确认你已订阅官方在线仓库后刷新。

### 收录流程

将本项目提交至 **[Xposed-Modules-Repo](https://github.com/Xposed-Modules-Repo/submission)**，即可在 Vector / LSPosed 客户端的「在线仓库」中直接搜索、一键安装与在线自动更新，后续更新无需每次手动 ADB 安装：

1. 打开 [Xposed-Modules-Repo Submission](https://github.com/Xposed-Modules-Repo/submission/issues/new/choose)；
2. 新建 Issue，标题命名为：`[submission] com.vcam.live`；
3. 提交后，GitHub 机器人会自动为该包名创建专用模块仓库并邀请你成为管理员；
4. 之后在 GitHub 发布附带 APK 文件的 Release，Vector / LSPosed 在线仓库会在 5 分钟内自动索引并上架更新。

---

## 本地编译构建

```bash
# 编译 Debug APK
./gradlew :app:assembleDebug

# 产物输出路径
# app/build/outputs/apk/debug/app-debug.apk

# 安装至已连接设备
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 开源协议

本项目基于 [MIT License](LICENSE) 开源。
