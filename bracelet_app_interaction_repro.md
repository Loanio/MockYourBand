# APP 与手环 Bracelet 交互逻辑复现实验参考文档

> 目标：用一个最小可运行实验，复现原 APP 与手环之间的核心交互逻辑：绑定、BLE 扫描连接、写命令、监听回包、解析心率/睡眠/电量/设置、上传后端、解绑。  
> 使用场景：把本文档作为 AI vibe coding 的参考，让 AI 按此生成 demo、小程序/uni-app 页面、Node 模拟器或测试脚本。

---

## 0. 总体结论

原 APP 的手环功能不是单纯的蓝牙本地功能，而是：

```text
BLE 蓝牙采集 + 本地缓存状态 + 后端绑定/存储 + 页面查询展示
```

核心链路：

```text
APP
  ↓
读取本地缓存 bindId / macAddress / stuNum / userId
  ↓
BLE 扫描指定服务 FFF0
  ↓
连接指定 deviceId/macAddress
  ↓
向 write characteristic 写入命令
  ↓
监听 read characteristic notify
  ↓
解析手环返回的十六进制数据
  ↓
调用 HTTP 接口上传心率/睡眠数据
  ↓
页面再从 HTTP 接口查询统计结果展示
```

---

## 1. 要复现的功能范围

建议分 5 个实验阶段做，不要一口气全做。

### 阶段 A：模拟后端接口

复现以下接口即可：

| 功能 | 方法 | 路径 |
|---|---|---|
| 查询柜子/可绑定手环 | POST | `/tyapi/mobile/bracelet/lsusearchBraceletBox` |
| 绑定手环 | POST | `/tyapi/mobile/bracelet/addBracelet` |
| 查询绑定手环 | POST | `/tyapi/mobile/bracelet/getBracelet` |
| 上传心率 | POST | `/tyapi/mobile/heartRate/addHeartRate` |
| 上传睡眠 | POST | `/tyapi/mobile/bracelet/addSleep` |
| 查询日心率 | POST | `/tyapi/mobile/heartRate/getDayHeartRates` |
| 查询周心率 | POST | `/tyapi/mobile/heartRate/getWeekHeartRates` |
| 查询日睡眠 | POST | `/tyapi/mobile/bracelet/getSleep` |
| 查询周睡眠 | POST | `/tyapi/mobile/bracelet/getWeekSleep` |
| 解绑手环 | POST | `/tyapi/mobile/bracelet/allThrowBracelet` |

统一响应格式建议：

```json
{
  "statusCode": 1,
  "message": "success",
  "obj": {}
}
```

---

### 阶段 B：模拟 APP 本地状态

原 APP 依赖 `uni.getStorageSync / uni.setStorageSync` 保存绑定状态。

需要复现的本地字段：

| key | 含义 |
|---|---|
| `id` | 当前用户 ID |
| `stuNum` | 学号/用户编号 |
| `bindId` | 后端返回的手环绑定 ID |
| `macAddress` | APP 保存的蓝牙设备 ID，代码里名字叫 macAddress |
| `model` | 手环型号 |
| `type` | 用户身份，比如学生 |

建议实验里实现一个简单 storage：

```js
const storage = new Map();

function setStorageSync(key, value) {
  storage.set(key, value);
}

function getStorageSync(key) {
  return storage.get(key);
}

function removeStorageSync(key) {
  storage.delete(key);
}
```

---

### 阶段 C：模拟 BLE 设备

需要模拟一个手环设备，具备以下能力：

1. 被 APP 扫描到。
2. 暴露服务 UUID。
3. 支持写入 characteristic。
4. 支持 notify 返回数据。
5. 根据 APP 写入的命令返回不同数据。

#### 关键 BLE UUID

```text
广播/扫描服务：
0000FFF0-0000-1000-8000-00805F9B34FB

实际通信 service_uuid：
6E40FFF0-B5A3-F393-E0A9-E50E24DCCA9E

写入 write_uuid：
6E400002-B5A3-F393-E0A9-E50E24DCCA9E

通知 read_uuid：
6E400003-B5A3-F393-E0A9-E50E24DCCA9E
```

#### 设备对象示例

```js
const fakeBraceletDevice = {
  name: "Bracelet-001",
  deviceId: "FAKE_MAC_001",
  localName: "Bracelet-001",
  advertisServiceUUIDs: [
    "0000FFF0-0000-1000-8000-00805F9B34FB"
  ]
};
```

