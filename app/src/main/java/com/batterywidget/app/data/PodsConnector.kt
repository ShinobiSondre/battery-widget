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

    // After a real event (lid message, AirPods linking up, widget tap): try now and once more 3 s later.
    private val window = RetryWindow(intervalMs = 3_000, lengthMs = 6_000)
    private val pause = DisconnectPause()
    @Volatile private var lastSeenAt = 0L
    @Volatile private var ticking = false
    @Volatile private var triesThisWindow = 0

    /**
     * Starts the burst of attempts (every 3 s for 30 s) unless the AirPods are already
     * connected, auto-connect is off, or they were just disconnected on purpose.
     */
    private fun startWindow(app: Context, why: String, explicit: Boolean = false) {
        if (!Prefs.autoConnect(app) || pairedPods(app) == null) return
        if (explicit) pause.clear()

        if (soundConnected(app)) return
        if (window.start(System.currentTimeMillis(), why)) {
            triesThisWindow = 0
            Prefs.addEvent(app, "auto-connect: $why, trying every 3 s for 30 s")
        }
        if (!ticking) { ticking = true; tick(app) }
    }

    private fun tick(app: Context) {
        val step = window.tick(System.currentTimeMillis(), Prefs.autoConnect(app), soundConnected(app), connecting(app))
        when (step) {
            is RetryWindow.Step.Connect -> {
                triesThisWindow++
                val n = triesThisWindow
                note(app, "${window.reason}: try $n…")
                if (n == 1) {
                    pressConnect(app) { ok -> note(app, "${window.reason}: try 1 ${if (ok) "asked Android" else "refused"}") }
                } else {
                    connectTappedAt = System.currentTimeMillis() // keeps the widget button hidden meanwhile
                    prepare(app)
                    val ok = connect(app)
                    note(app, "${window.reason}: try $n ${if (ok) "asked Android" else "refused"}")
                }
            }
            is RetryWindow.Step.Stop -> {
                ticking = false
                Prefs.addEvent(app, "auto-connect: stopped (${step.reason}) after $triesThisWindow tries")
                note(app, "stopped: ${step.reason}")
                WidgetUpdater.updateAll(app)
                return
            }
            RetryWindow.Step.Wait -> Unit
        }
        main.postDelayed({ tick(app) }, 3_000)
    }

    /** The AirPods dropped their link: stop trying, and don't grab them back until the case closes. */
    fun onUnlinked(ctx: Context) {
        val app = ctx.applicationContext
        if (window.active) window.stop()
        watch.onDisconnected(System.currentTimeMillis())
        Prefs.addEvent(app, "AirPods disconnected: auto-connect waits 60 s")
    }

    // ---- Screen-on watch: for AirPods that never announce the lid opening ----
    private val watch = ScreenOnWatch()
    @Volatile private var findMyAt = 0L
    @Volatile private var watching = false
    @Volatile private var watchTries = 0
    @Volatile private var lastWatchReason = ""

    /** The AirPods' Find My signal was heard (they send it all the time, case open or closed). */
    fun sawFindMy(now: Long) { findMyAt = now }

    // ---- Live Find My signal: shown on the widget, and a quick restart when it gets strong ----
    const val RESTART_RSSI = -70
    private const val FIND_MY_TRIGGER = false
    @Volatile var signalRssi = 0; private set
    @Volatile var signalAt = 0L; private set
    @Volatile private var strongAt = 0L
    @Volatile private var lastQuickRestart = 0L

    /** Every Find My message with a real strength (127 = the phone didn't say). */
    private val smoother = SignalSmoother()

    fun findMySignal(ctx: Context, now: Long, rssi: Int) {
        smoother.add(now, rssi)
        // The median of the last few seconds, so one odd reading can't trigger anything.
        val smooth = smoother.median(now) ?: return
        signalRssi = smooth; signalAt = now
        // Auto-connect no longer reacts to the Find My signal (too unreliable); only real events.
        if (!FIND_MY_TRIGGER) return
        if (smooth < RESTART_RSSI) return
        val crossed = now - strongAt > 15_000 // was weaker (or unheard) for a while: it just got close
        strongAt = now
        if (!crossed || now - lastQuickRestart < 30_000) return
        val rssi = smooth
        val app = ctx.applicationContext
        if (!Prefs.alwaysListen(app) || !Prefs.autoConnect(app) || soundConnected(app)) return
        lastQuickRestart = now
        val svc = AirPodsService.instance
        if (svc != null) svc.quickRestart("Find My $rssi dBm")
        else reopen(app) { connectAfterRestart(app, "Find My $rssi dBm") }
    }

    /** After the restart: connect like the button does, and once more 3 s later if it didn't take. */
    fun connectAfterRestart(ctx: Context, why: String) {
        val app = ctx.applicationContext
        if (soundConnected(app)) return
        pressConnect(app) { ok ->
            Prefs.addEvent(app, "$why: " + if (ok) "asked Android to connect" else "Android refused to connect")
            main.postDelayed({
                if (!soundConnected(app) && !connecting(app)) pressConnect(app) { ok2 ->
                    Prefs.addEvent(app, "$why (again): " + if (ok2) "asked Android to connect" else "Android refused to connect")
                }
            }, 3_000)
        }
    }

    private fun screenOn(app: Context) =
        (app.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive

    /** Another headset, speaker or car is connected for sound: leave things as they are. */
    private fun otherAudio(app: Context): Boolean {
        val pods = pairedPods(app)?.address ?: return false
        return try {
            listOfNotNull(headset, a2dp).any { p -> p.connectedDevices.any { it.address != pods } }
        } catch (_: SecurityException) { false }
    }

    /** Starts the every-3-seconds check while the screen is on (called on screen on / app start). */
    fun startWatch(ctx: Context) {
        val app = ctx.applicationContext
        if (watching) return
        watching = true
        prepare(app)
        watchTick(app)
    }

    private fun watchTick(app: Context) {
        val now = System.currentTimeMillis()
        val on = screenOn(app)
        val why = watch.check(
            now, on, Prefs.autoConnect(app), soundConnected(app), connecting(app), otherAudio(app),
            if (findMyAt == 0L) null else findMyAt
        )
        if (why == null) {
            watchTries++
            if (watchTries == 1) Prefs.addEvent(app, "AirPods nearby, not connected: trying every 3 s")
            connectTappedAt = now // keeps the widget's connect button hidden meanwhile
            val ok = connect(app)
            note(app, "try $watchTries: ${if (ok) "asked Android" else "refused"}")
        } else if (why != "waiting") {
            if (watchTries > 0) {
                Prefs.addEvent(app, "stopped trying after $watchTries tries: $why")
                if (why == "already connected") note(app, "connected after $watchTries tries")
                watchTries = 0
                WidgetUpdater.updateAll(app)
            }
            if (why != lastWatchReason && why != "screen off") lastWatchReason = why
        }
        if (!on) { watching = false; return } // screen off: sleep until it turns on again
        main.postDelayed({ watchTick(app) }, 3_000)
    }

    /** Any strong Apple signal right next to the phone: the AirPods are probably nearby. */
    fun sawNearby(now: Long) { lastSeenAt = now }

    /** Phone unlocked: if the AirPods were nearby a moment ago, get them ready. */
    fun onUnlock(ctx: Context) {
        if (System.currentTimeMillis() - lastSeenAt < 120_000) startWindow(ctx.applicationContext, "phone unlocked, AirPods nearby")
    }

    /** Widget tapped: if the AirPods are nearby, connect them. */
    fun onWidgetTap(ctx: Context) {
        if (System.currentTimeMillis() - lastSeenAt < 120_000) startWindow(ctx.applicationContext, "widget tapped", explicit = true)
    }

    /**
     * Called for every AirPods status message heard. [AutoConnectPolicy] decides (like CAPod):
     * connect the moment the case lid opens, try once more 5 s later, then every 20 s while
     * they're nearby and not connected. Connecting does exactly what the widget button does.
     */
    fun autoConnect(ctx: Context, rssi: Int, lid: Lid) {
        Finder.onPodsHeard(rssi) // warmer / colder
        val app = ctx.applicationContext
        if (lid != lastLid && lid != Lid.UNKNOWN) {
            val what = when (lid) { Lid.OPEN -> "case opened"; Lid.CLOSED -> "case closed"; else -> "AirPods out of the case" }
            Prefs.addEvent(app, "$what ($rssi dBm)")
            lastLid = lid
        }
        val now = System.currentTimeMillis()
        pause.onFrame(now, lid)
        if (rssi >= -80) lastSeenAt = now
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
                if (d.reason.startsWith("retry")) {
                    // The slow background retry: one attempt, never stretching the 30 s burst.
                    if (!window.active && !pause.paused) pressConnect(app) { ok ->
                        Prefs.addEvent(app, "${d.reason}: " + if (ok) "asked Android to connect" else "Android refused to connect")
                    }
                } else {
                    startWindow(app, "${d.reason} ($rssi dBm)")
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
        lastSeenAt = System.currentTimeMillis()
        pause.clear() // the AirPods reached out themselves: they're wanted
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
                is AutoConnectPolicy.Decision.Connect -> startWindow(app, d.reason, explicit = true)
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
