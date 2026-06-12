package com.example.mockbracelet

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
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
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.mockbracelet.ui.theme.MockBraceletTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

private val SCAN_SERVICE_UUID: UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
private val GATT_SERVICE_UUID: UUID = UUID.fromString("6E40FFF0-B5A3-F393-E0A9-E50E24DCCA9E")
private val WRITE_CHARACTERISTIC_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
private val NOTIFY_CHARACTERISTIC_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
private val CLIENT_CONFIG_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
private const val MAX_STEPS = 16_777_215
private const val HEART_HISTORY_WINDOW_MS = 10 * 60 * 1000L
private const val MAX_HEART_HISTORY_POINTS = 240
private const val ADVANCE_ADVERTISING_DELAY_MS = 800L
private const val ADAPTER_NAME_APPLY_DELAY_MS = 500L

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
        { msg ->
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            logs.add(0, "[$time] $msg")
            if (logs.size > 180) logs.removeRange(180, logs.size)
        }
    }
    val store = remember { AppStore(context.applicationContext) }
    var settings by remember { mutableStateOf(store.loadSettings()) }
    var repository by remember { mutableStateOf(store.loadRepository().ensureRepository()) }
    var selectedRepoNames by remember {
        mutableStateOf(
            setOf(
                repository.firstOrNull { it.name == settings.activeRepositoryName }?.name
                    ?: repository.first().name
            )
        )
    }
    var page by remember { mutableStateOf(AppPage.Control) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val peripheral = remember { BleBraceletPeripheral(context.applicationContext, logger) }
    var state by remember { mutableStateOf(peripheral.snapshot()) }
    val heartHistory = remember { mutableStateListOf<HeartSample>() }
    val requiredPermissions = remember { blePermissions() }
    var hasPermissions by remember { mutableStateOf(requiredPermissions.allGranted(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        hasPermissions = requiredPermissions.all { grants[it] == true || context.checkSelfPermissionCompat(it) }
        logger(if (hasPermissions) "BLE 权限已授权" else "BLE 权限不足，无法广播或打开 GATT Server")
    }

    fun persist(next: AppSettings) {
        val clean = next.sanitized()
        settings = clean
        store.saveSettings(clean)
        if (state.isRunning) {
            peripheral.updateParameters(runtimeParameters(repository, selectedRepoNames, clean))
        }
    }

    fun updateHeartRate(value: Int, source: String) {
        val bpm = value.coerceIn(30, 220)
        persist(settings.copy(heartRate = bpm.toString()))
        heartHistory.add(HeartSample(System.currentTimeMillis(), bpm))
        trimHeartHistory(heartHistory)
        logger("$source heartRate=$bpm")
    }

    fun saveRepositoryState(next: List<BraceletConfig>, nextSelected: Set<String>) {
        val safeRepo = next.ensureRepository()
        val safeSelected = normalizeRepositorySelection(safeRepo, nextSelected, settings)
        repository = safeRepo
        selectedRepoNames = safeSelected
        store.saveRepository(safeRepo)
        persist(settings.copy(activeRepositoryName = safeSelected.first()))
    }

    LaunchedEffect(Unit) {
        peripheral.onStateChanged = { state = it }
        heartHistory.add(HeartSample(System.currentTimeMillis(), settings.heartRate.decimalIn(76, 30, 220)))
        if (!hasPermissions) {
            permissionLauncher.launch(requiredPermissions)
        }
    }
    LaunchedEffect(
        state.isRunning,
        settings.autoHeartEnabled,
        settings.heartMin,
        settings.heartMax,
        settings.heartMode,
        settings.heartUpdateSeconds,
        settings.heartJitter,
        settings.heartCurvePeriodSeconds
    ) {
        while (state.isRunning && settings.autoHeartEnabled) {
            updateHeartRate(simulatedHeartRate(settings), "自动心率")
            delay(settings.heartUpdateSeconds.decimalIn(3, 1, 60) * 1000L)
        }
    }
    LaunchedEffect(state.isRunning, settings.autoStepsEnabled, settings.stepMinSeconds, settings.stepMaxSeconds) {
        while (state.isRunning && settings.autoStepsEnabled) {
            val min = settings.stepMinSeconds.decimalIn(2, 1, 3600)
            val max = settings.stepMaxSeconds.decimalIn(8, min, 3600)
            delay(Random.nextLong(min * 1000L, max * 1000L + 1L))
            val next = (settings.steps.decimalIn(0, 0, MAX_STEPS) + 1).let { if (it > MAX_STEPS) 0 else it }
            persist(settings.copy(steps = next.toString()))
            logger("自动步数 steps=$next")
        }
    }
    DisposableEffect(Unit) { onDispose { peripheral.stop() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawer(
                current = page,
                state = state,
                settings = settings,
                onSelect = { selected ->
                    page = selected
                    scope.launch { drawerState.close() }
                }
            )
        }
    ) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFFE5F3E9), Color(0xFFF7F0DD))))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Header(
                page = page,
                state = state,
                hasPermissions = hasPermissions,
                onMenuClick = { scope.launch { drawerState.open() } }
            )
            when (page) {
                AppPage.Control -> {
                    ControlPage(
                        settings = settings,
                        onChange = { persist(it) },
                        repository = repository,
                        selectedNames = selectedRepoNames,
                        heartHistory = heartHistory,
                        state = state,
                        onSelectionChange = { selectedRepoNames = normalizeRepositorySelection(repository, it, settings) },
                        onGoRepository = { page = AppPage.Repository },
                        onStart = {
                            if (!hasPermissions) {
                                permissionLauncher.launch(requiredPermissions)
                            } else {
                                val selected = effectiveRepositorySelection(repository, selectedRepoNames, settings)
                                val primary = runtimeParameters(repository, selectedRepoNames, settings)
                                peripheral.start(primary, selected)
                            }
                        },
                        onStop = { peripheral.stop() },
                        modifier = Modifier.weight(1f)
                    )
                }
                AppPage.Repository -> RepositoryPage(
                    settings = settings,
                    repository = repository,
                    selectedNames = selectedRepoNames,
                    onSelectionChange = {
                        val next = normalizeRepositorySelection(repository, it, settings)
                        selectedRepoNames = next
                        persist(settings.copy(activeRepositoryName = next.first()))
                    },
                    onAdd = { item ->
                        val safeName = uniqueRepositoryName(item.name, repository)
                        val nextItem = item.copy(name = safeName)
                        val nextSelected = if (settings.batchEnabled) selectedRepoNames + safeName else setOf(safeName)
                        saveRepositoryState(repository + nextItem, nextSelected)
                        logger("已新增手环配置：$safeName")
                    },
                    onUpdate = { oldName, item ->
                        val safeName = if (oldName == item.name) item.name else uniqueRepositoryName(item.name, repository.filterNot { it.name == oldName })
                        val next = repository.map { if (it.name == oldName) item.copy(name = safeName) else it }
                        val nextSelected = selectedRepoNames.map { if (it == oldName) safeName else it }.toSet()
                        saveRepositoryState(next, nextSelected)
                        logger("已修改手环配置：$safeName")
                    },
                    onDelete = { name ->
                        if (repository.size <= 1) {
                            logger("仓库至少保留一个手环配置，不能删除最后一项")
                        } else {
                            val next = repository.filterNot { it.name == name }
                            saveRepositoryState(next, selectedRepoNames - name)
                            logger("已删除手环配置：$name")
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                AppPage.Protocol -> ProtocolPage(
                    settings = settings,
                    onChange = { persist(it) },
                    canSend = state.isRunning && state.connectedDevices > 0,
                    onSend = { peripheral.sendTemplateReplies(settings.toParameters()) },
                    modifier = Modifier.weight(1f)
                )
                AppPage.Logs -> LogPanel(logs = logs, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AppDrawer(current: AppPage, state: PeripheralState, settings: AppSettings, onSelect: (AppPage) -> Unit) {
    ModalDrawerSheet(drawerContainerColor = Color(0xFFF7F3E8)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF173B33)), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("BLE Band", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    Text(settings.deviceName, color = Color(0xFFCFE9DE), fontFamily = FontFamily.Monospace)
                    Text(if (state.isRunning) "广播中 · ${state.advertisers} 个入口" else "未启动广播", color = Color(0xFFFFD98A), fontWeight = FontWeight.SemiBold)
                }
            }
            Text("功能模块", color = Color(0xFF5D6F68), fontWeight = FontWeight.Bold)
            AppPage.values().forEach { item ->
                NavigationDrawerItem(
                    label = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(item.label, fontWeight = FontWeight.Bold)
                            Text(item.description, style = MaterialTheme.typography.bodySmall, color = Color(0xFF63746E))
                        }
                    },
                    selected = item == current,
                    onClick = { onSelect(item) }
                )
            }
            Divider(color = Color(0xFFD9E5DC))
            Text("当前数据", color = Color(0xFF5D6F68), fontWeight = FontWeight.Bold)
            Text("心率 ${settings.heartRate} bpm · 电量 ${settings.battery}% · 步数 ${settings.steps}", color = Color(0xFF304B47))
            Text("广播 ${"%.2f".format(1000f / settings.advertiseIntervalMs)} Hz", color = Color(0xFF304B47))
        }
    }
}

