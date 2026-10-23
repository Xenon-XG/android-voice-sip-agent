package io.github.sparkcyf.voicesipagent.sip

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException

/** UDP socket plus a receive thread that hands parsed messages to [onMessage]. */
internal class SipTransport(
    bindPort: Int,
    private val log: SipLog,
    private val socks5Proxy: Socks5ProxyConfig? = null,
    private val onFailure: () -> Unit = {},
    private val onMessage: (SipMessage, InetSocketAddress) -> Unit,
) {
    private val socket = socks5Proxy?.let { Socks5DatagramSocket(it, bindPort) } ?: DatagramSocket(null).apply {
        reuseAddress = true
        bind(InetSocketAddress(bindPort))
    }
    @Volatile private var closed = false
    private val thread = Thread(::loop, "sip-rx").apply { isDaemon = true }

    val localPort: Int get() = socket.localPort
    val healthy: Boolean get() = !closed && !socket.isClosed

    fun start() = thread.start()

    fun send(msg: SipMessage, to: InetSocketAddress) {
        if (closed) return
        if (socket.isClosed) { failed(); return }
        val bytes = msg.encode()
        log.trace("--> $to\n${String(bytes)}")
        try {
            socket.send(DatagramPacket(bytes, bytes.size, to))
        } catch (e: Exception) {
            log.warn("send ${msg.startLine.take(40)} failed", e)
            if (socks5Proxy != null) failed()
        }
    }

    private fun loop() {
        val buf = ByteArray(65535)
        val pkt = DatagramPacket(buf, buf.size)
        while (!closed) {
            try {
                pkt.setData(buf, 0, buf.size)
                socket.receive(pkt)
            } catch (e: SocketException) {
                if (closed) return
                if (socket.isClosed) { failed(); return }
                continue
            } catch (e: Exception) {
                continue
            }
            val from = InetSocketAddress(pkt.address, pkt.port)
            val msg = try {
                SipParser.parse(buf, pkt.length)
            } catch (e: Exception) {
                log.warn("unparseable datagram from $from", e)
                null
            } ?: continue
            log.trace("<-- $from\n${String(buf, 0, pkt.length)}")
            onMessage(msg, from)
        }
    }

    @Synchronized
    private fun failed() {
        if (closed) return
        closed = true
        socket.close()
        log.warn("SIP transport closed; registration must be renewed")
        onFailure()
    }

    fun close() {
        closed = true
        socket.close()
    }
}
