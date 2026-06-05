# MockYourBand

[简体中文](README.zh-CN.md) | English

MockYourBand is an Android BLE wristband simulator and debugging tool. It turns an Android phone into a simulated BLE wristband peripheral, advertises a target service UUID, accepts GATT write commands, and sends mocked notification packets back to the client app.

This project is useful for debugging, reverse-engineering reproduction, protocol validation, and app integration testing.

## Features

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

## Project Status

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

## Tech Stack

- Kotlin
- Android Jetpack Compose
- Android Bluetooth LE API
- Nordic Android BLE Library core dependency
- Gradle Kotlin DSL

## Requirements

- Android Studio or a working JDK/Android SDK setup.
- An Android device that supports BLE Peripheral advertising.
- Android 8.0+, with project `minSdk` set to 26.
- Android 12+ requires `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, and `BLUETOOTH_ADVERTISE` permissions.

Note: Not every Android phone supports BLE Peripheral mode. Some devices can scan BLE peripherals but cannot advertise as one.

## Build

Run this from the project root:

```powershell
.\gradlew.bat :app:assembleDebug
```

The debug APK will be generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Usage

1. Install and open the app.
2. Grant BLE permissions.
3. Enter the simulated device name, battery, heart rate, steps, and other values.
4. Tap "Start Advertising".
5. Use the target app or a BLE debugging tool to scan for the `FFF0` service.
6. Connect to the device and write protocol commands to the Write characteristic.
7. Check RX/TX packets in the in-app debug log.

## Protocol Summary

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

## Repository Notes

The original reverse-engineering reference files in the repository root are ignored by Git and kept local only. The consolidated documentation is stored under `docs/项目总文档/`.

## Disclaimer

This project is intended only for learning, debugging, protocol reproduction, and compatibility testing. Do not use it for unauthorized device access, data spoofing, or bypassing security mechanisms.
