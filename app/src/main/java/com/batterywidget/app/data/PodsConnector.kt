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

    /** All paired devices that look like AirPods (some people have more than one pair). */
    private fun allPods(ctx: Context): List<BluetoothDevice> {
        if (!Perms.hasConnect(ctx)) return emptyList()
        return try {
            adapter(ctx)?.bondedDevices.orEmpty().filter {
                it.address == Prefs.podsAddress(ctx) || (it.name ?: "").contains("pods", ignoreCase = true)
            }
        } catch (_: SecurityException) { emptyList() }
    }

    /** Android's own "is this device linked right now" (null when the phone won't say). */
    private fun linked(dev: BluetoothDevice): Boolean? =
        try { dev.javaClass.getMethod("isConnected").invoke(dev) as Boolean } catch (_: Exception) { null }

    /**
     * Whether AirPods are connected for sound (call or music link), like CAPod checks it.
     * This is what auto-connect looks at: a bare link without sound still needs connecting.
     */
    fun audioConnected(ctx: Context): Boolean {
        val pods = allPods(ctx).map { it.address }.toSet()
        if (pods.isEmpty()) return false
        val proxies = listOfNotNull(headset, a2dp)
        if (proxies.isNotEmpty()) {
            return try {
                proxies.any { p -> p.connectedDevices.any { it.address in pods } }
            } catch (_: SecurityException) { false }
        }
        // The services aren't open yet: ask Android for any connected headset or speaker.
        return AirPods.audioConnected(ctx) && allPods(ctx).any { linked(it) != false }
    }

    /**
     * Whether any AirPods are connected at all. Matches what the widget shows (it lists AirPods
     * as soon as Android says they're linked), so the connect button never sits on top of them.
     */
    fun connected(ctx: Context): Boolean {
        val pods = allPods(ctx)
        if (pods.isEmpty()) return false
        if (audioConnected(ctx)) return true
        val states = pods.map { linked(it) }
        if (states.any { it == true }) return true
        if (states.all { it == false }) return false
        return Prefs.podsLinked(ctx) // the phone won't say; go by the last link event
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
     * Asks Android to connect the AirPods, the way CAPod does it: through the headset (calls)
     * service, which brings music along with it. Returns false when that didn't go through.
     */
    fun connect(ctx: Context): Boolean {
        val dev = pairedPods(ctx) ?: return false
        if (audioConnected(ctx)) return true
        prepare(ctx)
        val (proxy, cls) = when {
            headset != null -> headset!! to android.bluetooth.BluetoothHeadset::class.java
            a2dp != null -> a2dp!! to android.bluetooth.BluetoothA2dp::class.java
            else -> return false
        }
        return try {
            val m = cls.getDeclaredMethod("connect", BluetoothDevice::class.java).apply { isAccessible = true }
            val ok = m.invoke(proxy, dev) as? Boolean ?: false
            if (ok) Prefs.setConnectBlocked(ctx, false)
            ok
        } catch (e: Exception) {
            val security = e is SecurityException ||
                (e is java.lang.reflect.InvocationTargetException && e.cause is SecurityException)
            // Only a refusal with the Bluetooth permission granted means the phone blocks it for good.
            if (security && Perms.hasConnect(ctx)) Prefs.setConnectBlocked(ctx, true)
            false
        }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var lastLidOpen: Boolean? = null

    /**
     * Called whenever AirPods are heard close by. Like CAPod: connects them when the case lid
     * opens (straight away, every time it opens), or when they're out of the case and not
     * connected. While they stay unconnected it tries again every 20 seconds.
     */
    fun autoConnect(ctx: Context, rssi: Int, lidOpen: Boolean?, retried: Boolean = false) {
        val justOpened = lidOpen == true && lastLidOpen != true
        if (lidOpen != null) lastLidOpen = lidOpen
        if (!Prefs.autoConnect(ctx)) return
        if (Prefs.connectBlocked(ctx)) { note(ctx, "skipped: this phone blocks app connections"); return }
        if (lidOpen == false) return // case shut: nothing to connect
        val now = System.currentTimeMillis()
        if (!justOpened && now - lastAuto < AUTO_GAP_MS) return
        if (rssi < -80) { note(ctx, "heard AirPods but too far away ($rssi dBm)"); return }
        val app = ctx.applicationContext
        if (pairedPods(app) == null) { note(ctx, "skipped: no paired AirPods found"); return }
        if (headset == null && a2dp == null) {
            if (retried) { note(ctx, "Android's headset service didn't open"); return }
            // The app was asleep: open Android's headset service first, then try right away.
            prepare(app)
            lastAuto = now
            whenReady { lastAuto = 0L; autoConnect(app, rssi, null, retried = true) }
            return
        }
        if (audioConnected(app)) return
        lastAuto = now
        val ok = connect(app)
        val why = if (justOpened) "case opened" else "AirPods nearby"
        note(app, if (ok) "$why, asked Android to connect ($rssi dBm)" else "$why, Android refused to connect ($rssi dBm)")
    }

    /** Runs [then] once the headset service is open (checks every 50 ms, up to 2 s). */
    private fun whenReady(waited: Long = 0, then: () -> Unit) {
        if (headset != null || a2dp != null || waited >= 2_000) { then(); return }
        main.postDelayed({ whenReady(waited + 50, then) }, 50)
    }

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
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val ok = PodsConnector.connect(ctx)
            if (!ok) {
                PodsConnector.connectTappedAt = 0L
                try {
                    ctx.startActivity(PodsConnector.bluetoothSettings().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) { }
            }
            WidgetUpdater.updateAll(ctx)
            pending.finish()
        }, 400)
    }
}
