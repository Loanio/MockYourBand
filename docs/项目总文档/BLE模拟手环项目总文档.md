# BLE 模拟手环项目总文档

本文档整合自项目根目录下的需求、协议和交互复现资料，用于统一说明本 Android 项目的目标、功能范围、BLE 协议、实现路径和验收标准。

## 1. 项目定位

本项目是一个 Android 调试器工具，目标是让智能手机自身模拟成一个 BLE 蓝牙手环设备，对外发出模拟蓝牙广播，并响应目标 APP 的 BLE 指令。

项目第一阶段不追求完整复刻后端业务，而是先保证 App 可以运行、可以广播、可以被扫描连接、可以调试参数并打印日志。

核心能力：

1. 手机作为 BLE Peripheral 设备广播。
2. 暴露手环协议指定的 GATT Service 和 Characteristic。
3. 接收目标 APP 写入的手环命令。
4. 按协议生成模拟回包并通过 Notify 返回。
5. 在 App UI 中调节模拟参数。
6. 在 App UI 中打印调试日志，方便定位广播、连接、写入、回包错误。

## 2. 原始参考资料

项目目录中的资料分工如下：

| 文件 | 作用 |
|---|---|
| `需求文档.txt` | 项目目标、第一阶段功能需求和约束。 |
| `手环协议.md` | BLE UUID、16 字节帧结构、命令和回包解析规则。 |
| `bracelet_app_interaction_repro.md` | 原 APP 与手环交互链路、绑定流程、同步状态机、后端接口和复现实验建议。 |
| `app-service.deobfuscated.js` | 原项目反编译参考源，体积较大，作为逆向溯源材料，不在本文中展开。 |

## 3. 第一阶段目标

第一阶段目标是做出一个完整可运行的 Android App。范围如下：

1. 创建基础 Android 项目结构。
2. 自动请求并配置 BLE 权限。
3. 添加 Nordic BLE Library 依赖。
4. 实现一键 BLE 广播发送。
5. 支持调节广播频率。
6. 支持打印调试日志。
7. 支持在 App 内填写模拟数据字段。
8. UI 输入使用十进制，生成蓝牙帧时转换为字节和十六进制日志。
9. 做出基本 UI。
10. 保证能跑通运行。
11. 代码保持简单，优先可调试和可维护。

## 4. 技术边界

### 4.1 手机模拟手环的 BLE 角色

本项目要模拟的是手环设备，所以 Android 手机必须作为 BLE Peripheral / GATT Server。

这和普通手机 App 扫描并连接手环不同：

| 方向 | 角色 | 说明 |
|---|---|---|
| 普通手环 App | BLE Central | 扫描、连接、写命令、监听 Notify。 |
| 本项目 | BLE Peripheral | 广播、暴露 GATT Server、接收 Write、发送 Notify。 |

因此，核心实现应基于 Android 原生能力：

1. `BluetoothLeAdvertiser`
2. `BluetoothGattServer`
3. `BluetoothGattService`
4. `BluetoothGattCharacteristic`

Nordic BLE Library 可以作为依赖保留，但其主要价值在 BLE Central 连接管理；本项目的 Peripheral/GATT Server 逻辑仍以 Android 原生 API 为主。

### 4.2 设备兼容性

并非所有 Android 手机都支持 BLE Peripheral 广播。运行时需要检查：

1. 手机是否支持蓝牙。
2. 蓝牙是否已开启。
3. `BluetoothLeAdvertiser` 是否可用。
4. Android 12 及以上是否授予 `BLUETOOTH_ADVERTISE` / `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN`。
5. Android 11 及以下是否授予定位权限。

## 5. BLE 协议总览

### 5.1 UUID

| 参数 | 值 |
|---|---|
| 广播过滤 UUID | `0000FFF0-0000-1000-8000-00805F9B34FB` |
| 通信 Service UUID | `6E40FFF0-B5A3-F393-E0A9-E50E24DCCA9E` |
| 写入 Characteristic UUID | `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` |
| Notify Characteristic UUID | `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` |

### 5.2 帧结构

协议帧固定为 16 字节：

```text
Byte 0-14：数据内容
Byte 15：校验和
```

校验和规则：

```text
checksum = 前 15 字节累加和 & 0xFF
```

示例算法：

```kotlin
fun frame(vararg bytes: Int): ByteArray {
    val out = IntArray(16)
    bytes.take(15).forEachIndexed { index, value ->
        out[index] = value and 0xFF
    }
    out[15] = out.take(15).sum() and 0xFF
    return ByteArray(16) { out[it].toByte() }
}
```

## 6. App 到手环的下行命令

下行命令指目标 APP 写入模拟手环 Write Characteristic 的数据。

