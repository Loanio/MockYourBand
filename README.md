# MockYourBand

[中文](#中文) | [English](#english)

---

## 中文

MockYourBand 是一个 Android BLE 手环模拟器调试工具。它让一台 Android 手机模拟成 BLE 手环外设，对外广播指定服务 UUID，并通过 GATT Server 接收写入命令、返回模拟 Notify 数据。

本项目适合用于调试、逆向复现、协议验证和 App 对接测试。

### 功能特性

- 手机模拟 BLE Peripheral 手环设备。
- 广播过滤 UUID：`0000FFF0-0000-1000-8000-00805F9B34FB`。
- 暴露通信服务：`6E40FFF0-B5A3-F393-E0A9-E50E24DCCA9E`。
- 支持 Write 特征：`6E400002-B5A3-F393-E0A9-E50E24DCCA9E`。
- 支持 Notify 特征：`6E400003-B5A3-F393-E0A9-E50E24DCCA9E`。
- 自动申请 Android BLE 运行时权限。
- 一键开始和停止 BLE 广播。
- 支持调节广播模式参数。
- 支持输入模拟电量、心率、步数等十进制参数。
- 自动生成 16 字节协议帧和校验和。
- 支持常见手环命令模拟回包：`01`、`03`、`0A`、`15`、`16`、`44`、`69`。
- 内置调试日志，显示 RX/TX 十六进制帧。

### 项目状态

当前阶段：第一阶段可运行版本。

已完成：

- Android 基础项目结构。
- BLE 权限配置。
- BLE Peripheral 广播。
- GATT Server。
- 协议帧生成。
- Compose 调试 UI。
- Gradle Wrapper。
- 项目总文档。

### 技术栈

- Kotlin
- Android Jetpack Compose
- Android Bluetooth LE API
- Nordic Android BLE Library core dependency
- Gradle Kotlin DSL

### 环境要求

- Android Studio 或可用的 JDK/Android SDK 环境。
- Android 设备支持 BLE Peripheral 广播。
- Android 8.0+，项目 `minSdk` 为 26。
- Android 12+ 需要授予 `BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT`、`BLUETOOTH_ADVERTISE`。

注意：并非所有 Android 手机都支持 BLE Peripheral。部分手机可以扫描 BLE 设备，但不能作为 BLE 外设广播。

### 构建

在项目根目录执行：

```powershell
.\gradlew.bat :app:assembleDebug
```

构建成功后 APK 输出路径：

```text
app/build/outputs/apk/debug/app-debug.apk
```

### 使用方式

1. 安装并打开 App。
2. 授予 BLE 权限。
3. 填写设备名、电量、心率、步数等模拟参数。
4. 点击“开始广播”。
5. 使用目标 App 或 BLE 调试工具扫描 `FFF0` 服务。
6. 连接设备并向 Write 特征写入协议命令。
7. 在本 App 中查看 RX/TX 调试日志。

### 协议摘要

协议帧固定为 16 字节：

```text
Byte 0-14：数据内容
Byte 15：前 15 字节累加和 & 0xFF
```

核心 UUID：

| 类型 | UUID |
|---|---|
| 广播过滤 UUID | `0000FFF0-0000-1000-8000-00805F9B34FB` |
| GATT Service | `6E40FFF0-B5A3-F393-E0A9-E50E24DCCA9E` |
| Write Characteristic | `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` |
| Notify Characteristic | `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` |

更多细节见：[BLE 模拟手环项目总文档](docs/项目总文档/BLE模拟手环项目总文档.md)。

### 仓库说明

根目录中的原始逆向参考文件已加入 `.gitignore`，不会提交到仓库。整理后的项目总文档保留在 `docs/项目总文档/` 中。

### 免责声明

本项目仅用于学习、调试、协议复现和兼容性测试。请勿用于未授权的设备接入、数据伪造或规避安全机制。

[Back to top](#mockyourband)

---

## English

MockYourBand is an Android BLE wristband simulator and debugging tool. It turns an Android phone into a simulated BLE wristband peripheral, advertises a target service UUID, accepts GATT write commands, and sends mocked notification packets back to the client app.

This project is useful for debugging, reverse-engineering reproduction, protocol validation, and app integration testing.

### Features

- Simulates a BLE wristband as an Android BLE Peripheral.
- Advertising filter UUID: `0000FFF0-0000-1000-8000-00805F9B34FB`.
- Exposes communication service: `6E40FFF0-B5A3-F393-E0A9-E50E24DCCA9E`.
- Supports Write characteristic: `6E400002-B5A3-F393-E0A9-E50E24DCCA9E`.
- Supports Notify characteristic: `6E400003-B5A3-F393-E0A9-E50E24DCCA9E`.
- Requests Android BLE runtime permissions automatically.
- Starts and stops BLE advertising with one tap.
- Supports adjustable advertising mode parameters.
- Supports decimal input for mocked battery, heart rate, steps, and other data.
- Generates 16-byte protocol frames with checksum.
- Supports mocked responses for common commands: `01`, `03`, `0A`, `15`, `16`, `44`, `69`.
- Includes an in-app debug log showing RX/TX frames in hexadecimal.

### Project Status

Current stage: runnable first-stage version.

Completed:

- Basic Android project structure.
- BLE permission setup.
- BLE Peripheral advertising.
- GATT Server.
- Protocol frame generation.
- Compose debugging UI.
- Gradle Wrapper.
- Consolidated project documentation.

### Tech Stack

- Kotlin
- Android Jetpack Compose
- Android Bluetooth LE API
- Nordic Android BLE Library core dependency
- Gradle Kotlin DSL

### Requirements

- Android Studio or a working JDK/Android SDK setup.
- An Android device that supports BLE Peripheral advertising.
- Android 8.0+, with project `minSdk` set to 26.
- Android 12+ requires `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, and `BLUETOOTH_ADVERTISE` permissions.

Note: Not every Android phone supports BLE Peripheral mode. Some devices can scan BLE peripherals but cannot advertise as one.

### Build

Run this from the project root:

```powershell
.\gradlew.bat :app:assembleDebug
```

The debug APK will be generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### Usage

1. Install and open the app.
2. Grant BLE permissions.
3. Enter the simulated device name, battery, heart rate, steps, and other values.
4. Tap “Start Advertising”.
5. Use the target app or a BLE debugging tool to scan for the `FFF0` service.
6. Connect to the device and write protocol commands to the Write characteristic.
7. Check RX/TX packets in the in-app debug log.

### Protocol Summary

Each protocol frame is fixed to 16 bytes:

```text
Byte 0-14: payload
Byte 15: sum(Byte 0-14) & 0xFF
```

Core UUIDs:

| Type | UUID |
|---|---|
| Advertising Filter UUID | `0000FFF0-0000-1000-8000-00805F9B34FB` |
| GATT Service | `6E40FFF0-B5A3-F393-E0A9-E50E24DCCA9E` |
| Write Characteristic | `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` |
| Notify Characteristic | `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` |

For details, see: [Consolidated BLE Band Project Documentation](docs/项目总文档/BLE模拟手环项目总文档.md).

### Repository Notes

The original reverse-engineering reference files in the repository root are ignored by Git and kept local only. The consolidated documentation is stored under `docs/项目总文档/`.

### Disclaimer

This project is intended only for learning, debugging, protocol reproduction, and compatibility testing. Do not use it for unauthorized device access, data spoofing, or bypassing security mechanisms.

[Back to top](#mockyourband)
