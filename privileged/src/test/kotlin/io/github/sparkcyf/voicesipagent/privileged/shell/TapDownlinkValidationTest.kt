package io.github.sparkcyf.voicesipagent.privileged.shell

import io.github.sparkcyf.voicesipagent.privileged.AudioMethod
import io.github.sparkcyf.voicesipagent.privileged.shell.TapRecovery.Action
import org.junit.Assert.*
import org.junit.Test

class TapDownlinkValidationTest {
    private val window = TapRecovery.DOWNLINK_WINDOW_FRAMES
    private val probation = window * TapRecovery.RECOVERY_QUIET_WINDOWS

    private fun rebuilt(method: Int = AudioMethod.AUTO): TapRecovery {
        val r = TapRecovery(method).apply { opened(TapBackend.REDIRECTION) }
        assertEquals(Action.RecreateBackend(TapBackend.REDIRECTION), r.onInjectionFailure())
        r.opened(TapBackend.REDIRECTION)
        return r
    }

    @Test fun recordedTinyNoiseThenDigitalZeroFallsBackAfterRebuild() {
        val r = rebuilt()
        // Redmi trace: a rebuilt track works, capture peaks at 51, then remains digital zero.
        assertNull(r.onDownlinkFrame(51))
        repeat(probation - 2) { assertNull(r.onDownlinkFrame(0)) }
        assertEquals(Action.Switch(TapBackend.DIRECT), r.onDownlinkFrame(0))
        assertTrue(r.downlinkStatus.startsWith("near-silent downlink after injection rebuild:"))
        assertTrue(r.downlinkStatus.contains("peak=0 signalFrames=0/150"))
    }

    @Test fun persistentLowLevelNoiseDoesNotPassRecoveryValidation() {
        val r = rebuilt()
        repeat(probation - 1) { assertNull(r.onDownlinkFrame(51)) }
        assertEquals(Action.Switch(TapBackend.DIRECT), r.onDownlinkFrame(51))
    }

    @Test fun singleFullScaleClickCannotMaskSilentRecovery() {
        val r = rebuilt()
        assertNull(r.onDownlinkFrame(32767))
        repeat(probation - 2) { assertNull(r.onDownlinkFrame(0)) }
        assertEquals(Action.Switch(TapBackend.DIRECT), r.onDownlinkFrame(0))
    }

    @Test fun minimumSignalFramesEstablishRecoveryAndAllowSpeechPauses() {
        val r = rebuilt()
        repeat(TapRecovery.MIN_SIGNAL_FRAMES) { assertNull(r.onDownlinkFrame(301)) }
        repeat(window - TapRecovery.MIN_SIGNAL_FRAMES) { assertNull(r.onDownlinkFrame(0)) }
        assertTrue(r.downlinkStatus.startsWith("signal observed:"))
        repeat(window * 20) { assertNull(r.onDownlinkFrame(0)) }
        assertTrue(r.downlinkStatus.startsWith("signal observed:"))
    }

    @Test fun fewerSignalFramesDoNotCertifyRecovery() {
        val r = rebuilt()
        repeat(TapRecovery.MIN_SIGNAL_FRAMES - 1) { assertNull(r.onDownlinkFrame(301)) }
        repeat(probation - TapRecovery.MIN_SIGNAL_FRAMES) { assertNull(r.onDownlinkFrame(0)) }
        assertEquals(Action.Switch(TapBackend.DIRECT), r.onDownlinkFrame(0))
    }

    @Test fun startupNoiseDoesNotPermanentlyDisableDigitalZeroDetection() {
        val r = TapRecovery(AudioMethod.AUTO).apply { opened(TapBackend.REDIRECTION) }
        assertNull(r.onDownlinkFrame(51))
        repeat(window * 2 - 2) { assertNull(r.onDownlinkFrame(0)) }
        assertEquals(Action.Switch(TapBackend.DIRECT), r.onDownlinkFrame(0))
        assertTrue(r.downlinkStatus.startsWith("digital-zero startup downlink:"))
    }