---

## 2. 原 APP 的绑定流程

### 2.1 业务流程

```text
用户进入未连接设备页
  ↓
点击搜索/绑定
  ↓
调用 searchBraceletBox({ userId, stuNum })
  ↓
后端返回可绑定手环/柜子信息
  ↓
打开定位权限
  ↓
打开蓝牙适配器
  ↓
扫描 FFF0 服务
  ↓
发现符合条件的设备
  ↓
调用 addBracelet({ model, macAddress, system, stuNum })
  ↓
后端返回 bindId/model/macAddress
  ↓
写入本地缓存 bindId、model、macAddress
  ↓
createBLEConnection(deviceId)
  ↓
跳转到手环主页
```

### 2.2 复现实验代码结构

```js
async function searchAndBindBracelet() {
  const userId = getStorageSync("id");
  const stuNum = getStorageSync("stuNum");

  const boxResp = await api.searchBraceletBox({ userId, stuNum });
  assert(boxResp.statusCode === 1);

  await ble.openBluetoothAdapter();

  const devices = await ble.startBluetoothDevicesDiscovery({
    services: ["FFF0"]
  });

  const target = devices.find(d =>
    d.advertisServiceUUIDs?.includes("0000FFF0-0000-1000-8000-00805F9B34FB")
  );

  if (!target) throw new Error("未发现手环");

  const bindResp = await api.addBracelet({
    model: target.name,
    macAddress: target.deviceId,
    system: "Android",
    stuNum
  });

  setStorageSync("bindId", bindResp.obj.bindId);
  setStorageSync("model", bindResp.obj.model);
  setStorageSync("macAddress", bindResp.obj.macAddress || target.deviceId);

  await ble.createBLEConnection(target.deviceId);

  return bindResp.obj;
}
```

---

## 3. 已绑定后的启动流程

原 APP 进入手环主页时，优先检查本地缓存。

### 3.1 判断逻辑

```text
读取 macAddress + bindId
  ↓
如果任一不存在：
    跳转到未连接设备页
否则：
    打开蓝牙
    扫描 FFF0
    找到 deviceId == macAddress 的设备
    连接设备
    查询后端绑定信息
    开始初始化同步
```

### 3.2 复现实验代码

```js
async function enterBraceletMainPage() {
  const macAddress = getStorageSync("macAddress");
  const bindId = getStorageSync("bindId");

  if (!macAddress || !bindId) {
    return { page: "/pages/bracelet/index", reason: "未绑定" };
  }

  await ble.openBluetoothAdapter();

  const devices = await ble.startBluetoothDevicesDiscovery({
    services: ["FFF0"]
  });

  const target = devices.find(d => d.deviceId === macAddress);

  if (!target) {
    return { connected: false, reason: "未扫描到已绑定手环" };
  }

  await ble.createBLEConnection(target.deviceId);

  const info = await api.getBracelet({
    bindId,
    stuNum: getStorageSync("stuNum")
  });

  return {
    connected: true,
    braceletInfo: info.obj
  };
}
```

---

## 4. BLE 指令协议

### 4.1 命令通用格式

原 APP 生成的命令一般是 16 字节：

```text
前 15 字节：命令内容
第 16 字节：前 15 字节累加和 & 0xff
```

复现函数：

```js
function appendChecksum(bytes15) {
  let sum = 0;
  for (let i = 0; i < 15; i++) {
    sum += bytes15[i] || 0;
  }
  return [...bytes15, sum & 0xff];
}
```

### 4.2 常见命令

| 命令 | 用途 | 首字节 |
|---|---|---|
| `settingTime()` | 同步 APP 时间到手环 | `0x01` |
| `getElectric()` | 获取电量 | `0x03` |
| `getSetting()` | 获取手环设置 | `0x0a` |
| `settingFormat()` | 修改手环设置 | `0x0a` |
| `heartRate(day)` | 拉取历史心率 | `0x15` |
| `switchRate(type, value)` | 查询/开关定时心率 | `0x16` |
| `sleepData(day)` | 拉取睡眠数据 | `0x44` |
| `measureHeartRate()` | 即时心率测量 | `0x69` |

### 4.3 示例命令生成器

