package io.github.sparkcyf.voicesipagent.app

import org.junit.Assert.assertEquals
import org.junit.Test

class UiStatusTextTest {
    private val translations = mapOf(
        R.string.status_registering to "正在注册",
        R.string.status_registered_1_s_s to "已注册（%1\$s 秒）",
        R.string.status_registered_pjsua2_1_ss to "已注册（pjsua2，%1\$s 秒）",
        R.string.status_sip_1_s to "SIP：%1\$s",
        R.string.status_ready_1_s to "已就绪 · %1\$s",
        R.string.status_register_failed_1_s to "注册失败：%1\$s",
        R.string.status_held_unregistered_1_s to "暂停注册：%1\$s",
        R.string.status_no_cellular_call to "无蜂窝通话",
        R.string.status_gateway_call_1_s to "网关通话：%1\$s",
        R.string.status_other_call_1_s to "其他通话：%1\$s",
        R.string.status_active to "通话中",
        R.string.status_incoming to "来电振铃",
    )
    private fun display(text: String) = localizeStatus(text) { id, args ->
        val template = translations.getValue(id)
        args.foldIndexed(template) { index, result, value -> result.replace("%${index + 1}\$s", value) }
    }

    @Test fun registrationAndNotificationTemplatesKeepExpiryValues() {
        assertEquals("正在注册", display("registering"))
        assertEquals("已注册（300 秒）", display("registered (300 s)"))
        assertEquals("已就绪 · 已注册（pjsua2，600 秒）", display("Ready · registered (pjsua2, 600s)"))
        assertEquals("SIP：暂停注册：无蜂窝通话", display("SIP: held unregistered: No cellular call"))
    }

    @Test fun failureDetailsAreOpaqueEvenWhenTheyLookLikeStatusText() {
        assertEquals("注册失败：403 registering@pbx.example / active", display("register failed: 403 registering@pbx.example / active"))
        assertEquals("注册失败：registering", display("register failed: registering"))
    }

    @Test fun knownCallSummariesAreTranslatedWithoutChangingTheirOrder() {
        assertEquals("网关通话：通话中, 其他通话：来电振铃", display("Gateway call: active, Other call: incoming"))
    }

    @Test fun unknownOrPartialDiagnosticStringsPassThroughUnchanged() {
        for (text in listOf("", "sip:registering@pbx.example", "new backend error", "Gateway call: new-state",
            "Gateway call: active EXTRA", "registered (300 s) trailing", "not registering")) {
            assertEquals(text, display(text))
        }
    }

    @Test fun recursiveStatusInputHasABoundedDepth() {
        val text = "SIP: ".repeat(100) + "registering"
        assertEquals(100, Regex("SIP[:：]").findAll(display(text)).count())
    }
}
