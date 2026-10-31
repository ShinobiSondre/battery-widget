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
