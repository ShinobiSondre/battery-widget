package com.batterywidget.app.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
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
 * Keeps listening for AirPods all the time, like the AirPods pop-up apps do. Android requires a
 * small ongoing notification for this; it also shows the latest AirPods battery.
 */
class AirPodsService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var scanning = false
    private var lastLevel = -2
    private var lastPlugged = false

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = heard(listOf(result))
        override fun onBatchScanResults(results: MutableList<ScanResult>) = heard(results)
        override fun onScanFailed(errorCode: Int) {
            Prefs.setListenerNote(this@AirPodsService, "Bluetooth scan failed (code $errorCode)")
            scanning = false
            handler.postDelayed({ startScan() }, 30_000)
        }
    }

    private val events = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val on = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON
                    if (on) startScan() else { scanning = false }
                }
                Intent.ACTION_SCREEN_ON -> restartScan() // some phones pause scans while the screen is off
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
                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    AirPods.onConnectionChange(ctx, intent)
                    WidgetUpdater.updateAllThrottled(ctx, force = true)
                    handler.postDelayed({ updateNotice() }, 2_500)
                    // Battery levels arrive a few seconds after connecting.
                    handler.postDelayed({ WidgetUpdater.updateAllThrottled(ctx, force = true) }, 2_500)
                    handler.postDelayed({ WidgetUpdater.updateAllThrottled(ctx, force = true) }, 7_000)
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
        if (!goForeground()) { stopSelf(); return }
        PodsConnector.prepare(this)
        val f = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(BATTERY_LEVEL_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        ContextCompat.registerReceiver(this, events, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        startScan()
        handler.post(refreshNotice)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_RESTART, false) == true) restartScan()
        else if (!scanning) startScan()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        stopScan()
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

    private fun startScan() {
        if (!Perms.hasScan(this)) { Prefs.setListenerNote(this, "Needs Location and Nearby devices permission"); return }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val scanner = if (adapter != null && adapter.isEnabled) adapter.bluetoothLeScanner else null
        if (scanner == null) { Prefs.setListenerNote(this, "Bluetooth is off"); return }
        // Same approach as CAPod: only let the Bluetooth chip pre-filter when the phone says it can.
        // Phones that can't would otherwise return nothing at all, so there we listen to
        // everything and pick out the AirPods messages in code.
        val hwFilter = try { adapter.isOffloadedFilteringSupported } catch (_: Exception) { false }
        // Any Apple broadcast (the AirPods message is picked out in code).
        val filters = if (hwFilter) listOf(
            ScanFilter.Builder().setManufacturerData(AirPods.APPLE, ByteArray(0)).build()
        ) else emptyList()
        val settings = ScanSettings.Builder()
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .build()
        try {
            if (scanning) scanner.stopScan(callback)
            scanner.startScan(filters, settings, callback)
            Prefs.setListenerNote(this, if (hwFilter) "Listening (Apple filter)" else "Listening (all signals)")
            scanning = true
        } catch (e: Exception) {
            Prefs.setListenerNote(this, "Couldn't scan: ${e.javaClass.simpleName}")
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (_: Exception) { }
    }

    private fun restartScan() { stopScan(); startScan() }

    private fun heard(results: List<ScanResult>) {
        if (AirPods.onResults(this, results)) updateNotice()
    }

    // Android quietly ends very long scans on some phones; restarting every 20 minutes avoids that.
    private val refreshNotice = object : Runnable {
        override fun run() {
            updateNotice()
            if (System.currentTimeMillis() - lastRestart > 20 * 60_000L) { lastRestart = System.currentTimeMillis(); restartScan() }
            handler.postDelayed(this, 60_000)
        }
    }
    private var lastRestart = System.currentTimeMillis()

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
        const val BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"

        @Volatile var running = false; private set

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
