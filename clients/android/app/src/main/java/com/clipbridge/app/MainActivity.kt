package com.clipbridge.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.clipbridge.app.data.Prefs
import com.clipbridge.app.data.StatusHolder
import com.clipbridge.app.service.ClipClient
import com.clipbridge.app.service.ClipboardAccessibilityService
import com.clipbridge.app.service.KeepAlive
import com.clipbridge.app.service.ScreenshotWatcher
import com.clipbridge.app.service.SyncEngine
import com.clipbridge.app.service.SyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val OK_GREEN = Color(0xFF2E7D32)
private val WARN_RED = Color(0xFFC62828)
private val WARN_AMBER = Color(0xFFE65100)

/** 界面只有两页：主页（状态 + 日志）与设置页。 */
private enum class Screen { HOME, SETTINGS }

class MainActivity : ComponentActivity() {

    /** 每次回到前台自增，用来重新检测无障碍/电池优化等会随用户操作变化的状态 */
    private val resumeTick = mutableIntStateOf(0)

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AppRoot(
                    resumeTick = resumeTick.intValue,
                    requestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(
                                this, Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                )
            }
        }
    }
}

/** 判断本应用的无障碍服务是否已在系统设置里开启 */
fun isAccessibilityEnabled(ctx: Context): Boolean {
    val component = ComponentName(ctx, ClipboardAccessibilityService::class.java)
    val long = component.flattenToString()
    val short = component.flattenToShortString()
    val enabled = Settings.Secure.getString(
        ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabled.split(':').any { it.equals(long, true) || it.equals(short, true) }
}

/** 读取当前安装包的版本号，显示在设置页里 —— 排障第一步永远是"你装的是哪一版" */
private fun appVersion(ctx: Context): String = try {
    @Suppress("DEPRECATION")
    val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        @Suppress("DEPRECATION") info.versionCode.toLong()
    }
    "${info.versionName}（build $code）"
} catch (e: Exception) {
    "未知"
}

/**
 * 两页共享的状态与动作。
 *
 * 抽成一个类而不是散在一堆 `remember` 里：配对、后台保护、截图、诊断配置
 * 在两个页面上都要读要写，散着放很容易漏掉某处的刷新。
 */
private class AppState(val context: Context, val scope: CoroutineScope) {

    var screen by mutableStateOf(Screen.HOME)

    // ---- 配对 ----
    var serverUrl by mutableStateOf(Prefs.getServerUrl(context))
    var pairCode by mutableStateOf(Prefs.getPairCode(context))
    var deviceName by mutableStateOf(Prefs.getDeviceName(context).ifEmpty { "我的安卓手机" })

    /** 已配对 = 有 Token。它同时决定三个输入框是否可编辑。 */
    var paired by mutableStateOf(Prefs.hasToken(context))

    var busy by mutableStateOf(false)
    var message by mutableStateOf("")
    var showUnpairDialog by mutableStateOf(false)

    // ---- 开关 ----
    var bgProtect by mutableStateOf(Prefs.isBackgroundProtectEnabled(context))
    var screenshotSync by mutableStateOf(
        Prefs.isScreenshotSyncEnabled(context) && ScreenshotWatcher.hasPermission(context)
    )

    // ---- 诊断记录的条数与保留时长 ----
    var diagMax by mutableIntStateOf(Prefs.diagMax(context))
    var diagTtlMin by mutableIntStateOf(Prefs.diagTtlMinutes(context))

    /** 回到前台时重新读一遍这些状态（用户可能刚在系统设置里改过） */
    fun refreshOnResume() {
        paired = Prefs.hasToken(context)
        bgProtect = Prefs.isBackgroundProtectEnabled(context)
        screenshotSync =
            Prefs.isScreenshotSyncEnabled(context) && ScreenshotWatcher.hasPermission(context)
    }

    fun updateDiagMax(value: Int) {
        diagMax = value
        Prefs.setDiagMax(context, value)
        StatusHolder.applyConfig(diagMax, Prefs.diagTtlMs(context))
    }