```js
function settingTime(date = new Date()) {
  const year = date.getFullYear() - 2000;
  const month = date.getMonth() + 1;
  const day = date.getDate();
  const hour = date.getHours();
  const minute = date.getMinutes();
  const second = date.getSeconds();

  return appendChecksum([
    0x01, year, month, day, hour, minute, second,
    0, 0, 0, 0, 0, 0, 0, 0
  ]);
}

function getElectric() {
  return appendChecksum([
    0x03, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
  ]);
}

function getSetting() {
  return appendChecksum([
    0x0a, 0x01, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
  ]);
}

function heartRate(dayOffset = 0) {
  return appendChecksum([
    0x15, dayOffset, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
  ]);
}

function sleepData(dayOffset = 0) {
  return appendChecksum([
    0x44, dayOffset, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
  ]);
}

function measureHeartRate() {
  return appendChecksum([
    0x69, 0x01, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
  ]);
}
```

---

## 5. BLE 写入与监听模型

### 5.1 APP 侧抽象

```js
const SERVICE_UUID = "6E40FFF0-B5A3-F393-E0A9-E50E24DCCA9E";
const WRITE_UUID = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E";
const READ_UUID = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E";

async function writeBraceletCommand(deviceId, bytes) {
  await ble.writeBLECharacteristicValue({
    deviceId,
    serviceId: SERVICE_UUID,
    characteristicId: WRITE_UUID,
    value: Uint8Array.from(bytes).buffer
  });
}

function listenBraceletNotify(onHex) {
  ble.onBLECharacteristicValueChange((res) => {
    const hex = ab2hex(res.value);
    onHex(hex);
  });
}

function ab2hex(buffer) {
  return Array.from(new Uint8Array(buffer))
    .map(b => b.toString(16).padStart(2, "0"))
    .join("");
}
```

### 5.2 手环模拟器侧

```js
class FakeBracelet {
  constructor() {
    this.listeners = [];
  }

  onNotify(cb) {
    this.listeners.push(cb);
  }

  notify(hex) {
    for (const cb of this.listeners) cb(hex);
  }

  write(bytes) {
    const cmd = bytes[0];

    if (cmd === 0x01) {
      this.notify("01010000000000000000000000000000");
    }

    if (cmd === 0x03) {
      // 假设第 3 字节表示电量 80
      this.notify("03015000000000000000000000000000");
    }

    if (cmd === 0x0a) {
      this.notify("0a010101000000000000000000000000");
    }

    if (cmd === 0x16) {
      this.notify("16010100000000000000000000000000");
    }

    if (cmd === 0x15) {
      fakeHeartRatePackets().forEach(p => this.notify(p));
      this.notify("15ff0000000000000000000000000000");
    }

    if (cmd === 0x44) {
      fakeSleepPackets().forEach(p => this.notify(p));
      this.notify("44ff0000000000000000000000000000");
    }

    if (cmd === 0x69) {
      // 即时心率 76
      this.notify("69014c00000000000000000000000000");
    }
  }
}
```

---

## 6. 初始化同步流程

这是最重要的实验。

### 6.1 原 APP 逻辑

```text
connect()
  ↓
getBracelet({ bindId, stuNum })
  ↓
init()
  ↓
setTime()
  ↓
收到 01 返回
  ↓
electric()
  ↓
收到 03 返回
  ↓
getMySetting()
  ↓
收到 0a 返回
  ↓
readSwitchRate()
  ↓
收到 16 返回
  ↓
如果定时心率未开启：
    onoffRate(2, 1)
  ↓
syncSleep()
  ↓
syncHeartRate()
  ↓
同步完成
```

### 6.2 建议实现成状态机

