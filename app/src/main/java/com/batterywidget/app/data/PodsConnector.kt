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

    @Volatile private var headset: BluetoothProfile? = null
    @Volatile private var a2dp: BluetoothProfile? = null

    private fun adapter(ctx: Context): BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    /** Opens the headset and audio services so they're ready when a connect is needed. */
    fun prepare(ctx: Context) {
        if (!Perms.hasConnect(ctx)) return
        val a = adapter(ctx) ?: return
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HEADSET) headset = proxy else if (profile == BluetoothProfile.A2DP) a2dp = proxy
                Prefs.addEvent(ctx, "${name(profile)} service ready")
            }
            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HEADSET) headset = null else if (profile == BluetoothProfile.A2DP) a2dp = null
                Prefs.addEvent(ctx, "${name(profile)} service lost")
            }
        }
        try {
            if (headset == null) a.getProfileProxy(ctx.applicationContext, listener, BluetoothProfile.HEADSET)
            if (a2dp == null) a.getProfileProxy(ctx.applicationContext, listener, BluetoothProfile.A2DP)
        } catch (_: Exception) { }
    }

    private fun name(profile: Int) = if (profile == BluetoothProfile.HEADSET) "call" else "music"

    /**
     * Throws away the connections to Android's call and music services and opens fresh ones,
     * then runs [then]. This is what turning "Always listen" off and on used to fix by accident:
     * an old connection can say yes to "connect" and then do nothing.
     */
    fun reopen(ctx: Context, then: () -> Unit) {
        val app = ctx.applicationContext
        val a = adapter(app)
        try {
            headset?.let { a?.closeProfileProxy(BluetoothProfile.HEADSET, it) }
            a2dp?.let { a?.closeProfileProxy(BluetoothProfile.A2DP, it) }
        } catch (_: Exception) { }
        headset = null; a2dp = null
        prepare(app)
        whenServicesReady(0, then)
    }

    private fun whenServicesReady(waited: Long, then: () -> Unit) {
        if ((headset != null && a2dp != null) || waited >= 2_000) { then(); return }
        main.postDelayed({ whenServicesReady(waited + 50, then) }, 50)
    }

    /** Android's own state for the AirPods, per service, for the log: e.g. "call: connecting, music: off". */
    fun states(ctx: Context): String {
        val dev = pairedPods(ctx) ?: return "no paired AirPods"
        fun st(p: BluetoothProfile?) = try {
            when (p?.getConnectionState(dev)) {
                null -> "no service"; BluetoothProfile.STATE_CONNECTED -> "connected"
                BluetoothProfile.STATE_CONNECTING -> "connecting"; BluetoothProfile.STATE_DISCONNECTING -> "disconnecting"
                else -> "off"
            }
        } catch (_: SecurityException) { "no permission" }
        return "call: ${st(headset)}, music: ${st(a2dp)}"
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

    /**
     * Whether these AirPods are connected at all right now (link or sound). Asks Android live
     * first, so a leftover "linked" flag from a missed disconnect can't keep them on the widget.
     */
    fun connected(ctx: Context): Boolean {
        val dev = pairedPods(ctx) ?: return false
        val viaProfiles = try {
            listOfNotNull(headset, a2dp).any { p -> p.connectedDevices.any { it.address == dev.address } }
        } catch (_: SecurityException) { false }
        if (viaProfiles) return true
        // The Bluetooth link comes up a few seconds before sound does; count it straight away.
        val linked = try { dev.javaClass.getMethod("isConnected").invoke(dev) as Boolean } catch (_: Exception) { null }
        if (linked != null) return linked
        if (headset != null || a2dp != null) return false // the profiles know, and they say no
        return Prefs.podsLinked(ctx)
    }

    /** True while Android is in the middle of connecting the AirPods. */
    fun connecting(ctx: Context): Boolean {
        val dev = pairedPods(ctx) ?: return false
        return try {
            listOfNotNull(headset, a2dp).any { it.getConnectionState(dev) == BluetoothProfile.STATE_CONNECTING }
        } catch (_: SecurityException) { false }
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
        val parts = mutableListOf<String>()
        if (headset == null && a2dp == null) parts += "services not open yet"
        for ((label, proxy) in listOf("call" to headset, "music" to a2dp)) {
            if (proxy == null) continue
            try {
                val m = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                val ok = m.invoke(proxy, dev) as? Boolean == true
                if (ok) accepted = true
                parts += "$label ${if (ok) "yes" else "no"}"
            } catch (e: Exception) {
                blocked = true
                val cause = (e as? java.lang.reflect.InvocationTargetException)?.cause ?: e
                parts += "$label error ${cause.javaClass.simpleName}"
            }
        }
        Prefs.addEvent(ctx, "connect request: ${parts.joinToString(", ")}")
        if (!accepted && blocked && headset != null) Prefs.setConnectBlocked(ctx, true)
        if (accepted) Prefs.setConnectBlocked(ctx, false)
        return accepted
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private val policy = AutoConnectPolicy()
    @Volatile private var lastLid: Lid? = null
    @Volatile private var lastSkip = ""

    /**
     * Called for every AirPods status message heard. [AutoConnectPolicy] decides (like CAPod):
     * connect the moment the case lid opens, try once more 5 s later, then every 20 s while
     * they're nearby and not connected. Connecting does exactly what the widget button does.
     */
    fun autoConnect(ctx: Context, rssi: Int, lid: Lid) {
        val app = ctx.applicationContext
        if (lid != lastLid && lid != Lid.UNKNOWN) {
            val what = when (lid) { Lid.OPEN -> "case opened"; Lid.CLOSED -> "case closed"; else -> "AirPods out of the case" }
            Prefs.addEvent(app, "$what ($rssi dBm)")
            lastLid = lid
        }
        if (pairedPods(app) == null) { note(app, "skipped: no paired AirPods found"); return }
        if (headset == null && a2dp == null) prepare(app) // ready for the next message
        val d = policy.onFrame(
            System.currentTimeMillis(), lid, rssi,
            enabled = Prefs.autoConnect(app), soundConnected = soundConnected(app), connecting = connecting(app)
        )
        when (d) {
            is AutoConnectPolicy.Decision.Skip -> {
                if (d.reason != lastSkip && d.reason != "waiting to retry") Prefs.addEvent(app, "auto-connect: ${d.reason}")
                lastSkip = d.reason
            }
            is AutoConnectPolicy.Decision.Connect -> {
                lastSkip = ""
                note(app, "${d.reason} ($rssi dBm), connecting…")
                pressConnect(app) { ok ->
                    val msg = "${d.reason}: " + if (ok) "asked Android to connect" else "Android refused to connect"
                    note(app, "$msg ($rssi dBm)")
                    Prefs.addEvent(app, msg)
                }
            }
        }
    }

    /**
     * Called the moment the AirPods link up. When they did that by themselves (lid opened) and
     * sound isn't connected, connect them like the button does, then check once more 1.5 s later
     * while the link is still there.
     */
    fun onLinked(ctx: Context) {
        val app = ctx.applicationContext
        prepare(app)
        fun attempt(retry: Boolean) {
            if (retry && !Prefs.podsLinked(app)) return // they already gave up
            val now = System.currentTimeMillis()
            val d = policy.onLinked(
                now, Prefs.autoConnect(app), soundConnected(app), connecting(app),
                weAskedRecently = now - connectTappedAt < 12_000, retry = retry
            )
            when (d) {
                is AutoConnectPolicy.Decision.Skip -> if (!retry) Prefs.addEvent(app, "auto-connect: ${d.reason}")
                is AutoConnectPolicy.Decision.Connect -> pressConnect(app) { ok ->
                    val msg = "${d.reason}: " + if (ok) "asked Android to connect" else "Android refused to connect"
                    note(app, msg); Prefs.addEvent(app, msg)
                    if (!retry) main.postDelayed({ attempt(true) }, 1_500)
                }
            }
        }
        // The headset service may need a moment to open after the app was asleep.
        main.postDelayed({ attempt(false) }, if (headset == null && a2dp == null) 300L else 0L)
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
            if (ok) main.postDelayed({ checkItHappened(app, reopened = false) }, 1_200)
        }, 400)
    }

    /**
     * Android said yes; is it actually connecting? If neither call nor music has started, the
     * connection to Android's services is stale: open fresh ones and ask again (once).
     */
    private fun checkItHappened(app: Context, reopened: Boolean) {
        val now = states(app)
        if (soundConnected(app) || connecting(app)) { Prefs.addEvent(app, "after connect: $now"); return }
        if (reopened) { Prefs.addEvent(app, "still nothing after fresh services ($now)"); return }
        Prefs.addEvent(app, "Android said yes but did nothing ($now); reopening its services")
        reopen(app) {
            val ok = connect(app)
            Prefs.addEvent(app, "asked again with fresh services: ${if (ok) "accepted" else "refused"}")
            if (ok) main.postDelayed({ checkItHappened(app, reopened = true) }, 1_200)
        }
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
