package com.batterywidget.app.data

// Pure decision logic: no Android classes in this file, so it can be simulated and unit-tested
// on a normal computer (see app/src/test). The Android code feeds it facts and the current time.

/** What an AirPods status broadcast says about the case lid. */
enum class Lid { OPEN, CLOSED, OUT_OF_CASE, UNKNOWN }

/** An AirPods status broadcast ("proximity pairing", type 0x07) found in Apple's data. */
data class ProximityFrame(val model: Int, val status: Int, val lid: Lid)

object PodsLogic {
    /**
     * Finds the AirPods status message anywhere in Apple's manufacturer data. Apple packs several
     * small messages as [type, length, data...]. Only the plain status format counts (first data
     * byte 0x01): AirPods also send 0x07 messages in other formats (e.g. while connecting) whose
     * bytes mean something else (CAPod issue #603).
     */
    fun proximity(apple: ByteArray): ProximityFrame? {
        var i = 0
        while (i + 1 < apple.size) {
            val type = apple[i].toInt() and 0xFF
            val len = apple[i + 1].toInt() and 0xFF
            if (type == 0x07) {
                // data[k] sits at apple[i + 2 + k]; we need data[0] (prefix) .. data[6] (lid).
                if (len < 7 || i + 2 + 6 >= apple.size) return null
                if (apple[i + 2] != 0x01.toByte()) return null
                val model = ((apple[i + 3].toInt() and 0xFF) shl 8) or (apple[i + 4].toInt() and 0xFF)
                val status = apple[i + 5].toInt() and 0xFF
                val lidByte = apple[i + 8].toInt() and 0xFF
                return ProximityFrame(model, status, lid(status, lidByte))
            }
            i += 2 + len
        }
        return null
    }

    /**
     * Lid state, decoded the way CAPod does it (verified there on AirPods Pro 1, Pro 3, Gen 5):
     * - status bit 6: the pod sending this is in the case; bit 4: one pod in the case;
     *   bit 2: both pods in the case. None set = both out (in ears / in hand).
     * - lid byte bit 3: 0 = open, 1 = closed. It's only trustworthy from a pod inside the case or
     *   while both are in it; the out-of-case pod sends a stale lid byte.
     */
    fun lid(status: Int, lidByte: Int): Lid {
        val thisInCase = (status and 0x40) != 0
        val oneInCase = (status and 0x10) != 0
        val bothInCase = (status and 0x04) != 0
        if (!thisInCase && !oneInCase && !bothInCase) return Lid.OUT_OF_CASE
        if (!thisInCase && !bothInCase) return Lid.UNKNOWN
        return if (((lidByte shr 3) and 0x01) == 0) Lid.OPEN else Lid.CLOSED
    }
}

/**
 * Decides when to ask Android to connect the AirPods. Like CAPod: the moment the lid opens it
 * connects right away; if they're still not connected it tries once more after [quickRetryMs],
 * then every [retryMs] while they stay nearby and unconnected. Never while the lid is shut,
 * while already connected, or while Android is already connecting.
 */
