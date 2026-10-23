package io.github.sparkcyf.voicesipagent.app

import io.github.sparkcyf.voicesipagent.privileged.AudioMethod
import io.github.sparkcyf.voicesipagent.privileged.CallAccess
import io.github.sparkcyf.voicesipagent.pjsua2.SipTransport
import org.junit.Assert.*
import org.junit.Test

class SipSettingsDraftTest {
    @Test fun proxyDefaultsAreDisabledAndIgnoredUntilEnabled() {
        val defaults = GatewaySettings()
        assertFalse(defaults.socks5Enabled)
        assertEquals("127.0.0.1", defaults.socks5Host)
        assertEquals(10808, defaults.socks5Port)
        assertNull(defaults.socks5ProxyConfig())
        val invalid = defaults.copy(socks5Host = "", socks5Port = 0, socks5Username = "user")
        assertNull(invalid.socks5Error)
        assertNull(invalid.socks5ProxyConfig())
        assertNotNull(invalid.copy(socks5Enabled = true).socks5Error)
    }

    @Test fun enabledProxyValidatesStackAddressPortAndAuthenticationWithoutEchoingCredentials() {
        val valid = GatewaySettings(sipStack = SipStack.BUILTIN, socks5Enabled = true,
            socks5Host = "proxy.invalid", socks5Port = 10808, socks5Username = "proxy-secret-user",
            socks5Password = "proxy-secret-password", password = "sip-secret-password")
        assertNull(valid.socks5Error)
        val config = valid.socks5ProxyConfig()!!
        assertEquals("proxy.invalid", config.host)
        assertEquals(10808, config.port)
        assertEquals(valid.socks5Username, config.username)
        assertEquals(valid.socks5Password, config.password)
        for (text in listOf(valid.toString(), config.toString())) {
            assertFalse(text.contains("proxy-secret-user"))
            assertFalse(text.contains("proxy-secret-password"))
            assertFalse(text.contains("sip-secret-password"))
        }
        val invalid = listOf(valid.copy(sipStack = SipStack.PJSUA2), valid.copy(sipTransport = "tcp"),
            valid.copy(sipTransport = "tls"), valid.copy(socks5Host = ""), valid.copy(socks5Host = "socks5://proxy.invalid"),
            valid.copy(socks5Host = "user:secret@proxy.invalid"), valid.copy(socks5Host = "bad host"),
            valid.copy(socks5Port = 0), valid.copy(socks5Port = 65536), valid.copy(socks5Username = ""),
            valid.copy(socks5Password = ""), valid.copy(socks5Username = "u".repeat(256)),
            valid.copy(socks5Password = "密".repeat(86)))
        for (settings in invalid) {
            assertNotNull(settings.socks5Error)
            val error = assertThrows(IllegalArgumentException::class.java) { settings.socks5ProxyConfig() }
            assertFalse(error.message!!.contains("proxy-secret"))
        }
        assertNull(valid.copy(socks5Username = "", socks5Password = "").socks5Error)
        assertNull(valid.copy(socks5Username = "u".repeat(255), socks5Password = "密".repeat(85)).socks5Error)
        for (port in listOf(1, 65535)) assertNull(valid.copy(socks5Port = port).socks5Error)
    }

    @Test fun proxyDraftValidatesBeforeCaptureAndPreservesQueuedEdits() {
        val saved = GatewaySettings(sipStack = SipStack.BUILTIN)
        val draft = SipSettingsDraft().apply { syncSaved(saved); socks5Enabled = true }
        for (port in listOf("", "abc", "0", "65536")) {
            draft.socks5Port = port
            assertNotNull(draft.socks5Error)
            assertTrue(draft.hasUnsavedChanges(saved))
            assertThrows(IllegalArgumentException::class.java) { draft.settingsUpdate() }
        }
        draft.socks5Port = "10809"
        draft.socks5Username = "proxy-user"
        draft.socks5Password = "proxy-password"
        draft.stack = SipStack.PJSUA2
        assertThrows(IllegalArgumentException::class.java) { draft.settingsUpdate() }
        draft.stack = SipStack.BUILTIN
        val update = draft.settingsUpdate()
        val pending = update(saved.copy(muteEarpiece = false))
        assertEquals(10809, pending.socks5Port)
        assertFalse(pending.muteEarpiece)
        draft.syncSaved(saved, pending)
        assertFalse(draft.hasUnsavedChanges(saved, pending))
        draft.socks5Password = "unfinished-password"
        val external = pending.copy(socks5Host = "external.invalid")
        draft.syncSaved(saved, external)
        assertEquals("external.invalid", draft.socks5Host)
        assertEquals("unfinished-password", draft.socks5Password)
        assertEquals("proxy-password", update(saved).socks5Password)
        assertTrue(draft.hasUnsavedChanges(saved, external))
        draft.socks5Enabled = false
        draft.socks5Port = "invalid-hidden-port"
        assertNull(draft.socks5Error)
        assertNull(draft.settingsUpdate()(saved).socks5ProxyConfig())
    }

