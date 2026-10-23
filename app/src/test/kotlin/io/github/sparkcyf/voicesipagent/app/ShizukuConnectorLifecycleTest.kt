package io.github.sparkcyf.voicesipagent.app

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import io.github.sparkcyf.voicesipagent.privileged.IPrivilegedService
import io.github.sparkcyf.voicesipagent.privileged.ShizukuConnector
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import rikka.shizuku.Shizuku

class ShizukuConnectorLifecycleTest {
    @Test fun helperDisconnectRebindsAfterTheSdkFinishesItsCallback() = Fixture().use { f ->
        f.connect(0)
        f.disconnect(0)
        assertEquals(ShizukuConnector.State.ERROR, f.connector.state)
        assertEquals(1, f.connections.size)
        f.drain()
        assertEquals(2, f.connections.size)
        f.connect(1)
        assertEquals(ShizukuConnector.State.CONNECTED, f.connector.state)
    }

    @Test fun serverRestartAndLateOldConnectionCallbacksDoNotBlockOrReplaceTheNewBinding() = Fixture().use { f ->
        f.connect(0)
        f.serverUp = false
        f.died.onBinderDead()
        f.disconnect(0)
        f.serverUp = true
        f.received.onBinderReceived()
        f.disconnect(0)
        f.drain()
        assertEquals(2, f.connections.size)
        f.connect(1)
        f.connect(0, -1)
        assertEquals(ShizukuConnector.State.CONNECTED, f.connector.state)
    }

    @Test fun stoppingBeforeDeferredRebindDoesNotRestartHelper() = Fixture().use { f ->
        f.connect(0)
        f.disconnect(0)
        f.connector.stop()
        f.drain()
        assertEquals(1, f.connections.size)
        assertEquals(ShizukuConnector.State.STOPPED, f.connector.state)
    }

    @Test fun missingCallbackTimesOutAndAllowsAnExplicitRetry() = Fixture().use { f ->
        assertEquals(ShizukuConnector.State.BINDING, f.connector.state)
        assertEquals(listOf(35_000L), f.delays)
        f.expireTimeouts()
        assertEquals(ShizukuConnector.State.ERROR, f.connector.state)
        assertTrue(f.connector.detail.contains("timed out"))
        assertEquals(listOf(false, true), f.unbinds.map { it.second })
        assertEquals(1, f.connections.size)
        f.connector.requestPermission()
        assertEquals(2, f.connections.size)
        f.connect(1)
        assertEquals(ShizukuConnector.State.CONNECTED, f.connector.state)
    }

    @Test fun lateCallbacksFromTimedOutBindingCannotReplaceOrDisconnectTheRetry() = Fixture().use { f ->
        f.expireTimeouts()
        f.connect(0)
        assertEquals(ShizukuConnector.State.ERROR, f.connector.state)
        f.connector.requestPermission()
        f.connect(1)
        f.disconnect(0)
        f.connect(0, -1)
        f.drain()
        assertEquals(2, f.connections.size)
        assertEquals(ShizukuConnector.State.CONNECTED, f.connector.state)
    }

    @Test fun connectedHelperCancelsTimeout() = Fixture().use { f ->
        f.connect(0)
        f.expireTimeouts()
        assertEquals(ShizukuConnector.State.CONNECTED, f.connector.state)
        assertTrue(f.unbinds.isEmpty())
    }

    @Test fun stoppingCancelsTimeoutAndRejectsLateConnection() = Fixture().use { f ->
        f.connector.stop()
        f.expireTimeouts()
        f.connect(0)
        assertEquals(ShizukuConnector.State.STOPPED, f.connector.state)
        assertEquals(1, f.connections.size)
    }

    @Test fun serverDeathDuringBindingCancelsTimeoutAndRejectsLateConnection() = Fixture().use { f ->
        f.serverUp = false
        f.died.onBinderDead()
        f.expireTimeouts()
        f.connect(0)
        assertEquals(ShizukuConnector.State.SHIZUKU_NOT_RUNNING, f.connector.state)
        assertEquals(listOf(false), f.unbinds.map { it.second })
        f.serverUp = true
        f.received.onBinderReceived()
        f.connect(1)
        assertEquals(ShizukuConnector.State.CONNECTED, f.connector.state)
    }