class AutoConnectPolicy(
    private val retryMs: Long = 20_000,
    private val quickRetryMs: Long = 5_000,
    private val goneMs: Long = 30_000,
    private val minRssi: Int = -80,
) {
    sealed class Decision {
        data class Connect(val reason: String) : Decision()
        data class Skip(val reason: String) : Decision()
    }

    private var lastLid: Lid? = null
    private var lastHeard = 0L
    private var lastAttempt = 0L
    private var attempts = 0

    private var lastLinkTry: Long? = null

    /**
     * The AirPods opened a Bluetooth link to the phone by themselves (they do this when the lid
     * opens) but sound isn't connected. They give up after ~3 s, so connect right now, while
     * they're awake. Works even when the phone hears none of their status broadcasts.
     */
    fun onLinked(
        now: Long, enabled: Boolean, soundConnected: Boolean, connecting: Boolean,
        weAskedRecently: Boolean, retry: Boolean = false,
    ): Decision {
        if (!enabled) return Decision.Skip("auto-connect is off")
        if (soundConnected) return Decision.Skip("already connected")
        if (!retry && weAskedRecently) return Decision.Skip("link came from our own connect")
        if (connecting) return Decision.Skip("Android is connecting")
        if (!retry && lastLinkTry != null && now - lastLinkTry!! < 3_000) return Decision.Skip("just tried")
        lastLinkTry = now
        attempts = 1 // a broadcast after this counts as the quick retry
        lastAttempt = now
        return Decision.Connect(if (retry) "AirPods linked by themselves (again)" else "AirPods linked by themselves")
    }

    /** One status broadcast heard at [now]. */
    fun onFrame(
        now: Long, lid: Lid, rssi: Int,
        enabled: Boolean, soundConnected: Boolean, connecting: Boolean,
    ): Decision {
        // Back after a while (taken out of a pocket, walked back in range): a fresh start.
        val reappeared = lastHeard == 0L || now - lastHeard > goneMs
        lastHeard = now
        val opened = lid == Lid.OPEN && (lastLid != Lid.OPEN || reappeared)
        if (lid == Lid.OPEN || lid == Lid.CLOSED || lid == Lid.OUT_OF_CASE) lastLid = lid
        if (opened || reappeared) attempts = 0

        if (!enabled) return Decision.Skip("auto-connect is off")
        if (lid == Lid.CLOSED) return Decision.Skip("case closed")
        if (soundConnected) { attempts = 0; return Decision.Skip("already connected") }
        if (connecting) return Decision.Skip("Android is connecting")
        if (rssi < minRssi) return Decision.Skip("AirPods too far away")

        val wait = when (attempts) {
            0 -> 0L
            1 -> quickRetryMs
            else -> retryMs
        }
        if (attempts > 0 && now - lastAttempt < wait) return Decision.Skip("waiting to retry")
        val reason = when {
            attempts > 0 -> "retry ${attempts}"
            opened -> "case opened"
            lid == Lid.OUT_OF_CASE -> "AirPods out of the case"
            else -> "AirPods nearby"
        }
        attempts++
        lastAttempt = now
        return Decision.Connect(reason)
    }
}

/** Where an AirPods battery reading came from. */
enum class PodsSource { BROADCAST, DIRECT }

/**
 * Whether the widget should show an AirPods battery reading, and until when. Readings from the
 * AirPods' own channel only count while they're connected. Broadcasts heard while they aren't
 * connected (case open next to the phone) show for [nearbyMs], then the widget goes back to
 * the phone alone.
 */
object PodsVisibility {
    const val NEARBY_MS = 60_000L
    const val FRESH_MS = 5 * 60_000L

    /** Returns the time the reading stops showing (Long.MAX_VALUE = while it lasts), or null = hide now. */
    fun shownUntil(now: Long, at: Long, source: PodsSource, connected: Boolean, directLive: Boolean): Long? {
        val until = when (source) {
            PodsSource.DIRECT -> when {
                !connected -> return null
                directLive -> Long.MAX_VALUE
                else -> at + FRESH_MS
            }
            PodsSource.BROADCAST -> at + if (connected) FRESH_MS else NEARBY_MS
        }
        return if (now < until) until else null
    }
}

/**
 * Android silently ignores an app that starts Bluetooth scans more than 5 times in 30 seconds:
 * no error, just no results. This keeps every scan start (both of the app's scans) within budget.
 */
class ScanBudget(private val max: Int = 4, private val windowMs: Long = 30_000) {
    /** How long to wait before another scan may start, given the recent start times. 0 = now. */
    fun waitMs(now: Long, starts: List<Long>): Long {
        val recent = starts.filter { now - it < windowMs }.sorted()
        if (recent.size < max) return 0L
        return recent[recent.size - max] + windowMs - now
    }
}

