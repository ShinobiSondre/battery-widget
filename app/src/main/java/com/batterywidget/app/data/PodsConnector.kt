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
    private const val AUTO_GAP_MS = 6_000L

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
        if (connected(ctx)) return true
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

    /** Called when AirPods are heard close by: connects them if they aren't connected yet. */
    fun autoConnect(ctx: Context, rssi: Int) {
        if (!Prefs.autoConnect(ctx)) return
        if (Prefs.connectBlocked(ctx)) { note(ctx, "skipped: this phone blocks app connections"); return }
        val now = System.currentTimeMillis()
        if (now - lastAuto < AUTO_GAP_MS) return
        if (rssi < -80) { note(ctx, "heard AirPods but too far away ($rssi dBm)"); return }
        val app = ctx.applicationContext
        if (pairedPods(app) == null) { note(ctx, "skipped: no paired AirPods found"); return }
        if (headset == null && a2dp == null) {
            // The app was asleep: open Android's headset service first, then try.
            prepare(app)
            lastAuto = now
            whenReady(app) { lastAuto = 0L; autoConnect(app, rssi) }
            return
        }
        if (connected(app)) return
        lastAuto = now
        val ok = connect(app)
        note(app, if (ok) "asked Android to connect ($rssi dBm)" else "Android refused to connect ($rssi dBm)")
    }

    /** Runs [then] as soon as the headset/audio services are open (checks every 50 ms, up to 2 s). */
    private fun whenReady(ctx: Context, waited: Long = 0, then: () -> Unit) {
        if (headset != null || a2dp != null || waited >= 2_000) { then(); return }
        main.postDelayed({ whenReady(ctx, waited + 50, then) }, 50)
    }

    fun afterReady(ctx: Context, then: () -> Unit) = whenReady(ctx.applicationContext, 0, then)

    fun bluetoothSettings(): Intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
}

/** The widget's connect button. */
class ConnectReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        // Hide the button right away; it comes back only if connecting fails.
        PodsConnector.connectTappedAt = System.currentTimeMillis()
        WidgetUpdater.updateAll(ctx)
        PodsConnector.prepare(ctx)
        val pending = goAsync()
        // The headset service needs a moment to open the first time.
        PodsConnector.afterReady(ctx) {
            val ok = PodsConnector.connect(ctx)
            if (!ok) {
                PodsConnector.connectTappedAt = 0L
                try {
                    ctx.startActivity(PodsConnector.bluetoothSettings().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) { }
            }
            WidgetUpdater.updateAll(ctx)
            pending.finish()
        }
    }
}
