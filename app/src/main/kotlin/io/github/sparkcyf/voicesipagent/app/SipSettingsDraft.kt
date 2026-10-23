package io.github.sparkcyf.voicesipagent.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.github.sparkcyf.voicesipagent.pjsua2.SipTransport
import io.github.sparkcyf.voicesipagent.pjsua2.SrtpPolicy

/** Retained across activity recreation; credentials stay in memory rather than saved-state Bundles. */
class SipSettingsDraft : ViewModel() {
    var server by mutableStateOf("")
    var port by mutableStateOf("5060")
    var user by mutableStateOf("")
    var password by mutableStateOf("")
    var socks5Enabled by mutableStateOf(false)
    var socks5Host by mutableStateOf("127.0.0.1")
    var socks5Port by mutableStateOf("10808")
    var socks5Username by mutableStateOf("")
    var socks5Password by mutableStateOf("")
    var target by mutableStateOf("")
    var prefixes by mutableStateOf("")
    var domain by mutableStateOf("")
    var stack by mutableStateOf(SipStack.PJSUA2)
    var transport by mutableStateOf(SipTransport.UDP)
    var srtp by mutableStateOf(SrtpPolicy.OPTIONAL)
    private var saved: GatewaySettings? = null

    private val validatedPort: Int?
        get() = port.toIntOrNull()?.takeIf { it in 1..65535 }

    val portError: String?
        get() = if (validatedPort == null) "Enter a port number from 1 to 65535." else null

    val socks5Error: String?
        get() = socks5SettingsError(socks5Enabled, socks5Host, socks5Port.toIntOrNull(),
            socks5Username, socks5Password, stack, transport.id)

    /** Follow saved or queued changes to untouched fields, while keeping unfinished edits. */
    fun syncSaved(applied: GatewaySettings, pending: GatewaySettings? = null) {
        val current = pending ?: applied
        val previous = saved
        if (previous == null || server == previous.server) server = current.server
        if (previous == null || port == previous.port.toString()) port = current.port.toString()
        if (previous == null || user == previous.username) user = current.username
        if (previous == null || password == previous.password) password = current.password
        if (previous == null || socks5Enabled == previous.socks5Enabled) socks5Enabled = current.socks5Enabled
        if (previous == null || socks5Host == previous.socks5Host) socks5Host = current.socks5Host
        if (previous == null || socks5Port == previous.socks5Port.toString()) socks5Port = current.socks5Port.toString()
        if (previous == null || socks5Username == previous.socks5Username) socks5Username = current.socks5Username
        if (previous == null || socks5Password == previous.socks5Password) socks5Password = current.socks5Password
        if (previous == null || target == previous.inboundTarget) target = current.inboundTarget
        if (previous == null || prefixes == previous.allowedPrefixes) prefixes = current.allowedPrefixes
        if (previous == null || domain == previous.domain) domain = current.domain
        if (previous == null || stack == previous.sipStack) stack = current.sipStack
        if (previous == null || transport == (SipTransport.of(previous.sipTransport) ?: SipTransport.UDP))
            transport = SipTransport.of(current.sipTransport) ?: SipTransport.UDP
        if (previous == null || srtp == (SrtpPolicy.of(previous.srtp) ?: SrtpPolicy.OPTIONAL))
            srtp = SrtpPolicy.of(current.srtp) ?: SrtpPolicy.OPTIONAL
        saved = current
    }

    fun hasUnsavedChanges(applied: GatewaySettings, pending: GatewaySettings? = null): Boolean =
        portError != null || socks5Error != null || !snapshot().hasSameSipSettings(pending ?: applied)

    private fun snapshot() = GatewaySettings(
        server = server, port = requireNotNull(validatedPort) { "PBX port must be between 1 and 65535." },
        username = user, password = password, inboundTarget = target, allowedPrefixes = prefixes,
        domain = domain, sipStack = stack, sipTransport = transport.id, srtp = srtp.id,
        socks5Enabled = socks5Enabled, socks5Host = socks5Host,
        // A disabled proxy may retain unfinished hidden fields without blocking direct SIP settings.
        socks5Port = socks5Port.toIntOrNull() ?: saved?.socks5Port ?: 10808,
        socks5Username = socks5Username, socks5Password = socks5Password,
    )

    /** Capture the form at the click, before the runtime queues or applies it on another thread. */
    fun settingsUpdate(): (GatewaySettings) -> GatewaySettings {
        require(socks5Error == null) { requireNotNull(socks5Error) }
        val snapshot = snapshot()
        return { current -> current.copy(
            server = snapshot.server, port = snapshot.port, username = snapshot.username, password = snapshot.password,
            inboundTarget = snapshot.inboundTarget, allowedPrefixes = snapshot.allowedPrefixes, domain = snapshot.domain,
            sipStack = snapshot.sipStack, sipTransport = snapshot.sipTransport, srtp = snapshot.srtp,
            socks5Enabled = snapshot.socks5Enabled, socks5Host = snapshot.socks5Host, socks5Port = snapshot.socks5Port,
            socks5Username = snapshot.socks5Username, socks5Password = snapshot.socks5Password,
        ) }
    }
}

/** Gateway preferences do not count as edits to the SIP form. */
internal fun GatewaySettings.hasSameSipSettings(other: GatewaySettings): Boolean =
    server == other.server && port == other.port && username == other.username && password == other.password &&
        inboundTarget == other.inboundTarget && allowedPrefixes == other.allowedPrefixes && domain == other.domain &&
        sipStack == other.sipStack && sipTransport == other.sipTransport && srtp == other.srtp &&
        socks5Enabled == other.socks5Enabled && socks5Host == other.socks5Host && socks5Port == other.socks5Port &&
        socks5Username == other.socks5Username && socks5Password == other.socks5Password
