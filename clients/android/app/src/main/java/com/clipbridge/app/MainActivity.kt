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
import androidx.compose.material3.Button
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val OK_GREEN = Color(0xFF2E7D32)
private val WARN_RED = Color(0xFFC62828)
private val WARN_AMBER = Color(0xFFE65100)

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
                MainScreen(
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

@Composable
fun MainScreen(resumeTick: Int, requestNotificationPermission: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var serverUrl by remember { mutableStateOf(Prefs.getServerUrl(context)) }
    // 回填上次成功配对用的码。以前这里固定从空串开始，
    // 于是"重启后配对码没了"看上去像是配置丢了 —— 其实只是没存。
    var pairCode by remember { mutableStateOf(Prefs.getPairCode(context)) }
    var deviceName by remember {
        mutableStateOf(Prefs.getDeviceName(context).ifEmpty { "我的安卓手机" })
    }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    // 记住"上次成功配对用过的码"，只用来给输入框配一句提示。
    // 单独存一份是为了避免在 composition 里反复读 SharedPreferences。
    var savedPairCode by remember { mutableStateOf(Prefs.getPairCode(context)) }

    val connected by StatusHolder.connected.collectAsState()
    val statusText by StatusHolder.statusText.collectAsState()
    val diag by StatusHolder.diag.collectAsState()

    // 无障碍状态必须在回到前台时重新读，用户可能刚在系统设置里把它关掉
    val accessibilityOn = remember(resumeTick) { isAccessibilityEnabled(context) }
    var bgProtect by remember(resumeTick) { mutableStateOf(Prefs.isBackgroundProtectEnabled(context)) }
    var batteryOk by remember(resumeTick) {
        mutableStateOf(KeepAlive.isIgnoringBatteryOptimizations(context))
    }
    val hasToken = Prefs.hasToken(context)

    // 截图同步要读相册，属于敏感权限，默认关闭。
    // 开关只在权限真正拿到之后才落地 —— 否则会出现"显示已开启但实际不工作"的假象。
    var screenshotSync by remember(resumeTick) {
        mutableStateOf(Prefs.isScreenshotSyncEnabled(context) && ScreenshotWatcher.hasPermission(context))
    }
    val screenshotPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Prefs.setScreenshotSync(context, granted)
        screenshotSync = granted
        message = if (granted) {
            "截图同步已开启"
        } else {
            "没有相册权限，截图同步无法开启"
        }
        // 让正在运行的无障碍服务立刻重新评估开关，不用手动重启服务
        ClipboardAccessibilityService.notifyPrefsChanged()
    }

    // 诊断记录超过保留时长的自动淘汰。定时做，而不是只在新事件到来时做 ——
    // 否则没有新事件时列表不会收缩，旧记录会一直挂在界面上。
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            StatusHolder.pruneDiag()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text("ClipBridge", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                connected -> "已连接"
                hasToken -> "未连接"
                else -> "尚未配对"
            },
            color = if (connected) OK_GREEN else WARN_RED,
            style = MaterialTheme.typography.bodyLarge
        )
        if (statusText.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(statusText, style = MaterialTheme.typography.bodySmall)
        }

        // ---------- 上行诊断 ----------
        // 「本机复制了但对面没收到」这类问题，从服务端只看得到"零请求"，
        // 完全无法区分是设备没感知到复制、还是感知到了但发送失败。
        // 把最近的事件就地渲染出来，用户不用连电脑抓 logcat。
        //
        // 界面约束：外面整体是可滚动的一列，如果这里再直接把几十条文本铺开，
        // 整个页面会被撑得很长，别的设置项要滑半天才能看到。所以固定 180dp 高度、
        // 内部自己滚动，并在有新增时贴住底部。
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("上行诊断", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(8.dp))
            Text(
                "${diag.size} 条 · 保留 2 小时",
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
                    .height(180.dp)
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

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        // ---------- 无障碍服务：上行感知的核心 ----------
        Text("无感同步", style = MaterialTheme.typography.titleMedium)
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
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (accessibilityOn) "无障碍服务已开启（点此查看）" else "去开启无障碍服务")
        }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        // ---------- 后台保护 ----------
        Text("后台保护", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "让同步在 App 被划出最近任务之后继续工作。\n" +
                "Android 要求前台服务必须挂一条通知，无法隐藏 —— 那条通知就是它的标识，" +
                "清掉通知不会让它停止；万一被系统杀掉，看门狗会自动恢复。\n" +
                "唯一扛不住的是在系统设置里「强行停止」—— 那之后任何应用都无法自启。",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.padding(end = 12.dp).weight(1f)) {
                Text("开启后台保护", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (bgProtect)
                        "已开启 —— 通知栏显示「ClipBridge 后台同步中」"
                    else
                        "已关闭 —— App 被划掉后可能掉线",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (bgProtect) OK_GREEN else WARN_AMBER
                )
            }
            Switch(
                checked = bgProtect,
                onCheckedChange = { value ->
                    bgProtect = value
                    Prefs.setBackgroundProtect(context, value)
                    if (value) {
                        requestNotificationPermission()
                        if (Prefs.hasToken(context)) {
                            val ok = SyncService.start(context)
                            message = if (ok) {
                                "后台保护已开启"
                            } else {
                                "系统拒绝了启动请求，请退出后重新打开一次 App"
                            }
                        } else {
                            message = "后台保护已开启，配对后即生效"
                        }
                        KeepAlive.scheduleWatchdog(context)
                    } else {
                        SyncService.stop(context)
                        KeepAlive.cancelWatchdog(context)
                        message = "后台保护已关闭"
                    }
                }
            )
        }

        // 电池优化：不进白名单的话，Doze 会静默掐掉后台网络，
        // 表现是"手机放一会儿就收不到东西"，而且没有任何报错。
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.padding(end = 12.dp).weight(1f)) {
                Text("忽略电池优化", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (batteryOk)
                        "已加入白名单 —— 系统休眠时不会切断后台连接"
                    else
                        "未加入 —— 手机深度休眠一段时间后可能断连",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (batteryOk) OK_GREEN else WARN_AMBER
                )
            }
            TextButton(onClick = { KeepAlive.requestIgnoreBatteryOptimizations(context) }) {
                Text(if (batteryOk) "查看" else "去加入")
            }
        }

        // 自启动：国产 ROM 会独立控制这一项，和电池优化白名单互不替代
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.padding(end = 12.dp).weight(1f)) {
                Text("允许自启动", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "小米 / 华为 / OPPO / vivo 等系统需要单独允许，" +
                        "否则划掉 App 后不会被重新拉起。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = { KeepAlive.openAutoStartSettings(context) }) { Text("去设置") }
        }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        // ---------- 配对 ----------
        Text("配对", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("服务端地址") },
            placeholder = { Text("https://nas.example.com:8443") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = pairCode,
            onValueChange = { pairCode = it },
            label = { Text("配对码") },
            supportingText = {
                Text(
                    if (savedPairCode.isNotEmpty())
                        "已记住上次使用的配对码，换设备或改过服务端配置时才需要改"
                    else
                        "服务端启动日志里会打印这个码"
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = deviceName,
            onValueChange = { deviceName = it },
            label = { Text("设备名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                if (serverUrl.isBlank() || pairCode.isBlank()) {
                    message = "请填写服务端地址和配对码"
                    return@Button
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
                        savedPairCode = pairCode.trim()
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
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (busy) "配对中…" else "配对")
        }

        if (hasToken) {
            Spacer(Modifier.height(8.dp))
            Text(
                "已保存配对信息，无需重复配对。" +
                    (if (Prefs.getDeviceName(context).isNotEmpty()) "设备名：${Prefs.getDeviceName(context)}" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = OK_GREEN
            )
        }

        if (message.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        // ---------- 截图同步（可选） ----------
        Text("截图同步", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "开启后，本机新拍的截图会自动上传并同步到电脑。\n" +
                        "需要「${ScreenshotWatcher.requiredPermission().substringAfterLast('.')}」权限 —— " +
                        "这是读取相册级别的权限，所以默认关闭，由你决定。\n" +
                        "只同步开启之后新产生的截图，相册里的历史截图不会被补发。",
                    style = MaterialTheme.typography.bodySmall
                )
                if (screenshotSync) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (accessibilityOn)
                            "已开启 —— 截图后会自动同步"
                        else
                            "已开启，但无障碍服务没开，实际不工作",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (accessibilityOn) OK_GREEN else WARN_RED
                    )
                }
            }
            Switch(
                checked = screenshotSync,
                onCheckedChange = { value ->
                    if (value) {
                        if (ScreenshotWatcher.hasPermission(context)) {
                            Prefs.setScreenshotSync(context, true)
                            screenshotSync = true
                            ClipboardAccessibilityService.notifyPrefsChanged()
                        } else {
                            screenshotPermLauncher.launch(ScreenshotWatcher.requiredPermission())
                        }
                    } else {
                        Prefs.setScreenshotSync(context, false)
                        screenshotSync = false
                        ClipboardAccessibilityService.notifyPrefsChanged()
                    }
                }
            )
        }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text("使用说明", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "· 本机复制文本 → 自动同步到电脑（需要开启无障碍服务）\n" +
                "· 本机截图 → 自动同步（需单独开启上面的「截图同步」）\n" +
                "· 电脑复制文本 / 截图 → 自动写进本机剪贴板，同时存一份到相册 Pictures/ClipBridge\n" +
                "· 少数 App 的复制按钮无障碍服务识别不到，可用「分享 → ClipBridge」兜底\n" +
                "· 同步只针对你当下这一次复制，历史内容不会被重发",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
