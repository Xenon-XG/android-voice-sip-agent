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
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current
    val h by rt.health.collectAsState()
    val callAudio by rt.callAudioStatus.collectAsState()
    val directMuteIncompatible by rt.directMuteIncompatible.collectAsState()
    val pendingSettings by rt.pendingSettingsFlow.collectAsState()
    val log by AppLog.tail.collectAsState()
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(context.getString(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
        val cur by rt.settingsFlow.collectAsState()
        Section(context.getString(R.string.ui_takeover)) {
            Toggle(context.getString(R.string.ui_take_over_calls), cur.takeover, setTakeover) {
                when {
                    h.takeoverOffPending -> context.getString(R.string.ui_turning_off_after_the_current_call)
                    h.takeoverOnPending -> context.getString(R.string.ui_turning_on_after_current_calls_end)
                    h.dialerChangeRunning -> context.getString(R.string.ui_changing_the_default_dialer)
                    h.dialerChangePending -> if (h.dialerChangeAcquire) context.getString(R.string.ui_waiting_to_become_the_default_dialer) else context.getString(R.string.ui_waiting_to_restore_your_previous_dialer)
                    cur.takeover -> if (h.cellularReady && !h.loopbackTest) context.getString(R.string.ui_ready_to_bridge_calls_to_the_pbx) else context.displayStatus(h.readinessDetail)
                    h.holdsDialer && cur.keepDefaultDialer -> context.getString(R.string.ui_gateway_off_this_app_remains_the_default_phone_app)
                    h.holdsDialer -> context.getString(R.string.ui_gateway_off_dialer_restoration_needs_attention)
                    else -> context.getString(R.string.ui_gateway_off_calls_stay_with_your_dialer)
                }
            }
            if (h.takeover || h.holdsDialer) {
                Check(context.getString(R.string.ui_call_access), h.callAccessReady,
                    if (h.effectiveCallAccess == CallAccess.DIALER) {
                        if (h.holdsDialer) context.getString(R.string.ui_this_app_is_the_default_dialer) else context.getString(R.string.ui_default_dialer_selection_required)
                    } else if (Build.VERSION.SDK_INT == 30) context.getString(R.string.ui_alongside_dialer_access_is_unavailable_on_android_11)
                    else context.getString(R.string.ui_alongside_your_current_dialer))
            }
            if (h.holdsDialer || h.effectiveCallAccess == CallAccess.DIALER) {
                Text(when {
                    !h.holdsDialer -> context.getString(R.string.ui_enable_takeover_to_make_this_app_the_default_phone_app)
                    cur.keepDefaultDialer -> context.getString(R.string.ui_this_app_stays_the_default_when_takeover_is_off)
                    else -> context.getString(R.string.ui_turning_takeover_off_tries_to_restore_your_previous_phone_app)
                }, style = MaterialTheme.typography.bodySmall)
            }
            if (h.effectiveCallAccess == CallAccess.DIALER && !h.holdsDialer) {
                if (cur.takeover && !h.dialerChangePending) {
                    Button(onClick = { setTakeover(true) }, enabled = h.dialerRoleAvailable && !h.dialerChangeRunning) { Text(context.getString(R.string.ui_enable_takeover)) }
                }
                if (!h.dialerRoleAvailable) Text(context.getString(R.string.ui_default_dialer_selection_is_unavailable_on_this_device))
            }
            if (roleMessage.isNotEmpty()) Text(context.displayStatus(roleMessage))
            if (h.holdsDialer || h.dialerChangeFailed) {
                if (h.holdsDialer && cur.takeover && h.shizuku == ShizukuConnector.State.CONNECTED) {
                    OutlinedButton(onClick = switchDialer, enabled = !h.dialerChangeRunning) { Text(context.getString(R.string.ui_restore_previous_dialer)) }
                }
                OutlinedButton(onClick = chooseDialer, enabled = !h.dialerChangeRunning) { Text(context.getString(R.string.ui_choose_dialer_in_settings)) }
            }
            if (h.dialerChangePending) OutlinedButton(onClick = cancelDialer) { Text(context.getString(R.string.ui_cancel_dialer_change)) }
            Toggle(context.getString(R.string.ui_mute_call_audio_on_this_phone), cur.muteEarpiece, { rt.setMuteEarpiece(it) }) {
                val unavailable = h.privileged != null &&
                    (h.privileged?.get(HealthKeys.LOCAL_SUPPRESSION) != true || !h.audioReady)
                if (cur.muteEarpiece && unavailable)
                    context.getString(R.string.ui_playback_muting_cannot_be_attempted_with_the_current_setup_the_phone_m)
                else if (cur.muteEarpiece && directMuteIncompatible)
                    context.getString(R.string.ui_muting_stopped_direct_telephony_audio_on_this_phone_so_it_is_skipped_w)
                else
                    context.getString(R.string.ui_when_enabled_attempts_to_silence_the_earpiece_or_speaker_the_phone_mic)
            }
            if (h.settingsPending && pendingSettings?.hasSameSipSettings(cur) != false) {
                Text(context.getString(R.string.ui_changes_pending_until_calls_end), style = MaterialTheme.typography.bodySmall)
            }
        }
        Section(context.getString(R.string.ui_health)) {
            val p = h.privileged
            val actionsEnabled = h.gatewayRunning && cur.takeover && !h.dialerChangeRunning
            Text(when {
                h.takeover -> context.displayStatus(h.readinessDetail)
                h.takeoverOnPending -> context.getString(R.string.ui_health_checks_will_start_after_current_calls_end)
                else -> context.getString(R.string.ui_health_checks_are_paused_while_takeover_is_off)
            }, style = MaterialTheme.typography.bodySmall)
            Check("Shizuku", h.shizuku == ShizukuConnector.State.CONNECTED,
                if (h.takeover) "${context.displayStatus(h.shizuku.name)} ${context.displayStatus(h.shizukuDetail)}" else context.getString(R.string.ui_stopped_while_takeover_is_off))
            Check(context.getString(R.string.ui_call_audio_access), h.audioReady)
            Check(context.getString(R.string.ui_doze_allowlisted), p?.get(HealthKeys.DOZE_ALLOWLISTED) == true)
            Check(context.getString(R.string.ui_sip_registration), h.sip.ready,
                if (h.takeover) context.displayStatus(h.sip.detail) else context.getString(R.string.ui_stopped_while_takeover_is_off))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (h.shizuku == ShizukuConnector.State.PERMISSION_REQUIRED || h.shizuku == ShizukuConnector.State.PERMISSION_DENIED) {
                    Button(onClick = { rt.shizuku.requestPermission() }, enabled = actionsEnabled) { Text(context.getString(R.string.ui_grant_shizuku)) }
                }
                if (h.shizuku == ShizukuConnector.State.ERROR) {
                    OutlinedButton(onClick = { rt.shizuku.requestPermission() }, enabled = actionsEnabled) {
                        Text(context.getString(R.string.ui_retry_shizuku))
                    }
                }
                OutlinedButton(onClick = { rt.runSetup() },
                    enabled = actionsEnabled && h.shizuku == ShizukuConnector.State.CONNECTED) { Text(context.getString(R.string.ui_re_run_setup)) }
                OutlinedButton(onClick = { rt.restartSip() },
                    enabled = actionsEnabled && cur.sipConfigured) { Text(context.getString(R.string.ui_reconnect_sip)) }
            }
        }
        Section(context.getString(R.string.ui_call)) {
            Text(context.displayStatus(h.calls.summary))
            Text(h.session ?: context.getString(R.string.ui_no_active_session))
            if (callAudio.active) {
                Text(context.getString(R.string.ui_current_audio_method_1_s, context.displayStatus(if (callAudio.available) callAudio.backendLabel else context.getString(R.string.ui_status_unavailable))))
                Text(context.getString(R.string.ui_local_playback_android) + when {
                    !callAudio.available -> context.getString(R.string.ui_mute_state_unavailable)
                    !callAudio.muteRequested -> context.getString(R.string.ui_muting_not_requested)
                    callAudio.outputMute == CallAudioStatus.APPLIED -> context.getString(R.string.ui_mute_request_applied)
                    callAudio.outputMute == CallAudioStatus.PRESERVED -> context.getString(R.string.ui_existing_mute_preserved)
                    callAudio.outputMute == CallAudioStatus.NOT_APPLIED -> context.getString(R.string.ui_mute_request_not_applied)
                    callAudio.outputMute == CallAudioStatus.UNSUPPORTED -> context.getString(R.string.ui_muting_unsupported_with_this_audio_method)
                    else -> context.getString(R.string.ui_mute_state_unknown)
                })
                if (callAudio.available && callAudio.recoveryDetail.isNotEmpty()) {
                    Text(context.getString(R.string.ui_audio_recovered_1_s, callAudio.recoveryDetail), style = MaterialTheme.typography.bodySmall)
                }
                Text(context.getString(R.string.ui_next_call_playback_muting_1_s, if (cur.muteEarpiece) context.getString(R.string.ui_requested) else context.getString(R.string.ui_off)),
                    style = MaterialTheme.typography.bodySmall)
            } else if (h.calls.owned > 0 || h.session != null) {
                Text(context.getString(R.string.ui_call_audio_is_not_open))
            }
            if (callAudio.restorationPending) {
                Text(context.getString(R.string.ui_local_call_playback_restoration_is_unconfirmed_sound_may_remain_muted),
                    color = MaterialTheme.colorScheme.error)
                if (callAudio.ownsOutputChange && h.shizuku == ShizukuConnector.State.CONNECTED) {
                    OutlinedButton(onClick = { rt.retryAudioRestoration() },
                        enabled = !callAudio.active && h.calls.count == 0 && h.session == null) {
                        Text(context.getString(R.string.ui_retry_restoring_playback))
                    }
                } else {
                    Text(context.getString(R.string.ui_if_call_sound_stays_muted_raise_the_call_volume_during_a_normal_phone_),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            Toggle(context.getString(R.string.ui_echo_incoming_calls_test), h.loopbackTest, { rt.setLoopbackTest(it) }) {
                context.getString(R.string.ui_while_the_gateway_is_on_answers_incoming_cellular_calls_and_echoes_the)
            }
            if (h.loopbackTest) {
                Text(context.getString(R.string.ui_echo_test_enabled), style = MaterialTheme.typography.titleSmall)
                Button(onClick = { rt.setLoopbackTest(false) }) { Text(context.getString(R.string.ui_disable_echo_test)) }
            }
        }
        SettingsSection(rt, editor)
        var advanced by rememberSaveable { mutableStateOf(false) }
        OutlinedButton(onClick = { advanced = !advanced }) { Text(if (advanced) context.getString(R.string.ui_hide_advanced_settings) else context.getString(R.string.ui_advanced_settings)) }
        if (advanced) {
            Section(context.getString(R.string.ui_platform_integration)) {
                val preferredCallAccess = pendingSettings?.callAccess ?: cur.callAccess
                Choice(context.getString(R.string.ui_call_access), listOf(CallAccess.AUTO, CallAccess.COMPANION, CallAccess.DIALER), preferredCallAccess,
                    { when (it) { CallAccess.AUTO -> context.getString(R.string.ui_automatic); CallAccess.COMPANION -> context.getString(R.string.ui_alongside_dialer); else -> context.getString(R.string.ui_default_dialer) } },
                    enabled = { it != CallAccess.COMPANION || Build.VERSION.SDK_INT >= 31 }) {
                    rt.applySettings { current -> current.copy(callAccess = it) }
                }
                val callAccessHint = when (preferredCallAccess) {
                    CallAccess.AUTO -> if (Build.VERSION.SDK_INT < 31)
                        context.getString(R.string.ui_android_11_requires_this_app_as_the_default_phone_app)
                    else context.getString(R.string.ui_works_alongside_your_phone_app_unless_this_app_is_the_default)
                    CallAccess.COMPANION -> when {
                        Build.VERSION.SDK_INT < 31 -> context.getString(R.string.ui_requires_android_12_or_later)
                        h.holdsDialer -> context.getString(R.string.ui_restore_your_usual_phone_app_to_use_this_mode)
                        else -> context.getString(R.string.ui_keeps_your_current_phone_app)
                    }
                    else -> if (h.holdsDialer) null
                        else context.getString(R.string.ui_enable_takeover_or_tap_make_default_phone_app_to_switch)
                }
                if (callAccessHint != null) Text(callAccessHint, style = MaterialTheme.typography.bodySmall)
                if (preferredCallAccess != cur.callAccess) {
                    Text(context.getString(R.string.ui_applies_after_current_calls_end), style = MaterialTheme.typography.bodySmall)
                }
                val dialerControlsEnabled = h.effectiveCallAccess == CallAccess.DIALER && !h.dialerChangeRunning
                Toggle(context.getString(R.string.ui_keep_this_app_as_the_default_phone_app_when_gateway_is_off), cur.keepDefaultDialer,
                    { rt.setKeepDefaultDialer(it) }, enabled = dialerControlsEnabled) {
                    context.getString(R.string.ui_when_disabled_turning_takeover_off_tries_to_restore_your_previous_phon)
                }
                if (!h.holdsDialer && h.dialerRoleAvailable) {
                    OutlinedButton(onClick = requestDialer, enabled = dialerControlsEnabled) { Text(context.getString(R.string.ui_make_default_phone_app)) }
                }
                OutlinedButton(onClick = chooseDialer) { Text(context.getString(R.string.ui_choose_dialer_in_settings)) }
                Text(context.getString(R.string.ui_manual_dialer_selection_leaves_takeover_unchanged_selecting_default_di),
                    style = MaterialTheme.typography.bodySmall)
                val preferredAudioMethod = pendingSettings?.audioMethod ?: cur.audioMethod
                Choice(context.getString(R.string.ui_cellular_audio), listOf(AudioMethod.AUTO, AudioMethod.LEGACY, AudioMethod.OFFICIAL), preferredAudioMethod,
                    { when (it) { AudioMethod.AUTO -> context.getString(R.string.ui_automatic); AudioMethod.LEGACY -> context.getString(R.string.ui_direct_telephony); else -> context.getString(R.string.ui_cellular_redirection) } },
                    enabled = { it != AudioMethod.OFFICIAL || Build.VERSION.SDK_INT >= 33 }) {
                    rt.applySettings { current -> current.copy(audioMethod = it) }
                }
                Text(when (preferredAudioMethod) {
                    AudioMethod.AUTO -> context.getString(R.string.ui_prefers_cellular_call_redirection_falls_back_to_direct_telephony_durin)
                    AudioMethod.LEGACY -> context.getString(R.string.ui_uses_direct_cellular_audio_capture_and_playback_without_redirection_fa)
                    else -> context.getString(R.string.ui_uses_cellular_call_redirection_only_requires_android_13_and_privileged)
                } + context.getString(R.string.ui_changes_apply_to_the_next_call), style = MaterialTheme.typography.bodySmall)
                if (preferredAudioMethod != cur.audioMethod) {
                    Text(context.getString(R.string.ui_applies_after_current_calls_end), style = MaterialTheme.typography.bodySmall)
                }
            }
            Section(context.getString(R.string.ui_diagnostics)) {
                Text("Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}")
                Text("requestedCallAccess=${cur.callAccess} effective=${h.effectiveCallAccess} audio=${cur.audioMethod}")
                if (callAudio.backend.isNotEmpty()) Text(context.getString(R.string.ui_last_reported_audio_method_1_s, context.displayStatus(callAudio.backendLabel)))
                if (callAudio.muteDetail.isNotEmpty()) Text(context.getString(R.string.ui_playback_mute_1_s, callAudio.muteDetail))
                if (callAudio.restoreDetail.isNotEmpty()) Text(context.getString(R.string.ui_playback_restoration_1_s, callAudio.restoreDetail))
                Text(h.privileged?.entries?.joinToString("\n") { "${it.key}=${it.value}" } ?: context.getString(R.string.ui_waiting_for_helper))
                Text(h.privilegedDetail)
                Text(h.lastSetupLog)
                Text(context.getString(R.string.ui_audio_access_and_frame_counters_do_not_verify_remote_delivery_verify_b))
            }
        }
        Section(context.getString(R.string.ui_log)) {
            Text(log.takeLast(60).reversed().joinToString("\n"), fontFamily = FontFamily.Monospace,
                fontSize = 11.sp, lineHeight = 14.sp)
        }
    }
}

@Composable
private fun SettingsSection(rt: GatewayRuntime, editor: SipSettingsDraft) = with(editor) {
    val context = LocalContext.current
    val cur by rt.settingsFlow.collectAsState()
    val pending by rt.pendingSettingsFlow.collectAsState()
    val health by rt.health.collectAsState()
    LaunchedEffect(cur, pending) { syncSaved(cur, pending) }
    val builtin = stack == SipStack.BUILTIN
    val unsaved = hasUnsavedChanges(cur, pending)
    val sipPending = pending?.hasSameSipSettings(cur) == false
    val portValidationError = portError?.let(context::displayStatus)
    Section(context.getString(R.string.ui_settings)) {
        Choice(context.getString(R.string.ui_sip_stack), SipStack.entries, stack, { if (it == SipStack.BUILTIN) context.getString(R.string.ui_built_in_udp) else it.id }) {
            stack = it
            // The built-in stack is UDP only.
            if (it == SipStack.BUILTIN) {
                if (port == transport.defaultPort.toString()) port = SipTransport.UDP.defaultPort.toString()
                transport = SipTransport.UDP
            }
        }
        Choice(context.getString(R.string.ui_transport), SipTransport.entries, transport, { it.id }, enabled = { !builtin || it == SipTransport.UDP }) {
            // Follow the default port when it was still the previous transport's default.
            if (port == transport.defaultPort.toString()) port = it.defaultPort.toString()
            transport = it
        }
        Choice(context.getString(R.string.ui_srtp_tls_only), SrtpPolicy.entries, srtp, { context.displayStatus(it.id) }, enabled = { !builtin && transport == SipTransport.TLS }) {
            srtp = it
        }
        Field(context.getString(R.string.ui_pbx_host), server) { server = it }
        Field(context.getString(R.string.ui_pbx_port), port, KeyboardType.Number, error = portValidationError) { port = it }
        Field(context.getString(R.string.ui_sip_domain_empty_pbx_host), domain) { domain = it }
        Field(context.getString(R.string.ui_extension_user), user) { user = it }
        OutlinedTextField(
            value = password, onValueChange = { password = it }, label = { Text(context.getString(R.string.ui_password)) },
            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Field(context.getString(R.string.ui_cellular_inbound_sip_target_blank_username), target) { target = it }
        Field(context.getString(R.string.ui_allowed_dial_out_prefixes_comma_separated_empty_any), prefixes) { prefixes = it }
        if (unsaved) Text(context.getString(R.string.ui_unsaved_changes), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary)
        if (sipPending) Text(context.getString(R.string.ui_changes_pending_until_calls_end), style = MaterialTheme.typography.bodySmall)
        Button(onClick = { rt.applySettings(settingsUpdate()) }, enabled = portValidationError == null) { Text(context.getString(R.string.ui_save_apply)) }
        if (health.calls.count > 0 || health.session != null) {
            if (!sipPending) Text(context.getString(R.string.ui_saved_changes_apply_after_the_current_call_ends), style = MaterialTheme.typography.bodySmall)
        } else if (!cur.takeover) {
            Text(context.getString(R.string.ui_saved_sip_settings_are_used_when_takeover_is_next_enabled), style = MaterialTheme.typography.bodySmall)
        }
        Toggle(context.getString(R.string.ui_unregister_while_the_phone_cannot_take_calls), cur.holdSipWhenNotReady,
            { rt.setHoldSipWhenNotReady(it) }) {
            context.getString(R.string.ui_at_startup_waits_for_the_phone_to_be_ready_before_registering_with_the) +
                context.getString(R.string.ui_later_problems_get_1_d_s_to_recover_before_unregistering_so_the_pbx_ca, GatewayRuntime.SIP_HOLD_GRACE_MS / 1000) +
                context.getString(R.string.ui_registration_changes_wait_for_calls_to_end_registers_again_as_soon_as_)
        }
        Toggle(context.getString(R.string.ui_pjsip_debug_log), cur.sipDebugLog, { rt.setSipDebugLog(it) }) {
            context.getString(R.string.ui_info_level_logs_with_masked_numbers)
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
