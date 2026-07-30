# xCam - Virtual Camera Xposed Module

**xCam** is a modern Xposed module designed to replace the physical camera feed with a virtual video source. It is specifically optimized for Android 9+ and applications using the Camera2 API (including CameraX).

## 🚀 Features

- **Universal Support**: Works with both legacy **Camera1** and modern **Camera2** APIs.
- **Robust Injection**: Uses manual `AssetFileDescriptor` management for stable video streaming via `MediaPlayer`.
- **Internal Storage Import**: Automatically copies selected videos to internal storage to bypass Android URI permission restrictions.
- **Material 3 UI**: Clean, modern management interface with dynamic color support.
- **Auto-Loop**: Virtual video feed automatically loops during injection.

## 🛠 Prerequisites

- A rooted Android device.
- **Xposed Framework** installed (LSPosed recommended).

## 📦 Installation

1. Download and install the **xCam APK**.
2. Open your Xposed manager (e.g., LSPosed) and **enable** the xCam module.
3. Select the **target applications** you want to hook (e.g., a camera or attendance app).
4. Reboot or Force Stop the target application.

## 📖 Usage

1. Open the **xCam Manager** app.
2. Click **Select Video File** and choose an MP4 file.
   - *Wait for the "Video successfully imported" toast.*
3. Open your target application. The camera preview will now display your selected video.

## ⚖️ License

This project is licensed under the **GNU General Public License v3.0**.

```text
Copyright (C) 2026 hazbu

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.
```

See the [LICENSE](LICENSE) file for the full text.

## ⚠️ Disclaimer

This module is for educational and development purposes only. Use it responsibly and at your own risk. The developer is not responsible for any misuse or violations of terms of service of third-party applications.
