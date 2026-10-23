package io.github.sparkcyf.voicesipagent.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.telecom.Call
import io.github.sparkcyf.voicesipagent.telephony.GatewayInCallService
import io.github.sparkcyf.voicesipagent.telephony.telecomState

/** The role-qualified UI service shares call ownership with companion mode. */
class GatewayDialerService : GatewayInCallService() {
    override val isDialerService = true
    private val callbacks = mutableMapOf<Call, Call.Callback>()

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) = updateNotification()
        }
        callbacks[call] = callback
        call.registerCallback(callback)
        updateNotification()
    }

    override fun onCallRemoved(call: Call) {
        callbacks.remove(call)?.let(call::unregisterCallback)
        super.onCallRemoved(call)
        updateNotification()
    }

    override fun onBringToForeground(showDialpad: Boolean) {
        runCatching {
            startActivity(Intent(this, GatewayDialerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }.onFailure { AppLog.w("Could not show dialer recovery screen", it) }
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        val live = calls.filter { it.telecomState != Call.STATE_DISCONNECTED }
        if (live.isEmpty()) { nm.cancel(ID); return }
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.ui_cellular_call), NotificationManager.IMPORTANCE_HIGH))
        val incoming = live.any { it.telecomState == Call.STATE_RINGING }
        val open = PendingIntent.getActivity(this, ID, Intent(this, GatewayDialerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_gateway)
            .setContentTitle(if (incoming) getString(R.string.ui_incoming_cellular_call) else getString(R.string.ui_cellular_call_in_progress))
            .setContentText(getString(R.string.ui_open_gateway_status_or_switch_dialer_for_recovery))
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true).setContentIntent(open)
        if (incoming) notification.setFullScreenIntent(open, true)
        runCatching { nm.notify(ID, notification.build()) }
            .onFailure { AppLog.w("Could not post call notification", it) }
    }

    override fun onDestroy() {
        callbacks.forEach { (call, callback) -> call.unregisterCallback(callback) }
        callbacks.clear()
        getSystemService(NotificationManager::class.java).cancel(ID)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "cellular_calls"
        private const val ID = 2
    }
}