| CMD | 用途 | 格式摘要 |
|---|---|---|
| `0x01` | 同步时间 | `01 YY MM DD HH mm SS 00... CS` |
| `0x03` | 查询电量 | `03 00... CS` |
| `0x0A` | 读取/写入设备设置 | 读取 `0A 01 ...`，写入 `0A 02 bb cc ...` |
| `0x15` | 查询历史心率 | `15 T3 T2 T1 T0 00... CS` |
| `0x16` | 心率定时开关 | `16 xx yy 00... CS` |
| `0x44` | 查询睡眠数据 | `44 NN 0F 00 96 00... CS` |
| `0x69` | 实时测量心率 | `69 01 ...` 或 `69 04 ...` |

### 6.1 时间同步 `0x01`

```text
01 YY MM DD HH mm SS 00 00 00 00 00 00 00 00 CS
```

字段说明：

1. 年取后两位。
2. YY-SS 使用 BCD 编码。
3. 收到后模拟手环返回 `0x01` ACK。

### 6.2 查询电量 `0x03`

```text
03 00 00 00 00 00 00 00 00 00 00 00 00 00 00 03
```

模拟手环返回 `0x03` 开头帧，电量字段用一个字节表示百分比。

### 6.3 设备设置 `0x0A`

读取：

```text
0A 01 00 x 13 CS
```

写入：

```text
0A 02 bb cc dd ee ff gg hh ii jj 00 x 4 CS
```

字段：

| 字段 | 含义 |
|---|---|
| `bb` | 时间制，`01` 为 12 小时制，`00` 为 24 小时制。 |
| `cc` | 单位，`00` 为公制，`01` 为英制。 |
| `dd-jj` | 其他配置，通常从设备回包缓存后原样透传。 |

### 6.4 历史心率 `0x15`

```text
15 T3 T2 T1 T0 00 x 10 CS
```

`T0-T3` 是目标日期 00:00:00 的 Unix 时间戳，按大端序放入帧中。

### 6.5 心率定时开关 `0x16`

```text
16 xx yy 00 x 12 CS
```

字段：

| 字段 | 值 | 含义 |
|---|---|---|
| `xx` | `01` | 读取状态。 |
| `xx` | `02` | 设置状态。 |
| `yy` | `01` | 开启。 |
| `yy` | `02` | 关闭。 |

### 6.6 睡眠数据 `0x44`

```text
44 NN 0F 00 96 00 x 10 CS
```

字段：

| 字段 | 含义 |
|---|---|
| `NN` | 天偏移，`00` 今天，`01` 昨天。 |
| `0F` | 固定参数 15。 |
| `96` | 固定参数 150。 |

### 6.7 实时心率 `0x69`

```text
69 01 00 x 13 CS
69 04 00 x 13 CS
```

`69 01` 常见于绑定页，`69 04` 常见于运动页轮询。

## 7. 手环到 App 的上行回包

上行回包指模拟手环通过 Notify Characteristic 返回给目标 APP 的数据。

| CMD | 用途 | 解析重点 |
|---|---|---|
| `0x01` | 时间同步 ACK | 收到后目标 APP 通常进入查询电量。 |
| `0x03` | 电量 | 电量值为 1 字节百分比。 |
| `0x0A` | 设置回包 | 缓存 9 个配置字节供后续写入。 |
| `0x15` | 历史心率 | 多包数据，`ff` 结束。 |
| `0x16` | 心率开关 ACK | 读状态或写入确认。 |
| `0x44` | 睡眠数据 | 多包数据，`ff` 或 `c4` 结束。 |
| `0x69` | 实时心率 | 心率 bpm 位于回包数据字节中。 |

### 7.1 电量回包

```text
03 BB 00 ... CS
```

`BB` 表示电量百分比。

### 7.2 历史心率回包

心率数据由多帧组成：

```text
15 01 ...    首帧，包含时间戳和部分心率数据
15 02 ...    普通数据帧
15 17 ...    扩展数据帧
15 FF ...    结束帧
```

每个心率值 1 字节：

1. `0x00` 表示无效。
2. `0xFF` 表示无效。
3. 有效值转成三位十进制字符串，例如 `76 -> 076`。

### 7.3 睡眠回包

睡眠数据由多帧组成：

```text
44 F0 ...    日期帧
44 2A ...    短睡眠帧
44 xx ...    长睡眠帧
44 FF ...    结束帧
C4 xx ...    也可作为结束标记
```

### 7.4 心率开关回包

```text
16 01 01 ...    读到已开启
16 01 02 ...    读到已关闭
16 02 ...       写入 ACK
```

### 7.5 实时心率回包

```text
69 01 00 HR ... CS
```

`HR` 为心率 bpm。

## 8. 原 APP 业务链路

