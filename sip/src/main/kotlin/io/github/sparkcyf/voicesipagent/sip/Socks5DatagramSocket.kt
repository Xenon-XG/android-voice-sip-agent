package io.github.sparkcyf.voicesipagent.sip

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

/** Credentials authenticate the local proxy; SOCKS5 itself does not encrypt traffic. */
data class Socks5ProxyConfig(
    val host: String,
    val port: Int,
    val username: String = "",
    val password: String = "",
) {
    init {
        require(host.isNotBlank() && host.none { it.isWhitespace() || it.isISOControl() || it.code == 92 || it in "/@?#" }) { "Invalid SOCKS5 host" }
        require(port in 1..65535) { "Invalid SOCKS5 port" }
        require(username.isEmpty() == password.isEmpty()) { "SOCKS5 username and password must both be set" }
        require(username.toByteArray(Charsets.UTF_8).size <= 255 && password.toByteArray(Charsets.UTF_8).size <= 255) {
            "SOCKS5 credentials exceed the protocol byte limit"
        }
    }
    override fun toString() = "Socks5ProxyConfig($host:$port, authenticated=${username.isNotEmpty()})"
}

internal object Socks5Datagrams {
    data class Decoded(val source: InetSocketAddress, val offset: Int, val length: Int)

    fun address(destination: InetSocketAddress): ByteArray {
        require(destination.port in 1..65535) { "Invalid SOCKS5 destination port" }
        val bytes = ByteArrayOutputStream()
        val ip = destination.address
        if (ip != null) {
            val raw = ip.address
            bytes.write(if (raw.size == 4) 1 else 4)
            bytes.write(raw)
        } else {
            val host = destination.hostString.toByteArray(Charsets.US_ASCII)
            require(host.size in 1..255 && destination.hostString.all { it.code in 33..126 }) { "Invalid SOCKS5 destination host" }
            bytes.write(3)
            bytes.write(host.size)
            bytes.write(host)
        }
        bytes.write(destination.port ushr 8)
        bytes.write(destination.port and 255)
        return bytes.toByteArray()
    }

    fun encode(packet: DatagramPacket): ByteArray {
        val destination = packet.socketAddress as? InetSocketAddress ?: throw IOException("Missing SOCKS5 destination")
        val header = address(destination)
        if (packet.length + header.size + 3 > 65507) throw IOException("SOCKS5 datagram too large")
        return ByteArray(3 + header.size + packet.length).also {
            header.copyInto(it, 3)
            packet.data.copyInto(it, 3 + header.size, packet.offset, packet.offset + packet.length)
        }
    }

    fun decode(data: ByteArray, length: Int): Decoded? {
        if (length < 4 || length > data.size || data[0] != 0.toByte() || data[1] != 0.toByte() || data[2] != 0.toByte()) return null
        var offset = 4
        val address: InetAddress = when (data[3].toInt() and 255) {
            1, 4 -> {
                val size = if (data[3] == 1.toByte()) 4 else 16
                if (offset + size + 2 > length) return null
                InetAddress.getByAddress(data.copyOfRange(offset, offset + size)).also { offset += size }
            }
            3 -> {
                if (offset >= length) return null
                val size = data[offset++].toInt() and 255
                if (size == 0 || offset + size + 2 > length) return null
                val host = String(data, offset, size, Charsets.US_ASCII)
                if (host.any { it.code !in 33..126 }) return null
                offset += size
                try { InetAddress.getByName(host) } catch (_: IOException) { return null }
            }
            else -> return null
        }
        val port = ((data[offset].toInt() and 255) shl 8) or (data[offset + 1].toInt() and 255)
        offset += 2
        if (port == 0) return null
        return Decoded(InetSocketAddress(address, port), offset, length - offset)
    }
}

