package io.github.sparkcyf.voicesipagent.privileged.shell

import android.content.ContextWrapper
import android.system.ErrnoException
import android.system.StructTimeval
import io.github.sparkcyf.voicesipagent.privileged.AudioMethod
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor

/** Opt-in real-device transport test. Uses synthetic bytes, not recorded or generated audio. */
fun main() {
    val local = FileDescriptor()
    val peer = FileDescriptor()
    var peerClosed = false
    Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_SEQPACKET, 0, local, peer)
    try {
        Os.setsockoptInt(local, OsConstants.SOL_SOCKET, OsConstants.SO_SNDBUF, 4096)
        val writer = BoundedPcmWriter(local)
        val packet = ByteArray(320) { 7 }
        val received = ByteArray(321)
        fun receivePacket() {
            val n = Os.read(peer, received, 0, received.size)
            check(n == packet.size) { "Packet boundary changed: $n" }
            check(received.copyOf(n).contentEquals(packet)) { "Packet data changed" }
        }
        check(writer.write(packet))
        receivePacket()
        println("PASS: production sender preserves connected UNIX SEQPACKET frames")

        var queued = 0
        val start = System.nanoTime()
        while (queued < 100_000 && writer.write(packet)) queued++
        check(queued in 1 until 100_000) { "Could not exercise backpressure" }
        check(writer.droppedFrames == 1L)
        check(!writer.write(packet))
        check(writer.droppedFrames == 2L)
        check(System.nanoTime() - start < 2_000_000_000L) { "Backpressure send blocked" }
        println("PASS: full socket drops whole frames with bounded recovery-dispatch delay")

        repeat(queued) { receivePacket() }
        check(writer.write(packet))
        receivePacket()
        check(writer.droppedFrames == 2L)
        println("PASS: packet delivery resumes after peer drains its queue")

        Os.close(peer)
        peerClosed = true
        var failed = false
        try {
            writer.write(packet)
        } catch (e: ErrnoException) {
            failed = e.errno != OsConstants.EAGAIN
        }
        check(failed) { "Peer closure was incorrectly treated as congestion" }
        println("PASS: peer closure remains fatal rather than silently dropping forever")
        verifyCloseBeforeAudioLock(false)
        verifyCloseBeforeAudioLock(true)
    } finally {
        if (!peerClosed) runCatching { Os.close(peer) }
        runCatching { Os.close(local) }
    }
}

/** Exercise actual tap shutdown without opening AudioRecord, AudioTrack, or a cellular call. */
private fun verifyCloseBeforeAudioLock(timedOut: Boolean) {
    val local = FileDescriptor()
    val peer = FileDescriptor()
    Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_SEQPACKET, 0, local, peer)
    val closed = CountDownLatch(1)
    val hookCalled = CountDownLatch(1)
    val tap = CallAudioTap(ContextWrapper(null), AudioMethod.AUTO, 8000, object : CallAudioTap.Hooks {
        override fun withdrawMuteForCaptureFailure(failure: String) = false
        override fun beforeBackendSwitch() {}
        override fun afterBackendSwitch(usedMethod: String) {}
        override fun onClosed() { hookCalled.countDown() }
    })
    fun field(name: String) = CallAudioTap::class.java.getDeclaredField(name).apply { isAccessible = true }
    field("local").set(tap, local)
    field("running").setBoolean(tap, true)
    val request = AudioRecoveryRequest(Any(), "synthetic shutdown test")
    @Suppress("UNCHECKED_CAST")
    val mailbox = field("injectionRecovery").get(tap) as AtomicReference<Any?>
    mailbox.set(request)
    if (timedOut) check(request.await(0) == AudioRecoveryRequest.Result.TIMED_OUT)
    try {
        Os.setsockoptTimeval(peer, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO,
            StructTimeval.fromMillis(1000))
        synchronized(tap) {
            Thread({ tap.close(); closed.countDown() }, "probe-tap-close").start()
            // close() must signal EOF while native audio cleanup cannot acquire this monitor.
            check(Os.read(peer, ByteArray(321), 0, 321) == 0) { "Tap socket did not close before native cleanup" }
            check(!tap.isOpen)
            val expected = if (timedOut) AudioRecoveryRequest.Result.TIMED_OUT else AudioRecoveryRequest.Result.FAILED
            check(request.await(0) == expected)
            request.complete(true)
            check(request.await(0) == expected) { "Late recovery revived a cancelled request" }
            check(!closed.await(0, TimeUnit.MILLISECONDS))
        }
        check(closed.await(2, TimeUnit.SECONDS)) { "Cleanup did not finish after releasing native monitor" }
        check(hookCalled.await(2, TimeUnit.SECONDS))
        println(if (timedOut) "PASS: timeout closes tap socket before native lock and rejects late recovery"
            else "PASS: hangup wakes app and cancels pending recovery before native cleanup")
    } finally {
        tap.close()
        runCatching { Os.close(peer) }
    }
}
