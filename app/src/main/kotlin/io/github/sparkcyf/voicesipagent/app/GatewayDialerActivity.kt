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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.sparkcyf.voicesipagent.privileged.ShizukuConnector

/** Safe to display over the lockscreen: no configuration, caller numbers, or diagnostic logs. */
class GatewayDialerActivity : DialerRoleActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runtime = GatewayRuntime.get(this)
        setContent {
            val context = LocalContext.current
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
                        Text(context.getString(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                        Text(context.displayStatus(health.calls.summary))
                        Text(if (health.session == null) context.getString(R.string.ui_no_gateway_session) else context.getString(R.string.ui_gateway_bridge_active))
                        // Avoid putting server names or low-level failure details on a locked screen.
                        Text(when {
                            health.takeoverOffPending -> context.getString(R.string.ui_gateway_will_turn_off_after_current_calls_end)
                            health.takeoverOnPending -> context.getString(R.string.ui_gateway_will_turn_on_after_current_calls_end)
                            health.dialerChangePending -> context.getString(R.string.ui_waiting_for_current_calls_to_end_before_changing_the_dialer)
                            !health.takeover -> context.getString(R.string.ui_gateway_is_off_switch_to_your_usual_dialer_to_handle_calls)
                            health.cellularReady -> context.getString(R.string.ui_gateway_is_ready_to_bridge_calls_through_the_pbx)
                            else -> context.getString(R.string.ui_gateway_is_unavailable_open_configuration_or_switch_to_your_usual_dial)
                        })
                        Button(onClick = { setTakeoverFromUi(!settings.takeover) }, enabled = !health.dialerChangeRunning) {
                            Text(if (settings.takeover) context.getString(R.string.ui_turn_takeover_off) else context.getString(R.string.ui_turn_takeover_on))
                        }
                        Text(context.getString(R.string.ui_for_manual_calls_restore_your_usual_phone_app_or_choose_one_in_system_))
                        if (health.holdsDialer && settings.takeover && health.shizuku == ShizukuConnector.State.CONNECTED) {
                            Button(onClick = ::switchDialer, enabled = !health.dialerChangeRunning) { Text(context.getString(R.string.ui_restore_previous_dialer)) }
                        }
                        OutlinedButton(onClick = ::chooseDialerInSettings, enabled = !health.dialerChangeRunning) { Text(context.getString(R.string.ui_choose_dialer_in_settings)) }
                        if (health.dialerChangePending) OutlinedButton(onClick = ::cancelDialerChange) { Text(context.getString(R.string.ui_cancel_dialer_change)) }
                        if (!health.holdsDialer && health.dialerRoleAvailable) {
                            OutlinedButton(onClick = { setTakeoverFromUi(true) }, enabled = !health.dialerChangeRunning) { Text(context.getString(R.string.ui_enable_takeover)) }
                        }
                        OutlinedButton(onClick = ::openConfiguration) { Text(context.getString(R.string.ui_open_configuration)) }
                        if (message.isNotEmpty()) Text(context.displayStatus(message))
                    }
                }
            }
        }
    }
}
