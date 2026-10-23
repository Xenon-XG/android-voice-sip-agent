package io.github.sparkcyf.voicesipagent.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class SettingsStoreTest {
    @Test fun proxyDefaultsAndPasswordsRoundTripWithOnlySealedPasswordsInPreferences(): Unit = Fixture().use { f ->
        assertEquals(GatewaySettings(), f.store.load())
        val settings = GatewaySettings(sipStack = SipStack.BUILTIN, password = "sip-secret-password",
            socks5Enabled = true, socks5Host = "proxy.invalid", socks5Port = 10809,
            socks5Username = "proxy-user", socks5Password = "proxy-secret-password")
        f.store.save(settings)
        assertEquals(settings, f.store.load())
        assertFalse(f.values.containsKey("password"))
        assertFalse(f.values.containsKey("socks5_password"))
        for (key in listOf("password_sealed", "socks5_password_sealed")) {
            val sealed = f.values[key] as String
            assertFalse(sealed.contains("secret-password"))
            assertTrue(sealed.isNotEmpty())
        }
        val first = f.values["socks5_password_sealed"]
        f.store.save(settings)
        assertNotEquals(first, f.values["socks5_password_sealed"])
        assertEquals(settings, f.store.load())
        f.store.save(settings.copy(socks5Username = "", socks5Password = ""))
        assertEquals("", f.store.load().socks5Password)
        assertNull(f.store.load().socks5Error)
    }

    @Test fun unreadableProxyPasswordLeavesAuthenticatedProxyInvalidInsteadOfFallingBackToNoAuth(): Unit =
        Fixture().use { f ->
            f.store.save(GatewaySettings(sipStack = SipStack.BUILTIN, socks5Enabled = true,
                socks5Username = "proxy-user", socks5Password = "proxy-secret-password"))
            f.values["socks5_password_sealed"] = "broken ciphertext"
            val loaded = f.store.load()
            assertTrue(loaded.socks5Enabled)
            assertEquals("proxy-user", loaded.socks5Username)
            assertEquals("", loaded.socks5Password)
            assertNotNull(loaded.socks5Error)
            assertThrows(IllegalArgumentException::class.java) { loaded.socks5ProxyConfig() }
        }

    /** Exercise actual AES-GCM while replacing only Android storage/Keystore/Base64 plumbing. */
    private class Fixture : AutoCloseable {
        val values = mutableMapOf<String, Any?>()
        private val context = mock(Context::class.java)
        private val prefs = mock(SharedPreferences::class.java)
        private val editor = mock(SharedPreferences.Editor::class.java)
        private val keyStore = mock(KeyStore::class.java)
        private val keys = mockStatic(KeyStore::class.java)
        private val base64 = mockStatic(Base64::class.java)
        private val logs = mockStatic(Log::class.java)
        val store: SettingsStore

        init {
            `when`(context.getSharedPreferences("gateway", Context.MODE_PRIVATE)).thenReturn(prefs)
            `when`(prefs.edit()).thenReturn(editor)
            doAnswer { values[it.getArgument<String>(0)] ?: it.getArgument<Any?>(1) }
                .`when`(prefs).getString(anyString(), nullable(String::class.java))
            doAnswer { values[it.getArgument<String>(0)] ?: it.getArgument<Boolean>(1) }
                .`when`(prefs).getBoolean(anyString(), anyBoolean())
            doAnswer { values[it.getArgument<String>(0)] ?: it.getArgument<Int>(1) }
                .`when`(prefs).getInt(anyString(), anyInt())
            doAnswer { values[it.getArgument(0)] = it.getArgument<String?>(1); editor }
                .`when`(editor).putString(anyString(), nullable(String::class.java))
            doAnswer { values[it.getArgument(0)] = it.getArgument<Boolean>(1); editor }
                .`when`(editor).putBoolean(anyString(), anyBoolean())
            doAnswer { values[it.getArgument(0)] = it.getArgument<Int>(1); editor }
                .`when`(editor).putInt(anyString(), anyInt())
            keys.`when`<KeyStore> { KeyStore.getInstance("AndroidKeyStore") }.thenReturn(keyStore)
            `when`(keyStore.getKey("sip-password", null)).thenReturn(SecretKeySpec(ByteArray(32) { 7 }, "AES"))
            base64.`when`<String> { Base64.encodeToString(any(ByteArray::class.java), eq(Base64.NO_WRAP)) }
                .thenAnswer { java.util.Base64.getEncoder().encodeToString(it.getArgument(0)) }
            base64.`when`<ByteArray> { Base64.decode(anyString(), eq(Base64.NO_WRAP)) }
                .thenAnswer { java.util.Base64.getDecoder().decode(it.getArgument<String>(0)) }
            store = SettingsStore(context)
        }

        override fun close() {
            logs.close()
            base64.close()
            keys.close()
        }
    }
}