    fun updateDiagTtl(minutes: Int) {
        diagTtlMin = minutes
        Prefs.setDiagTtlMinutes(context, minutes)
        StatusHolder.applyConfig(diagMax, Prefs.diagTtlMs(context))
    }

    fun pair() {
        if (serverUrl.isBlank() || pairCode.isBlank()) {
            message = "请填写服务端地址和配对码"
            return
        }
        busy = true
        message = ""
        scope.launch {
            val deviceId = Prefs.deviceId(context)
            val result = withContext(Dispatchers.IO) {
                ClipClient.pair(serverUrl, pairCode, deviceId, deviceName)
            }
            busy = false
            if (result.ok) {
                // 连同配对码一起落盘（commit 同步写），
                // 这样进程被杀也不会出现"配对成功但重启后又要重新配对"
                Prefs.save(context, serverUrl, result.token, deviceId, deviceName, pairCode)
                paired = true
                message = "配对成功，重启 App 后仍然有效"
                // 无障碍服务在的话连接已经在跑，重启一下让它换用新 Token
                SyncEngine.restart()
                if (Prefs.isBackgroundProtectEnabled(context)) {
                    SyncService.start(context)
                    KeepAlive.scheduleWatchdog(context)
                }
            } else {
                message = "配对失败：${result.error}"
            }
        }
    }

    /**
     * 取消配对。
     *
     * 三件事缺一不可，否则会出现"界面显示未配对、后台却还在连"的错位：
     *   1. 清掉 Token（连接的前提条件）
     *   2. 强制断开现有连接 —— 不能靠 release()，因为无障碍服务仍然持有引用，
     *      它会觉得自己该继续连着
     *   3. 停掉后台保护服务与看门狗 —— 否则它会每 5 分钟醒一次做无用功
     */
    fun unpair() {
        Prefs.clearPairing(context)
        SyncEngine.disconnectAll()
        SyncService.stop(context)
        KeepAlive.cancelWatchdog(context)

        paired = false
        message = "已取消配对，现在可以修改上面的信息了"
        StatusHolder.addDiag("已取消配对，连接已断开")
    }
}

@Composable
fun AppRoot(resumeTick: Int, requestNotificationPermission: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { AppState(context, scope) }

    val connected by StatusHolder.connected.collectAsState()
    val statusText by StatusHolder.statusText.collectAsState()
    val diag by StatusHolder.diag.collectAsState()

    val accessibilityOn = remember(resumeTick) { isAccessibilityEnabled(context) }
    val batteryOk = remember(resumeTick) { KeepAlive.isIgnoringBatteryOptimizations(context) }

    // 从系统设置回来后同步一遍内部状态。
    // 用 LaunchedEffect 而不是 remember(resumeTick)：后者会在**组合期**改状态，
    // 属于反模式，改动可能被同一帧的其它读取覆盖掉。
    LaunchedEffect(resumeTick) { state.refreshOnResume() }

    // 截图同步要读相册，属于敏感权限，默认关闭。
    // 开关只在权限真正拿到之后才落地 —— 否则会出现"显示已开启但实际不工作"的假象。
    val screenshotPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Prefs.setScreenshotSync(context, granted)
        state.screenshotSync = granted
        state.message = if (granted) "截图同步已开启" else "没有相册权限，截图同步无法开启"
        // 让正在运行的无障碍服务立刻重新评估开关，不用手动重启服务
        ClipboardAccessibilityService.notifyPrefsChanged()
    }

    // 诊断记录超过保留时长后自动淘汰。定时做，而不是只在新事件到来时做 ——
    // 否则没有新事件时列表不会收缩，旧记录会一直挂在界面上。
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            StatusHolder.pruneDiag()
        }
    }

    when (state.screen) {
        Screen.HOME -> HomeScreen(
            state = state,
            connected = connected,
            statusText = statusText,
            diag = diag,
            accessibilityOn = accessibilityOn,
            batteryOk = batteryOk,
            onOpenSettings = { state.screen = Screen.SETTINGS },
        )

        Screen.SETTINGS -> {
            // 系统返回键也要能回到主页，不能退出 App
            BackHandler { state.screen = Screen.HOME }
            SettingsScreen(
                state = state,
                accessibilityOn = accessibilityOn,
                batteryOk = batteryOk,
                onBack = { state.screen = Screen.HOME },
                requestNotificationPermission = requestNotificationPermission,
                screenshotPermLauncher = screenshotPermLauncher,
            )
        }
    }
}