原 APP 的手环功能并不是纯本地 BLE 功能，而是以下组合：

```text
BLE 蓝牙采集 + 本地缓存状态 + 后端绑定/存储 + 页面查询展示
```

核心链路：

```text
APP
  -> 读取本地缓存 bindId / macAddress / stuNum / userId
  -> BLE 扫描指定服务 FFF0
  -> 连接指定 deviceId/macAddress
  -> 向 write characteristic 写入命令
  -> 监听 read characteristic notify
  -> 解析手环返回的十六进制数据
  -> 调用 HTTP 接口上传心率/睡眠数据
  -> 页面再从 HTTP 接口查询统计结果展示
```

## 9. 绑定流程

原 APP 的绑定逻辑：

```text
用户进入未连接设备页
  -> 点击搜索/绑定
  -> 调用 searchBraceletBox({ userId, stuNum })
  -> 后端返回可绑定手环/柜子信息
  -> 打开定位权限
  -> 打开蓝牙适配器
  -> 扫描 FFF0 服务
  -> 发现符合条件的设备
  -> 调用 addBracelet({ model, macAddress, system, stuNum })
  -> 后端返回 bindId/model/macAddress
  -> 写入本地缓存 bindId、model、macAddress
  -> createBLEConnection(deviceId)
  -> 跳转到手环主页
```

需要注意：原代码中的 `macAddress` 实际可能是系统 BLE `deviceId`，在 iOS 上通常不是硬件 MAC，而是系统生成 UUID。

## 10. 已绑定启动流程

原 APP 进入手环主页时，优先检查本地缓存：

```text
读取 macAddress + bindId
  -> 如果任一不存在，跳转未连接设备页
  -> 打开蓝牙
  -> 扫描 FFF0
  -> 找到 deviceId == macAddress 的设备
  -> 连接设备
  -> 查询后端绑定信息
  -> 开始初始化同步
```

## 11. 同步状态机

初始化同步流程：

```text
连接成功
  -> [10%] CMD 01 同步时间
  -> [20%] CMD 03 查电量
  -> [40%] CMD 0A 读设置
  -> [50%] CMD 16 读心率开关
    -> 已开启：继续同步睡眠
    -> 未开启：CMD 16 开启后重读确认
  -> [60%] CMD 44 同步睡眠
  -> [80%] CMD 15 同步历史心率
  -> [100%] 上传服务器 / 同步完成
```

本模拟器第一阶段可以先不实现完整后端上传，只需要能返回对应 BLE Notify，并在日志中确认状态切换即可。

## 12. 后端接口参考

原 APP 涉及接口如下：

| 功能 | 方法 | 路径 |
|---|---|---|
| 查询柜子/可绑定手环 | POST | `/tyapi/mobile/bracelet/lsusearchBraceletBox` |
| 搜索配对 | POST | `/tyapi/mobile/bracelet/searchBracelet` |
| 绑定手环 | POST | `/tyapi/mobile/bracelet/addBracelet` |
| 查询绑定手环 | POST | `/tyapi/mobile/bracelet/getBracelet` |
| 上传心率 | POST | `/tyapi/mobile/heartRate/addHeartRate` |
| 上传睡眠 | POST | `/tyapi/mobile/bracelet/addSleep` |
| 查询日心率 | POST | `/tyapi/mobile/heartRate/getDayHeartRates` |
| 查询周心率 | POST | `/tyapi/mobile/heartRate/getWeekHeartRates` |
| 查询日睡眠 | POST | `/tyapi/mobile/bracelet/getSleep` |
| 查询周睡眠 | POST | `/tyapi/mobile/bracelet/getWeekSleep` |
| 解绑手环 | POST | `/tyapi/mobile/bracelet/allThrowBracelet` |

统一响应格式可模拟为：

```json
{
  "statusCode": 1,
  "message": "success",
  "obj": {}
}
```

## 13. 当前 Android 项目实现建议

### 13.1 模块职责

建议保持简单结构：

| 模块 | 职责 |
|---|---|
| `MainActivity` | Compose UI、权限申请、用户输入、日志显示。 |
| `BleBraceletPeripheral` | 管理 BLE 广播、GATT Server、连接、Write、Notify。 |
| `ProtocolFrames` | 根据命令生成 16 字节协议回包。 |
| `DeviceParameters` | 保存用户输入的模拟电量、心率、步数、广播频率。 |
| `PeripheralState` | 保存运行状态、连接数、最近 RX/TX、心率开关状态。 |

### 13.2 UI 功能

第一阶段 UI 应包括：

