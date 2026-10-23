package io.github.sparkcyf.voicesipagent.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.sparkcyf.voicesipagent.privileged.ShizukuConnector

/** Safe to display over the lockscreen: no configuration, caller numbers, or diagnostic logs. */
class GatewayDialerActivity : DialerRoleActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runtime = GatewayRuntime.get(this)
        setContent {
            val health by runtime.health.collectAsState()
            val settings by runtime.settingsFlow.collectAsState()
            val message by roleMessage.collectAsState()
            LaunchedEffect(health.holdsDialer, health.calls) {
                setShowWhenLocked(health.holdsDialer && health.calls.count > 0)
                setTurnScreenOn(health.holdsDialer && health.calls.incoming)
            }
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Scaffold { inset ->
                    Column(Modifier.fillMaxSize().padding(inset).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Voice SIP Agent", style = MaterialTheme.typography.headlineSmall)
                        Text(health.calls.summary)
                        Text(if (health.session == null) "无网关会话" else "网关桥接中")
                        // Avoid putting server names or low-level failure details on a locked screen.
                        Text(when {
                            health.takeoverOffPending -> "网关将在所有通话结束后关闭。"
                            health.takeoverOnPending -> "网关将在所有通话结束后开启。"
                            health.dialerChangePending -> "等待所有通话结束后切换电话应用。"
                            !health.takeover -> "网关已关闭，请切换至常用电话应用处理通话。"
                            health.cellularReady -> "网关已就绪，可以通过 PBX 桥接通话。"
                            else -> "网关不可用，请打开配置或切换至常用电话应用。"
                        })
                        Button(onClick = { setTakeoverFromUi(!settings.takeover) }, enabled = !health.dialerChangeRunning) {
                            Text(if (settings.takeover) "关闭通话接管" else "开启通话接管")
                        }
                        Text("如需手动拨号，请恢复常用电话应用，或在系统设置中选择电话应用。")
                        if (health.holdsDialer && settings.takeover && health.shizuku == ShizukuConnector.State.CONNECTED) {
                            Button(onClick = ::switchDialer, enabled = !health.dialerChangeRunning) { Text("恢复之前的电话应用") }
                        }
                        OutlinedButton(onClick = ::chooseDialerInSettings, enabled = !health.dialerChangeRunning) { Text("在系统设置中选择电话应用") }
                        if (health.dialerChangePending) OutlinedButton(onClick = ::cancelDialerChange) { Text("取消切换电话应用") }
                        if (!health.holdsDialer && health.dialerRoleAvailable) {
                            OutlinedButton(onClick = { setTakeoverFromUi(true) }, enabled = !health.dialerChangeRunning) { Text("开启通话接管") }
                        }
                        OutlinedButton(onClick = ::openConfiguration) { Text("打开配置") }
                        if (message.isNotEmpty()) Text(message)
                    }
                }
            }
        }
    }
}