// ==================== 主页 ====================

/**
 * 主页只放"看"的东西：连接状态、各项开关的概览、上行诊断日志。
 * 所有"改"的入口都收进了设置页。
 */
@Composable
private fun HomeScreen(
    state: AppState,
    connected: Boolean,
    statusText: String,
    diag: List<com.clipbridge.app.data.DiagEntry>,
    accessibilityOn: Boolean,
    batteryOk: Boolean,
    onOpenSettings: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ClipBridge", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onOpenSettings) { Text("设置") }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            when {
                connected -> "已连接"
                state.paired -> "未连接"
                else -> "尚未配对"
            },
            color = when {
                connected -> OK_GREEN
                state.paired -> WARN_AMBER
                else -> WARN_RED
            },
            style = MaterialTheme.typography.bodyLarge
        )
        if (statusText.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(statusText, style = MaterialTheme.typography.bodySmall)
        }

        if (!state.paired) {
            Spacer(Modifier.height(10.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("还没有配对", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "到「设置 → 配对」里填写服务端地址与配对码。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // ---------- 状态概览 ----------
        Spacer(Modifier.height(16.dp))
        Text("状态", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                OverviewRow(
                    "配对",
                    if (state.paired) "已配对" else "未配对",
                    level = if (state.paired) 1 else 0,
                )
                OverviewRow(
                    "无感同步",
                    if (accessibilityOn) "无障碍服务已开启" else "无障碍服务未开启",
                    level = if (accessibilityOn) 1 else 0,
                )
                OverviewRow(
                    "后台保护",
                    if (!state.bgProtect) {
                        "已关闭"
                    } else if (batteryOk) {
                        "已开启 · 已加入电池白名单"
                    } else {
                        "已开启 · 未加入电池白名单"
                    },
                    level = when {
                        !state.bgProtect -> 2
                        batteryOk -> 1
                        else -> 0
                    },
                )
                OverviewRow(
                    "截图同步",
                    when {
                        !state.screenshotSync -> "已关闭"
                        accessibilityOn -> "已开启"
                        else -> "已开启（但无障碍没开，实际不工作）"
                    },
                    level = when {
                        !state.screenshotSync -> 2
                        accessibilityOn -> 1
                        else -> 0
                    },
                )
                OverviewRow("版本", appVersion(state.context), level = 1)
            }
        }

        // ---------- 上行诊断 ----------
        // 「本机复制了但对面没收到」这类问题，从服务端只看得到"零请求"，
        // 完全无法区分是设备没感知到复制、还是感知到了但发送失败。
        // 把最近的事件就地渲染出来，用户不用连电脑抓 logcat。
        //
        // 界面约束：外面整体是可滚动的一列，如果这里再直接把几十条文本铺开，
        // 整个页面会被撑得很长。所以固定 200dp 高度、内部自己滚动，并在有新增时贴住底部。
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("上行诊断", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
            Text(
                "${diag.size} 条 · 保留 ${Prefs.formatTtl(state.diagTtlMin)}",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { StatusHolder.clearDiag() }) { Text("清空") }
        }
        Spacer(Modifier.height(4.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()
        ) {
            val scroll = rememberScrollState()
            LaunchedEffect(diag.size) {
                // 等这一帧布局完成后再读 maxValue，否则拿到的还是滚动前的值
                delay(60)
                // 用户主动往上翻过就不打扰；只在自己贴底时跟随新内容
                if (scroll.maxValue == 0 || scroll.value >= scroll.maxValue - 48) {
                    scroll.animateScrollTo(scroll.maxValue)
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .verticalScroll(scroll)
                    .padding(10.dp)
            ) {
                if (diag.isEmpty()) {
                    Text(
                        "暂无记录",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    )
                } else {
                    Column {
                        diag.forEach { entry ->
                            Text(
                                entry.text,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "只记录长度、类型与命中与否，不记录剪贴板内容本身。",
            style = MaterialTheme.typography.bodySmall
        )
        TextButton(onClick = onOpenSettings) { Text("调整记录条数与保留时长 →") }
    }
}

/**
 * 概览里的一行。level：1 正常（绿）、0 需要注意（橙）、2 已关闭（灰）。
 * 用颜色而不是图标：状态行很密，图标反而更乱。
 */
@Composable
private fun OverviewRow(label: String, value: String, level: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(76.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = when (level) {
                1 -> OK_GREEN
                0 -> WARN_AMBER
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

// ==================== 设置页 ====================

@Composable
private fun SettingsScreen(
    state: AppState,
    accessibilityOn: Boolean,
    batteryOk: Boolean,
    onBack: () -> Unit,
    requestNotificationPermission: () -> Unit,
    screenshotPermLauncher: androidx.activity.result.ActivityResultLauncher<String>,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Spacer(Modifier.width(4.dp))
            Text("设置", style = MaterialTheme.typography.headlineSmall)
        }

        // ---------- 配对 ----------
        Spacer(Modifier.height(12.dp))
        SectionTitle("配对")
        Spacer(Modifier.height(8.dp))

        if (state.paired) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("已配对", style = MaterialTheme.typography.bodyMedium, color = OK_GREEN)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "下面的信息已锁定，避免误改之后连接对不上。需要修改请先「取消配对」。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // 配对成功后这三个输入框变灰不可编辑 —— 它们决定"连到哪、以什么身份连"，
        // 允许随手改的结果是"看起来还在用，其实已经连不上了"，而且很难自己发现。
        OutlinedTextField(
            value = state.serverUrl,
            onValueChange = { state.serverUrl = it },
            label = { Text("服务端地址") },
            placeholder = { Text("https://nas.example.com:8443") },
            singleLine = true,
            enabled = !state.paired,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = state.pairCode,
            onValueChange = { state.pairCode = it },
            label = { Text("配对码") },
            supportingText = {
                Text(
                    if (state.paired) {
                        "已配对"
                    } else if (Prefs.getPairCode(state.context).isNotEmpty()) {
                        "已记住上次使用的配对码，换设备或改过服务端配置时才需要改"
                    } else {
                        "服务端启动日志里会打印这个码"
                    }
                )
            },
            singleLine = true,
            enabled = !state.paired,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = state.deviceName,
            onValueChange = { state.deviceName = it },
            label = { Text("设备名") },
            supportingText = { Text("同步时对端会看到这个名字，用来区分是哪台设备") },
            singleLine = true,
            enabled = !state.paired,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))
        if (state.paired) {
            Button(
                onClick = { state.showUnpairDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("取消配对") }
        } else {
            Button(
                onClick = { state.pair() },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (state.busy) "配对中…" else "配对") }
        }

        if (state.message.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(state.message, style = MaterialTheme.typography.bodySmall)
        }

        if (state.showUnpairDialog) {
            AlertDialog(
                onDismissRequest = { state.showUnpairDialog = false },
                title = { Text("取消配对？") },
                text = {
                    Text(
                        "会断开与服务端的连接、停掉后台保护。\n" +
                            "服务端地址、配对码、设备名会保留下来，重新配对时不用重敲。"
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        state.showUnpairDialog = false
                        state.unpair()
                    }) { Text("取消配对") }
                },
                dismissButton = {
                    TextButton(onClick = { state.showUnpairDialog = false }) { Text("再想想") }
                },
            )
        }

        SectionDivider()

        // ---------- 无感同步 ----------
        SectionTitle("无感同步")
        Spacer(Modifier.height(4.dp))
        Text(
            if (accessibilityOn)
                "无障碍服务已开启 —— 本机复制会自动同步，收到内容会直接写进剪贴板。"
            else
                "需要开启无障碍服务才能在后台感知你的复制操作。这是 Android 10 之后的系统限制，" +
                    "没有任何权限可以替代。",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                state.context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (accessibilityOn) "无障碍服务已开启（点此查看）" else "去开启无障碍服务")
        }

        SectionDivider()

        // ---------- 后台保护 ----------
        SectionTitle("后台保护")
        Spacer(Modifier.height(4.dp))
        Text(
            "让同步在 App 被划出最近任务之后继续工作。\n" +
                "Android 要求前台服务必须挂一条通知，无法隐藏 —— 那条通知就是它的标识，" +
                "清掉通知不会让它停止；万一被系统杀掉，看门狗会自动恢复。\n" +
                "唯一扛不住的是在系统设置里「强行停止」—— 那之后任何应用都无法自启。",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        SwitchRow(
            title = "开启后台保护",
            subtitle = if (state.bgProtect)
                "已开启 —— 通知栏显示「ClipBridge 后台同步中」"
            else
                "已关闭 —— App 被划掉后可能掉线",
            subtitleColor = if (state.bgProtect) OK_GREEN else WARN_AMBER,
            checked = state.bgProtect,
            onCheckedChange = { value ->
                state.bgProtect = value
                Prefs.setBackgroundProtect(state.context, value)
                if (value) {
                    requestNotificationPermission()
                    if (Prefs.hasToken(state.context)) {
                        val ok = SyncService.start(state.context)
                        state.message = if (ok) {
                            "后台保护已开启"
                        } else {
                            "系统拒绝了启动请求，请退出后重新打开一次 App"
                        }
                    } else {
                        state.message = "后台保护已开启，配对后即生效"
                    }
                    KeepAlive.scheduleWatchdog(state.context)
                } else {
                    SyncService.stop(state.context)
                    KeepAlive.cancelWatchdog(state.context)
                    state.message = "后台保护已关闭"
                }
            },
        )

        // 电池优化：不进白名单的话，Doze 会静默掐掉后台网络，
        // 表现是"手机放一会儿就收不到东西"，而且没有任何报错。
        ActionRow(
            title = "忽略电池优化",
            subtitle = if (batteryOk)
                "已加入白名单 —— 系统休眠时不会切断后台连接"
            else
                "未加入 —— 手机深度休眠一段时间后可能断连",
            subtitleColor = if (batteryOk) OK_GREEN else WARN_AMBER,
            actionLabel = if (batteryOk) "查看" else "去加入",
            onAction = { KeepAlive.requestIgnoreBatteryOptimizations(state.context) },
        )

        // 自启动：国产 ROM 会独立控制这一项，和电池优化白名单互不替代
        ActionRow(
            title = "允许自启动",
            subtitle = "小米 / 华为 / OPPO / vivo 等系统需要单独允许，" +
                "否则划掉 App 后不会被重新拉起。",
            subtitleColor = null,
            actionLabel = "去设置",
            onAction = { KeepAlive.openAutoStartSettings(state.context) },
        )

        SectionDivider()

        // ---------- 截图同步 ----------
        SectionTitle("截图同步")
        Spacer(Modifier.height(4.dp))
        SwitchRow(
            title = "自动同步本机截图",
            subtitle = "开启后，本机新拍的截图会自动上传并同步到电脑。\n" +
                "需要「${ScreenshotWatcher.requiredPermission().substringAfterLast('.')}」权限 —— " +
                "这是读取相册级别的权限，所以默认关闭，由你决定。\n" +
                "只同步开启之后新产生的截图，相册里的历史截图不会被补发。" +
                if (state.screenshotSync && !accessibilityOn)
                    "\n\n⚠️ 已开启，但无障碍服务没开，实际不工作。"
                else "",
            subtitleColor = if (state.screenshotSync && !accessibilityOn) WARN_RED else null,
            checked = state.screenshotSync,
            onCheckedChange = { value ->
                if (value) {
                    if (ScreenshotWatcher.hasPermission(state.context)) {
                        Prefs.setScreenshotSync(state.context, true)
                        state.screenshotSync = true
                        ClipboardAccessibilityService.notifyPrefsChanged()
                    } else {
                        screenshotPermLauncher.launch(ScreenshotWatcher.requiredPermission())
                    }
                } else {
                    Prefs.setScreenshotSync(state.context, false)
                    state.screenshotSync = false
                    ClipboardAccessibilityService.notifyPrefsChanged()
                }
            },
        )

        SectionDivider()

        // ---------- 诊断记录 ----------
        SectionTitle("诊断记录")
        Spacer(Modifier.height(4.dp))
        Text(
            "主页那块「上行诊断」的容量。排障时留得越多、看得越久，代价是内存占用略增。" +
                "只是想知道刚才为什么没同步，200 条 / 2 小时就够了。",
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(10.dp))
        Text("保留条数", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        ChipGrid(
            options = Prefs.DIAG_MAX_CHOICES,
            selected = state.diagMax,
            labelFor = { "$it 条" },
            onSelect = { state.updateDiagMax(it) },
        )

        Spacer(Modifier.height(12.dp))
        Text("保留时长", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        ChipGrid(
            options = Prefs.DIAG_TTL_CHOICES_MIN,
            selected = state.diagTtlMin,
            labelFor = { Prefs.formatTtl(it) },
            onSelect = { state.updateDiagTtl(it) },
        )

        Spacer(Modifier.height(12.dp))
        Text(
            "当前：最多 ${state.diagMax} 条，超过 ${Prefs.formatTtl(state.diagTtlMin)} 的记录自动清掉。",
            style = MaterialTheme.typography.bodySmall,
            color = OK_GREEN,
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { StatusHolder.clearDiag() }) { Text("立即清空诊断记录") }

        SectionDivider()

        // ---------- 关于 ----------
        SectionTitle("关于")
        Spacer(Modifier.height(4.dp))
        Text("版本：${appVersion(state.context)}", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(10.dp))
        Text("使用说明", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "· 本机复制文本 → 自动同步到电脑（需要开启无障碍服务）\n" +
                "· 本机截图 → 自动同步（需单独开启上面的「截图同步」）\n" +
                "· 电脑复制文本 / 截图 → 自动写进本机剪贴板，同时存一份到相册 Pictures/ClipBridge\n" +
                "· 少数 App 的复制按钮无障碍服务识别不到，可用「分享 → ClipBridge」兜底\n" +
                "· 同步只针对你当下这一次复制，历史内容不会被重发",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(24.dp))
    }
}

// ==================== 小部件 ====================

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(16.dp))
    Text(text, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun SectionDivider() {
    Spacer(Modifier.height(20.dp))
    HorizontalDivider()
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    subtitleColor: Color?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.padding(end = 12.dp).weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = subtitleColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ActionRow(
    title: String,
    subtitle: String,
    subtitleColor: Color?,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.padding(end = 12.dp).weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = subtitleColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

/**
 * 每行两个的选项网格。
 *
 * 没用 FlowRow：它的自动换行在窄屏上偶尔会把最后一个 chip 挤到看不见的位置，
 * 这里选项数量固定且很少，两列固定布局更好预期。
 */
@Composable
private fun <T> ChipGrid(
    options: List<T>,
    selected: T,
    labelFor: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column {
        options.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth()) {
                pair.forEach { option ->
                    FilterChip(
                        selected = option == selected,
                        onClick = { onSelect(option) },
                        label = { Text(labelFor(option)) },
                        modifier = Modifier.padding(end = 8.dp, bottom = 4.dp),
                    )
                }
            }
        }
    }
}
