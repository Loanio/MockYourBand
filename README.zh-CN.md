# MockYourBand

简体中文 | [English](README.md)


## 这个项目能做什么

你是否曾因校园跑运动手环不断连接失败被折磨半个小时？
你是否曾兴致勃勃打算去锻炼却因为运动手环没电而感到气馁？
你是否因为手环丢失而被迫花好几十块大洋买了个新的？

不用着急，不要害怕，试试看我们这款软件！

这款APP可以把你用不上的安卓手机变成一个模拟运动手环！


你可以用它来：

- 不带真实手环，也能演示手环 App 的配对连接。
- 稳定复现连接失败、数据同步、心率测量等问题。
- 按需要模拟电量、心率、睡眠、设备状态等场景。

下载测试版 App：<https://github.com/Loanio/MockYourBand/releases>

## 技术特性

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

## 项目状态

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

## 技术栈

- Kotlin
- Android Jetpack Compose
- Android Bluetooth LE API
- Nordic Android BLE Library core dependency
- Gradle Kotlin DSL

## 环境要求

- Android Studio 或可用的 JDK/Android SDK 环境。
- Android 设备支持 BLE Peripheral 广播。
- Android 8.0+，项目 `minSdk` 为 26。
- Android 12+ 需要授予 `BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT`、`BLUETOOTH_ADVERTISE`。

注意：并非所有 Android 手机都支持 BLE Peripheral。部分手机可以扫描 BLE 设备，但不能作为 BLE 外设广播。

## 构建

在项目根目录执行：

```powershell
.\gradlew.bat :app:assembleDebug
```

构建成功后 APK 输出路径：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 使用方式

1. 安装并打开 App。
2. 授予 BLE 权限。
3. 填写设备名、电量、心率、步数等模拟参数。
4. 点击“开始广播”。
5. 使用目标 App 或 BLE 调试工具扫描 `FFF0` 服务。
6. 连接设备并向 Write 特征写入协议命令。
7. 在本 App 中查看 RX/TX 调试日志。

## 协议摘要

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

## 免责声明

本项目仅用于学习、调试、协议复现和兼容性测试。请勿用于未授权的设备接入、数据伪造或规避安全机制。


