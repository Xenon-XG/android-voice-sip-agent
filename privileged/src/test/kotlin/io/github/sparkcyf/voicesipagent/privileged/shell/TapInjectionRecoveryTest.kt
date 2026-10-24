package io.github.sparkcyf.voicesipagent.privileged.shell

import io.github.sparkcyf.voicesipagent.privileged.AudioMethod
import io.github.sparkcyf.voicesipagent.privileged.shell.TapRecovery.Action
import org.junit.Assert.*
import org.junit.Test

class TapInjectionRecoveryTest {
    @Test fun firstDeadObjectRebuildsCurrentBackend() {
        val r = TapRecovery(AudioMethod.AUTO).apply { opened(TapBackend.REDIRECTION) }
        assertEquals(Action.RecreateBackend(TapBackend.REDIRECTION), r.onInjectionFailure())
    }

    @Test fun secondFailureFallsForwardAndDirectGetsOnlyOneRebuild() {
        val r = TapRecovery(AudioMethod.AUTO).apply { opened(TapBackend.REDIRECTION) }
        assertEquals(Action.RecreateBackend(TapBackend.REDIRECTION), r.onInjectionFailure())
        r.opened(TapBackend.REDIRECTION)
        assertEquals(Action.Switch(TapBackend.DIRECT), r.onInjectionFailure())
        r.opened(TapBackend.DIRECT)
        assertEquals(Action.RecreateBackend(TapBackend.DIRECT), r.onInjectionFailure())
        r.opened(TapBackend.DIRECT)
        repeat(10) { assertEquals(Action.Close, r.onInjectionFailure()) }
    }

    @Test fun failedRebuildStillConsumesBudgetAndPermitsFallback() {
        val r = TapRecovery(AudioMethod.AUTO).apply { opened(TapBackend.REDIRECTION) }
        assertEquals(Action.RecreateBackend(TapBackend.REDIRECTION), r.onInjectionFailure())
        // Construction failed, so opened() was never called again.
        assertEquals(Action.Switch(TapBackend.DIRECT), r.onInjectionFailure())
    }

    @Test fun explicitRedirectionRebuildsOnceButNeverSelectsDirect() {
        val r = TapRecovery(AudioMethod.OFFICIAL).apply { opened(TapBackend.REDIRECTION) }
        assertEquals(Action.RecreateBackend(TapBackend.REDIRECTION), r.onInjectionFailure())
        r.opened(TapBackend.REDIRECTION)
        assertEquals(Action.Close, r.onInjectionFailure())
    }

    @Test fun explicitDirectRebuildsOnceThenCloses() {
        val r = TapRecovery(AudioMethod.LEGACY).apply { opened(TapBackend.DIRECT) }
        assertEquals(Action.RecreateBackend(TapBackend.DIRECT), r.onInjectionFailure())
        r.opened(TapBackend.DIRECT)
        assertEquals(Action.Close, r.onInjectionFailure())
    }

    @Test fun failedExplicitRebuildDoesNotLoop() {
        val r = TapRecovery(AudioMethod.LEGACY).apply { opened(TapBackend.DIRECT) }
        assertEquals(Action.RecreateBackend(TapBackend.DIRECT), r.onInjectionFailure())
        assertEquals(Action.Close, r.onInjectionFailure())
    }

    @Test fun autoStartingOnDirectHasNoBackwardFallback() {
        val r = TapRecovery(AudioMethod.AUTO).apply { opened(TapBackend.DIRECT) }
        assertEquals(Action.RecreateBackend(TapBackend.DIRECT), r.onInjectionFailure())
        r.opened(TapBackend.DIRECT)
        assertEquals(Action.Close, r.onInjectionFailure())
        assertTrue(runCatching { r.opened(TapBackend.REDIRECTION) }.isFailure)
    }

    @Test fun injectionRebuildDoesNotResetCaptureBudgets() {
        val r = TapRecovery(AudioMethod.LEGACY).apply { opened(TapBackend.DIRECT) }
        assertEquals(Action.RecreateRecord, r.onCaptureFailure(true))
        assertEquals(Action.RecreateRecord, r.onCaptureFailure(false))
        assertEquals(Action.RecreateBackend(TapBackend.DIRECT), r.onInjectionFailure())
        r.opened(TapBackend.DIRECT)
        assertEquals(Action.Close, r.onCaptureFailure(true))
        assertEquals(Action.Close, r.onCaptureFailure(false))
        assertEquals(Action.Close, r.onInjectionFailure())
    }

    @Test fun captureRecoveryDoesNotConsumeInjectionBudget() {
        val r = TapRecovery(AudioMethod.LEGACY).apply { opened(TapBackend.DIRECT) }
        assertEquals(Action.RecreateRecord, r.onCaptureFailure(false))
        assertEquals(Action.RecreateBackend(TapBackend.DIRECT), r.onInjectionFailure())
    }

    @Test fun silenceFallbackStillWorksAfterInjectionRebuild() {
        val r = TapRecovery(AudioMethod.AUTO).apply { opened(TapBackend.REDIRECTION) }
        assertNull(r.onDownlinkFrames(TapRecovery.ZERO_DOWNLINK_FRAMES, 100))
        assertEquals(Action.RecreateBackend(TapBackend.REDIRECTION), r.onInjectionFailure())
        r.opened(TapBackend.REDIRECTION)
        assertEquals(Action.Switch(TapBackend.DIRECT), r.onDownlinkFrames(TapRecovery.ZERO_DOWNLINK_FRAMES, 0))
        r.opened(TapBackend.DIRECT)
        assertEquals(Action.RecreateBackend(TapBackend.DIRECT), r.onInjectionFailure())
    }

    @Test fun captureFallbackGivesDirectIndependentRecoveryBudget() {
        val r = TapRecovery(AudioMethod.AUTO).apply { opened(TapBackend.REDIRECTION) }
        assertEquals(Action.RecreateBackend(TapBackend.REDIRECTION), r.onInjectionFailure())
        r.opened(TapBackend.REDIRECTION)
        assertEquals(Action.Switch(TapBackend.DIRECT), r.onCaptureFailure(false))
        r.opened(TapBackend.DIRECT)
        assertEquals(Action.RecreateRecord, r.onCaptureFailure(false))
        assertEquals(Action.RecreateBackend(TapBackend.DIRECT), r.onInjectionFailure())
    }

    @Test fun failureBeforeBackendOpenedCloses() {
        assertEquals(Action.Close, TapRecovery(AudioMethod.AUTO).onInjectionFailure())
    }
}