```js
const SyncStep = {
  SET_TIME: "SET_TIME",
  GET_ELECTRIC: "GET_ELECTRIC",
  GET_SETTING: "GET_SETTING",
  GET_SWITCH_RATE: "GET_SWITCH_RATE",
  ENABLE_SWITCH_RATE: "ENABLE_SWITCH_RATE",
  SYNC_SLEEP: "SYNC_SLEEP",
  SYNC_HEART_RATE: "SYNC_HEART_RATE",
  DONE: "DONE"
};

class BraceletSyncController {
  constructor({ deviceId, bindId, api, ble }) {
    this.deviceId = deviceId;
    this.bindId = bindId;
    this.api = api;
    this.ble = ble;
    this.step = SyncStep.SET_TIME;
    this.heartPackets = [];
    this.sleepPackets = [];
  }

  async start() {
    listenBraceletNotify(hex => this.handleNotify(hex));
    await writeBraceletCommand(this.deviceId, settingTime());
  }

  async handleNotify(hex) {
    const type = hex.slice(0, 2);

    if (type === "01" && this.step === SyncStep.SET_TIME) {
      this.step = SyncStep.GET_ELECTRIC;
      return writeBraceletCommand(this.deviceId, getElectric());
    }

    if (type === "03" && this.step === SyncStep.GET_ELECTRIC) {
      const battery = parseInt(hex.slice(4, 6), 16);
      console.log("电量", battery);
      this.step = SyncStep.GET_SETTING;
      return writeBraceletCommand(this.deviceId, getSetting());
    }

    if (type === "0a" && this.step === SyncStep.GET_SETTING) {
      console.log("设置返回", hex);
      this.step = SyncStep.GET_SWITCH_RATE;
      return writeBraceletCommand(this.deviceId, switchRateRead());
    }

    if (type === "16" && this.step === SyncStep.GET_SWITCH_RATE) {
      console.log("定时心率状态", hex);
      this.step = SyncStep.SYNC_SLEEP;
      return writeBraceletCommand(this.deviceId, sleepData(0));
    }

    if (type === "44" && this.step === SyncStep.SYNC_SLEEP) {
      return this.handleSleepPacket(hex);
    }

    if (type === "15" && this.step === SyncStep.SYNC_HEART_RATE) {
      return this.handleHeartPacket(hex);
    }
  }

  async handleSleepPacket(hex) {
    if (hex.slice(2, 4) === "ff") {
      const sleep = parseSleepPackets(this.sleepPackets);
      await this.api.addSleepData({
        bindId: this.bindId,
        time: sleep.time,
        data: sleep.sleepData
      });

      this.step = SyncStep.SYNC_HEART_RATE;
      return writeBraceletCommand(this.deviceId, heartRate(0));
    }

    this.sleepPackets.push(hex);
  }

  async handleHeartPacket(hex) {
    if (hex.slice(2, 4) === "ff") {
      const heart = parseHeartRatePackets(this.heartPackets);
      await this.api.addHeartRate({
        bindId: this.bindId,
        timestamp: heart.time,
        data: heart.heartRateData
      });

      this.step = SyncStep.DONE;
      console.log("同步完成");
      return;
    }

    this.heartPackets.push(hex);
  }
}
```

---

## 7. 心率数据复现

### 7.1 数据流

```text
APP 写入 heartRate(day)
  ↓
手环连续 notify 多包 15 开头数据
  ↓
APP 收集 heartPackets
  ↓
遇到 15ff 或包数达到预期
  ↓
parseHeartRatePackets()
  ↓
api.addHeartRate({ bindId, timestamp, data })
```

### 7.2 模拟心率包

原逻辑中，历史心率会把多个包拼成一个长字符串，每个心率值转成 3 位数字：

```text
76  -> "076"
88  -> "088"
0   -> "000"
255 -> "000"
101 -> "101"
```

模拟包：

```js
function fakeHeartRatePackets() {
  return [
    // 第一包：15 01 + 时间戳 + 部分心率
    "1501000000004c50585aff0000000000",
    // 后续包
    "15024d4e4f505152535455565758595a",
    "15035b5c5d5e5f606162636465666768"
  ];
}
```

### 7.3 解析函数

```js
function parseHeartRatePackets(packets) {
  if (!packets.length) return null;

  const result = {
    time: 0,
    heartRateData: ""
  };

  for (const hex of packets) {
    const seq = hex.slice(2, 4);

    if (seq === "01") {
      // 原 APP 会从 4~12 取小端时间戳
      const timeHexLE = hex.slice(4, 12);
      let timeHexBE = "";
      for (let i = 3; i >= 0; i--) {
        timeHexBE += timeHexLE.slice(i * 2, i * 2 + 2);
      }
      result.time = parseInt(timeHexBE, 16);

      result.heartRateData += hex.slice(12, 30);
    } else if (seq === "17") {
      result.heartRateData += hex.slice(4, 16);
    } else if (parseInt(seq, 16) > 1) {
      result.heartRateData += hex.slice(4, 30);
    }
  }

  let formatted = "";

  for (let i = 0; i < result.heartRateData.length; i += 2) {
    const value = parseInt(result.heartRateData.slice(i, i + 2), 16);

    if (value === 255 || value === 0 || Number.isNaN(value)) {
      formatted += "000";
    } else {
      formatted += String(value).padStart(3, "0");
    }
  }

  result.heartRateData = formatted;
  return result;
}
```

