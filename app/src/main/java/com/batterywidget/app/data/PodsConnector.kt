package com.batterywidget.app.data

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.batterywidget.app.widget.WidgetUpdater

/**
 * Connects the phone to your AirPods. Android has no public way for apps to do this, so (like
 * CAPod) it asks Android's own headset and audio services to connect them. Many phones allow
 * that; if this one doesn't, the widget button opens Bluetooth settings instead.
 */
object PodsConnector {
    private const val AUTO_GAP_MS = 20_000L

    @Volatile private var headset: BluetoothProfile? = null
    @Volatile private var a2dp: BluetoothProfile? = null
    @Volatile private var lastAuto = 0L

    private fun adapter(ctx: Context): BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    /** Opens the headset and audio services so they're ready when a connect is needed. */
    fun prepare(ctx: Context) {
        if (!Perms.hasConnect(ctx)) return
        val a = adapter(ctx) ?: return
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HEADSET) headset = proxy else if (profile == BluetoothProfile.A2DP) a2dp = proxy
            }
            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HEADSET) headset = null else if (profile == BluetoothProfile.A2DP) a2dp = null
            }
        }
        try {
            if (headset == null) a.getProfileProxy(ctx.applicationContext, listener, BluetoothProfile.HEADSET)
            if (a2dp == null) a.getProfileProxy(ctx.applicationContext, listener, BluetoothProfile.A2DP)
        } catch (_: Exception) { }
    }

    /** Your paired AirPods: the ones connected last, or any paired device called "...Pods". */
    fun pairedPods(ctx: Context): BluetoothDevice? {
        if (!Perms.hasConnect(ctx)) return null
        return try {
            val bonded = adapter(ctx)?.bondedDevices.orEmpty()
            val known = Prefs.podsAddress(ctx)
            bonded.firstOrNull { it.address == known }
                ?: bonded.firstOrNull { (it.name ?: "").contains("pods", ignoreCase = true) }
        } catch (_: SecurityException) { null }
    }

    /** Whether these AirPods (not just any headset) are connected right now. */
    fun connected(ctx: Context): Boolean {
        // The Bluetooth link comes up a few seconds before audio does; count it straight away.
        if (Prefs.podsLinked(ctx)) return true
        val dev = pairedPods(ctx) ?: return false
        val viaProfiles = try {
            listOfNotNull(headset, a2dp).any { p -> p.connectedDevices.any { it.address == dev.address } }
        } catch (_: SecurityException) { false }
        if (viaProfiles) return true
        if (headset != null || a2dp != null) return false // the profiles know, and they say no
        return try { dev.javaClass.getMethod("isConnected").invoke(dev) as Boolean } catch (_: Exception) { Prefs.podsLinked(ctx) }
    }

    /**
     * Whether the AirPods are connected for sound (calls or music), as Android's headset and
     * audio services see it. Unlike [connected], a leftover "linked" flag can't fool it.
     */
    fun soundConnected(ctx: Context): Boolean {
        val dev = pairedPods(ctx) ?: return false
        val proxies = listOfNotNull(headset, a2dp)
        if (proxies.isEmpty()) return AirPods.audioConnected(ctx) && connected(ctx)
        return try {
            proxies.any { p -> p.connectedDevices.any { it.address == dev.address } }
        } catch (_: SecurityException) { false }
    }

    private fun note(ctx: Context, msg: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        Prefs.setAutoNote(ctx, "$t · $msg")
    }

    /** True when the widget should offer the connect button. */
    fun canOffer(ctx: Context) =
        System.currentTimeMillis() - connectTappedAt > 12_000 && pairedPods(ctx) != null && !connected(ctx)

    /** Set when the button is tapped, so it hides at once while the connection is made. */
    @Volatile var connectTappedAt = 0L

    /**
     * Asks Android to connect the AirPods. Returns false when this phone doesn't allow it
     * (remembered, so the widget button opens Bluetooth settings from then on).
     */
    fun connect(ctx: Context): Boolean {
        val dev = pairedPods(ctx) ?: return false
        if (soundConnected(ctx)) return true
        prepare(ctx)
        var accepted = false
        var blocked = false
        for (proxy in listOfNotNull(headset, a2dp)) {
            try {
                val m = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                if (m.invoke(proxy, dev) as? Boolean == true) accepted = true
            } catch (e: Exception) {
                blocked = true
            }
        }
        if (!accepted && blocked && headset != null) Prefs.setConnectBlocked(ctx, true)
        if (accepted) Prefs.setConnectBlocked(ctx, false)
        return accepted
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Called when AirPods are heard close by. First makes sure they're really there and not
     * connected for sound, then does exactly what tapping the widget's connect button does.
     * Tries again every 20 seconds while they stay unconnected.
     */
    fun autoConnect(ctx: Context, rssi: Int) {
        if (!Prefs.autoConnect(ctx)) return
        val now = System.currentTimeMillis()
        if (now - lastAuto < AUTO_GAP_MS) return
        if (rssi < -80) { note(ctx, "heard AirPods but too far away ($rssi dBm)"); return }
        val app = ctx.applicationContext
        if (pairedPods(app) == null) { note(ctx, "skipped: no paired AirPods found"); return }
        if (soundConnected(app)) return
        lastAuto = now
        note(app, "AirPods nearby ($rssi dBm), connecting…")
        pressConnect(app) { ok ->
            note(app, if (ok) "asked Android to connect ($rssi dBm)" else "Android refused to connect ($rssi dBm)")
        }
    }

    /**
     * What the widget's connect button does: hide the button, open the headset service,
     * give it a moment, then connect. [done] gets whether Android accepted.
     */
    fun pressConnect(ctx: Context, done: (Boolean) -> Unit) {
        val app = ctx.applicationContext
        connectTappedAt = System.currentTimeMillis()
        WidgetUpdater.updateAll(app)
        prepare(app)
        // The headset service needs a moment to open the first time.
        main.postDelayed({
            val ok = connect(app)
            if (!ok) connectTappedAt = 0L
            WidgetUpdater.updateAll(app)
            done(ok)
        }, 400)
    }

    fun bluetoothSettings(): Intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
}

/** The widget's connect button. */
class ConnectReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        PodsConnector.pressConnect(ctx) { ok ->
            if (!ok) {
                try {
                    ctx.startActivity(PodsConnector.bluetoothSettings().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) { }
            }
            pending.finish()
        }
    }
}
