package io.github.sparkcyf.voicesipagent.privileged

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import io.github.sparkcyf.voicesipagent.privileged.shell.PrivilegedService
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArrayList

/**
 * App-side lifecycle for the Shizuku UserService: waits for the Shizuku binder (it appears ~15 s
 * after boot via wireless debugging), checks permission, binds [PrivilegedService] and reports
 * every transition so the app can re-run setup when the service (re)connects.
 */
class ShizukuConnector(context: Context, private val log: (String) -> Unit) {
    enum class State { STOPPED, SHIZUKU_NOT_RUNNING, PERMISSION_REQUIRED, PERMISSION_DENIED, BINDING, CONNECTED, ERROR }

    fun interface Listener {
        fun onChanged(state: State, service: IPrivilegedService?)
    }

    private val app = context.applicationContext
    private val listeners = CopyOnWriteArrayList<Listener>()
    @Volatile var state = State.SHIZUKU_NOT_RUNNING; private set
    @Volatile var service: IPrivilegedService? = null; private set
    @Volatile var detail: String = ""; private set
    @Volatile private var started = false
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0L
    private var connection: ServiceConnection? = null
    private var bindTimeout: Runnable? = null

    private val args = Shizuku.UserServiceArgs(ComponentName(app.packageName, PrivilegedService::class.java.name))
        .daemon(false)
        .processNameSuffix("shell")
        .debuggable(false)
        .tag("vsa-privileged")
        // Restart the shell process whenever a new APK is installed so it never runs stale code.
        .version((app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime / 1000 % Int.MAX_VALUE).toInt())

    private fun connection(epoch: Long) = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
            synchronized(this@ShizukuConnector) {
                if (!started || generation != epoch) return
                if (binder == null || !binder.pingBinder()) {
                    set(State.ERROR, null, "UserService binder dead")
                    return
                }
                val svc = IPrivilegedService.Stub.asInterface(binder)
                val uid = try { svc.uid } catch (e: Exception) { -1 }
                if (uid != 2000 && uid != 0) {
                    set(State.ERROR, null, "unexpected privileged uid $uid")
                    return
                }
                set(State.CONNECTED, svc, "uid $uid")
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            synchronized(this@ShizukuConnector) {
                if (!started || generation != epoch) return
                connection = null
                set(State.ERROR, null, "UserService disconnected")
                // Shizuku clears its dead connection after this callback returns. Rebinding
                // inside the callback would reuse the connection it is about to discard.
                main.post {
                    synchronized(this@ShizukuConnector) {
                        if (started && generation == epoch) onBinder()
                    }
                }
            }
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { onBinder() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        synchronized(this) {
            if (started) {
                generation++
                clearConnection(removeHelper = false)
                set(State.SHIZUKU_NOT_RUNNING, null, "Shizuku binder died")
            }
        }
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { code, result ->
        synchronized(this) {
            if (!started || code != PERMISSION_REQUEST) return@OnRequestPermissionResultListener
            if (result == PackageManager.PERMISSION_GRANTED) bind() else set(State.PERMISSION_DENIED, null, "denied in Shizuku")
        }
    }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        generation++
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
    }

    /** Removes this app's listeners and helper; the shared Shizuku server is left running. */
    @Synchronized
    fun stop() {
        if (!started && state == State.STOPPED) return
        started = false
        generation++
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        runCatching { service?.closeCallAudio() }.onFailure { log("shizuku: audio cleanup failed: ${it.message}") }
        clearConnection()
        set(State.STOPPED, null, "takeover off")
    }

    fun addListener(l: Listener) {
        listeners.addIfAbsent(l)
        l.onChanged(state, service)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    /** Asks Shizuku for permission (shows Shizuku's dialog). Call from the UI. */
    @Synchronized
    fun requestPermission() {
        if (!started) return
        try {
            if (!Shizuku.pingBinder()) return set(State.SHIZUKU_NOT_RUNNING, null, "start Shizuku first")
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) bind()
            else Shizuku.requestPermission(PERMISSION_REQUEST)
        } catch (e: RuntimeException) {
            set(State.ERROR, null, "permission request failed: ${e.message}")
        }
    }

    @Synchronized
    private fun onBinder() {
        if (!started) return
        try {
            if (!Shizuku.pingBinder()) return set(State.SHIZUKU_NOT_RUNNING, null, "start Shizuku first")
            if (Shizuku.isPreV11() || Shizuku.getVersion() < 13) return set(State.ERROR, null, "Shizuku v13+ required")
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) bind()
            else set(State.PERMISSION_REQUIRED, null, "grant access in Shizuku")
        } catch (e: RuntimeException) {
            set(State.ERROR, null, "Shizuku check failed: ${e.message}")
        }
    }

    private fun bind() {
        if (!started || state == State.BINDING) return
        if (state == State.CONNECTED && service?.asBinder()?.pingBinder() == true) return
        val epoch = ++generation
        clearConnection()
        set(State.BINDING, null, "binding UserService")
        val timeout = Runnable {
            synchronized(this) {
                if (!started || generation != epoch || state != State.BINDING) return@Runnable
                generation++
                clearConnection()
                log("shizuku: UserService did not connect within ${BIND_TIMEOUT_MS}ms; " +
                    "check ShizukuServiceStarter logs for bootstrap exceptions")
                set(State.ERROR, null, "UserService connection timed out; check Shizuku logs and retry")
            }
        }
        bindTimeout = timeout
        main.postDelayed(timeout, BIND_TIMEOUT_MS)
        try {
            val bound = connection(epoch)
            connection = bound
            Shizuku.bindUserService(args, bound)
        } catch (e: RuntimeException) {
            generation++
            clearConnection()
            set(State.ERROR, null, "bindUserService failed: ${e.message}")
        }
    }

    private fun clearConnection(removeHelper: Boolean = true) {
        val bound = connection
        connection = null
        if (bound != null) {
            // Remove the SDK's cached connection before removing the remote helper so retries
            // cannot reuse a dead connection. Never stop the shared Shizuku server.
            runCatching { Shizuku.unbindUserService(args, bound, false) }
                .onFailure { log("shizuku: connection removal failed: ${it.message}") }
            if (removeHelper) {
                runCatching { Shizuku.unbindUserService(args, bound, true) }
                    .onFailure { log("shizuku: helper removal failed: ${it.message}") }
            }
        }
    }

    private fun set(s: State, svc: IPrivilegedService?, why: String) {
        if (s != State.BINDING) {
            bindTimeout?.let { main.removeCallbacks(it) }
            bindTimeout = null
        }
        state = s
        service = svc
        detail = why
        log("shizuku: $s ($why)")
        listeners.forEach { it.onChanged(s, svc) }
    }

    companion object {
        private const val PERMISSION_REQUEST = 4101
        // Shizuku gives a new UserService 30 seconds to attach its binder.
        internal const val BIND_TIMEOUT_MS = 35_000L
    }
}
