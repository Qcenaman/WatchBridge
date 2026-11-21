package com.qcenaman.watchbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.qcenaman.watchbridge.core.Command
import com.qcenaman.watchbridge.core.Delivery
import com.qcenaman.watchbridge.core.Framer
import com.qcenaman.watchbridge.core.Message
import com.qcenaman.watchbridge.core.NotifInfo
import com.qcenaman.watchbridge.core.Rules

object MainHandler : Handler(Looper.getMainLooper())

object Prefs {
    private const val FILE = "watchbridge"
    private const val MUTED = "muted"

    fun muted(c: Context): Set<String> =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getStringSet(MUTED, emptySet()) ?: emptySet()

    fun setMuted(c: Context, pkg: String, mute: Boolean) {
        val next = muted(c).toMutableSet().apply { if (mute) add(pkg) else remove(pkg) }
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putStringSet(MUTED, next).apply()
    }
}

/** Reads the phone's notifications and forwards them to the watch over BLE. */
class BridgeService : NotificationListenerService() {
    private lateinit var ble: BleServer

    override fun onListenerConnected() {
        instance = this
        goForeground()
        ble = BleServer(this, ::handle).also {
            it.onStateChanged = { if (it.connected) sync(); changed?.invoke() }
            it.start()
        }
        changed?.invoke()
    }

    override fun onListenerDisconnected() {
        if (::ble.isInitialized) ble.stop()
        instance = null
        changed?.invoke()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        forward(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (::ble.isInitialized) ble.send(Message.Removed(sbn.key))
    }

    val connected: Boolean get() = ::ble.isInitialized && ble.connected

    fun restartBle() {
        if (!::ble.isInitialized) return
        ble.stop(); ble.start(); changed?.invoke()
    }

    private fun forward(sbn: StatusBarNotification, allowBuzz: Boolean = true) {
        if (!::ble.isInitialized) return
        val ranking = Ranking()
        val ranked = currentRanking?.getRanking(sbn.key, ranking) == true
        val n = sbn.notification
        val info = NotifInfo(
            pkg = sbn.packageName,
            ongoing = sbn.isOngoing || !sbn.isClearable,
            groupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            importance = if (ranked) ranking.importance else NotificationManager.IMPORTANCE_DEFAULT,
            passesDnd = !ranked || ranking.matchesInterruptionFilter(),
            mutedByUser = sbn.packageName in Prefs.muted(this),
        )
        val delivery = Rules.decide(info, packageName)
        if (delivery == Delivery.SKIP) return
        seen.add(sbn.packageName)
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        ble.send(
            Message.Posted(
                id = sbn.key,
                app = appLabel(sbn.packageName),
                title = Framer.clip(title, 80),
                text = Framer.clip(text),
                time = sbn.postTime,
                buzz = allowBuzz && delivery == Delivery.BUZZ,
            ),
        )
    }

    private fun sync() {
        sendBattery()
        activeNotifications?.sortedBy { it.postTime }?.forEach { forward(it, allowBuzz = false) }
    }

    private fun sendBattery() {
        val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        if (level >= 0) ble.send(Message.Battery(level * 100 / scale, plugged))
    }

    private fun handle(cmd: Command) {
        when (cmd) {
            is Command.Dismiss -> runCatching { cancelNotification(cmd.id) }
            Command.FindPhone -> ringPhone()
            Command.Sync -> sync()
        }
    }

    private fun ringPhone() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) ?: return
        val ringtone = RingtoneManager.getRingtone(this, uri) ?: return
        ringtone.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        ringtone.play()
        MainHandler.postDelayed({ ringtone.stop() }, 15_000)
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Watch link", NotificationManager.IMPORTANCE_MIN),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("WatchBridge running")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(1, notification)
            }
        }
    }

    companion object {
        private const val CHANNEL = "link"
        var instance: BridgeService? = null
            private set
        var changed: (() -> Unit)? = null
        /** Apps that sent something this session, so they can be muted from the app. */
        val seen = sortedSetOf<String>()
    }
}
