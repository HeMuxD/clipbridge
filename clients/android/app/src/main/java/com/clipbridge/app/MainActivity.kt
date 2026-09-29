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
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import com.clipbridge.app.service.SyncEngine
import com.clipbridge.app.service.SyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    /** 每次回到前台自增，用来重新检测无障碍服务是否仍处于开启状态 */
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
    var pairCode by remember { mutableStateOf("") }
    var deviceName by remember {
        mutableStateOf(Prefs.getDeviceName(context).ifEmpty { "我的安卓手机" })
    }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    val connected by StatusHolder.connected.collectAsState()
    val statusText by StatusHolder.statusText.collectAsState()

    // 无障碍状态必须在回到前台时重新读，用户可能刚在系统设置里把它关掉
    val accessibilityOn = remember(resumeTick) { isAccessibilityEnabled(context) }
    var keepAlive by remember(resumeTick) { mutableStateOf(Prefs.isKeepAliveEnabled(context)) }
    val hasToken = Prefs.hasToken(context)

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
            color = if (connected) Color(0xFF2E7D32) else Color(0xFFC62828),
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
        val diag by StatusHolder.diag.collectAsState()
        if (diag.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("上行诊断（最近 ${diag.size} 条）", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(10.dp))
                TextButton(onClick = { StatusHolder.clearDiag() }) { Text("清空") }
            }
            Spacer(Modifier.height(4.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(10.dp)) {
                    diag.forEach { line ->
                        Text(
                            line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        // ---------- 无障碍服务：无感同步的核心 ----------
        Text("无感同步", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            if (accessibilityOn)
                "无障碍服务已开启 —— 本机复制会自动同步，收到内容会直接写进剪贴板，全程无提示。"
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
                        Prefs.save(context, serverUrl, result.token, deviceId, deviceName)
                        message = "配对成功"
                        // 无障碍服务在的话连接已经在跑，重启一下让它换用新 Token
                        SyncEngine.restart()
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

        if (message.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        // ---------- 可选：常驻通知保活 ----------
        Text("保活（可选）", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.padding(end = 12.dp)) {
                Text("常驻通知保活", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "默认关闭。连接由无障碍服务持有，通知栏里什么都没有。" +
                        "若你的手机经常把后台掐掉导致掉线，再打开它。" +
                        "（Android 要求前台服务必须显示一条通知，无法隐藏）",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(
                checked = keepAlive,
                onCheckedChange = { value ->
                    keepAlive = value
                    Prefs.setKeepAlive(context, value)
                    if (value) {
                        requestNotificationPermission()
                        SyncService.start(context)
                    } else {
                        SyncService.stop(context)
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
                "· 电脑复制文本 / 截图 → 自动写进本机剪贴板，同时存一份到相册 Pictures/ClipBridge\n" +
                "· 部分 App 的复制按钮无障碍服务识别不到，此时可用「分享 → ClipBridge」兜底\n" +
                "· 同步只针对你当下这一次复制，历史内容不会被重发",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