    @Test fun invalidPortsRemainUnsavedAndCannotFallBackToADefault() {
        val saved = GatewaySettings()
        val draft = SipSettingsDraft()
        draft.syncSaved(saved)

        for (transport in SipTransport.entries) {
            draft.transport = transport
            for (input in listOf("", " ", "abc", "5060x", "5060.0", "0", "-1", "65536", "2147483647", "999999999999")) {
                draft.port = input
                assertNotNull("port '$input' should be invalid", draft.portError)
                assertTrue(draft.hasUnsavedChanges(saved))
                assertThrows(IllegalArgumentException::class.java) { draft.settingsUpdate() }
                assertEquals(input, draft.port)
            }
        }
    }

    @Test fun validPortsIncludingBoundariesCanBeSavedAfterAnError() {
        val saved = GatewaySettings()
        val draft = SipSettingsDraft()
        draft.syncSaved(saved)
        draft.port = "65536"
        assertNotNull(draft.portError)

        for (input in listOf("1", "5060", "5061", "15062", "65535")) {
            draft.port = input
            assertNull("port '$input' should be valid", draft.portError)
            val submitted = draft.settingsUpdate()(saved)
            assertEquals(input.toInt(), submitted.port)
            draft.syncSaved(submitted)
            assertFalse(draft.hasUnsavedChanges(submitted))
        }
    }

    @Test fun queuedSettingsStayCleanOnReopenAndPreserveFurtherEdits() {
        val applied = GatewaySettings(server = "saved.invalid", username = "saved-user")
        val queued = applied.copy(server = "queued.invalid")
        val draft = SipSettingsDraft()
        draft.syncSaved(applied, queued)
        assertEquals("queued.invalid", draft.server)
        assertFalse(draft.hasUnsavedChanges(applied, queued))

        draft.server = "unfinished.invalid"
        draft.password = "unfinished-password"
        val updated = queued.copy(server = "external.invalid", username = "external-user", sipDebugLog = true)
        draft.syncSaved(applied.copy(sipDebugLog = true), updated)

        assertEquals("unfinished.invalid", draft.server)
        assertEquals("unfinished-password", draft.password)
        assertEquals("external-user", draft.user)
        assertTrue(draft.hasUnsavedChanges(applied, updated))

        // Applying the queue preserves subsequent edits; saving them clears the unsaved indicator.
        draft.syncSaved(updated)
        assertEquals("unfinished.invalid", draft.server)
        assertEquals("unfinished-password", draft.password)
        assertTrue(draft.hasUnsavedChanges(updated))
        val submitted = draft.settingsUpdate()(updated)
        draft.syncSaved(updated, submitted)
        assertFalse(draft.hasUnsavedChanges(updated, submitted))
        draft.syncSaved(submitted)
        assertFalse(draft.hasUnsavedChanges(submitted))
    }

    @Test fun submissionCapturesClickTimeValuesWithoutReplacingOtherQueuedPreferences() {
        val draft = SipSettingsDraft()
        draft.syncSaved(GatewaySettings())
        draft.server = "submitted.invalid"
        draft.password = "submitted-password"
        val submit = draft.settingsUpdate()
        draft.server = "later.invalid"
        draft.password = "later-password"
        val latest = GatewaySettings(audioMethod = AudioMethod.LEGACY, callAccess = CallAccess.DIALER,
            muteEarpiece = false, sipDebugLog = true, keepDefaultDialer = true)
        val result = submit(latest)

        assertEquals("submitted.invalid", result.server)
        assertEquals("submitted-password", result.password)
        assertEquals(AudioMethod.LEGACY, result.audioMethod)
        assertEquals(CallAccess.DIALER, result.callAccess)
        assertFalse(result.muteEarpiece)
        assertTrue(result.sipDebugLog)
        assertTrue(result.keepDefaultDialer)
    }
}
