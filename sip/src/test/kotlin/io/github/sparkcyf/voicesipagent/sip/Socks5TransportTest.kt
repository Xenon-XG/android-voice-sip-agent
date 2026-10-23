package io.github.sparkcyf.voicesipagent.sip

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class Socks5TransportTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val quiet = object : SipLog {
        override fun info(msg: String) {}
        override fun warn(msg: String, t: Throwable?) {}
    }

    @Test fun configRejectsInvalidInputAndDoesNotPrintCredentials() {
        val config = Socks5ProxyConfig("127.0.0.1", 10808, "private-user", "private-password")
        assertFalse(config.toString().contains("private-user"))
        assertFalse(config.toString().contains("private-password"))
        for (create in listOf<() -> Unit>(
            { Socks5ProxyConfig("", 10808) }, { Socks5ProxyConfig("socks://host", 10808) },
            { Socks5ProxyConfig("host", 0) }, { Socks5ProxyConfig("host", 65536) },
            { Socks5ProxyConfig("host", 10808, "user", "") },
            { Socks5ProxyConfig("host", 10808, "user", "é".repeat(128)) },
        )) {
            assertThrows(IllegalArgumentException::class.java) { create() }
        }
    }

    @Test fun framingPreservesOffsetsAndIpv4Port() {
        val input = byteArrayOf(99, 10, 20, 30, 88)
        val packet = DatagramPacket(input, 1, 3, InetSocketAddress(loopback, 5060))
        val wire = Socks5Datagrams.encode(packet)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 127, 0, 0, 1, 19, -60, 10, 20, 30), wire)
        val decoded = Socks5Datagrams.decode(wire, wire.size)!!
        assertEquals(InetSocketAddress(loopback, 5060), decoded.source)
        assertArrayEquals(byteArrayOf(10, 20, 30), wire.copyOfRange(decoded.offset, decoded.offset + decoded.length))
    }

    @Test fun framingSupportsIpv6AndDomainAddresses() {
        val ipv6 = InetAddress.getByName("::1")
        val wire = Socks5Datagrams.encode(DatagramPacket(byteArrayOf(42), 1, InetSocketAddress(ipv6, 40000)))
        assertEquals(ipv6, Socks5Datagrams.decode(wire, wire.size)!!.source.address)
        assertEquals(40000, Socks5Datagrams.decode(wire, wire.size)!!.source.port)
        val domain = Socks5Datagrams.address(InetSocketAddress.createUnresolved("example.test", 5060))
        assertEquals(3, domain[0].toInt())
        assertEquals("example.test", String(domain, 2, domain[1].toInt()))
    }

    @Test fun malformedOrFragmentedDatagramsAreRejected() {
        val valid = Socks5Datagrams.encode(DatagramPacket(byteArrayOf(1), 1, InetSocketAddress(loopback, 5060)))
        for (size in 0..9) assertNull(Socks5Datagrams.decode(valid, size))
        for (position in listOf(0, 1, 2, 3)) {
            val bad = valid.copyOf(); bad[position] = 99
            assertNull(Socks5Datagrams.decode(bad, bad.size))
        }
        assertNull(Socks5Datagrams.decode(byteArrayOf(0, 0, 0, 3, -1, 1), 6))
        val zeroPort = valid.copyOf(); zeroPort[8] = 0; zeroPort[9] = 0
        assertNull(Socks5Datagrams.decode(zeroPort, zeroPort.size))
    }

    @Test fun noAuthUdpRoundTripUsesRelayRatherThanDirectSocket(): Unit = ProxyFixture().use { proxy ->
        DatagramSocket(0, loopback).use { peer ->
            peer.soTimeout = 2000
            Socks5DatagramSocket(proxy.config).use { client ->
                client.soTimeout = 2000
                client.send(DatagramPacket(byteArrayOf(7, 8, 9), 3, InetSocketAddress(loopback, peer.localPort)))
                val request = DatagramPacket(ByteArray(32), 32)
                peer.receive(request)
                assertEquals(proxy.relay.localPort, request.port)
                assertNotEquals(client.localPort, request.port)
                peer.send(DatagramPacket(byteArrayOf(9, 8), 2, request.socketAddress))
                val out = ByteArray(8)
                val response = DatagramPacket(out, 2, 4)
                client.receive(response)
                assertEquals(peer.localPort, response.port)
                assertEquals(loopback, response.address)
                assertEquals(2, response.length)
                assertArrayEquals(byteArrayOf(0, 0, 9, 8, 0, 0, 0, 0), out)
                assertEquals(1, proxy.forwarded.get())
            }
        }
    }

    @Test fun mediaSessionSendsAndReceivesRtpViaTheProxy(): Unit = ProxyFixture().use { proxy ->
        DatagramSocket(0, loopback).use { peer ->
            peer.soTimeout = 2000
            val socket = Socks5DatagramSocket(proxy.config)
            val media = io.github.sparkcyf.voicesipagent.sip.media.RtpSession(socket,
                io.github.sparkcyf.voicesipagent.sip.media.RtpParams(InetSocketAddress(loopback, peer.localPort),
                    io.github.sparkcyf.voicesipagent.sip.media.AudioCodec.PCMU, 0, 101, true)) { _, _ -> }
            try {
                media.start()
                media.writeFrame(ShortArray(160) { 1000 })
                val packet = DatagramPacket(ByteArray(2048), 2048)
                peer.receive(packet)
                assertEquals(proxy.relay.localPort, packet.port)
                assertEquals(172, packet.length)
                assertEquals(0x80, packet.data[0].toInt() and 255)
                peer.send(DatagramPacket(packet.data, packet.length, packet.socketAddress))
                val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (media.packetsReceived == 0L && System.nanoTime() < end) Thread.sleep(5)
                assertEquals(1L, media.packetsSent)
                assertEquals(1L, media.packetsReceived)
            } finally { media.close() }
        }
    }

    @Test fun usernamePasswordAuthenticationWorksWithoutLeakingSecrets(): Unit = ProxyFixture(auth = true).use { proxy ->
        Socks5DatagramSocket(proxy.config).use { assertFalse(it.isClosed) }
        assertTrue(proxy.authenticated.await(2, TimeUnit.SECONDS))
    }

    @Test fun authenticationFailureDoesNotCreateADirectUdpFallback(): Unit = ProxyFixture(auth = true).use { proxy ->
        val error = assertThrows(java.io.IOException::class.java) {
            Socks5DatagramSocket(proxy.config.copy(password = "wrong-secret"))
        }
        assertFalse(error.toString().contains("wrong-secret"))
        assertEquals(0, proxy.forwarded.get())
    }

    @Test fun associationRejectionReportsFailure(): Unit = ProxyFixture(reject = true).use { proxy ->
        val error = assertThrows(java.io.IOException::class.java) { Socks5DatagramSocket(proxy.config) }
        assertTrue(error.message!!.contains("code 7"))
    }

    @Test fun controlDisconnectUnblocksReceiveAndPreventsMoreSends(): Unit = ProxyFixture().use { proxy ->
        Socks5DatagramSocket(proxy.config).use { client ->
            val reader = CompletableFuture.supplyAsync {
                try { client.receive(DatagramPacket(ByteArray(16), 16)); false }
                catch (_: SocketException) { true }
            }
            proxy.dropControl()
            assertTrue(reader.get(3, TimeUnit.SECONDS))
            assertTrue(client.isClosed)
            assertThrows(SocketException::class.java) {
                client.send(DatagramPacket(byteArrayOf(1), 1, InetSocketAddress(loopback, 12345)))
            }
        }
    }

    @Test fun stalledProxyHandshakeHasBoundedTimeout() {
        ServerSocket(0, 1, loopback).use { listener ->
            val accepted = CompletableFuture.supplyAsync { listener.accept() }
            assertThrows(java.io.IOException::class.java) {
                Socks5DatagramSocket(Socks5ProxyConfig("127.0.0.1", listener.localPort), timeoutMs = 150)
            }
            accepted.get(2, TimeUnit.SECONDS).close()
        }
    }

    @Test fun sipRegistersThroughProxyAndUnregistersCleanly(): Unit = ProxyFixture().use { proxy ->
        DatagramSocket(0, loopback).use { registrar ->
            registrar.soTimeout = 3000
            val registered = CountDownLatch(1)
            val ua = SipUserAgent(SipConfig(SipAccount("127.0.0.1", registrar.localPort, "test", "test"), socks5Proxy = proxy.config), quiet)
            ua.addStatusListener { _, state -> if (state.ready) registered.countDown() }
            val responder = CompletableFuture.runAsync {
                repeat(2) {
                    val packet = DatagramPacket(ByteArray(8192), 8192)
                    registrar.receive(packet)
                    assertEquals(proxy.relay.localPort, packet.port)
                    val request = SipParser.parse(packet.data, packet.length) as SipRequest
                    assertEquals("REGISTER", request.method)
                    val response = ua.buildResponse(request, 200).apply { headers.set("Expires", request.headers["Expires"]!!) }.encode()
                    registrar.send(DatagramPacket(response, response.size, packet.socketAddress))
                }
            }
            try {
                ua.start()
                assertTrue(registered.await(3, TimeUnit.SECONDS))
                ua.stop().get(3, TimeUnit.SECONDS)
                responder.get(3, TimeUnit.SECONDS)
                assertFalse(ua.status.ready)
            } finally { ua.stop(timeoutMs = 50) }
        }
    }

    @Test fun failedProxyDoesNotSendRegisterDirectlyToServer(): Unit = ProxyFixture(reject = true).use { proxy ->
        DatagramSocket(0, loopback).use { registrar ->
            registrar.soTimeout = 200
            val failed = CountDownLatch(1)
            val ua = SipUserAgent(SipConfig(SipAccount("127.0.0.1", registrar.localPort, "test", "test"), socks5Proxy = proxy.config), quiet)
            ua.addStatusListener { _, state -> if (state.detail.contains("transport setup failed")) failed.countDown() }
            try {
                ua.start()
                assertTrue(failed.await(3, TimeUnit.SECONDS))
                assertFalse(ua.status.ready)
                assertThrows(SocketTimeoutException::class.java) { registrar.receive(DatagramPacket(ByteArray(128), 128)) }
            } finally { ua.stop(timeoutMs = 50) }
        }
    }

    private inner class ProxyFixture(val auth: Boolean = false, val reject: Boolean = false) : AutoCloseable {
        private val listener = ServerSocket(0, 1, loopback)
        val relay = DatagramSocket(0, loopback)
        val config = Socks5ProxyConfig("127.0.0.1", listener.localPort, if (auth) "test-user" else "", if (auth) "test-password" else "")
        val authenticated = CountDownLatch(1)
        val forwarded = AtomicInteger()
        private val failures = CopyOnWriteArrayList<Throwable>()
        @Volatile private var control: Socket? = null
        @Volatile private var clientAddress: InetSocketAddress? = null
        @Volatile private var closing = false
        private val ready = CountDownLatch(1)
        private val handshake = Thread({
            try {
                listener.accept().use { s ->
                    control = s
                    s.soTimeout = 5000
                    val input = DataInputStream(s.getInputStream())
                    val output = s.getOutputStream()
                    assertEquals(5, input.readUnsignedByte())
                    val methods = ByteArray(input.readUnsignedByte()).also(input::readFully)
                    val selected = if (auth) 2 else 0
                    assertTrue(methods.contains(selected.toByte()))
                    output.write(byteArrayOf(5, selected.toByte()))
                    if (auth) {
                        assertEquals(1, input.readUnsignedByte())
                        val user = String(ByteArray(input.readUnsignedByte()).also(input::readFully), Charsets.UTF_8)
                        val password = String(ByteArray(input.readUnsignedByte()).also(input::readFully), Charsets.UTF_8)
                        val valid = user == config.username && password == config.password
                        output.write(byteArrayOf(1, if (valid) 0 else 1))
                        if (!valid) return@use
                        authenticated.countDown()
                    }
                    assertEquals(5, input.readUnsignedByte()); assertEquals(3, input.readUnsignedByte()); assertEquals(0, input.readUnsignedByte())
                    assertEquals(1, input.readUnsignedByte())
                    val addr = InetAddress.getByAddress(ByteArray(4).also(input::readFully))
                    val port = input.readUnsignedShort()
                    clientAddress = InetSocketAddress(addr, port)
                    output.write(byteArrayOf(5, if (reject) 7 else 0, 0, 1, 0, 0, 0, 0, (relay.localPort ushr 8).toByte(), relay.localPort.toByte()))
                    ready.countDown()
                    if (!reject) { s.soTimeout = 0; input.read() }
                }
            } catch (e: Throwable) { if (!closing && e !is SocketException) failures += e }
        }, "fake-socks-control").apply { isDaemon = true; start() }
        private val forwarding = Thread({
            try {
                while (!closing) {
                    val packet = DatagramPacket(ByteArray(65535), 65535)
                    relay.receive(packet)
                    val client = clientAddress ?: continue
                    if (packet.socketAddress == client) {
                        val decoded = Socks5Datagrams.decode(packet.data, packet.length) ?: continue
                        forwarded.incrementAndGet()
                        relay.send(DatagramPacket(packet.data, decoded.offset, decoded.length, decoded.source))
                    } else {
                        val encoded = Socks5Datagrams.encode(packet)
                        relay.send(DatagramPacket(encoded, encoded.size, client))
                    }
                }
            } catch (e: Throwable) { if (!closing && e !is SocketException) failures += e }
        }, "fake-socks-udp").apply { isDaemon = true; start() }
        fun dropControl() { assertTrue(ready.await(2, TimeUnit.SECONDS)); control!!.close() }
        override fun close() {
            closing = true
            listener.close(); control?.close(); relay.close()
            handshake.join(1000); forwarding.join(1000)
            if (failures.isNotEmpty()) throw AssertionError("Fake proxy failed", failures.first())
        }
    }
}
