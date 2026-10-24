package io.github.sparkcyf.voicesipagent.privileged.shell

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
import java.io.FileDescriptor

/** Atomic SEQPACKET sends with a bounded wait, so congestion cannot deadlock audio recovery. */
internal class BoundedPcmWriter(private val socket: FileDescriptor) {
    @Volatile var droppedFrames = 0L; private set

    init {
        // This changes send behavior only; the uplink reader remains blocking. Use the public
        // socket timeout API rather than hidden MSG_DONTWAIT constants or changing fd flags.
        Os.setsockoptTimeval(socket, OsConstants.SOL_SOCKET, OsConstants.SO_SNDTIMEO,
            StructTimeval.fromMillis(20))
    }

    fun write(frame: ByteArray): Boolean {
        val sent = try {
            Os.write(socket, frame, 0, frame.size)
        } catch (e: ErrnoException) {
            if (e.errno != OsConstants.EAGAIN) throw e
            droppedFrames++
            return false
        }
        check(sent == frame.size) { "short downlink packet write" }
        return true
    }
}