    @Test fun repeatedPermissionActionsDoNotStartConcurrentBindings() = Fixture().use { f ->
        f.connector.requestPermission()
        f.received.onBinderReceived()
        assertEquals(1, f.connections.size)
        assertEquals(1, f.delays.size)
    }

    private class Fixture : AutoCloseable {
        private val scopes = mutableListOf<AutoCloseable>()
        private val posted = ArrayDeque<Runnable>()
        private val delayed = mutableListOf<Runnable>()
        val delays = mutableListOf<Long>()
        val unbinds = mutableListOf<Pair<ServiceConnection, Boolean>>()
        val connections = mutableListOf<ServiceConnection>()
        var serverUp = true
        lateinit var received: Shizuku.OnBinderReceivedListener
        lateinit var died: Shizuku.OnBinderDeadListener
        val connector: ShizukuConnector

        init {
            scopes += mockConstruction(ComponentName::class.java)
            scopes += mockStatic(Looper::class.java)
            scopes += mockConstruction(Handler::class.java) { handler, _ ->
                doAnswer { posted.addLast(it.getArgument(0)); true }.`when`(handler).post(any(Runnable::class.java))
                doAnswer {
                    delayed += it.getArgument<Runnable>(0)
                    delays += it.getArgument<Long>(1)
                    true
                }.`when`(handler).postDelayed(any(Runnable::class.java), anyLong())
                doAnswer { delayed.remove(it.getArgument<Runnable>(0)); null }
                    .`when`(handler).removeCallbacks(any(Runnable::class.java))
            }
            // Prevent Shizuku's static Binder instance from calling Android stub methods.
            scopes += mockConstruction(Class.forName("rikka.shizuku.Shizuku\$1"))
            val shizuku = mockStatic(Shizuku::class.java)
            scopes += shizuku
            shizuku.`when`<Boolean> { Shizuku.pingBinder() }.thenAnswer { serverUp }
            shizuku.`when`<Int> { Shizuku.getVersion() }.thenReturn(13)
            shizuku.`when`<Int> { Shizuku.checkSelfPermission() }.thenReturn(PackageManager.PERMISSION_GRANTED)
            shizuku.`when`<Unit> { Shizuku.addBinderReceivedListenerSticky(any()) }.thenAnswer {
                received = it.getArgument(0)
                received.onBinderReceived()
                null
            }
            shizuku.`when`<Unit> { Shizuku.addBinderDeadListener(any()) }.thenAnswer {
                died = it.getArgument(0)
                null
            }
            shizuku.`when`<Unit> { Shizuku.bindUserService(any(), any()) }.thenAnswer {
                connections += it.getArgument<ServiceConnection>(1)
                null
            }
            shizuku.`when`<Unit> { Shizuku.unbindUserService(any(), any(), anyBoolean()) }.thenAnswer {
                unbinds += it.getArgument<ServiceConnection>(1) to it.getArgument<Boolean>(2)
                null
            }
            val context = mock(Context::class.java)
            val pm = mock(PackageManager::class.java)
            `when`(context.applicationContext).thenReturn(context)
            `when`(context.packageName).thenReturn("gateway")
            `when`(context.packageManager).thenReturn(pm)
            `when`(pm.getPackageInfo("gateway", 0)).thenReturn(PackageInfo())
            connector = ShizukuConnector(context) {}
            connector.start()
        }

        fun connect(index: Int, uid: Int = 2000) {
            val service = mock(IPrivilegedService::class.java)
            val binder = mock(IBinder::class.java)
            `when`(service.uid).thenReturn(uid)
            `when`(service.asBinder()).thenReturn(binder)
            `when`(binder.pingBinder()).thenReturn(true)
            `when`(binder.queryLocalInterface(anyString())).thenReturn(service)
            connections[index].onServiceConnected(mock(ComponentName::class.java), binder)
        }
        fun disconnect(index: Int) = connections[index].onServiceDisconnected(mock(ComponentName::class.java))
        fun drain() { while (posted.isNotEmpty()) posted.removeFirst().run() }
        fun expireTimeouts() {
            val tasks = delayed.toList()
            delayed.clear()
            tasks.forEach { it.run() }
        }
        override fun close() {
            connector.stop()
            scopes.asReversed().forEach { it.close() }
        }
    }
}
