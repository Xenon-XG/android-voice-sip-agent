package io.github.sparkcyf.voicesipagent.app

import android.os.Bundle
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import io.github.sparkcyf.voicesipagent.pjsua2.SipTransport
import io.github.sparkcyf.voicesipagent.pjsua2.SrtpPolicy
import io.github.sparkcyf.voicesipagent.privileged.HealthKeys
import io.github.sparkcyf.voicesipagent.privileged.AudioMethod
import io.github.sparkcyf.voicesipagent.privileged.CallAccess
import io.github.sparkcyf.voicesipagent.privileged.CallAudioStatus
import io.github.sparkcyf.voicesipagent.privileged.ShizukuConnector

class MainActivity : DialerRoleActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runtime = GatewayRuntime.get(this)
        val editor = ViewModelProvider(this)[SipSettingsDraft::class.java]
        editor.syncSaved(runtime.settings, runtime.pendingSettingsFlow.value)
        GatewayService.start(this)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val message by roleMessage.collectAsState()
                Scaffold { pad -> Screen(runtime, editor, Modifier.padding(pad), ::requestDialer, ::switchDialer,
                    ::chooseDialerInSettings, ::cancelDialerChange, ::setTakeoverFromUi, message) }
            }
        }
    }
}

@Composable
private fun Screen(rt: GatewayRuntime, editor: SipSettingsDraft, modifier: Modifier, requestDialer: () -> Unit, switchDialer: () -> Unit,
    chooseDialer: () -> Unit, cancelDialer: () -> Unit, setTakeover: (Boolean) -> Unit, roleMessage: String) {
    val h by rt.health.collectAsState()
    val callAudio by rt.callAudioStatus.collectAsState()
    val directMuteIncompatible by rt.directMuteIncompatible.collectAsState()
    val pendingSettings by rt.pendingSettingsFlow.collectAsState()
    val log by AppLog.tail.collectAsState()
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Voice SIP Agent", style = MaterialTheme.typography.headlineSmall)
        val cur by rt.settingsFlow.collectAsState()
        Section("通话接管") {
            Toggle("启用网关通话接管", cur.takeover, setTakeover) {
                when {
                    h.takeoverOffPending -> "当前通话结束后关闭"
                    h.takeoverOnPending -> "所有通话结束后开启"
                    h.dialerChangeRunning -> "正在切换默认电话应用"
                    h.dialerChangePending -> if (h.dialerChangeAcquire) "等待设为默认电话应用" else "等待恢复之前的电话应用"
                    cur.takeover -> if (h.cellularReady && !h.loopbackTest) "已就绪，可以将通话桥接至 PBX" else h.readinessDetail
                    h.holdsDialer && cur.keepDefaultDialer -> "网关已关闭；本应用仍是默认电话应用"
                    h.holdsDialer -> "网关已关闭；请检查电话应用是否已恢复"
                    else -> "网关已关闭；通话由原电话应用处理"
                }
            }
            if (h.takeover || h.holdsDialer) {
                Check("通话访问模式", h.callAccessReady,
                    if (h.effectiveCallAccess == CallAccess.DIALER) {
                        if (h.holdsDialer) "本应用是默认电话应用" else "需要选择默认电话应用"
                    } else if (Build.VERSION.SDK_INT == 30) "Android 11 不支持与原电话应用并行访问通话"
                    else "与当前电话应用并行使用")
            }
            if (h.holdsDialer || h.effectiveCallAccess == CallAccess.DIALER) {
                Text(when {
                    !h.holdsDialer -> "开启通话接管以将本应用设为默认电话应用。"
                    cur.keepDefaultDialer -> "关闭通话接管后，本应用仍保持为默认电话应用。"
                    else -> "关闭通话接管时，会尝试恢复之前的电话应用。"
                }, style = MaterialTheme.typography.bodySmall)
            }
            if (h.effectiveCallAccess == CallAccess.DIALER && !h.holdsDialer) {
                if (cur.takeover && !h.dialerChangePending) {
                    Button(onClick = { setTakeover(true) }, enabled = h.dialerRoleAvailable && !h.dialerChangeRunning) { Text("开启通话接管") }
                }
                if (!h.dialerRoleAvailable) Text("此设备无法选择默认电话应用。")
            }
            if (roleMessage.isNotEmpty()) Text(roleMessage)
            if (h.holdsDialer || h.dialerChangeFailed) {
                if (h.holdsDialer && cur.takeover && h.shizuku == ShizukuConnector.State.CONNECTED) {
                    OutlinedButton(onClick = switchDialer, enabled = !h.dialerChangeRunning) { Text("恢复之前的电话应用") }
                }
                OutlinedButton(onClick = chooseDialer, enabled = !h.dialerChangeRunning) { Text("在系统设置中选择电话应用") }
            }
            if (h.dialerChangePending) OutlinedButton(onClick = cancelDialer) { Text("取消切换电话应用") }
            Toggle("尝试将本机听筒或扬声器静音", cur.muteEarpiece, { rt.setMuteEarpiece(it) }) {
                val unavailable = h.privileged != null &&
                    (h.privileged?.get(HealthKeys.LOCAL_SUPPRESSION) != true || !h.audioReady)
                if (cur.muteEarpiece && unavailable)
                    "当前配置无法尝试将播放声音静音。网关通话期间，手机麦克风始终静音。更改在下次通话生效。"
                else if (cur.muteEarpiece && directMuteIncompatible)
                    "静音导致本机的直连通话音频中断，因此使用该方式时会跳过静音。关闭并重新开启此选项可再次测试。"
                else
                    "开启后会尝试将听筒或扬声器静音。网关通话期间，手机麦克风始终静音。更改在下次通话生效。"
            }
            if (h.settingsPending && pendingSettings?.hasSameSipSettings(cur) != false) {
                Text("更改将在所有通话结束后生效", style = MaterialTheme.typography.bodySmall)
            }
        }
        Section("运行状态") {
            val p = h.privileged
            val actionsEnabled = h.gatewayRunning && cur.takeover && !h.dialerChangeRunning
            Text(when {
                h.takeover -> h.readinessDetail
                h.takeoverOnPending -> "所有通话结束后开始状态检查。"
                else -> "关闭通话接管期间暂停状态检查。"
            }, style = MaterialTheme.typography.bodySmall)
            Check("Shizuku", h.shizuku == ShizukuConnector.State.CONNECTED,
                if (h.takeover) "${h.shizuku} ${h.shizukuDetail}" else "关闭通话接管期间已停止")
            Check("通话音频访问权限", h.audioReady)
            Check("已加入 Doze 省电白名单", p?.get(HealthKeys.DOZE_ALLOWLISTED) == true)
            Check("SIP 注册", h.sip.ready,
                if (h.takeover) h.sip.detail else "关闭通话接管期间已停止")
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (h.shizuku == ShizukuConnector.State.PERMISSION_REQUIRED || h.shizuku == ShizukuConnector.State.PERMISSION_DENIED) {
                    Button(onClick = { rt.shizuku.requestPermission() }, enabled = actionsEnabled) { Text("授予 Shizuku 权限") }
                }
                OutlinedButton(onClick = { rt.runSetup() },
                    enabled = actionsEnabled && h.shizuku == ShizukuConnector.State.CONNECTED) { Text("重新执行配置") }
                OutlinedButton(onClick = { rt.restartSip() },
                    enabled = actionsEnabled && cur.sipConfigured) { Text("重新连接 SIP") }
            }
        }
        Section("通话") {
            Text(h.calls.summary)
            Text(h.session ?: "无活动会话")
            if (callAudio.active) {
                Text("当前音频方式：${if (callAudio.available) callAudio.backendLabel else "状态不可用"}")
                Text("本机播放（Android）：" + when {
                    !callAudio.available -> "静音状态不可用"
                    !callAudio.muteRequested -> "未请求静音"
                    callAudio.outputMute == CallAudioStatus.APPLIED -> "静音请求已应用"
                    callAudio.outputMute == CallAudioStatus.PRESERVED -> "保留原有静音状态"
                    callAudio.outputMute == CallAudioStatus.NOT_APPLIED -> "静音请求未应用"
                    callAudio.outputMute == CallAudioStatus.UNSUPPORTED -> "此音频方式不支持静音"
                    else -> "静音状态未知"
                })
                if (callAudio.available && callAudio.recoveryDetail.isNotEmpty()) {
                    Text("音频恢复详情：${callAudio.recoveryDetail}", style = MaterialTheme.typography.bodySmall)
                }
                Text("下次通话：本机播放静音${if (cur.muteEarpiece) "已请求" else "关闭"}。",
                    style = MaterialTheme.typography.bodySmall)
            } else if (h.calls.owned > 0 || h.session != null) {
                Text("通话音频尚未打开。")
            }
            if (callAudio.restorationPending) {
                Text("尚未确认本机通话声音已恢复，可能仍处于静音状态。",
                    color = MaterialTheme.colorScheme.error)
                if (callAudio.ownsOutputChange && h.shizuku == ShizukuConnector.State.CONNECTED) {
                    OutlinedButton(onClick = { rt.retryAudioRestoration() },
                        enabled = !callAudio.active && h.calls.count == 0 && h.session == null) {
                        Text("重试恢复播放声音")
                    }
                } else {
                    Text("如果通话仍无声音，请在普通电话通话中调高通话音量。",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            Toggle("蜂窝来电回声测试（不桥接 PBX）", h.loopbackTest, { rt.setLoopbackTest(it) }) {
                "网关开启时，接听蜂窝来电并将声音回传给来电方，不连接 PBX。更改在当前通话结束后生效。"
            }
            if (h.loopbackTest) {
                Text("回声测试已开启", style = MaterialTheme.typography.titleSmall)
                Button(onClick = { rt.setLoopbackTest(false) }) { Text("关闭回声测试") }
            }
        }
        SettingsSection(rt, editor)
        var advanced by rememberSaveable { mutableStateOf(false) }
        OutlinedButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级设置" else "高级设置") }
        if (advanced) {
            Section("系统集成") {
                val preferredCallAccess = pendingSettings?.callAccess ?: cur.callAccess
                Choice("通话访问模式", listOf(CallAccess.AUTO, CallAccess.COMPANION, CallAccess.DIALER), preferredCallAccess,
                    { when (it) { CallAccess.AUTO -> "自动"; CallAccess.COMPANION -> "伴随现有电话应用"; else -> "默认电话应用模式" } },
                    enabled = { it != CallAccess.COMPANION || Build.VERSION.SDK_INT >= 31 }) {
                    rt.applySettings { current -> current.copy(callAccess = it) }
                }
                val callAccessHint = when (preferredCallAccess) {
                    CallAccess.AUTO -> if (Build.VERSION.SDK_INT < 31)
                        "Android 11 要求将本应用设为默认电话应用。"
                    else "本应用不是默认电话应用时，与原电话应用并行运行。"
                    CallAccess.COMPANION -> when {
                        Build.VERSION.SDK_INT < 31 -> "需要 Android 12 或更高版本。"
                        h.holdsDialer -> "请先恢复常用电话应用，再使用此模式。"
                        else -> "保留当前电话应用。"
                    }
                    else -> if (h.holdsDialer) null
                        else "开启通话接管，或点击“设为默认电话应用”进行切换。"
                }
                if (callAccessHint != null) Text(callAccessHint, style = MaterialTheme.typography.bodySmall)
                if (preferredCallAccess != cur.callAccess) {
                    Text("所有通话结束后生效。", style = MaterialTheme.typography.bodySmall)
                }
                val dialerControlsEnabled = h.effectiveCallAccess == CallAccess.DIALER && !h.dialerChangeRunning
                Toggle("网关关闭时仍将本应用保留为默认电话应用", cur.keepDefaultDialer,
                    { rt.setKeepDefaultDialer(it) }, enabled = dialerControlsEnabled) {
                    "关闭此选项后，关闭通话接管时会尝试恢复之前的电话应用。"
                }
                if (!h.holdsDialer && h.dialerRoleAvailable) {
                    OutlinedButton(onClick = requestDialer, enabled = dialerControlsEnabled) { Text("设为默认电话应用") }
                }
                OutlinedButton(onClick = chooseDialer) { Text("在系统设置中选择电话应用") }
                Text("手动选择电话应用不会改变通话接管开关。上方选择的“默认电话应用”模式将在下次开启时应用。",
                    style = MaterialTheme.typography.bodySmall)
                val preferredAudioMethod = pendingSettings?.audioMethod ?: cur.audioMethod
                Choice("蜂窝通话音频方式", listOf(AudioMethod.AUTO, AudioMethod.LEGACY, AudioMethod.OFFICIAL), preferredAudioMethod,
                    { when (it) { AudioMethod.AUTO -> "自动"; AudioMethod.LEGACY -> "直接采集和播放通话音频"; else -> "蜂窝通话音频重定向" } },
                    enabled = { it != AudioMethod.OFFICIAL || Build.VERSION.SDK_INT >= 33 }) {
                    rt.applySettings { current -> current.copy(audioMethod = it) }
                }
                Text(when (preferredAudioMethod) {
                    AudioMethod.AUTO -> "优先使用蜂窝通话重定向；配置时若不可用，则回退至直连通话音频。"
                    AudioMethod.LEGACY -> "直接采集和播放蜂窝通话音频，不回退至重定向方式。"
                    else -> "仅使用蜂窝通话重定向，需要 Android 13 或更高版本及特权访问权限。"
                } + " 更改在下次通话生效。", style = MaterialTheme.typography.bodySmall)
                if (preferredAudioMethod != cur.audioMethod) {
                    Text("所有通话结束后生效。", style = MaterialTheme.typography.bodySmall)
                }
            }
            Section("诊断信息") {
                Text("Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}")
                Text("requestedCallAccess=${cur.callAccess} effective=${h.effectiveCallAccess} audio=${cur.audioMethod}")
                if (callAudio.backend.isNotEmpty()) Text("最近报告的音频方式：${callAudio.backendLabel}")
                if (callAudio.muteDetail.isNotEmpty()) Text("播放静音详情：${callAudio.muteDetail}")
                if (callAudio.restoreDetail.isNotEmpty()) Text("播放恢复详情：${callAudio.restoreDetail}")
                Text(h.privileged?.entries?.joinToString("\n") { "${it.key}=${it.value}" } ?: "等待辅助服务")
                Text(h.privilegedDetail)
                Text(h.lastSetupLog)
                Text("具备音频权限和帧计数并不能证明远端收到音频，请在本机验证双向通话。")
            }
        }
        Section("日志") {
            Text(log.takeLast(60).reversed().joinToString("\n"), fontFamily = FontFamily.Monospace,
                fontSize = 11.sp, lineHeight = 14.sp)
        }
    }
}

@Composable
private fun SettingsSection(rt: GatewayRuntime, editor: SipSettingsDraft) = with(editor) {
    val cur by rt.settingsFlow.collectAsState()
    val pending by rt.pendingSettingsFlow.collectAsState()
    val health by rt.health.collectAsState()
    LaunchedEffect(cur, pending) { syncSaved(cur, pending) }
    val builtin = stack == SipStack.BUILTIN
    val unsaved = hasUnsavedChanges(cur, pending)
    val sipPending = pending?.hasSameSipSettings(cur) == false
    val portValidationError = portError
    Section("设置") {
        Choice("SIP 协议栈", SipStack.entries, stack, { it.id }) {
            stack = it
            // The built-in stack is UDP only.
            if (it == SipStack.BUILTIN) {
                if (port == transport.defaultPort.toString()) port = SipTransport.UDP.defaultPort.toString()
                transport = SipTransport.UDP
            }
        }
        Choice("传输协议", SipTransport.entries, transport, { it.id }, enabled = { !builtin || it == SipTransport.UDP }) {
            // Follow the default port when it was still the previous transport's default.
            if (port == transport.defaultPort.toString()) port = it.defaultPort.toString()
            transport = it
        }
        Choice("SRTP（仅适用于 TLS）", SrtpPolicy.entries, srtp, { it.id }, enabled = { !builtin && transport == SipTransport.TLS }) {
            srtp = it
        }
        Field("PBX 主机地址", server) { server = it }
        Field("PBX 端口", port, KeyboardType.Number, error = portValidationError) { port = it }
        Field("SIP 域（留空则使用 PBX 主机地址）", domain) { domain = it }
        Field("分机号 / 用户名", user) { user = it }
        OutlinedTextField(
            value = password, onValueChange = { password = it }, label = { Text("密码") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Field("蜂窝来电转接至 SIP 目标（留空使用用户名）", target) { target = it }
        Field("允许外拨的号码前缀（英文逗号分隔，留空不限）", prefixes) { prefixes = it }
        if (unsaved) Text("有未保存的更改", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary)
        if (sipPending) Text("更改将在所有通话结束后生效", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { rt.applySettings(settingsUpdate()) }, enabled = portValidationError == null) { Text("保存并应用") }
        if (health.calls.count > 0 || health.session != null) {
            if (!sipPending) Text("已保存的更改在当前通话结束后生效。", style = MaterialTheme.typography.bodySmall)
        } else if (!cur.takeover) {
            Text("已保存的 SIP 设置将在下次开启通话接管时使用。", style = MaterialTheme.typography.bodySmall)
        }
        Toggle("手机无法处理网关通话时暂停 SIP 注册", cur.holdSipWhenNotReady,
            { rt.setHoldSipWhenNotReady(it) }) {
            "启动时等待手机就绪，再向 PBX 注册。" +
                "后续出现问题时，将等待 ${GatewayRuntime.SIP_HOLD_GRACE_MS / 1000} 秒尝试恢复，再注销注册，以便 PBX 切换备用线路。" +
                "注册状态的更改会等待通话结束；手机就绪后立即重新注册。"
        }
        Toggle("pjsip 调试日志", cur.sipDebugLog, { rt.setSipDebugLog(it) }) {
            "记录 Info 级别日志，号码已脱敏"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choice(
    label: String,
    options: List<T>,
    selected: T,
    name: (T) -> String,
    enabled: (T) -> Boolean = { true },
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            options.forEachIndexed { i, opt ->
                SegmentedButton(
                    modifier = Modifier.fillMaxHeight(),
                    selected = opt == selected,
                    onClick = { onSelect(opt) },
                    enabled = enabled(opt),
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text(name(opt)) }
            }
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit,
    enabled: Boolean = true, detail: () -> String) {
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = color)
            Text(detail(), style = MaterialTheme.typography.bodySmall, color = color)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun Field(label: String, value: String, type: KeyboardType = KeyboardType.Text,
    error: String? = null, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type), modifier = Modifier.fillMaxWidth(),
        isError = error != null,
        supportingText = if (error != null) { { Text(error) } } else null,
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Check(label: String, ok: Boolean, detail: String = "") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "●" else "○", color = if (ok) Color(0xFF2E7D32) else Color(0xFFC62828))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label)
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}
