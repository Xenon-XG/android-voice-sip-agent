package io.github.sparkcyf.voicesipagent.privileged.shell

import io.github.sparkcyf.voicesipagent.privileged.shell.AudioRecoveryRequest.Result
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class AudioRecoveryRequestTest {
    @Test fun successfulRebuildReleasesWriter() {
        val track = Any()
        val request = AudioRecoveryRequest(track, "track write -6")
        assertSame(track, request.failedTrack)
        assertTrue(request.isPending)
        request.complete(true)
        assertFalse(request.isPending)
        assertEquals(Result.RECOVERED, request.await(0))
    }

    @Test fun hangupReleasesWaitingWriterWithoutRevival() {
        val request = AudioRecoveryRequest(Any(), "track write -6")
        val executor = Executors.newSingleThreadExecutor()
        try {
            val entered = CountDownLatch(1)
            val writer = executor.submit<Result> {
                entered.countDown()
                request.await(2_000)
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            request.complete(false)
            assertEquals(Result.FAILED, writer.get(1, TimeUnit.SECONDS))
            request.complete(true)
            assertEquals(Result.FAILED, request.await(0))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun stalledCaptureTimesOutAndRejectsLateSuccess() {
        val request = AudioRecoveryRequest(Any(), "track write -6")
        assertEquals(Result.TIMED_OUT, request.await(20))
        assertFalse(request.isPending)
        request.complete(true)
        assertEquals(Result.TIMED_OUT, request.await(0))
    }

    @Test fun lateCloseCannotRewriteCompletedRecovery() {
        val request = AudioRecoveryRequest(Any(), "track write -6")
        request.complete(true)
        request.complete(false)
        assertEquals(Result.RECOVERED, request.await(0))
    }

    @Test fun repeatedFailuresUseIndependentRequests() {
        val failed = AudioRecoveryRequest(Any(), "first")
        failed.complete(false)
        val next = AudioRecoveryRequest(Any(), "second")
        assertTrue(next.isPending)
        next.complete(true)
        assertEquals(Result.FAILED, failed.await(0))
        assertEquals(Result.RECOVERED, next.await(0))
    }

    @Test fun recoveryCompletionIsVisibleAcrossThreads() {
        val request = AudioRecoveryRequest(Any(), "track write -6")
        val executor = Executors.newSingleThreadExecutor()
        try {
            val writer = executor.submit<Result> { request.await(2_000) }
            request.complete(true)
            assertEquals(Result.RECOVERED, writer.get(1, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun concurrentCloseAndRecoveryHaveOneTerminalResult() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(100) {
                val request = AudioRecoveryRequest(Any(), "track write -6")
                val start = CountDownLatch(1)
                val recover = executor.submit { start.await(); request.complete(true) }
                val close = executor.submit { start.await(); request.complete(false) }
                start.countDown()
                recover.get(1, TimeUnit.SECONDS)
                close.get(1, TimeUnit.SECONDS)
                val result = request.await(0)
                assertTrue(result == Result.RECOVERED || result == Result.FAILED)
                assertFalse(request.isPending)
                assertEquals(result, request.await(0))
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun timeoutMustNotBeNegative() {
        assertTrue(runCatching { AudioRecoveryRequest(Any(), "failure").await(-1) }.isFailure)
    }
}
