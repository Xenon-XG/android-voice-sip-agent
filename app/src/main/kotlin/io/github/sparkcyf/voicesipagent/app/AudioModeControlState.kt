package io.github.sparkcyf.voicesipagent.app

import io.github.sparkcyf.voicesipagent.privileged.CallAudioStatus

internal enum class AudioBackendDisplay { ACTIVE, CONNECTING, IDLE, UNAVAILABLE }

/** Keep requested policy, applied policy, and observed audio routing separate in the UI. */
internal data class AudioModeControlState(
    val appliedMethod: Int,
    val selectedMethod: Int,
    val changePending: Boolean,
    val backendDisplay: AudioBackendDisplay,
    val currentBackend: String,
    val lastBackend: String,
)

internal fun audioModeControlState(
    appliedMethod: Int,
    pendingMethod: Int?,
    audio: CallAudioStatus,
    callInProgress: Boolean,
): AudioModeControlState {
    val selectedMethod = pendingMethod ?: appliedMethod
    val knownBackend = audio.backend in setOf("direct", "redirection-helper", "redirection-manual")
    val display = when {
        !audio.available -> AudioBackendDisplay.UNAVAILABLE
        audio.active && knownBackend -> AudioBackendDisplay.ACTIVE
        audio.active -> AudioBackendDisplay.UNAVAILABLE
        callInProgress -> AudioBackendDisplay.CONNECTING
        else -> AudioBackendDisplay.IDLE
    }
    return AudioModeControlState(
        appliedMethod = appliedMethod,
        selectedMethod = selectedMethod,
        changePending = selectedMethod != appliedMethod,
        backendDisplay = display,
        currentBackend = if (display == AudioBackendDisplay.ACTIVE) audio.backendLabel else "",
        lastBackend = if (display == AudioBackendDisplay.IDLE && knownBackend) audio.backendLabel else "",
    )
}