### 7.4 验证点

成功复现时，应看到：

```text
收到 15 开头 notify
heartPackets 数组持续增长
parseHeartRatePackets 返回 time + heartRateData
调用 /tyapi/mobile/heartRate/addHeartRate
```

---

## 8. 睡眠数据复现

### 8.1 数据流

```text
APP 写入 sleepData(day)
  ↓
手环连续 notify 多包 44 开头数据
  ↓
APP 收集 sleepPackets
  ↓
遇到 44ff 或 c4/ff 结束标记
  ↓
parseSleepPackets()
  ↓
api.addSleepData({ bindId, time, data })
```

### 8.2 模拟睡眠包

```js
function fakeSleepPackets() {
  return [
    // 44 f0 表示日期相关包：20 + YY-MM-DD
    "44f00000180605000000000000000000",
    // 44 01 / 44 02 表示睡眠片段
    "44010102030405060708090a0b0c0d0e",
    "44020f101112131415161718191a1b1c"
  ];
}
```

### 8.3 解析函数

```js
function parseSleepPackets(packets) {
  if (!packets.length) return null;

  const result = {
    time: "20",
    sleepData: ""
  };

  for (const hex of packets) {
    const seq = hex.slice(2, 4);

    if (seq === "f0") {
      // 原 APP 逻辑近似：time = "20" + YY + "-" + MM + "-" + DD
      result.time =
        "20" +
        hex.slice(6, 8) +
        "-" +
        hex.slice(8, 10) +
        "-" +
        hex.slice(10, 12);
    } else {
      result.sleepData += hex.slice(4, 30);
    }
  }

  return result;
}
```

### 8.4 验证点

成功复现时，应看到：

```text
收到 44 开头 notify
sleepPackets 数组持续增长
parseSleepPackets 返回 time + sleepData
调用 /tyapi/mobile/bracelet/addSleep
随后切换到心率同步
```

---

## 9. 即时心率测量复现

### 9.1 流程

```text
用户点击“测量心率”
  ↓
APP 写入 measureHeartRate()
  ↓
手环返回 69 开头数据
  ↓
APP 解析当前心率
  ↓
页面展示
```

### 9.2 代码

```js
async function measureOnce(deviceId) {
  return new Promise(async (resolve) => {
    listenBraceletNotify((hex) => {
      if (hex.slice(0, 2) === "69") {
        const bpm = parseInt(hex.slice(4, 6), 16);
        resolve(bpm);
      }
    });

    await writeBraceletCommand(deviceId, measureHeartRate());
  });
}
```

验证：

```text
发送命令：69...
收到返回：69014c...
解析心率：0x4c = 76
```

---

## 10. 解绑复现

### 10.1 流程

```text
用户点击解绑
  ↓
弹窗确认
  ↓
api.allThrowBracelet({ userId })
  ↓
成功后删除 bindId、macAddress
  ↓
提示“已解绑”
  ↓
返回未连接设备页
```

### 10.2 代码

```js
async function unbindBracelet() {
  const userId = getStorageSync("id");

  const resp = await api.allThrowBracelet({ userId });

  if (resp.statusCode === 1) {
    removeStorageSync("bindId");
    removeStorageSync("macAddress");
    removeStorageSync("model");

    return {
      success: true,
      message: "已解绑"
    };
  }

  return {
    success: false,
    message: resp.message
  };
}
```

---

## 11. 最小实验目录建议

```text
bracelet-repro/
  server/
    index.js              # mock 后端接口
    db.json               # 保存绑定、心率、睡眠
  app/
    storage.js            # 模拟 uni storage
    api.js                # HTTP 封装
    ble.js                # BLE 抽象层，可连接真实 BLE 或 fake BLE
    braceletCommands.js   # 命令生成
    braceletParser.js     # 心率/睡眠解析
    braceletSync.js       # 同步状态机
    main.js               # 实验入口
  fake-device/
    FakeBracelet.js       # 模拟手环
  README.md
```

---

## 12. AI vibe coding 提示词

可以直接把下面这段给 AI：

