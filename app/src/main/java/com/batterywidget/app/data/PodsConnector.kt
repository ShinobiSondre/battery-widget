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

    fun connected(ctx: Context) = Prefs.podsLinked(ctx) || AirPods.audioConnected(ctx)

    /** True when the widget should offer the connect button. */
    fun canOffer(ctx: Context) = pairedPods(ctx) != null && !connected(ctx)

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
        if (!Prefs.autoConnect(ctx) || Prefs.connectBlocked(ctx)) return
        if (rssi < -72) return // only AirPods close to the phone
        val now = System.currentTimeMillis()
        if (now - lastAuto < AUTO_GAP_MS) return
        if (connected(ctx) || pairedPods(ctx) == null) return
        lastAuto = now
        val app = ctx.applicationContext
        if (headset != null || a2dp != null) {
            connect(app)
        } else {
            // The app was asleep: open Android's headset service first, then connect.
            prepare(app)
            main.postDelayed({ if (!connect(app)) lastAuto = 0L }, 600)
        }
    }

    fun bluetoothSettings(): Intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
}

/** The widget's connect button. */
class ConnectReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        PodsConnector.prepare(ctx)
        val pending = goAsync()
        // The headset service needs a moment to open the first time.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val ok = PodsConnector.connect(ctx)
            if (!ok) {
                try {
                    ctx.startActivity(PodsConnector.bluetoothSettings().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) { }
            }
            WidgetUpdater.updateAll(ctx)
            pending.finish()
        }, 400)
    }
}
