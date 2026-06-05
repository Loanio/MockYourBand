package com.example.mockbracelet

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.mockbracelet.ui.theme.MockBraceletTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

private val SCAN_SERVICE_UUID: UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
private val GATT_SERVICE_UUID: UUID = UUID.fromString("6E40FFF0-B5A3-F393-E0A9-E50E24DCCA9E")
private val WRITE_CHARACTERISTIC_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
private val NOTIFY_CHARACTERISTIC_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MockBraceletTheme(dynamicColor = false) {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    BraceletDebuggerApp(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
private fun BraceletDebuggerApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val logs = remember { mutableStateListOf<String>() }
    val logger: (String) -> Unit = remember {
        { message ->
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            logs.add(0, "[$time] $message")
            if (logs.size > 160) logs.removeRange(160, logs.size)
        }
    }
    val peripheral = remember { BleBraceletPeripheral(context.applicationContext, logger) }
    var state by remember { mutableStateOf(peripheral.snapshot()) }
    var deviceName by remember { mutableStateOf("Bracelet-001") }
    var heartRate by remember { mutableStateOf("76") }
    var battery by remember { mutableStateOf("87") }
    var steps by remember { mutableStateOf("6842") }
    var frequency by remember { mutableStateOf(1000f) }

    val requiredPermissions = remember { blePermissions() }
    var hasPermissions by remember { mutableStateOf(requiredPermissions.allGranted(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        hasPermissions = requiredPermissions.all { grants[it] == true || context.checkSelfPermissionCompat(it) }
        if (hasPermissions) logger("BLE 权限已授权") else logger("BLE 权限不足，无法广播或打开 GATT Server")
    }

    LaunchedEffect(Unit) {
        peripheral.onStateChanged = { state = it }
    }
    DisposableEffect(Unit) {
        onDispose { peripheral.stop() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFFEEF8F4), Color(0xFFE7EEF8))))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "BLE Band Simulator",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            color = Color(0xFF143A35)
        )
        Text(
            text = "手机作为手环外设广播 FFF0，并暴露 6E40FFF0 GATT 服务。",
            color = Color(0xFF44615B)
        )

        StatusCard(state = state, hasPermissions = hasPermissions)

        Card(colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.88f))) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(deviceName, { deviceName = it }, label = { Text("广播设备名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    DecimalField("电量 %", battery, { battery = it }, Modifier.weight(1f))
                    DecimalField("心率 bpm", heartRate, { heartRate = it }, Modifier.weight(1f))
                    DecimalField("步数", steps, { steps = it }, Modifier.weight(1f))
                }
                Text("广播频率：${frequency.roundToInt()} ms", color = Color(0xFF304B47), fontWeight = FontWeight.SemiBold)
                Slider(value = frequency, onValueChange = { frequency = it }, valueRange = 100f..5000f, steps = 48)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("心率定时开关模拟", modifier = Modifier.weight(1f))
                    Switch(checked = state.autoHeartRateEnabled, onCheckedChange = { peripheral.setAutoHeartRateEnabled(it) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { permissionLauncher.launch(requiredPermissions) }, enabled = !hasPermissions) { Text("申请 BLE 权限") }
                    Button(onClick = {
                        if (!hasPermissions) {
                            permissionLauncher.launch(requiredPermissions)
                        } else {
                            peripheral.start(
                                DeviceParameters(
                                    deviceName = deviceName.ifBlank { "Bracelet-001" },
                                    heartRate = heartRate.decimalByteOr(76),
                                    battery = battery.decimalByteOr(87),
                                    steps = steps.decimalIntOr(0),
                                    advertiseIntervalMs = frequency.roundToInt()
                                )
                            )
                        }
                    }, enabled = !state.isRunning) { Text("开始广播") }
                    Button(onClick = { peripheral.stop() }, enabled = state.isRunning) { Text("停止") }
                }
                Button(onClick = {
                    peripheral.updateParameters(
                        DeviceParameters(
                            deviceName = deviceName.ifBlank { "Bracelet-001" },
                            heartRate = heartRate.decimalByteOr(76),
                            battery = battery.decimalByteOr(87),
                            steps = steps.decimalIntOr(0),
                            advertiseIntervalMs = frequency.roundToInt()
                        )
                    )
                }, enabled = state.isRunning) { Text("更新模拟数据") }
            }
        }

        LogPanel(logs = logs, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatusCard(state: PeripheralState, hasPermissions: Boolean) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF143A35)),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (state.isRunning) "正在广播" else "未启动", color = Color.White, fontWeight = FontWeight.Bold)
            Text("权限：${if (hasPermissions) "已授权" else "待授权"}", color = Color(0xFFC8EFE5))
            Text("连接设备：${state.connectedDevices}", color = Color(0xFFC8EFE5))
            Text("最近 RX：${state.lastRx.ifBlank { "无" }}", color = Color(0xFFC8EFE5), fontFamily = FontFamily.Monospace)
            Text("最近 TX：${state.lastTx.ifBlank { "无" }}", color = Color(0xFFC8EFE5), fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun DecimalField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter { char -> char.isDigit() }.take(8)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

@Composable
private fun LogPanel(logs: List<String>, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1D1B))) {
        Column(Modifier.padding(12.dp)) {
            Text("调试日志", color = Color.White, fontWeight = FontWeight.Bold)
            Divider(Modifier.padding(vertical = 8.dp), color = Color(0xFF24403B))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(logs) { line ->
                    Text(line, color = Color(0xFFBFE8DD), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private data class DeviceParameters(
    val deviceName: String,
    val heartRate: Int,
    val battery: Int,
    val steps: Int,
    val advertiseIntervalMs: Int
)

private data class PeripheralState(
    val isRunning: Boolean = false,
    val connectedDevices: Int = 0,
    val lastRx: String = "",
    val lastTx: String = "",
    val autoHeartRateEnabled: Boolean = true
)

private class BleBraceletPeripheral(
    private val context: Context,
    private val log: (String) -> Unit
) {
    var onStateChanged: ((PeripheralState) -> Unit)? = null
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private var gattServer: BluetoothGattServer? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private val subscribedDevices = mutableSetOf<BluetoothDevice>()
    private var params = DeviceParameters("Bracelet-001", 76, 87, 0, 1000)
    private var state = PeripheralState(autoHeartRateEnabled = true)
        set(value) {
            field = value
            onStateChanged?.invoke(value)
        }

    fun snapshot(): PeripheralState = state

    fun setAutoHeartRateEnabled(enabled: Boolean) {
        state = state.copy(autoHeartRateEnabled = enabled)
        log("心率定时开关模拟=${if (enabled) "开" else "关"}")
    }

    @SuppressLint("MissingPermission")
    fun start(newParams: DeviceParameters) {
        params = newParams
        val localAdapter = adapter
        if (localAdapter == null) {
            log("设备不支持蓝牙")
            return
        }
        if (!localAdapter.isEnabled) {
            log("蓝牙未开启")
            return
        }
        if (localAdapter.bluetoothLeAdvertiser == null) {
            log("设备不支持 BLE Peripheral 广播")
            return
        }
        stop()
        localAdapter.name = params.deviceName.take(18)
        openGattServer()
        startAdvertising(localAdapter)
        state = state.copy(isRunning = true)
    }

    fun updateParameters(newParams: DeviceParameters) {
        params = newParams
        log("更新参数：battery=${params.battery}, heartRate=${params.heartRate}, steps=${params.steps}")
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        gattServer?.close()
        gattServer = null
        notifyCharacteristic = null
        subscribedDevices.clear()
        state = state.copy(isRunning = false, connectedDevices = 0)
    }

    @SuppressLint("MissingPermission")
    private fun openGattServer() {
        val service = BluetoothGattService(GATT_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val writeCharacteristic = BluetoothGattCharacteristic(
            WRITE_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        notifyCharacteristic = BluetoothGattCharacteristic(
            NOTIFY_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(writeCharacteristic)
        service.addCharacteristic(notifyCharacteristic)
        gattServer = bluetoothManager.openGattServer(context, gattCallback).also { server ->
            server.addService(service)
            log("GATT Server 已开启：service=$GATT_SERVICE_UUID")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising(adapter: BluetoothAdapter) {
        val mode = when {
            params.advertiseIntervalMs <= 250 -> AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
            params.advertiseIntervalMs <= 1200 -> AdvertiseSettings.ADVERTISE_MODE_BALANCED
            else -> AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(mode)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(SCAN_SERVICE_UUID))
            .build()
        adapter.bluetoothLeAdvertiser.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            log("BLE 广播启动成功：${params.deviceName}, service=$SCAN_SERVICE_UUID")
        }

        override fun onStartFailure(errorCode: Int) {
            log("BLE 广播启动失败：error=$errorCode")
            state = state.copy(isRunning = false)
        }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                subscribedDevices.add(device)
                log("设备连接：${device.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subscribedDevices.remove(device)
                log("设备断开：${device.address}")
            }
            state = state.copy(connectedDevices = subscribedDevices.size)
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = ProtocolFrames.heartRate(params.heartRate)
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            state = state.copy(lastTx = value.toHex())
            log("READ -> ${value.toHex()}")
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            val rx = value.toHex()
            state = state.copy(lastRx = rx)
            log("WRITE <- $rx")
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
            val replies = ProtocolFrames.replyTo(value, params, state.autoHeartRateEnabled)
            replies.forEach { notify(device, it) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun notify(device: BluetoothDevice, value: ByteArray) {
        val characteristic = notifyCharacteristic ?: return
        characteristic.value = value
        gattServer?.notifyCharacteristicChanged(device, characteristic, false)
        state = state.copy(lastTx = value.toHex())
        log("NOTIFY -> ${value.toHex()}")
    }
}

private object ProtocolFrames {
    fun replyTo(command: ByteArray, params: DeviceParameters, autoHeartRateEnabled: Boolean): List<ByteArray> {
        if (command.isEmpty()) return emptyList()
        return when (command[0].toInt() and 0xFF) {
            0x01 -> listOf(frame(0x01))
            0x03 -> listOf(frame(0x03, params.battery.coerceIn(0, 100)))
            0x0A -> listOf(frame(0x0A, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
            0x15 -> historyHeartRate(params.heartRate)
            0x16 -> {
                val mode = command.getOrNull(1)?.toInt()?.and(0xFF) ?: 0x01
                if (mode == 0x02) listOf(frame(0x16, 0x02)) else listOf(frame(0x16, 0x01, if (autoHeartRateEnabled) 0x01 else 0x02))
            }
            0x44 -> sleepFrames()
            0x69 -> listOf(heartRate(params.heartRate))
            else -> listOf(frame(command[0].toInt() and 0xFF))
        }
    }

    fun heartRate(heartRate: Int): ByteArray = frame(0x69, 0x01, 0x00, heartRate.coerceIn(0, 255))

    private fun historyHeartRate(heartRate: Int): List<ByteArray> {
        val nowSeconds = (System.currentTimeMillis() / 1000).toInt()
        val first = mutableListOf(0x15, 0x01)
        first.addAll(nowSeconds.toBigEndianBytes())
        repeat(9) { first.add((heartRate + (it % 5) - 2).coerceIn(45, 180)) }
        return listOf(frame(*first.toIntArray()), frame(0x15, 0xFF))
    }

    private fun sleepFrames(): List<ByteArray> {
        val calendar = java.util.Calendar.getInstance()
        val year = calendar.get(java.util.Calendar.YEAR) % 100
        val month = calendar.get(java.util.Calendar.MONTH) + 1
        val day = calendar.get(java.util.Calendar.DAY_OF_MONTH)
        return listOf(
            frame(0x44, 0xF0, 0x00, year, month, day),
            frame(0x44, 0x2A, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66),
            frame(0x44, 0xFF)
        )
    }

    private fun frame(vararg bytes: Int): ByteArray {
        val out = IntArray(16)
        bytes.take(15).forEachIndexed { index, value -> out[index] = value and 0xFF }
        out[15] = out.take(15).sum() and 0xFF
        return ByteArray(16) { out[it].toByte() }
    }

    private fun Int.toBigEndianBytes(): List<Int> = listOf(
        (this ushr 24) and 0xFF,
        (this ushr 16) and 0xFF,
        (this ushr 8) and 0xFF,
        this and 0xFF
    )
}

private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
private fun String.decimalByteOr(default: Int): Int = toIntOrNull()?.coerceIn(0, 255) ?: default
private fun String.decimalIntOr(default: Int): Int = toIntOrNull()?.coerceAtLeast(0) ?: default

private fun blePermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
} else {
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

private fun Array<String>.allGranted(context: Context): Boolean = all { context.checkSelfPermissionCompat(it) }
private fun Context.checkSelfPermissionCompat(permission: String): Boolean = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
