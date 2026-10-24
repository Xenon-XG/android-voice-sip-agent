package io.github.sparkcyf.voicesipagent.privileged.shell

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** One injection failure handed to the capture thread, which owns backend replacement. */
internal class AudioRecoveryRequest<T>(val failedTrack: T, val reason: String) {
    enum class Result { RECOVERED, FAILED, TIMED_OUT }

    private val result = AtomicReference<Result?>(null)
    private val done = CountDownLatch(1)
    val isPending: Boolean get() = result.get() == null

    /** First completion wins, including close and timeout; a late rebuild cannot revive a call. */
    fun complete(recovered: Boolean) {
        finish(if (recovered) Result.RECOVERED else Result.FAILED)
    }

    fun await(timeoutMs: Long): Result {
        require(timeoutMs >= 0)
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) finish(Result.TIMED_OUT)
        return checkNotNull(result.get())
    }

    private fun finish(value: Result) {
        if (result.compareAndSet(null, value)) done.countDown()
    }
}