/** One UDP association. Every payload uses the relay; losing its TCP control channel closes UDP. */
internal class Socks5DatagramSocket(
    config: Socks5ProxyConfig,
    bindPort: Int = 0,
    timeoutMs: Int = 5000,
) : DatagramSocket(null as SocketAddress?) {
    private val control = Socket(Proxy.NO_PROXY)
    private val disposed = AtomicBoolean()
    private val sendLock = Any()
    private val receiveLock = Any()
    private val receiveBuffer = ByteArray(65535)
    private val relay: InetSocketAddress

    init {
        try {
            control.connect(InetSocketAddress(config.host, config.port), timeoutMs)
            control.soTimeout = timeoutMs
            control.keepAlive = true
            val input = DataInputStream(control.getInputStream())
            val output = control.getOutputStream()
            val method = if (config.username.isEmpty()) 0 else 2
            output.write(byteArrayOf(5, 1, method.toByte()))
            if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != method) throw IOException("SOCKS5 authentication method rejected")
            if (method == 2) {
                val user = config.username.toByteArray(Charsets.UTF_8)
                val password = config.password.toByteArray(Charsets.UTF_8)
                try {
                    output.write(byteArrayOf(1, user.size.toByte()))
                    output.write(user)
                    output.write(password.size)
                    output.write(password)
                    if (input.readUnsignedByte() != 1 || input.readUnsignedByte() != 0) throw IOException("SOCKS5 authentication failed")
                } finally { user.fill(0); password.fill(0) }
            }
            super.bind(InetSocketAddress(control.localAddress, bindPort))
            output.write(byteArrayOf(5, 3, 0) + Socks5Datagrams.address(InetSocketAddress(control.localAddress, localPort)))
            if (input.readUnsignedByte() != 5) throw IOException("Invalid SOCKS5 reply version")
            val status = input.readUnsignedByte()
            if (input.readUnsignedByte() != 0) throw IOException("Invalid SOCKS5 reserved byte")
            val address = when (input.readUnsignedByte()) {
                1 -> InetAddress.getByAddress(ByteArray(4).also(input::readFully))
                4 -> InetAddress.getByAddress(ByteArray(16).also(input::readFully))
                3 -> {
                    val size = input.readUnsignedByte()
                    if (size == 0) throw IOException("Invalid SOCKS5 relay address")
                    InetAddress.getByName(String(ByteArray(size).also(input::readFully), Charsets.US_ASCII))
                }
                else -> throw IOException("Unsupported SOCKS5 relay address type")
            }
            val port = input.readUnsignedShort()
            if (status != 0) throw IOException("SOCKS5 UDP ASSOCIATE rejected (code $status)")
            if (port == 0) throw IOException("SOCKS5 relay port is zero")
            relay = InetSocketAddress(if (address.isAnyLocalAddress) control.inetAddress else address, port)
            // A connected UDP socket accepts packets only from this negotiated relay.
            super.connect(relay)
            control.soTimeout = 0
            Thread({
                try { control.getInputStream().read() } catch (_: IOException) { }
                finally { close() }
            }, "socks5-control-$localPort").apply { isDaemon = true; start() }
        } catch (e: Exception) {
            close()
            throw IOException("SOCKS5 UDP setup failed: ${e.message}", e)
        }
    }

    override fun send(packet: DatagramPacket) {
        if (isClosed) throw SocketException("SOCKS5 UDP association closed")
        val wire = Socks5Datagrams.encode(packet)
        synchronized(sendLock) { super.send(DatagramPacket(wire, wire.size, relay)) }
    }

    override fun receive(packet: DatagramPacket) {
        synchronized(receiveLock) {
            while (!isClosed) {
                val wire = DatagramPacket(receiveBuffer, receiveBuffer.size)
                super.receive(wire)
                val decoded = Socks5Datagrams.decode(receiveBuffer, wire.length) ?: continue
                val count = minOf(packet.length, decoded.length)
                receiveBuffer.copyInto(packet.data, packet.offset, decoded.offset, decoded.offset + count)
                packet.length = count
                packet.socketAddress = decoded.source
                return
            }
            throw SocketException("SOCKS5 UDP association closed")
        }
    }

    override fun close() {
        super.close()
        if (disposed.compareAndSet(false, true)) runCatching { control.close() }
    }
}