1. 广播设备名输入。
2. 电量百分比输入，十进制。
3. 心率 bpm 输入，十进制。
4. 步数输入，十进制。
5. 广播频率滑块。
6. 心率定时开关模拟。
7. 申请 BLE 权限按钮。
8. 开始广播按钮。
9. 停止广播按钮。
10. 更新模拟数据按钮。
11. 调试日志窗口。
12. 最近 RX/TX 十六进制帧展示。

### 13.3 广播频率说明

Android 原生 BLE 广播不能直接设置任意毫秒间隔，只能选择系统预设模式：

| UI 输入区间 | Android 广播模式 |
|---|---|
| `<= 250 ms` | `ADVERTISE_MODE_LOW_LATENCY` |
| `251-1200 ms` | `ADVERTISE_MODE_BALANCED` |
| `> 1200 ms` | `ADVERTISE_MODE_LOW_POWER` |

因此，UI 中的广播频率是调试参数，会映射到系统广播模式，而不是精确周期控制。

## 14. 第一阶段验收标准

### 14.1 App 构建验收

应能执行：

```powershell
.\gradlew.bat :app:assembleDebug
```

并输出：

```text
BUILD SUCCESSFUL
```

### 14.2 权限验收

首次启动 App 后应能申请：

1. Android 12+：`BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT`、`BLUETOOTH_ADVERTISE`。
2. Android 11 及以下：`ACCESS_FINE_LOCATION`。

### 14.3 广播验收

点击开始广播后，日志应出现类似：

```text
GATT Server 已开启
BLE 广播启动成功
```

其他 BLE 扫描工具应能看到设备名和 `FFF0` 服务。

### 14.4 连接和写入验收

目标 APP 或 BLE 调试工具连接后，日志应出现：

```text
设备连接：xx:xx:xx:xx:xx:xx
WRITE <- 03 00 00 ...
NOTIFY -> 03 ...
```

### 14.5 协议回包验收

至少覆盖以下命令：

| CMD | 期望 |
|---|---|
| `01` | 返回时间同步 ACK。 |
| `03` | 返回当前 UI 输入的电量。 |
| `0A` | 返回设置帧。 |
| `15` | 返回模拟历史心率多帧和结束帧。 |
| `16` | 返回心率开关状态或写入 ACK。 |
| `44` | 返回模拟睡眠多帧和结束帧。 |
| `69` | 返回当前 UI 输入的实时心率。 |

## 15. 后续阶段建议

### 阶段 2：完善协议调试能力

1. 支持手动输入任意 16 字节十进制数组。
2. 支持手动输入十六进制帧。
3. 支持选择回包模板。
4. 支持校验和自动计算和错误帧模拟。
5. 支持保存多套模拟场景。

### 阶段 3：模拟完整业务链路

1. 加入 mock 后端。
2. 实现绑定、查询绑定、上传心率、上传睡眠、解绑接口。
3. 实现本地存储状态展示。
4. 做完整同步状态机可视化。

### 阶段 4：自动化测试

1. 为 `ProtocolFrames` 添加单元测试。
2. 为心率和睡眠解析逻辑添加测试样例。
3. 为权限和 BLE 不支持场景添加 UI 提示测试。

## 16. 常见问题

### 16.1 为什么扫描不到模拟手环？

优先检查：

1. 手机蓝牙是否打开。
2. 手机是否支持 BLE Peripheral。
3. Android 12+ 是否授予 `BLUETOOTH_ADVERTISE`。
4. 广播数据是否因为设备名太长导致启动失败。
5. 扫描端是否按 `FFF0` 过滤。

### 16.2 为什么 Nordic BLE Library 没有直接用于广播？

Nordic Android BLE Library 主要解决 Central 端连接和队列管理问题。本项目模拟的是 Peripheral，所以广播和 GATT Server 更适合直接使用 Android 原生 API。

### 16.3 为什么 UI 输入十进制，日志显示十六进制？

需求要求调试参数在 UI 中使用十进制，便于人工输入和理解；BLE 帧本质是字节数组，发送和回包日志使用十六进制更适合协议排查。

### 16.4 为什么 `macAddress` 可能不是硬件 MAC？

很多平台不会暴露真实硬件 MAC，原 APP 变量名叫 `macAddress`，但实际连接使用的可能是 BLE `deviceId`。开发和文档中应把它理解为“用于重连的设备标识”。

## 17. 最小完成定义

第一阶段只要跑通下面链路，即可认为项目具备基础调试价值：

```text
打开 App
  -> 授权 BLE 权限
  -> 填写电量、心率等参数
  -> 点击开始广播
  -> 目标 APP 扫描到 FFF0 设备
  -> 目标 APP 连接 GATT Service
  -> 目标 APP 写入命令
  -> 本 App 日志显示 WRITE
  -> 本 App 返回 Notify
  -> 目标 APP 能解析模拟电量、心率、睡眠或设置数据
```