```text
请帮我实现一个最小可运行 demo，复现 uni-app APP 与智能手环 Bracelet 的交互逻辑。

要求：
1. 使用 JavaScript/TypeScript。
2. 实现 mock 后端接口：
   - POST /tyapi/mobile/bracelet/lsusearchBraceletBox
   - POST /tyapi/mobile/bracelet/addBracelet
   - POST /tyapi/mobile/bracelet/getBracelet
   - POST /tyapi/mobile/heartRate/addHeartRate
   - POST /tyapi/mobile/bracelet/addSleep
   - POST /tyapi/mobile/bracelet/allThrowBracelet
3. 实现本地 storage，模拟 uni.getStorageSync / setStorageSync / removeStorageSync。
4. 实现 FakeBracelet，模拟 BLE 设备：
   - service UUID: 0000FFF0-0000-1000-8000-00805F9B34FB
   - communication service_uuid: 6E40FFF0-B5A3-F393-E0A9-E50E24DCCA9E
   - write_uuid: 6E400002-B5A3-F393-E0A9-E50E24DCCA9E
   - read_uuid: 6E400003-B5A3-F393-E0A9-E50E24DCCA9E
5. 实现命令生成：
   - settingTime
   - getElectric
   - getSetting
   - heartRate
   - sleepData
   - measureHeartRate
   命令为 16 字节，前 15 字节累加和 & 0xff 作为第 16 字节。
6. 实现同步状态机：
   - 设置时间
   - 获取电量
   - 获取设置
   - 查询定时心率
   - 同步睡眠
   - 同步心率
   - 上传后端
7. 实现 parseHeartRatePackets 和 parseSleepPackets。
8. 最后在 main.js 里跑通完整流程：
   - 初始化用户 id/stuNum
   - 搜索并绑定手环
   - 连接手环
   - 同步时间、电量、设置、睡眠、心率
   - 上传 mock 后端
   - 打印最终数据库内容
   - 执行解绑并验证本地缓存已清除

请输出完整项目代码，并保证 npm install 后可以直接运行。
```

---

## 13. 验收标准

### 13.1 绑定验收

应输出：

```text
发现设备 Bracelet-001
绑定成功 bindId=...
本地已保存 bindId/macAddress/model
连接成功
```

### 13.2 同步验收

应输出类似：

```text
[10%] 设置时间成功
[20%] 获取电量成功 battery=80
[40%] 获取设置成功
[60%] 睡眠数据同步成功
[80%] 心率数据同步成功
[100%] 同步完成
```

### 13.3 后端数据验收

mock DB 中应出现：

```json
{
  "bindings": [
    {
      "bindId": "bind_001",
      "stuNum": "S001",
      "macAddress": "FAKE_MAC_001",
      "model": "Bracelet-001"
    }
  ],
  "heartRates": [
    {
      "bindId": "bind_001",
      "timestamp": 0,
      "data": "076080088090000..."
    }
  ],
  "sleeps": [
    {
      "bindId": "bind_001",
      "time": "2024-06-05",
      "data": "010203..."
    }
  ]
}
```

### 13.4 解绑验收

应输出：

```text
解绑成功
bindId 已删除
macAddress 已删除
```

---

## 14. 复现时要注意的坑

### 14.1 macAddress 实际可能是 deviceId

原代码里变量叫 `macAddress`，但 BLE 连接时用的是 `deviceId`。  
在 iOS 上，`deviceId` 可能不是硬件 MAC，而是系统生成的 UUID。

实验里可以直接把 `deviceId` 当作 `macAddress`，但文档和代码注释要说明这一点。

### 14.2 notify 监听不要重复注册

原代码里多个函数都会注册 `onBLECharacteristicValueChange`，真实项目里容易导致重复回调。

实验建议统一只注册一次监听，再用状态机分发。

### 14.3 包数量不要写死

原代码中心率/睡眠有固定包数逻辑，比如心率 24 包、睡眠 44 包。  
实验建议同时支持：

```text
固定包数结束
或
收到 ff 结束标记
```

这样更稳定。

### 14.4 手环数据解析可以先模拟

第一阶段不需要真实手环。  
先用 `FakeBracelet` 复现完整链路，再替换成真实 BLE。

---

## 15. 最小完成目标

只要跑通下面这条链路，就算复现成功：

```text
搜索设备
  → 绑定
  → 本地保存 bindId/macAddress
  → BLE 连接
  → 写 settingTime
  → 写 getElectric
  → 写 getSetting
  → 写 sleepData
  → 解析睡眠并上传
  → 写 heartRate
  → 解析心率并上传
  → 解绑并清缓存
```