/**
 * A short burst of connect attempts: once something suggests you're about to use the AirPods
 * (case opened, they linked up, phone unlocked with them nearby), try every [intervalMs] for
 * [lengthMs]. Stops the moment they're connected. Never asks while Android is mid-connect, so it
 * can't interrupt a connection that's on its way.
 */
class RetryWindow(private val intervalMs: Long = 3_000, private val lengthMs: Long = 30_000) {
    sealed class Step {
        object Connect : Step()
        object Wait : Step()
        data class Stop(val reason: String) : Step()
    }

    private var until: Long? = null
    private var lastTry: Long? = null
    var reason = ""; private set

    val active get() = until != null

    /** Opens the window (or keeps an open one going). Returns true if it was newly opened. */
    fun start(now: Long, why: String): Boolean {
        val fresh = !active
        if (fresh) { reason = why; lastTry = null }
        until = now + lengthMs
        return fresh
    }

    fun stop() { until = null }

    /** Called every [intervalMs] while active. */
    fun tick(now: Long, enabled: Boolean, connected: Boolean, connecting: Boolean): Step {
        if (!active) return Step.Stop("not active")
        if (!enabled) { stop(); return Step.Stop("auto-connect is off") }
        if (connected) { stop(); return Step.Stop("connected") }
        if (now >= until!!) { stop(); return Step.Stop("gave up after ${lengthMs / 1000} s") }
        if (connecting) return Step.Wait
        val last = lastTry
        if (last != null && now - last < intervalMs - 200) return Step.Wait
        lastTry = now
        return Step.Connect
    }
}

/**
 * After the AirPods disconnect while out of the case (you disconnected them, or they went to
 * another device), the gentle triggers (nearby, unlock) stay quiet so the phone doesn't grab them
 * back. It wakes up again when the case closes (seen as a closed lid, or a [silenceMs] gap with
 * no AirPods messages, since a closed case goes quiet), or when the AirPods link up by themselves.
 */
class DisconnectPause(private val silenceMs: Long = 8_000) {
    var paused = false; private set
    private var lastFrame = 0L

    fun onDisconnected() { paused = true }

    /** A message from the AirPods heard at [now]. */
    fun onFrame(now: Long, lid: Lid) {
        if (paused && (lid == Lid.CLOSED || (lastFrame != 0L && now - lastFrame >= silenceMs))) paused = false
        lastFrame = now
    }

    /** The AirPods themselves reached out, or you asked explicitly: they're wanted. */
    fun clear() { paused = false }
}

/**
 * For AirPods that never say when the lid opens (like Pro 3 on a Samsung): while the screen is
 * on and their Find My signal is around, try connecting every [intervalMs]. Tries while the case
 * is closed just fail quietly; the first one after the lid opens connects.
 */
class ScreenOnWatch(
    private val intervalMs: Long = 3_000,
    private val seenWithinMs: Long = 60_000,
    private val afterDisconnectMs: Long = 60_000,
) {
    private var lastTry: Long? = null
    private var disconnectedAt: Long? = null

    fun onDisconnected(now: Long) { disconnectedAt = now }

    /** Returns null to try now, or the reason it's not trying. */
    fun check(
        now: Long, screenOn: Boolean, enabled: Boolean, connected: Boolean, connecting: Boolean,
        otherAudio: Boolean, lastSeenAt: Long?,
    ): String? {
        if (!enabled) return "auto-connect is off"
        if (!screenOn) return "screen off"
        if (connected) return "already connected"
        if (connecting) return "Android is connecting"
        if (otherAudio) return "another headset or car is connected"
        val d = disconnectedAt
        if (d != null && now - d < afterDisconnectMs) return "just disconnected"
        if (lastSeenAt == null || now - lastSeenAt > seenWithinMs) return "AirPods not nearby"
        val last = lastTry
        if (last != null && now - last < intervalMs - 200) return "waiting"
        lastTry = now
        return null
    }
}
