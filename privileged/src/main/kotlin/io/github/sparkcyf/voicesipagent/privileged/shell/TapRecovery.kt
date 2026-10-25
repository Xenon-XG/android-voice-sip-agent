package io.github.sparkcyf.voicesipagent.privileged.shell

import io.github.sparkcyf.voicesipagent.privileged.AudioMethod

/** Capture/injection backends the tap can open. */
internal enum class TapBackend { REDIRECTION, DIRECT }

/**
 * Pure decision logic for keeping a call's audio tap alive. [CallAudioTap] performs the actions;
 * this class only tracks which recoveries the current tap has already used.
 */
internal class TapRecovery(method: Int) {
    sealed interface Action {
        /** Rebuild only the capture record of the current backend, at the same rate. */
        data object RecreateRecord : Action
        /** Rebuild both record and track once after an injection failure. */
        data class RecreateBackend(val backend: TapBackend) : Action
        /** Release the current backend and open [backend] mid-call. */
        data class Switch(val backend: TapBackend) : Action
        data object Close : Action
    }

    /** Backends this tap may use, in order. Only AUTO has more than one. */
    val plan: List<TapBackend> = when (method) {
        AudioMethod.AUTO -> AUTO_ORDER
        AudioMethod.OFFICIAL -> listOf(TapBackend.REDIRECTION)
        AudioMethod.LEGACY -> listOf(TapBackend.DIRECT)
        else -> throw IllegalArgumentException("unknown audio method $method")
    }

    private var current = -1
    private var muteRecoveryUsed = false
    private var transientRecoveryUsed = false
    private var observingDownlink = false
    private var afterInjectionRebuild = false
    private var windowFrames = 0
    private var windowPeak = 0
    private var windowSignalFrames = 0
    private var observationWindows = 0
    private var quietWindows = 0
    @Volatile var downlinkStatus = "not opened"; private set
    private val injectionRebuilt = mutableSetOf<TapBackend>()

    val backend: TapBackend? get() = plan.getOrNull(current)

    /** Records which plan entry actually opened, so fallbacks only move forward. */
    fun opened(backend: TapBackend) {
        val index = plan.indexOf(backend)
        require(index >= 0 && index >= current) { "$backend cannot follow ${this.backend} in $plan" }
        if (index != current) {
            // A same-backend injection rebuild must not replenish capture recovery budgets.
            muteRecoveryUsed = false
            transientRecoveryUsed = false
        }
        afterInjectionRebuild = index == current && backend in injectionRebuilt
        current = index
        observingDownlink = backend == TapBackend.REDIRECTION && next() != null
        windowFrames = 0
        windowPeak = 0
        windowSignalFrames = 0
        observationWindows = 0
        quietWindows = 0
        downlinkStatus = when {
            !observingDownlink -> "not monitored: no automatic downlink fallback for $backend"
            afterInjectionRebuild -> "observing downlink after injection rebuild"
            else -> "observing initial downlink"
        }
    }

    /**
     * The record returned <= 0. [muteWithdrawn] is true when our own voice-call stream mute was
     * just withdrawn because of this failure.
     */
    fun onCaptureFailure(muteWithdrawn: Boolean): Action {
        if (backend == TapBackend.DIRECT) {
            if (muteWithdrawn && !muteRecoveryUsed) {
                muteRecoveryUsed = true
                return Action.RecreateRecord
            }
            if (!transientRecoveryUsed) {
                transientRecoveryUsed = true
                return Action.RecreateRecord
            }
        }
        return next()?.let(Action::Switch) ?: Action.Close
    }

    /**
     * A write returned <= 0. Recreate the current backend once, then move forward in AUTO.
     * Consume the budget before attempting construction: failed opens and repeated dead objects
     * cannot reset it. Explicit modes never silently change the selected backend.
     */
    fun onInjectionFailure(): Action {
        val active = backend ?: return Action.Close
        if (injectionRebuilt.add(active)) return Action.RecreateBackend(active)
        return next()?.let(Action::Switch) ?: Action.Close
    }

    /**
     * Called once per complete 20 ms capture frame, never with a cumulative peak. Observe
     * non-overlapping windows until signal is established or the bounded startup check ends.
     * After a known injection fault/rebuild, two near-silent windows warrant AUTO fallback.
     * Without that fault, retain the digital-zero startup fallback but do not treat low-level
     * audio alone as failure. Established calls are not switched for ordinary speech pauses.
     * These levels are a recovery heuristic, not proof that either remote party can hear audio.
     */
    fun onDownlinkFrame(peak: Int): Action? {
        if (!observingDownlink) return null
        windowFrames++
        windowPeak = maxOf(windowPeak, peak)
        if (peak > SIGNAL_THRESHOLD) windowSignalFrames++
        if (windowFrames < DOWNLINK_WINDOW_FRAMES) return null

        observationWindows++
        val summary = "window=$observationWindows peak=$windowPeak signalFrames=$windowSignalFrames/$windowFrames"
        val signalObserved = windowSignalFrames >= MIN_SIGNAL_FRAMES
        val digitalZero = windowPeak == 0
        windowFrames = 0
        windowPeak = 0
        windowSignalFrames = 0

        if (signalObserved) {
            observingDownlink = false
            downlinkStatus = "signal observed: $summary"
            return null
        }
        if (afterInjectionRebuild) quietWindows++
        val switch = if (afterInjectionRebuild) quietWindows >= RECOVERY_QUIET_WINDOWS else digitalZero
        if (switch) {
            observingDownlink = false
            val reason = if (afterInjectionRebuild) "near-silent downlink after injection rebuild" else "digital-zero startup downlink"
            downlinkStatus = "$reason: $summary quietWindows=$quietWindows"
            return next()?.let(Action::Switch)
        }
        if (observationWindows >= MAX_STARTUP_WINDOWS) {
            observingDownlink = false
            downlinkStatus = "inconclusive startup downlink; no switch: $summary"
        } else {
            downlinkStatus = "observing downlink: $summary quietWindows=$quietWindows"
        }
        return null
    }

    private fun next(): TapBackend? = plan.getOrNull(current + 1)

    companion object {
        /**
         * AUTO order. Field reports (issue #1: POCO F6/F3, vivo V30 Lite, Galaxy A15, Redmi Note 13 Pro)
         * only got two-way audio from DIRECT, but redirection is the only backend verified on the
         * Pixel 11 Pro. Decide direct-first after testing DIRECT on the Pixel 11 Pro and moto.
         */
        val AUTO_ORDER = listOf(TapBackend.REDIRECTION, TapBackend.DIRECT)

        /** Match the existing diagnostic signal threshold; do not count tiny DC offsets as speech. */
        const val SIGNAL_THRESHOLD = 300
        /** Three seconds of captured audio, independent of 8/16 kHz sample rate. */
        const val DOWNLINK_WINDOW_FRAMES = 150
        /** Ignore an isolated click: at least 100 ms of above-threshold frames in a window. */
        const val MIN_SIGNAL_FRAMES = 5
        /** Six seconds of near-silent capture after a known fault, not a single silent window. */
        const val RECOVERY_QUIET_WINDOWS = 2
        /** Bound initial observation when low-level audio cannot distinguish silence from a fault. */
        const val MAX_STARTUP_WINDOWS = 4
    }
}