    @Test fun lowLevelAudioWithoutKnownFaultDoesNotCauseNearSilenceFallback() {
        val r = TapRecovery(AudioMethod.AUTO).apply { opened(TapBackend.REDIRECTION) }
        repeat(window * TapRecovery.MAX_STARTUP_WINDOWS) { assertNull(r.onDownlinkFrame(51)) }
        assertTrue(r.downlinkStatus.startsWith("inconclusive startup downlink; no switch:"))
        repeat(window * 20) { assertNull(r.onDownlinkFrame(0)) }
    }

    @Test fun explicitRedirectionNeverChangesBackendOnSilentRebuild() {
        val r = rebuilt(AudioMethod.OFFICIAL)
        repeat(probation * 10) { assertNull(r.onDownlinkFrame(0)) }
        assertEquals(TapBackend.REDIRECTION, r.backend)
    }

    @Test fun directDoesNotSwitchOrCloseForSilenceAfterRebuild() {
        val r = TapRecovery(AudioMethod.AUTO).apply { opened(TapBackend.DIRECT) }
        assertEquals(Action.RecreateBackend(TapBackend.DIRECT), r.onInjectionFailure())
        r.opened(TapBackend.DIRECT)
        repeat(probation * 10) { assertNull(r.onDownlinkFrame(0)) }
        assertEquals(TapBackend.DIRECT, r.backend)
    }

    @Test fun fallbackIsIssuedOnceAndDoesNotLoopOnDirect() {
        val r = rebuilt()
        repeat(probation - 1) { assertNull(r.onDownlinkFrame(0)) }
        assertEquals(Action.Switch(TapBackend.DIRECT), r.onDownlinkFrame(0))
        repeat(probation) { assertNull(r.onDownlinkFrame(0)) }
        r.opened(TapBackend.DIRECT)
        repeat(probation) { assertNull(r.onDownlinkFrame(0)) }
        assertEquals(Action.RecreateBackend(TapBackend.DIRECT), r.onInjectionFailure())
        r.opened(TapBackend.DIRECT)
        assertEquals(Action.Close, r.onInjectionFailure())
    }

    @Test fun previousSignalAndWindowPeaksDoNotLeakIntoRebuildValidation() {
        val r = TapRecovery(AudioMethod.AUTO).apply { opened(TapBackend.REDIRECTION) }
        repeat(window) { assertNull(r.onDownlinkFrame(30000)) }
        assertEquals(Action.RecreateBackend(TapBackend.REDIRECTION), r.onInjectionFailure())
        r.opened(TapBackend.REDIRECTION)
        assertNull(r.onDownlinkFrame(51))
        repeat(window - 1) { assertNull(r.onDownlinkFrame(0)) }
        assertTrue(r.downlinkStatus.contains("peak=51 signalFrames=0/150"))
        repeat(window - 1) { assertNull(r.onDownlinkFrame(0)) }
        assertEquals(Action.Switch(TapBackend.DIRECT), r.onDownlinkFrame(0))
        assertTrue(r.downlinkStatus.contains("peak=0 signalFrames=0/150"))
    }

    @Test fun thresholdMatchesExistingDiagnosticComparison() {
        val r = rebuilt()
        repeat(probation - 1) { assertNull(r.onDownlinkFrame(TapRecovery.SIGNAL_THRESHOLD)) }
        assertEquals(Action.Switch(TapBackend.DIRECT), r.onDownlinkFrame(TapRecovery.SIGNAL_THRESHOLD))
    }

    @Test fun newCallDoesNotInheritRecoveryGraceOrBudgets() {
        val previous = rebuilt()
        repeat(window) { assertNull(previous.onDownlinkFrame(0)) }
        val next = TapRecovery(AudioMethod.AUTO).apply { opened(TapBackend.REDIRECTION) }
        repeat(window - 1) { assertNull(next.onDownlinkFrame(0)) }
        assertEquals(Action.Switch(TapBackend.DIRECT), next.onDownlinkFrame(0))
        assertEquals(Action.RecreateBackend(TapBackend.REDIRECTION), next.onInjectionFailure())
    }
}
