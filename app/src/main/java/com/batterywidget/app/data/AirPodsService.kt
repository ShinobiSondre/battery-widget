package com.batterywidget.app.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.batterywidget.app.MainActivity
import com.batterywidget.app.R
import com.batterywidget.app.widget.WidgetUpdater

/**
 * Keeps the batteries notification and widget up to date, and reacts to Bluetooth events
 * (connect, disconnect, battery). It doesn't scan itself: the only Bluetooth scan is the gentle,
 * chip-filtered one in [AirPods.updateScanning], and only while the AirPods aren't connected.
 */
class AirPodsService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastLevel = -2
    private var lastPlugged = false

    private val events = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val on = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON
                    if (on) AirPods.updateScanning(ctx, force = true) // Bluetooth off removed it
                    else Prefs.setSystemScanAt(ctx, 0L)
                }
                // Phone battery and charger: redraw right away when something visible changes.
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                    val plugged = intent.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
                    if (level != lastLevel || plugged != lastPlugged) {
                        val plugChange = lastLevel != -2 && plugged != lastPlugged
                        lastLevel = level; lastPlugged = plugged
                        WidgetUpdater.updateAllThrottled(ctx, force = plugChange)
                        updateNotice()
                    }
                }
                Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED ->
                    WidgetUpdater.updateAllThrottled(ctx, force = true)
                // Headset or audio link finished connecting: redraw now (hides the connect button).
                HEADSET_STATE, A2DP_STATE -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    val what = if (intent.action == HEADSET_STATE) "call audio" else "music audio"
                    when (state) {
                        BluetoothProfile.STATE_CONNECTED -> Prefs.addEvent(ctx, "$what connected")
                        BluetoothProfile.STATE_CONNECTING -> Prefs.addEvent(ctx, "$what connecting…")
                        BluetoothProfile.STATE_DISCONNECTED -> Prefs.addEvent(ctx, "$what disconnected")
                    }
                    // Audio is up: open the AirPods' own channel for exact battery.
                    if (PodsConnector.soundConnected(ctx)) handler.postDelayed({ AapClient.start(ctx) }, 1_000)
                    // Connected: no need to listen for them. Disconnected: listen again (gently).
                    handler.postDelayed({ AirPods.updateScanning(ctx) }, 1_500)
                    WidgetUpdater.updateAllThrottled(ctx, force = true)
                    updateNotice()
                }
                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    AirPods.onConnectionChange(ctx, intent)
                    WidgetUpdater.updateAllThrottled(ctx, force = true)
                    handler.postDelayed({ updateNotice() }, 2_500)
                    // Battery levels arrive a few seconds after connecting.
                    handler.postDelayed({ WidgetUpdater.updateAllThrottled(ctx, force = true) }, 2_500)
                    handler.postDelayed({ WidgetUpdater.updateAllThrottled(ctx, force = true) }, 7_000)
                    handler.postDelayed({ AirPods.updateScanning(ctx) }, 3_000)
                }
                BATTERY_LEVEL_CHANGED -> {
                    val d = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    val level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
                    if (d != null && level >= 0) {
                        Prefs.saveDeviceBattery(ctx, d.address, level)
                        WidgetUpdater.updateAllThrottled(ctx, force = true)
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        instance = this
        Prefs.addEvent(this, "listener started (${Perms.situation(this)})")
        if (!goForeground()) { stopSelf(); return }
        PodsConnector.prepare(this)
        handler.postDelayed({ if (PodsConnector.soundConnected(this)) AapClient.start(this) }, 1_500)
        val f = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BATTERY_LEVEL_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(HEADSET_STATE)
            addAction(A2DP_STATE)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        ContextCompat.registerReceiver(this, events, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        AirPods.updateScanning(this)
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AirPods.updateScanning(this, force = intent?.getBooleanExtra(EXTRA_RESTART, false) == true)
        return START_STICKY
    }

    override fun onDestroy() {
        Prefs.addEvent(this, "listener stopped")
        running = false
        if (instance === this) instance = null
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(events) } catch (_: Exception) { }
        super.onDestroy()
    }

    private fun goForeground(): Boolean = try {
        val n = notification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else startForeground(NOTE_ID, n)
        true
    } catch (e: Exception) {
        Prefs.setListenerNote(this, "Couldn't start listening: ${e.javaClass.simpleName}")
        false
    }

    /**
     * What switching "Always listen" off and on does, without closing this listener (Android may
     * not let it reopen while the app is closed): stop both scans, then start them fresh, open
     * fresh connections to Android's call and music services, and restart the AirPods channel.
     */
    fun quickRestart(why: String) {
        Prefs.addEvent(this, "Always listen off and on: $why")
        AirPods.stopSystemScan(this)
        AapClient.stop()
        handler.postDelayed({
            AirPods.updateScanning(this, force = true)
            PodsConnector.reopen(this) {
                if (PodsConnector.soundConnected(this)) AapClient.start(this)
                else PodsConnector.connectAfterRestart(this, why)
            }
        }, 400)
    }

    // Every 5 minutes: refresh the notification and widget (readings that went stale leave it),
    // and make sure listening is on or off as it should be. Everything else is event-driven.
    private val tick = object : Runnable {
        override fun run() {
            updateNotice()
            WidgetUpdater.updateAllThrottled(this@AirPodsService)
            AirPods.updateScanning(this@AirPodsService)
            SleepGuard.check(this@AirPodsService) // put back to sleep? one-tap notification
            handler.postDelayed(this, 5 * 60_000L)
        }
    }

    /** New AirPods battery heard: show it in the notification. */
    fun refreshNotice() = updateNotice()

    private fun updateNotice() {
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTE_ID, notification())
        } catch (_: SecurityException) { }
    }

    private fun notification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.deleteNotificationChannel("airpods") // the old, hidden-on-lock-screen one
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Batteries", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Your batteries, also shown on the lock screen"
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }
        // Every battery in one line, so it's useful on the lock screen and cover screen too.
        val items = Devices.read(this)
        val text = items.joinToString("  ·  ") { i ->
            (if (i.level < 0) "${i.name} –" else "${i.name} ${i.level}%") + (if (i.charging) " ⚡" else "")
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_buds)
            .setContentTitle(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL = "batteries"
        private const val NOTE_ID = 42
        const val HEADSET_STATE = "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED"
        const val A2DP_STATE = "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED"
        const val BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"

        @Volatile var running = false; private set
        @Volatile var instance: AirPodsService? = null; private set

        /** Starts listening if the user wants it and Android allows it right now. */
        fun ensure(ctx: Context) {
            // Runs as soon as Bluetooth access is allowed, so the widget updates live; AirPods
            // scanning inside it starts once location is allowed too.
            if (!Prefs.alwaysListen(ctx) || !Perms.hasConnect(ctx)) return
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, AirPodsService::class.java))
            } catch (e: Exception) {
                // Android blocks starting from the background in a few cases; the next app open,
                // widget tap or Bluetooth connection starts it instead.
                Prefs.setListenerNote(ctx, "Waiting to start (open the app once)")
            }
        }

        private const val EXTRA_RESTART = "restart"

        /** Starts scanning again, e.g. after a new permission was granted. */
        fun restart(ctx: Context) {
            if (!Prefs.alwaysListen(ctx) || !Perms.hasConnect(ctx)) return
            try {
                ContextCompat.startForegroundService(
                    ctx, Intent(ctx, AirPodsService::class.java).putExtra(EXTRA_RESTART, true)
                )
            } catch (_: Exception) { }
        }

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, AirPodsService::class.java))
    }
}