@Composable
private fun Header(page: AppPage, state: PeripheralState, hasPermissions: Boolean, onMenuClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Button(onClick = onMenuClick, shape = RoundedCornerShape(14.dp)) {
            Text("☰", fontWeight = FontWeight.ExtraBold)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text("BLE Band", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = Color(0xFF173B33))
            Text("${page.label} · ${page.description}", color = Color(0xFF63746E), style = MaterialTheme.typography.bodySmall)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(if (state.isRunning) "广播中" else "未启动", color = if (state.isRunning) Color(0xFF168A5B) else Color(0xFF8A6A16), fontWeight = FontWeight.Bold)
            Text(
                "${if (hasPermissions) "权限OK" else "待授权"} · ${state.connectedDevices}连接",
                color = Color(0xFF63746E),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun ControlPage(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    repository: List<BraceletConfig>,
    selectedNames: Set<String>,
    heartHistory: List<HeartSample>,
    state: PeripheralState,
    onSelectionChange: (Set<String>) -> Unit,
    onGoRepository: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val effective = effectiveRepositorySelection(repository, selectedNames, settings)
    val advertiseHz = settings.advertiseIntervalMs.toHz()
    LazyColumn(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard("当前模拟") {
                if (effective.size == 1) {
                    val item = effective.first()
                    Text(item.name, color = Color(0xFF173B33), fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                    Text("${item.deviceName} · HR ${item.heartRate} · BAT ${item.battery}% · STEP ${item.steps}", color = Color(0xFF48645D))
                } else {
                    Text("已选择 ${effective.size} 个手环参与模拟", color = Color(0xFF173B33), fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                    Text("扫描阶段显示当前待连接手环；每连上一台设备后自动切到下一个手环。", color = Color(0xFF63746E), style = MaterialTheme.typography.bodySmall)
                    effective.take(4).forEach { item ->
                        Text("${item.name} · ${item.deviceName} · HR ${item.heartRate}", color = Color(0xFF48645D), style = MaterialTheme.typography.bodySmall)
                    }
                    if (effective.size > 4) Text("还有 ${effective.size - 4} 个...", color = Color(0xFF63746E), style = MaterialTheme.typography.bodySmall)
                }
                Text("广播频率：${"%.2f".format(advertiseHz)} Hz（${settings.advertiseIntervalMs} ms）", color = Color(0xFF173B33), fontWeight = FontWeight.Bold)
                Slider(
                    value = advertiseHz,
                    onValueChange = { onChange(settings.copy(advertiseIntervalMs = it.toAdvertiseIntervalMs())) },
                    valueRange = 0.2f..10f,
                    steps = 97
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onGoRepository) { Text("去仓库修改") }
                    Button(onClick = onStart, enabled = !state.isRunning) { Text("开始广播") }
                    Button(onClick = onStop, enabled = state.isRunning) { Text("停止") }
                }
            }
        }
        item {
            SectionCard("模拟心率") {
                ToggleRow("开启后按范围生成并自动更新", settings.autoHeartEnabled, { onChange(settings.copy(autoHeartEnabled = it)) })
                if (settings.autoHeartEnabled) {
                    Text("广播启动后才会开始生成心率；未广播时不会运行随机/曲线计算。", color = Color(0xFF63746E), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        DeferredDecimalField("最小 bpm", settings.heartMin, { onChange(settings.copy(heartMin = it)) }, 30, 220, Modifier.weight(1f))
                        DeferredDecimalField("最大 bpm", settings.heartMax, { onChange(settings.copy(heartMax = it)) }, 30, 220, Modifier.weight(1f))
                        ModePicker(settings.heartMode, { onChange(settings.copy(heartMode = it)) }, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        DecimalField("更新间隔 秒", settings.heartUpdateSeconds, { onChange(settings.copy(heartUpdateSeconds = it)) }, 1, 60, Modifier.weight(1f))
                        DecimalField("抖动 bpm", settings.heartJitter, { onChange(settings.copy(heartJitter = it)) }, 0, 30, Modifier.weight(1f))
                    }
                    if (settings.heartMode == HeartMode.TimeCurve) {
                        DecimalField("曲线周期 秒", settings.heartCurvePeriodSeconds, { onChange(settings.copy(heartCurvePeriodSeconds = it)) }, 10, 600, Modifier.fillMaxWidth())
                        Text("曲线周期越长，心率变化越平缓；抖动用于模拟真实手环读数的小幅波动。", color = Color(0xFF63746E), style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("随机模式会在最小/最大心率之间取值，抖动越大，连续读数变化越明显。", color = Color(0xFF63746E), style = MaterialTheme.typography.bodySmall)
                    }
                    ToggleRow("显示实时趋势图", settings.showHeartChart, { onChange(settings.copy(showHeartChart = it)) })
                    if (settings.showHeartChart) HeartRateChart(heartHistory)
                }
            }
        }
        item {
            SectionCard("模拟步数增加") {
                ToggleRow("开启后随机间隔步数 +1，超过上限自动归零", settings.autoStepsEnabled, { onChange(settings.copy(autoStepsEnabled = it)) })
                if (settings.autoStepsEnabled) {
                    Text("广播启动后才会按随机间隔递增步数；停止广播后自动暂停。", color = Color(0xFF63746E), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        DecimalField("最小秒", settings.stepMinSeconds, { onChange(settings.copy(stepMinSeconds = it)) }, 1, 3600, Modifier.weight(1f))
                        DecimalField("最大秒", settings.stepMaxSeconds, { onChange(settings.copy(stepMaxSeconds = it)) }, 1, 3600, Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            SectionCard("批量模拟") {
                ToggleRow(
                    "开启后可同时批量模拟仓库里面多个手环",
                    settings.batchEnabled,
                    { enabled ->
                        val nextSettings = settings.copy(batchEnabled = enabled)
                        onChange(nextSettings)
                        onSelectionChange(normalizeRepositorySelection(repository, selectedNames, nextSettings))
                    }
                )
                if (settings.batchEnabled) {
                    Text("传统 BLE 广播会受机型限制；这里会尽量启动多个广播实例，并用服务数据区分。", color = Color(0xFF48645D))
                    DecimalField(
                        "设备数量 1-8",
                        settings.batchCount,
                        {
                            val nextSettings = settings.copy(batchCount = it)
                            onChange(nextSettings)
                            onSelectionChange(normalizeRepositorySelection(repository, selectedNames, nextSettings))
                        },
                        1,
                        8,
                        Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun RepositoryPage(
    settings: AppSettings,
    repository: List<BraceletConfig>,
    selectedNames: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    onAdd: (BraceletConfig) -> Unit,
    onUpdate: (String, BraceletConfig) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var editingName by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("手环-${repository.size + 1}") }
    var deviceName by remember { mutableStateOf("Bracelet-${repository.size + 1}") }
    var heartRate by remember { mutableStateOf("76") }
    var battery by remember { mutableStateOf("87") }
    var steps by remember { mutableStateOf("0") }
    val formValid = name.isNotBlank() &&
        deviceName.isNotBlank() &&
        heartRate.toIntOrNull()?.let { it in 30..220 } == true &&
        battery.toIntOrNull()?.let { it in 0..100 } == true &&
        steps.toIntOrNull()?.let { it in 0..MAX_STEPS } == true

    fun fillForm(item: BraceletConfig) {
        editingName = item.name
        name = item.name
        deviceName = item.deviceName
        heartRate = item.heartRate
        battery = item.battery
        steps = item.steps
    }

    fun clearForm() {
        editingName = null
        name = "手环-${repository.size + 1}"
        deviceName = "Bracelet-${repository.size + 1}"
        heartRate = "76"
        battery = "87"
        steps = "0"
    }

    fun formItem(): BraceletConfig = BraceletConfig(
        name = name.trim(),
        deviceName = deviceName.trim(),
        heartRate = heartRate,
        battery = battery,
        steps = steps,
        advertiseIntervalMs = settings.advertiseIntervalMs,
        replyTemplate = ReplyTemplate.Auto,
        autoChecksum = true,
        corruptChecksum = false,
        overrideAutoReplies = false,
        decimalFrameInput = "",
        hexFrameInput = ""
    )

    val batchLimit = settings.batchCount.decimalIn(1, 1, 8)
    val selectionFull = settings.batchEnabled && selectedNames.size >= batchLimit
    Column(modifier = modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("手环仓库") {
            Text("填写参数后点击 + 添加到仓库。长按列表项可修改或删除。", color = Color(0xFF63746E), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(name, { name = it }, label = { Text("配置名称，随便填写，只是给这一项配置起个名字，和实际发送数据无关") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(deviceName, { deviceName = it.take(18) }, label = { Text("广播设备名，不能为空，尽量和真实手环的名称一致，比如M6_XXXX") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                DecimalField("心率 30-220", heartRate, { heartRate = it }, 30, 220, Modifier.weight(1f))
                DecimalField("电量 0-100", battery, { battery = it }, 0, 100, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                DecimalField("步数 0-$MAX_STEPS", steps, { steps = it }, 0, MAX_STEPS, Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val item = formItem()
                        val currentEdit = editingName
                        if (currentEdit == null) onAdd(item) else onUpdate(currentEdit, item)
                        clearForm()
                    },
                    enabled = formValid
                ) { Text(if (editingName == null) "+ 新增" else "保存修改") }
                Button(onClick = { clearForm() }, enabled = editingName != null) { Text("取消修改") }
            }
            Divider(color = Color(0xFFE1ECE8))
            Text(
                if (settings.batchEnabled) "批量模式：最多加入 $batchLimit 个手环，已加入 ${selectedNames.size} 个。" else "单设备模式：点击任意手环会直接设为当前模拟对象。",
                color = Color(0xFF63746E),
                style = MaterialTheme.typography.bodySmall
            )
            repository.forEachIndexed { index, item ->
                val checked = item.name in selectedNames
                RepositoryListItem(
                    index = index,
                    item = item,
                    selected = checked,
                    batchEnabled = settings.batchEnabled,
                    selectionFull = selectionFull,
                    canDelete = repository.size > 1,
                    onSelectClick = {
                        val next = if (settings.batchEnabled) {
                            if (checked) selectedNames - item.name else selectedNames + item.name
                        } else {
                            setOf(item.name)
                        }
                        onSelectionChange(next)
                    },
                    onEdit = { fillForm(item) },
                    onDelete = { onDelete(item.name) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RepositoryListItem(
    index: Int,
    item: BraceletConfig,
    selected: Boolean,
    batchEnabled: Boolean,
    selectionFull: Boolean,
    canDelete: Boolean,
    onSelectClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val canJoin = selected || !batchEnabled || !selectionFull
    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = { if (canJoin) onSelectClick() }, onLongClick = { menuOpen = true }),
            colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFFEAF7F0) else Color(0xFFF5F1E6)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("${index + 1}. ${item.name}", color = Color(0xFF173B33), fontWeight = FontWeight.Bold)
                    Text("${item.deviceName} · HR ${item.heartRate} · BAT ${item.battery}% · STEP ${item.steps}", color = Color(0xFF48645D), style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = onSelectClick, enabled = canJoin, shape = RoundedCornerShape(14.dp)) {
                    Text(
                        when {
                            !batchEnabled && selected -> "当前"
                            !batchEnabled -> "设为当前"
                            selected -> "移除"
                            selectionFull -> "已满"
                            else -> "加入"
                        }
                    )
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text("修改") }, onClick = { menuOpen = false; onEdit() })
            DropdownMenuItem(text = { Text("删除") }, enabled = canDelete, onClick = { menuOpen = false; onDelete() })
        }
    }
}

@Composable
private fun ProtocolPage(settings: AppSettings, onChange: (AppSettings) -> Unit, canSend: Boolean, onSend: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("协议模板") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TemplatePicker(settings.replyTemplate, { onChange(settings.copy(replyTemplate = it)) }, Modifier.weight(1f))
                Button(onClick = onSend, enabled = canSend) { Text("发送回包") }
            }
            ToggleRow("收到命令时使用当前模板覆盖自动回包", settings.overrideAutoReplies, { onChange(settings.copy(overrideAutoReplies = it)) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleRow("自动计算校验和", settings.autoChecksum, { onChange(settings.copy(autoChecksum = it)) }, Modifier.weight(1f))
                ToggleRow("错误帧模拟", settings.corruptChecksum, { onChange(settings.copy(corruptChecksum = it)) }, Modifier.weight(1f))
            }
            OutlinedTextField(settings.decimalFrameInput, { onChange(settings.copy(decimalFrameInput = it)) }, label = { Text("16 字节十进制数组 0-255") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(settings.hexFrameInput, { onChange(settings.copy(hexFrameInput = it)) }, label = { Text("十六进制帧") }, modifier = Modifier.fillMaxWidth())
            Text("预览：${ProtocolFrames.preview(settings.toParameters()).joinToString("\n") { it.toHex() }.ifBlank { "无有效帧" }}", color = Color(0xFF304B47), fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.94f)), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = Color(0xFF173B33))
            content()
        }
    }
}

@Composable
private fun DecimalField(label: String, value: String, onValueChange: (String) -> Unit, min: Int, max: Int, modifier: Modifier = Modifier) {
    val parsed = value.toIntOrNull()
    val valid = parsed != null && parsed in min..max
    OutlinedTextField(
        value = value,
        onValueChange = { raw -> onValueChange(raw.filter { it.isDigit() }.take(max.toString().length)) },
        label = { Text(label) },
        supportingText = { if (!valid) Text("范围 $min-$max") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

@Composable
private fun DeferredDecimalField(label: String, value: String, onCommit: (String) -> Unit, min: Int, max: Int, modifier: Modifier = Modifier) {
    var draft by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val parsed = draft.toIntOrNull()
    val valid = parsed != null && parsed in min..max

    fun commit() {
        val next = parsed?.coerceIn(min, max)?.toString() ?: value
        draft = next
        onCommit(next)
    }

    OutlinedTextField(
        value = draft,
        onValueChange = { raw -> draft = raw.filter { it.isDigit() } },
        label = { Text(label) },
        supportingText = { if (!valid) Text("范围 $min-$max") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = modifier.onFocusChanged { state ->
            if (focused && !state.isFocused) commit()
            focused = state.isFocused
        }
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = Color(0xFF304B47))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun TemplatePicker(selected: ReplyTemplate, onSelected: (ReplyTemplate) -> Unit, modifier: Modifier = Modifier) {
    DropButton(selected.label, ReplyTemplate.values().map { it.label }, { label -> onSelected(ReplyTemplate.values().first { it.label == label }) }, modifier)
}

@Composable
private fun ModePicker(selected: HeartMode, onSelected: (HeartMode) -> Unit, modifier: Modifier = Modifier) {
    DropButton(selected.label, HeartMode.values().map { it.label }, { label -> onSelected(HeartMode.values().first { it.label == label }) }, modifier)
}

@Composable
private fun RepositoryPicker(items: List<BraceletConfig>, selectedIndex: Int, onSelected: (Int) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Button(onClick = { expanded = true }, enabled = items.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(items.getOrNull(selectedIndex)?.name ?: "选择配置")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEachIndexed { index, item ->
                DropdownMenuItem(text = { Text(item.name) }, onClick = { onSelected(index); expanded = false })
            }
        }
    }
}

@Composable
private fun DropButton(selected: String, options: List<String>, onSelected: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Button(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(selected) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { item ->
                DropdownMenuItem(text = { Text(item) }, onClick = { onSelected(item); expanded = false })
            }
        }
    }
}

@Composable
private fun HeartRateChart(values: List<HeartSample>) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF102A25)), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val spanMs = ((values.lastOrNull()?.timestampMs ?: 0L) - (values.firstOrNull()?.timestampMs ?: 0L)).coerceAtLeast(0L)
            val timeUnit = if (spanMs >= 90_000L) "min" else "s"
            Text("心率趋势（bpm / $timeUnit，最近 ${values.size} 点）", color = Color.White, fontWeight = FontWeight.Bold)
            Canvas(modifier = Modifier.fillMaxWidth().height(190.dp)) {
                val now = System.currentTimeMillis()
                val points = values.ifEmpty { listOf(HeartSample(now, 60), HeartSample(now + 1000L, 80)) }
                val minValue = points.minOf { it.bpm }
                val maxValue = points.maxOf { it.bpm }
                val min = ((minValue - 5).coerceAtLeast(30) / 10) * 10
                val max = (((maxValue + 14).coerceAtMost(220)) / 10) * 10
                val span = (max - min).coerceAtLeast(10)
                val startMs = points.first().timestampMs
                val endMs = points.last().timestampMs.coerceAtLeast(startMs + 1000L)
                val timeSpanMs = (endMs - startMs).coerceAtLeast(1000L)
                val left = 44f
                val right = size.width - 8f
                val top = 12f
                val bottom = size.height - 32f
                val chartWidth = (right - left).coerceAtLeast(1f)
                val chartHeight = (bottom - top).coerceAtLeast(1f)
                val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.rgb(217, 255, 244)
                    textSize = 24f
                }
                val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.rgb(141, 181, 169)
                    textSize = 22f
                    textAlign = Paint.Align.CENTER
                }
                val path = Path()
                points.forEachIndexed { index, value ->
                    val x = left + ((value.timestampMs - startMs).toFloat() / timeSpanMs) * chartWidth
                    val y = bottom - ((value.bpm - min).toFloat() / span) * chartHeight
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                repeat(4) { i ->
                    val ratio = i / 3f
                    val y = bottom - ratio * chartHeight
                    val bpm = (min + span * ratio).roundToInt()
                    drawLine(Color(0x335FE4B1), Offset(left, y), Offset(right, y), strokeWidth = 1f)
                    drawContext.canvas.nativeCanvas.drawText("$bpm", 4f, y + 8f, labelPaint)
                }
                repeat(4) { i ->
                    val ratio = i / 3f
                    val x = left + ratio * chartWidth
                    val seconds = timeSpanMs / 1000f * ratio
                    val label = if (timeSpanMs >= 90_000L) "%.1f".format(seconds / 60f) else seconds.roundToInt().toString()
                    drawLine(Color(0x225FE4B1), Offset(x, top), Offset(x, bottom), strokeWidth = 1f)
                    drawContext.canvas.nativeCanvas.drawText(label, x, size.height - 6f, mutedPaint)
                }
                drawLine(Color(0x885FE4B1), Offset(left, bottom), Offset(right, bottom), strokeWidth = 2f)
                drawLine(Color(0x885FE4B1), Offset(left, top), Offset(left, bottom), strokeWidth = 2f)
                drawPath(path, Color(0xFF5FE4B1), style = Stroke(width = 5f))
                points.lastOrNull()?.let { last ->
                    val x = left + ((last.timestampMs - startMs).toFloat() / timeSpanMs) * chartWidth
                    val y = bottom - ((last.bpm - min).toFloat() / span) * chartHeight
                    drawCircle(Color(0xFFFFC857), radius = 8f, center = Offset(x, y))
                    drawContext.canvas.nativeCanvas.drawText("${last.bpm} bpm", (x - 44f).coerceIn(left, right - 72f), (y - 12f).coerceAtLeast(top + 20f), labelPaint)
                }
                drawContext.canvas.nativeCanvas.drawText("时间 ($timeUnit)", (left + right) / 2f, size.height - 6f, mutedPaint)
                drawContext.canvas.nativeCanvas.drawText("bpm", 6f, top + 4f, labelPaint)
            }
        }
    }
}

@Composable
private fun LogPanel(logs: List<String>, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1D1B)), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text("调试日志", color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            LazyColumn { items(logs) { line -> Text(line, color = Color(0xFFD9FFF4), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } }
        }
    }
}

private enum class AppPage(val label: String, val description: String) {
    Control("控制", "广播、自动心率、自动步数"),
    Repository("仓库", "保存和加载不同手环配置"),
    Protocol("协议", "回包模板和手动帧调试"),
    Logs("日志", "查看 BLE 调试事件")
}
private enum class HeartMode(val label: String) { Random("随机"), TimeCurve("时间曲线") }
private data class HeartSample(val timestampMs: Long, val bpm: Int)
private enum class ReplyTemplate(val label: String) {
    Auto("自动协议回包"),
    ManualDecimal("手动十进制帧"),
    ManualHex("手动十六进制帧"),
    TimeAck("01 时间同步 ACK"),
    Battery("03 电量回包"),
    Settings("0A 设置回包"),
    HistoryHeartRate("15 历史心率"),
    HeartRateSwitch("16 心率开关"),
    Sleep("44 睡眠数据"),
    RealtimeHeartRate("69 实时心率"),
    EchoAck("未知命令 ACK")
}

private data class AppSettings(
    val deviceName: String = "Bracelet-001",
    val heartRate: String = "76",
    val battery: String = "87",
    val steps: String = "6842",
    val advertiseIntervalMs: Int = 1000,
    val autoHeartEnabled: Boolean = false,
    val heartMin: String = "62",
    val heartMax: String = "96",
    val heartMode: HeartMode = HeartMode.TimeCurve,
    val heartUpdateSeconds: String = "3",
    val heartJitter: String = "2",
    val heartCurvePeriodSeconds: String = "36",
    val showHeartChart: Boolean = true,
    val autoStepsEnabled: Boolean = false,
    val stepMinSeconds: String = "2",
    val stepMaxSeconds: String = "8",
    val batchEnabled: Boolean = false,
    val batchCount: String = "1",
    val activeRepositoryName: String = "默认手环",
    val replyTemplate: ReplyTemplate = ReplyTemplate.Auto,
    val autoChecksum: Boolean = true,
    val corruptChecksum: Boolean = false,
    val overrideAutoReplies: Boolean = false,
    val decimalFrameInput: String = "03,87,0,0,0,0,0,0,0,0,0,0,0,0,0,90",
    val hexFrameInput: String = "69 01 00 4C 00 00 00 00 00 00 00 00 00 00 00 B6"
) {
    fun sanitized(): AppSettings {
        val minHr = heartMin.decimalIn(62, 30, 220)
        val maxHr = heartMax.decimalIn(96, minHr, 220)
        val updateSeconds = heartUpdateSeconds.decimalIn(3, 1, 60)
        val jitter = heartJitter.decimalIn(2, 0, 30)
        val curvePeriod = heartCurvePeriodSeconds.decimalIn(36, 10, 600)
        val minStep = stepMinSeconds.decimalIn(2, 1, 3600)
        val maxStep = stepMaxSeconds.decimalIn(8, minStep, 3600)
        return copy(
            deviceName = deviceName.ifBlank { "Bracelet-001" }.take(18),
            heartRate = heartRate.decimalIn(76, 30, 220).toString(),
            battery = battery.decimalIn(87, 0, 100).toString(),
            steps = steps.decimalIn(0, 0, MAX_STEPS).toString(),
            advertiseIntervalMs = advertiseIntervalMs.coerceIn(100, 5000),
            heartMin = minHr.toString(),
            heartMax = maxHr.toString(),
            heartUpdateSeconds = updateSeconds.toString(),
            heartJitter = jitter.toString(),
            heartCurvePeriodSeconds = curvePeriod.toString(),
            stepMinSeconds = minStep.toString(),
            stepMaxSeconds = maxStep.toString(),
            batchCount = if (batchEnabled) batchCount.decimalIn(1, 1, 8).toString() else "1"
        )
    }

    fun toParameters(): DeviceParameters = DeviceParameters(
        deviceName = deviceName.ifBlank { "Bracelet-001" },
        heartRate = heartRate.decimalIn(76, 30, 220),
        battery = battery.decimalIn(87, 0, 100),
        steps = steps.decimalIn(0, 0, MAX_STEPS),
        advertiseIntervalMs = advertiseIntervalMs,
        replyTemplate = replyTemplate,
        autoChecksum = autoChecksum,
        corruptChecksum = corruptChecksum,
        decimalFrameInput = decimalFrameInput,
        hexFrameInput = hexFrameInput,
        overrideAutoReplies = overrideAutoReplies
    )

    fun toRepositoryItem(name: String): BraceletConfig = BraceletConfig(
        name, deviceName, heartRate, battery, steps, advertiseIntervalMs, replyTemplate, autoChecksum, corruptChecksum, overrideAutoReplies, decimalFrameInput, hexFrameInput
    )
}

private data class BraceletConfig(
    val name: String,
    val deviceName: String,
    val heartRate: String,
    val battery: String,
    val steps: String,
    val advertiseIntervalMs: Int,
    val replyTemplate: ReplyTemplate,
    val autoChecksum: Boolean,
    val corruptChecksum: Boolean,
    val overrideAutoReplies: Boolean,
    val decimalFrameInput: String,
    val hexFrameInput: String
) {
    fun toSettings(base: AppSettings): AppSettings = base.copy(
        deviceName = deviceName,
        heartRate = heartRate,
        battery = battery,
        steps = steps,
        replyTemplate = replyTemplate,
        autoChecksum = autoChecksum,
        corruptChecksum = corruptChecksum,
        overrideAutoReplies = overrideAutoReplies,
        decimalFrameInput = decimalFrameInput,
        hexFrameInput = hexFrameInput
    )
}

private data class DeviceParameters(
    val deviceName: String,
    val heartRate: Int,
    val battery: Int,
    val steps: Int,
    val advertiseIntervalMs: Int,
    val replyTemplate: ReplyTemplate = ReplyTemplate.Auto,
    val autoChecksum: Boolean = true,
    val corruptChecksum: Boolean = false,
    val decimalFrameInput: String = "",
    val hexFrameInput: String = "",
    val overrideAutoReplies: Boolean = false
)

private data class VirtualBracelet(val name: String, val params: DeviceParameters)

private data class PeripheralState(
    val isRunning: Boolean = false,
    val connectedDevices: Int = 0,
    val advertisers: Int = 0,
    val lastRx: String = "",
    val lastTx: String = ""
)

private class AppStore(context: Context) {
    private val prefs = context.getSharedPreferences("mock_bracelet_phase2", Context.MODE_PRIVATE)

    fun loadSettings(): AppSettings = runCatching {
        prefs.getString("settings", null)?.let { JSONObject(it).toSettings() } ?: AppSettings()
    }.getOrDefault(AppSettings())

    fun saveSettings(settings: AppSettings) {
        prefs.edit().putString("settings", settings.toJson().toString()).apply()
    }

    fun loadRepository(): List<BraceletConfig> = runCatching {
        val raw = prefs.getString("repository", null) ?: return listOf(AppSettings().toRepositoryItem("默认手环"))
        val arr = JSONArray(raw)
        List(arr.length()) { arr.getJSONObject(it).toBraceletConfig() }.ensureRepository()
    }.getOrDefault(listOf(AppSettings().toRepositoryItem("默认手环")))

    fun saveRepository(items: List<BraceletConfig>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("repository", arr.toString()).apply()
    }
}

private fun JSONObject.toSettings(): AppSettings = AppSettings(
    deviceName = optString("deviceName", "Bracelet-001"),
    heartRate = optString("heartRate", "76"),
    battery = optString("battery", "87"),
    steps = optString("steps", "6842"),
    advertiseIntervalMs = optInt("advertiseIntervalMs", 1000),
    autoHeartEnabled = optBoolean("autoHeartEnabled", false),
    heartMin = optString("heartMin", "62"),
    heartMax = optString("heartMax", "96"),
    heartMode = enumValue(optString("heartMode", HeartMode.TimeCurve.name), HeartMode.TimeCurve),
    heartUpdateSeconds = optString("heartUpdateSeconds", "3"),
    heartJitter = optString("heartJitter", "2"),
    heartCurvePeriodSeconds = optString("heartCurvePeriodSeconds", "36"),
    showHeartChart = optBoolean("showHeartChart", true),
    autoStepsEnabled = optBoolean("autoStepsEnabled", false),
    stepMinSeconds = optString("stepMinSeconds", "2"),
    stepMaxSeconds = optString("stepMaxSeconds", "8"),
    batchEnabled = optBoolean("batchEnabled", false),
    batchCount = optString("batchCount", "1"),
    activeRepositoryName = optString("activeRepositoryName", "默认手环"),
    replyTemplate = enumValue(optString("replyTemplate", ReplyTemplate.Auto.name), ReplyTemplate.Auto),
    autoChecksum = optBoolean("autoChecksum", true),
    corruptChecksum = optBoolean("corruptChecksum", false),
    overrideAutoReplies = optBoolean("overrideAutoReplies", false),
    decimalFrameInput = optString("decimalFrameInput", ""),
    hexFrameInput = optString("hexFrameInput", "")
).sanitized()

private fun AppSettings.toJson(): JSONObject = JSONObject()
    .put("deviceName", deviceName)
    .put("heartRate", heartRate)
    .put("battery", battery)
    .put("steps", steps)
    .put("advertiseIntervalMs", advertiseIntervalMs)
    .put("autoHeartEnabled", autoHeartEnabled)
    .put("heartMin", heartMin)
    .put("heartMax", heartMax)
    .put("heartMode", heartMode.name)
    .put("heartUpdateSeconds", heartUpdateSeconds)
    .put("heartJitter", heartJitter)
    .put("heartCurvePeriodSeconds", heartCurvePeriodSeconds)
    .put("showHeartChart", showHeartChart)
    .put("autoStepsEnabled", autoStepsEnabled)
    .put("stepMinSeconds", stepMinSeconds)
    .put("stepMaxSeconds", stepMaxSeconds)
    .put("batchEnabled", batchEnabled)
    .put("batchCount", batchCount)
    .put("activeRepositoryName", activeRepositoryName)
    .put("replyTemplate", replyTemplate.name)
    .put("autoChecksum", autoChecksum)
    .put("corruptChecksum", corruptChecksum)
    .put("overrideAutoReplies", overrideAutoReplies)
    .put("decimalFrameInput", decimalFrameInput)
    .put("hexFrameInput", hexFrameInput)

private fun JSONObject.toBraceletConfig(): BraceletConfig = BraceletConfig(
    optString("name", "未命名手环"),
    optString("deviceName", "Bracelet-001"),
    optString("heartRate", "76"),
    optString("battery", "87"),
    optString("steps", "6842"),
    optInt("advertiseIntervalMs", 1000),
    enumValue(optString("replyTemplate", ReplyTemplate.Auto.name), ReplyTemplate.Auto),
    optBoolean("autoChecksum", true),
    optBoolean("corruptChecksum", false),
    optBoolean("overrideAutoReplies", false),
    optString("decimalFrameInput", ""),
    optString("hexFrameInput", "")
)

private fun BraceletConfig.toJson(): JSONObject = JSONObject()
    .put("name", name)
    .put("deviceName", deviceName)
    .put("heartRate", heartRate)
    .put("battery", battery)
    .put("steps", steps)
    .put("replyTemplate", replyTemplate.name)
    .put("autoChecksum", autoChecksum)
    .put("corruptChecksum", corruptChecksum)
    .put("overrideAutoReplies", overrideAutoReplies)
    .put("decimalFrameInput", decimalFrameInput)
    .put("hexFrameInput", hexFrameInput)

private inline fun <reified T : Enum<T>> enumValue(name: String, default: T): T =
    runCatching { enumValueOf<T>(name) }.getOrDefault(default)

private class BleBraceletPeripheral(private val context: Context, private val log: (String) -> Unit) {
    var onStateChanged: ((PeripheralState) -> Unit)? = null
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private var gattServer: BluetoothGattServer? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private val subscribedDevices = mutableSetOf<BluetoothDevice>()
    private val virtualBracelets = mutableListOf<VirtualBracelet>()
    private val deviceAssignments = mutableMapOf<String, Int>()
    private val availableQueue = ArrayDeque<Int>()
    private val callbacks = mutableListOf<AdvertiseCallback>()
    private val queueHandler = Handler(Looper.getMainLooper())
    private var advertisingIndex = 0
    private var advanceRunnable: Runnable? = null
    private var pendingAdvertiseRunnable: Runnable? = null
    private var params = DeviceParameters("Bracelet-001", 76, 87, 0, 1000)
    private var state = PeripheralState()
        set(value) {
            field = value
            onStateChanged?.invoke(value)
        }

    fun snapshot(): PeripheralState = state

    @SuppressLint("MissingPermission")
    fun start(newParams: DeviceParameters, batch: List<BraceletConfig>) {
        params = newParams
        val localAdapter = adapter ?: return log("设备不支持蓝牙")
        if (!localAdapter.isEnabled) return log("蓝牙未开启")
        if (localAdapter.bluetoothLeAdvertiser == null) return log("设备不支持 BLE Peripheral 广播")
        stop()
        openGattServer()
        val pool = batch.ifEmpty { listOf(params.toConfig("当前手环")) }.map { item ->
            VirtualBracelet(
                name = item.name,
                params = item.toSettings(AppSettings())
                    .copy(advertiseIntervalMs = params.advertiseIntervalMs)
                    .toParameters()
            )
        }
        virtualBracelets.clear()
        virtualBracelets.addAll(pool)
        availableQueue.clear()
        availableQueue.addAll(virtualBracelets.indices)
        advertisingIndex = availableQueue.firstOrNull() ?: 0
        advertiseCurrentVirtualBracelet(localAdapter)
        state = state.copy(isRunning = callbacks.isNotEmpty(), advertisers = callbacks.size)
        log("虚拟手环池已就绪：${virtualBracelets.joinToString { it.name }}；扫描阶段显示当前待连接手环，连接后推进到下一个")
    }

    fun updateParameters(newParams: DeviceParameters) {
        params = newParams
        log("更新参数：battery=${params.battery}, heartRate=${params.heartRate}, steps=${params.steps}")
    }

    fun sendTemplateReplies(newParams: DeviceParameters) {
        params = newParams
        if (subscribedDevices.isEmpty()) return log("没有已连接设备，无法发送模板回包")
        subscribedDevices.forEach { device ->
            val replies = ProtocolFrames.preview(paramsFor(device))
            if (replies.isEmpty()) log("${device.address} 当前模板没有可发送帧") else replies.forEach { notify(device, it) }
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        cancelPendingAdvance()
        stopAdvertisingOnly()
        gattServer?.close()
        gattServer = null
        notifyCharacteristic = null
        subscribedDevices.clear()
        virtualBracelets.clear()
        deviceAssignments.clear()
        availableQueue.clear()
        state = state.copy(isRunning = false, connectedDevices = 0, advertisers = 0)
    }

    @SuppressLint("MissingPermission")
    private fun advertiseCurrentVirtualBracelet(adapter: BluetoothAdapter) {
        val current = virtualBracelets.getOrNull(advertisingIndex) ?: VirtualBracelet("当前手环", params)
        stopAdvertisingOnly()
        pendingAdvertiseRunnable?.let { queueHandler.removeCallbacks(it) }
        adapter.name = current.params.deviceName.take(18)
        pendingAdvertiseRunnable = Runnable {
            startAdvertising(adapter, current.params, advertisingIndex)
            state = state.copy(isRunning = callbacks.isNotEmpty(), advertisers = callbacks.size)
            log("等待连接 -> ${current.name} (${adapter.name}, HR ${current.params.heartRate}, BAT ${current.params.battery}%, STEP ${current.params.steps})")
            pendingAdvertiseRunnable = null
        }.also {
            queueHandler.postDelayed(it, ADAPTER_NAME_APPLY_DELAY_MS)
            log("切换广播名 -> ${current.params.deviceName.take(18)}，${ADAPTER_NAME_APPLY_DELAY_MS}ms 后开始广播")
        }
    }

    private fun cancelPendingAdvance() {
        advanceRunnable?.let { queueHandler.removeCallbacks(it) }
        advanceRunnable = null
        pendingAdvertiseRunnable?.let { queueHandler.removeCallbacks(it) }
        pendingAdvertiseRunnable = null
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertisingOnly() {
        callbacks.forEach { adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) }
        callbacks.clear()
    }

    @SuppressLint("MissingPermission")
    private fun openGattServer() {
        val service = BluetoothGattService(GATT_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val writeCharacteristic = BluetoothGattCharacteristic(WRITE_CHARACTERISTIC_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE)
        notifyCharacteristic = BluetoothGattCharacteristic(
            NOTIFY_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            addDescriptor(BluetoothGattDescriptor(CLIENT_CONFIG_DESCRIPTOR_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        }
        service.addCharacteristic(writeCharacteristic)
        service.addCharacteristic(notifyCharacteristic)
        gattServer = bluetoothManager.openGattServer(context, gattCallback).also {
            it.addService(service)
            log("GATT Server 已开启：service=$GATT_SERVICE_UUID")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising(adapter: BluetoothAdapter, item: DeviceParameters, index: Int) {
        val mode = when {
            item.advertiseIntervalMs <= 250 -> AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
            item.advertiseIntervalMs <= 1200 -> AdvertiseSettings.ADVERTISE_MODE_BALANCED
            else -> AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(mode)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val payload = byteArrayOf(
            index.toByte(),
            item.heartRate.toByte(),
            item.battery.toByte(),
            (item.steps and 0xFF).toByte(),
            ((item.steps ushr 8) and 0xFF).toByte(),
            ((item.steps ushr 16) and 0xFF).toByte()
        )
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SCAN_SERVICE_UUID))
            .addServiceData(ParcelUuid(SCAN_SERVICE_UUID), payload)
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                log("BLE 广播[$index] 启动成功：${item.deviceName}, ${"%.2f".format(1000f / item.advertiseIntervalMs)} Hz")
            }

            override fun onStartFailure(errorCode: Int) {
                log("BLE 广播[$index] 启动失败：error=$errorCode")
            }
        }
        callbacks.add(callback)
        adapter.bluetoothLeAdvertiser.startAdvertising(settings, data, scanResponse, callback)
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                subscribedDevices.add(device)
                val assigned = assignBraceletForConnection(device)
                log("设备连接：${device.address} -> ${assigned.name} (${assigned.params.deviceName}, HR ${assigned.params.heartRate}, BAT ${assigned.params.battery}%, STEP ${assigned.params.steps})")
                advertiseNextAvailable()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subscribedDevices.remove(device)
                releaseAssignment(device)
                advertiseNextAvailable()
            }
            state = state.copy(connectedDevices = subscribedDevices.size)
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            val assigned = assignedBracelet(device)
            val value = ProtocolFrames.heartRate(assigned.params.heartRate)
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            state = state.copy(lastTx = value.toHex())
            log("READ ${device.address}(${assigned.name}) -> ${value.toHex()}")
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            if (descriptor.uuid == CLIENT_CONFIG_DESCRIPTOR_UUID) {
                subscribedDevices.add(device)
                assignBraceletForConnection(device)
                state = state.copy(connectedDevices = subscribedDevices.size)
                log("NOTIFY 订阅写入：${device.address} value=${value.toHex()}")
            }
            if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            val rx = value.toHex()
            state = state.copy(lastRx = rx)
            val assigned = assignedBracelet(device)
            log("WRITE ${device.address}(${assigned.name}) <- $rx")
            if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            ProtocolFrames.replyTo(value, assigned.params).forEach { notify(device, it) }
        }
    }

    private fun assignedBracelet(device: BluetoothDevice): VirtualBracelet {
        val pool = virtualBracelets.ifEmpty { listOf(VirtualBracelet("当前手环", params)) }
        val index = deviceAssignments[device.address] ?: advertisingIndex.coerceIn(0, pool.lastIndex)
        return pool[index % pool.size]
    }

    @SuppressLint("MissingPermission")
    private fun assignBraceletForConnection(device: BluetoothDevice): VirtualBracelet {
        val pool = virtualBracelets.ifEmpty { listOf(VirtualBracelet("当前手环", params)) }
        val existing = deviceAssignments[device.address]
        if (existing != null) return pool[existing % pool.size]
        val assignedIndex = if (availableQueue.isNotEmpty()) availableQueue.removeFirst() else advertisingIndex.coerceIn(0, pool.lastIndex)
        deviceAssignments[device.address] = assignedIndex
        return pool[assignedIndex % pool.size]
    }

    private fun releaseAssignment(device: BluetoothDevice) {
        val releasedIndex = deviceAssignments.remove(device.address)
        if (releasedIndex == null) {
            log("设备断开：${device.address}，无绑定手环")
            return
        }
        if (releasedIndex !in availableQueue) availableQueue.addLast(releasedIndex)
        val released = virtualBracelets.getOrNull(releasedIndex)
        log("设备断开：${device.address}，${released?.name ?: "手环$releasedIndex"} 已回到队尾")
    }

    @SuppressLint("MissingPermission")
    private fun advertiseNextAvailable() {
        val pool = virtualBracelets
        if (pool.isEmpty()) return
        val nextIndex = availableQueue.firstOrNull() ?: run {
            stopAdvertisingOnly()
            state = state.copy(isRunning = subscribedDevices.isNotEmpty(), advertisers = callbacks.size)
            log("当前无空闲虚拟手环，停止等待广播，保持现有连接分别响应")
            return
        }
        val localAdapter = adapter ?: return
        cancelPendingAdvance()
        advanceRunnable = Runnable {
            advertisingIndex = nextIndex
            advertiseCurrentVirtualBracelet(localAdapter)
            advanceRunnable = null
        }.also {
            queueHandler.postDelayed(it, ADVANCE_ADVERTISING_DELAY_MS)
            log("${ADVANCE_ADVERTISING_DELAY_MS}ms 后切换等待 ${pool[nextIndex].name}")
        }
    }

    private fun paramsFor(device: BluetoothDevice): DeviceParameters = assignedBracelet(device).params

    @SuppressLint("MissingPermission")
    private fun notify(device: BluetoothDevice, value: ByteArray) {
        val characteristic = notifyCharacteristic ?: return
        characteristic.value = value
        gattServer?.notifyCharacteristicChanged(device, characteristic, false)
        state = state.copy(lastTx = value.toHex())
        log("NOTIFY ${device.address}(${assignedBracelet(device).name}) -> ${value.toHex()}")
    }
}

private object ProtocolFrames {
    fun replyTo(command: ByteArray, params: DeviceParameters): List<ByteArray> {
        if (command.isEmpty()) return emptyList()
        if (params.overrideAutoReplies && params.replyTemplate != ReplyTemplate.Auto) return repliesForTemplate(params.replyTemplate, params)
        return when (command[0].toInt() and 0xFF) {
            0x01 -> listOf(frame(0x01))
            0x03 -> listOf(frame(0x03, params.battery))
            0x0A -> listOf(frame(0x0A, 0x01, 0, 0, 0, 0, 0, 0, 0, 0))
            0x15 -> historyHeartRate(params.heartRate)
            0x16 -> listOf(frame(0x16, 0x01, 0x01))
            0x44 -> sleepFrames()
            0x69 -> listOf(heartRate(params.heartRate))
            else -> listOf(frame(command[0].toInt() and 0xFF))
        }
    }

    fun preview(params: DeviceParameters): List<ByteArray> = repliesForTemplate(params.replyTemplate, params)
    fun heartRate(heartRate: Int): ByteArray = frame(0x69, 0x01, 0x00, heartRate.coerceIn(30, 220))

    private fun repliesForTemplate(template: ReplyTemplate, params: DeviceParameters): List<ByteArray> {
        val replies = when (template) {
            ReplyTemplate.Auto -> emptyList()
            ReplyTemplate.ManualDecimal -> listOfNotNull(manualFrame(parseDecimalBytes(params.decimalFrameInput), params.autoChecksum, params.corruptChecksum))
            ReplyTemplate.ManualHex -> listOfNotNull(manualFrame(parseHexBytes(params.hexFrameInput), params.autoChecksum, params.corruptChecksum))
            ReplyTemplate.TimeAck -> listOf(frame(0x01))
            ReplyTemplate.Battery -> listOf(frame(0x03, params.battery))
            ReplyTemplate.Settings -> listOf(frame(0x0A, 0x01, 0, 0, 0, 0, 0, 0, 0, 0))
            ReplyTemplate.HistoryHeartRate -> historyHeartRate(params.heartRate)
            ReplyTemplate.HeartRateSwitch -> listOf(frame(0x16, 0x01, 0x01))
            ReplyTemplate.Sleep -> sleepFrames()
            ReplyTemplate.RealtimeHeartRate -> listOf(heartRate(params.heartRate))
            ReplyTemplate.EchoAck -> listOf(frame(0xEE))
        }
        return if (params.corruptChecksum && template !in listOf(ReplyTemplate.ManualDecimal, ReplyTemplate.ManualHex)) replies.map { it.withCorruptChecksum() } else replies
    }

    private fun historyHeartRate(heartRate: Int): List<ByteArray> {
        val nowSeconds = (System.currentTimeMillis() / 1000).toInt()
        val first = mutableListOf(0x15, 0x01)
        first.addAll(nowSeconds.toBigEndianBytes())
        repeat(9) { first.add((heartRate + (it % 7) - 3).coerceIn(30, 220)) }
        return listOf(frame(*first.toIntArray()), frame(0x15, 0xFF))
    }

    private fun sleepFrames(): List<ByteArray> {
        val calendar = java.util.Calendar.getInstance()
        return listOf(
            frame(0x44, 0xF0, 0, calendar.get(java.util.Calendar.YEAR) % 100, calendar.get(java.util.Calendar.MONTH) + 1, calendar.get(java.util.Calendar.DAY_OF_MONTH)),
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

    private fun manualFrame(bytes: List<Int>, autoChecksum: Boolean, corruptChecksum: Boolean): ByteArray? {
        if (bytes.isEmpty()) return null
        val out = IntArray(16)
        bytes.take(16).forEachIndexed { index, value -> out[index] = value and 0xFF }
        if (autoChecksum) out[15] = out.take(15).sum() and 0xFF
        if (corruptChecksum) out[15] = (out[15] + 1) and 0xFF
        return ByteArray(16) { out[it].toByte() }
    }

    private fun parseDecimalBytes(input: String): List<Int> =
        input.split(Regex("[,;\\s]+")).filter { it.isNotBlank() }.mapNotNull { it.toIntOrNull()?.coerceIn(0, 255) }

    private fun parseHexBytes(input: String): List<Int> {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return emptyList()
        val tokens = trimmed.split(Regex("[,;\\s]+")).filter { it.isNotBlank() }
        val normalized = if (tokens.size > 1) tokens else trimmed.replace("0x", "", ignoreCase = true)
            .filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
            .chunked(2)
        return normalized.mapNotNull { it.removePrefix("0x").removePrefix("0X").toIntOrNull(16)?.coerceIn(0, 255) }
    }

    private fun Int.toBigEndianBytes(): List<Int> = listOf((this ushr 24) and 0xFF, (this ushr 16) and 0xFF, (this ushr 8) and 0xFF, this and 0xFF)
    private fun ByteArray.withCorruptChecksum(): ByteArray = copyOf().also { it[15] = (((it[15].toInt() and 0xFF) + 1) and 0xFF).toByte() }
}

private fun DeviceParameters.toConfig(name: String): BraceletConfig =
    BraceletConfig(name, deviceName, heartRate.toString(), battery.toString(), steps.toString(), advertiseIntervalMs, replyTemplate, autoChecksum, corruptChecksum, overrideAutoReplies, decimalFrameInput, hexFrameInput)

private fun List<BraceletConfig>.ensureRepository(): List<BraceletConfig> =
    if (isEmpty()) listOf(AppSettings().toRepositoryItem("默认手环")) else this

private fun normalizeRepositorySelection(
    repository: List<BraceletConfig>,
    selectedNames: Set<String>,
    settings: AppSettings
): Set<String> {
    val safeRepo = repository.ensureRepository()
    val selectedInOrder = safeRepo.filter { it.name in selectedNames }
    val nonEmpty = selectedInOrder.ifEmpty { listOf(safeRepo.first()) }
    val limited = if (settings.batchEnabled) {
        nonEmpty.take(settings.batchCount.decimalIn(1, 1, 8))
    } else {
        nonEmpty.take(1)
    }
    return limited.map { it.name }.toSet()
}

private fun effectiveRepositorySelection(
    repository: List<BraceletConfig>,
    selectedNames: Set<String>,
    settings: AppSettings
): List<BraceletConfig> {
    val selected = repository.filter { it.name in selectedNames }.ifEmpty { listOf(repository.ensureRepository().first()) }
    if (!settings.batchEnabled) return selected.take(1)
    return selected.take(settings.batchCount.decimalIn(1, 1, 8))
}

private fun runtimeParameters(
    repository: List<BraceletConfig>,
    selectedNames: Set<String>,
    settings: AppSettings
): DeviceParameters {
    val base = effectiveRepositorySelection(repository, selectedNames, settings)
        .firstOrNull()
        ?.toSettings(settings)
        ?: settings
    return base.copy(
        heartRate = if (settings.autoHeartEnabled) settings.heartRate else base.heartRate,
        steps = if (settings.autoStepsEnabled) settings.steps else base.steps,
        advertiseIntervalMs = settings.advertiseIntervalMs
    ).toParameters()
}

private fun uniqueRepositoryName(name: String, repository: List<BraceletConfig>): String {
    val base = name.ifBlank { "未命名手环" }
    if (repository.none { it.name == base }) return base
    var index = 2
    while (repository.any { it.name == "$base-$index" }) index++
    return "$base-$index"
}

private fun simulatedHeartRate(settings: AppSettings): Int {
    val min = settings.heartMin.decimalIn(62, 30, 220)
    val max = settings.heartMax.decimalIn(96, min, 220)
    val jitter = settings.heartJitter.decimalIn(2, 0, 30)
    if (settings.heartMode == HeartMode.Random) {
        val current = settings.heartRate.decimalIn((min + max) / 2, min, max)
        val low = (current - jitter.coerceAtLeast(1)).coerceAtLeast(min)
        val high = (current + jitter.coerceAtLeast(1)).coerceAtMost(max)
        return Random.nextInt(low, high + 1)
    }
    val period = settings.heartCurvePeriodSeconds.decimalIn(36, 10, 600).toDouble()
    val phase = (System.currentTimeMillis() / 1000.0) / period * 2.0 * PI
    val base = min + (max - min) / 2.0
    val wave = sin(phase) * (max - min) / 2.8
    val noise = if (jitter == 0) 0 else Random.nextInt(-jitter, jitter + 1)
    return (base + wave + noise).roundToInt().coerceIn(min, max)
}

private fun trimHeartHistory(history: MutableList<HeartSample>) {
    val cutoff = System.currentTimeMillis() - HEART_HISTORY_WINDOW_MS
    while (history.size > 1 && history.first().timestampMs < cutoff) history.removeAt(0)
    if (history.size > MAX_HEART_HISTORY_POINTS) {
        history.subList(0, history.size - MAX_HEART_HISTORY_POINTS).clear()
    }
}

private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
private fun String.decimalIn(default: Int, min: Int, max: Int): Int = toIntOrNull()?.coerceIn(min, max) ?: default.coerceIn(min, max)
private fun Int.toHz(): Float = (1000f / coerceIn(100, 5000)).coerceIn(0.2f, 10f)
private fun Float.toAdvertiseIntervalMs(): Int = (1000f / coerceIn(0.2f, 10f)).roundToInt().coerceIn(100, 5000)

private fun blePermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
} else {
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

private fun Array<String>.allGranted(context: Context): Boolean = all { context.checkSelfPermissionCompat(it) }
private fun Context.checkSelfPermissionCompat(permission: String): Boolean = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
