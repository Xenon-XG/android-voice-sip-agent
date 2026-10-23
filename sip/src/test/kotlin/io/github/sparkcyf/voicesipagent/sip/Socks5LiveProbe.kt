package io.github.sparkcyf.voicesipagent.sip

import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.SecureRandom

/** Explicit opt-in probe; never runs as part of offline unit tests. */
fun main(args: Array<String>) {
    require(args.size == 2) { "Usage: Socks5LiveProbeKt proxy-host proxy-port" }
    val config = Socks5ProxyConfig(args[0], args[1].toInt(),
        System.getenv("SOCKS5_USERNAME").orEmpty(), System.getenv("SOCKS5_PASSWORD").orEmpty())
    val target = InetSocketAddress(InetAddress.getByName("stun.cloudflare.com"), 3478)
    val transaction = ByteArray(12).also(SecureRandom()::nextBytes)
    val request = ByteBuffer.allocate(20).putShort(1).putShort(0).putInt(0x2112a442).put(transaction).array()
    Socks5DatagramSocket(config).use { socket ->
        socket.soTimeout = 5000
        val before = System.nanoTime()
        socket.send(DatagramPacket(request, request.size, target))
        val packet = DatagramPacket(ByteArray(2048), 2048)
        socket.receive(packet)
        check(packet.socketAddress == target) { "Unexpected logical source" }
        check(packet.length >= 20) { "Short STUN response" }
        val header = ByteBuffer.wrap(packet.data, 0, packet.length)
        check(header.short.toInt() == 0x0101) { "STUN binding not successful" }
        header.short
        check(header.int == 0x2112a442)
        check(ByteArray(12).also(header::get).contentEquals(transaction))
        println("PASS: compiled SOCKS5 UDP transport completed STUN round trip in ${(System.nanoTime() - before) / 1_000_000} ms")
    }
}
