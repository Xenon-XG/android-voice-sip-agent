package io.github.sparkcyf.voicesipagent.app

import io.github.sparkcyf.voicesipagent.privileged.AudioMethod
import io.github.sparkcyf.voicesipagent.privileged.CallAudioStatus
import org.junit.Assert.*
import org.junit.Test

class AudioModeControlStateTest {
    @Test fun autoPolicyRemainsAutoWhenActualBackendFallsBackToDirect() {
        val state = state(audio = CallAudioStatus(active = true, backend = "direct"))
        assertEquals(AudioMethod.AUTO, state.appliedMethod)
        assertEquals(AudioMethod.AUTO, state.selectedMethod)
        assertFalse(state.changePending)
        assertEquals(AudioBackendDisplay.ACTIVE, state.backendDisplay)
        assertEquals("Direct telephony", state.currentBackend)
        assertEquals("", state.lastBackend)
    }

    @Test fun fixedDirectIsDistinctFromAnAutomaticDirectFallback() {
        val state = state(applied = AudioMethod.LEGACY, audio = CallAudioStatus(active = true, backend = "direct"))
        assertEquals(AudioMethod.LEGACY, state.selectedMethod)
        assertEquals("Direct telephony", state.currentBackend)
    }

    @Test fun bothRedirectionImplementationsHaveTheSameUserFacingBackend() {
        for (backend in listOf("redirection-helper", "redirection-manual")) {
            val state = state(applied = AudioMethod.OFFICIAL, audio = CallAudioStatus(active = true, backend = backend))
            assertEquals(AudioBackendDisplay.ACTIVE, state.backendDisplay)
            assertEquals("Cellular call redirection", state.currentBackend)
        }
    }

    @Test fun pendingSelectionDoesNotRelabelTheRunningBackend() {
        val state = state(pending = AudioMethod.LEGACY,
            audio = CallAudioStatus(active = true, backend = "redirection-manual"), inCall = true)
        assertEquals(AudioMethod.AUTO, state.appliedMethod)
        assertEquals(AudioMethod.LEGACY, state.selectedMethod)
        assertTrue(state.changePending)
        assertEquals("Cellular call redirection", state.currentBackend)
    }

    @Test fun switchingBackToAppliedModeClearsOnlyTheAudioPendingIndicator() {
        val state = state(pending = AudioMethod.AUTO, inCall = true)
        assertFalse(state.changePending)
        assertEquals(AudioMethod.AUTO, state.selectedMethod)
    }

    @Test fun idleTapShowsPreviousBackendAsHistoryNotAsCurrent() {
        val state = state(audio = CallAudioStatus(active = false, backend = "direct"))
        assertEquals(AudioBackendDisplay.IDLE, state.backendDisplay)
        assertEquals("", state.currentBackend)
        assertEquals("Direct telephony", state.lastBackend)
    }

    @Test fun newCallDoesNotPresentOldBackendAsTheCurrentInterface() {
        val state = state(audio = CallAudioStatus(active = false, backend = "direct"), inCall = true)
        assertEquals(AudioBackendDisplay.CONNECTING, state.backendDisplay)
        assertEquals("", state.currentBackend)
        assertEquals("", state.lastBackend)
    }

    @Test fun unavailableHelperDoesNotShowStaleActiveRouting() {
        val state = state(audio = CallAudioStatus(active = true, backend = "direct", available = false))
        assertEquals(AudioBackendDisplay.UNAVAILABLE, state.backendDisplay)
        assertEquals("", state.currentBackend)
        assertEquals("", state.lastBackend)
    }

    @Test fun unknownOrEmptyActiveBackendIsUnavailableRatherThanGuessed() {
        for (backend in listOf("", "future-backend")) {
            val state = state(audio = CallAudioStatus(active = true, backend = backend))
            assertEquals(AudioBackendDisplay.UNAVAILABLE, state.backendDisplay)
            assertEquals("", state.currentBackend)
        }
    }

    @Test fun freshIdleSessionDoesNotInventAnAudioBackend() {
        val state = state()
        assertEquals(AudioBackendDisplay.IDLE, state.backendDisplay)
        assertEquals("", state.currentBackend)
        assertEquals("", state.lastBackend)
    }

    @Test fun appliedSelectionAppearsAfterDeferredSettingsCommit() {
        val state = state(applied = AudioMethod.LEGACY)
        assertFalse(state.changePending)
        assertEquals(AudioMethod.LEGACY, state.selectedMethod)
        assertEquals(AudioBackendDisplay.IDLE, state.backendDisplay)
    }

    private fun state(applied: Int = AudioMethod.AUTO, pending: Int? = null,
        audio: CallAudioStatus = CallAudioStatus(), inCall: Boolean = false) =
        audioModeControlState(applied, pending, audio, inCall)
}
