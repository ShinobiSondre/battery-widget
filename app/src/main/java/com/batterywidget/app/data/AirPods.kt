package com.batterywidget.app.data

import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Build
import androidx.core.content.IntentCompat
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanResult
import android.content.Context
import com.batterywidget.app.widget.WidgetUpdater
import kotlin.math.abs

/** One AirPods battery reading. Levels are 0–100, or -1 when that part isn't reporting. */
data class PodsReading(
    val left: Int, val right: Int, val case: Int,
    val chargingLeft: Boolean, val chargingRight: Boolean, val chargingCase: Boolean,
    val model: String, val at: Long
) {
    fun toItems(): List<Item> {
        val out = mutableListOf<Item>()
        when {
            left >= 0 && right >= 0 && abs(left - right) < 20 ->
                out += Item(model, minOf(left, right), IconKind.EARBUDS, chargingLeft || chargingRight)
            else -> {
                if (left >= 0) out += Item("Left", left, IconKind.BUD_LEFT, chargingLeft)
                if (right >= 0) out += Item("Right", right, IconKind.BUD_RIGHT, chargingRight)
            }
        }
        if (case >= 0) out += Item("Case", case, IconKind.CASE, chargingCase)
        return out
    }

    fun sameLevels(o: PodsReading?) = o != null && left == o.left && right == o.right && case == o.case &&
        chargingLeft == o.chargingLeft && chargingRight == o.chargingRight && chargingCase == o.chargingCase
}

/**
 * AirPods don't share their battery with Android the normal way. Instead they keep broadcasting
 * a small Bluetooth "status" message (the same one an iPhone uses for its pop-up), which has
 * left, right and case battery in steps of 10%. [AirPodsService] listens for those all the time.
 */
object AirPods {
    const val APPLE = 76
    private const val NEAR_RSSI = -82 // ignores other people's AirPods far away

    /**
     * The latest reading, if it should be on the widget now. Readings from the AirPods' own
     * channel only while they're connected; broadcasts heard while they aren't connected (case
     * open next to the phone) for a minute. After that the widget goes back to the phone alone.
     */
    fun current(ctx: Context): PodsReading? {
        val p = Prefs.loadPods(ctx) ?: return null
        val until = PodsVisibility.shownUntil(
            System.currentTimeMillis(), p.at, Prefs.podsSource(ctx),
            connected = PodsConnector.connected(ctx), directLive = AapClient.live
        ) ?: return null
        // Redraw the moment it should disappear, even if nothing else happens.
        if (until != Long.MAX_VALUE) WidgetUpdater.redrawAt(ctx, until + 500)
        return p
    }

    /**
     * Called the instant any Bluetooth device connects or disconnects. When it's AirPods, the
     * widget shows them right away with their last known battery, instead of waiting for the
     * next battery broadcast or for the audio link to finish setting up.
     */
    fun onConnectionChange(ctx: Context, intent: Intent) {
        val d = IntentCompat.getParcelableExtra(intent, android.bluetooth.BluetoothDevice.EXTRA_DEVICE, android.bluetooth.BluetoothDevice::class.java)
            ?: return
        val name = try { d.name } catch (_: SecurityException) { null } ?: return
        if (!name.contains("pods", ignoreCase = true)) return
        val connected = intent.action == android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED
        val changed = Prefs.podsLinked(ctx) != connected
        if (changed) Prefs.addEvent(ctx, if (connected) "AirPods linked" else "AirPods unlinked")
        Prefs.setPodsLinked(ctx, connected)
        if (connected) Prefs.setPodsAddress(ctx, d.address) else AapClient.stop()
        // Lid opened: the AirPods reach out to the phone on their own. Finish the job.
        if (connected && changed) PodsConnector.onLinked(ctx)
    }

    /**
     * Apple packs several small messages into one broadcast, each as [type, length, data...].
     * Newer AirPods can put other messages before the battery one, so look through all of them.
     */
    fun parse(all: ByteArray): PodsReading? {
        var i = 0
        while (i + 1 < all.size) {
            val type = all[i].toInt() and 0xFF
            val len = all[i + 1].toInt() and 0xFF
            if (type == 0x07) {
                return parseStatus(all.copyOfRange(i, minOf(all.size, i + 2 + len)))
            }
            i += 2 + len
        }
        return null
    }

    /** Message types found in a broadcast, for the debug line (like "10 07"). */
    fun types(all: ByteArray): String {
        val out = mutableListOf<String>()
        var i = 0
        while (i + 1 < all.size) {
            out += "%02X".format(all[i].toInt() and 0xFF)
            i += 2 + (all[i + 1].toInt() and 0xFF)
        }
        return out.joinToString(" ")
    }

    private fun parseStatus(d: ByteArray): PodsReading? {
        // 0x07 = "proximity pairing" status message. Older and newer AirPods differ in length,
        // but the battery part sits in the same place.
        // Only the plain status message counts: it has 0x01 right after the length, and is long
        // enough to hold the battery bytes. AirPods also send short 0x07 messages (for example
        // "07 09 03 ..." when connecting) whose bytes would read as nonsense levels.
        if (d.size < 10 || d[0] != 0x07.toByte() || d[2] != 0x01.toByte() || (d[1].toInt() and 0xFF) < 8) return null
        // Digit i of the message written out in hex: even = high half of a byte, odd = low half.
        fun nib(i: Int): Int {
            val b = d[i / 2].toInt() and 0xFF
            return if (i % 2 == 0) b shr 4 else b and 0x0F
        }
        val flipped = (nib(10) and 0x02) == 0
        fun level(v: Int) = when (v) { 10 -> 100; in 0..9 -> v * 10 + 5; else -> -1 }
        val charge = nib(14)
        val model = when (((d[3].toInt() and 0xFF) shl 8) or (d[4].toInt() and 0xFF)) {
            0x0E20, 0x1420, 0x2420, 0x2720 -> "AirPods Pro"
            0x0A20, 0x1F20 -> "AirPods Max"
            else -> "AirPods"
        }
        val r = PodsReading(
            left = level(nib(if (flipped) 12 else 13)),
            right = level(nib(if (flipped) 13 else 12)),
            case = level(nib(15)),
            chargingLeft = (charge and (if (flipped) 0b10 else 0b01)) != 0,
            chargingRight = (charge and (if (flipped) 0b01 else 0b10)) != 0,
            chargingCase = (charge and 0b100) != 0,
            model = model,
            at = System.currentTimeMillis()
        )
        return if (r.left < 0 && r.right < 0 && r.case < 0) null else r
    }

    /** True while a Bluetooth headset or speaker is connected. */
    fun audioConnected(ctx: Context): Boolean = try {
        val a = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        a != null && a.isEnabled && (
            a.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED ||
                a.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED)
    } catch (_: SecurityException) { false }

    /** Makes sure the always-on listener is running (when it's switched on). */
    fun sync(ctx: Context) {
        AirPodsService.ensure(ctx)
        if (PodsConnector.soundConnected(ctx)) AapClient.start(ctx)
        if (Prefs.alwaysListen(ctx)) startSystemScan(ctx) else stopSystemScan(ctx)
    }

    private fun systemScanIntent(ctx: Context): PendingIntent {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(ctx, 7, Intent(ctx, AirPodsScanReceiver::class.java), flags)
    }

    /**
     * Hands the listening to Android's own Bluetooth system. It keeps going even when Samsung
     * or Android closes the app to save battery, and wakes the app only when AirPods speak.
     */
    fun startSystemScan(ctx: Context, force: Boolean = false) {
        if (!Perms.hasScan(ctx)) return
        // It keeps running on its own once started, so don't restart it on every little event:
        // each start counts toward Android's limit of 5 scan starts in 30 seconds.
        val age = System.currentTimeMillis() - Prefs.systemScanAt(ctx)
        if (!force && age in 0 until 15 * 60_000L) return
        if (!ScanGate.tryStart(ctx, "background scan")) return
        try {
            val a = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            val scanner = (if (a != null && a.isEnabled) a.bluetoothLeScanner else null) ?: return
            // Only AirPods status messages (07 19), so the app isn't woken for every iPhone nearby.
            val filter = ScanFilter.Builder()
                .setManufacturerData(APPLE, byteArrayOf(0x07, 0x19), byteArrayOf(-1, -1))
                .build()
            // Fastest scanning; the narrow filter runs on the Bluetooth chip, so the phone
            // itself stays asleep until AirPods actually speak.
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .build()
            val pi = systemScanIntent(ctx)
            scanner.stopScan(pi)
            scanner.startScan(listOf(filter), settings, pi)
            Prefs.setSystemScanAt(ctx, System.currentTimeMillis())
        } catch (_: Exception) { }
    }

    fun stopSystemScan(ctx: Context) {
        if (!Perms.hasScan(ctx)) return
        try {
            val a = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            a?.bluetoothLeScanner?.stopScan(systemScanIntent(ctx))
        } catch (_: Exception) { }
        Prefs.setSystemScanAt(ctx, 0L)
    }

    private class Tally { var count = 0; var best = -999; var hex = "" }
    private val tally = HashMap<String, Tally>()
    private var tallyStart = 0L
    private var lastDebugSave = 0L

    /** Handles AirPods messages. Returns true when the battery changed. */
    fun onResults(ctx: Context, results: List<ScanResult>): Boolean {
        // Debug line: a tally of Apple message types heard in the last minute, with the
        // strongest signal for each (AirPods right next to the phone are about -30 to -55).
        val now = System.currentTimeMillis()
        if (now - tallyStart > 60_000) { tally.clear(); tallyStart = now }
        for (r in results) {
            val data = r.scanRecord?.getManufacturerSpecificData(APPLE) ?: continue
            for (t in types(data).split(" ").filter { it.isNotBlank() }) {
                val e = tally.getOrPut(t) { Tally() }
                e.count++
                if (r.rssi > e.best) {
                    e.best = r.rssi
                    e.hex = data.joinToString("") { "%02X".format(it.toInt() and 0xFF) }.take(40)
                }
            }
        }
        if (tally.isNotEmpty() && now - lastDebugSave > 3_000) {
            lastDebugSave = now
            Prefs.setDebug(ctx, tally.entries.sortedByDescending { it.value.best }.joinToString("\n") { (t, e) ->
                "type $t ×${e.count}, best ${e.best} dBm: ${e.hex}"
            })
        }
        // Any AirPods status message counts for auto-connect, even one without readable battery:
        // all it needs to know is that the AirPods are here and whether the lid is open.
        val frames = results
            .filter { it.rssi < 20 } // 127 means "strength unknown"
            .mapNotNull { r -> r.scanRecord?.getManufacturerSpecificData(APPLE)?.let { PodsLogic.proximity(it) }?.let { r to it } }
        frames.maxByOrNull { it.first.rssi }?.let { (r, f) ->
            Prefs.setLastHeard(ctx, now, r.rssi)
            PodsConnector.autoConnect(ctx, r.rssi, f.lid)
        }
        val best = results
            .filter { it.rssi >= NEAR_RSSI && it.rssi < 20 }
            .mapNotNull { r -> r.scanRecord?.getManufacturerSpecificData(APPLE)?.let { parse(it)?.let { p -> r to p } } }
            .maxByOrNull { it.first.rssi } ?: return false
        val reading = best.second
        // The AirPods' own channel is exact; don't overwrite it with a 10%-step broadcast.
        if (AapClient.live) return false
        val old = Prefs.loadPods(ctx)
        val changed = !reading.sameLevels(old)
        val stale = old == null || reading.at - old.at > 30_000 || Prefs.podsSource(ctx) != PodsSource.BROADCAST
        if (!changed && !stale) return false
        val wasHidden = current(ctx) == null
        Prefs.savePods(ctx, reading, PodsSource.BROADCAST)
        // AirPods change in 10% steps, so every change is worth showing straight away.
        if (changed || wasHidden) WidgetUpdater.updateAllThrottled(ctx, force = true)
        return changed || wasHidden
    }
}

/**
 * Every Bluetooth scan start in the app goes through here. Android silently stops giving an app
 * any results if it starts scans more than 5 times in 30 seconds, which is easy to hit when the
 * screen turns on, AirPods connect and the widget refreshes all at once.
 */
object ScanGate {
    private val budget = ScanBudget()

    /** How long until a scan may start (0 = now). */
    @Synchronized
    fun waitMs(ctx: Context): Long = budget.waitMs(System.currentTimeMillis(), Prefs.scanStarts(ctx))

    /** Records a start and returns true if one is allowed right now. */
    @Synchronized
    fun tryStart(ctx: Context, what: String): Boolean {
        val now = System.currentTimeMillis()
        val starts = Prefs.scanStarts(ctx).filter { now - it in 0 until 60_000L }
        val wait = budget.waitMs(now, starts)
        if (wait > 0) { Prefs.addEvent(ctx, "$what held back ${wait / 1000}s (Android scan limit)"); return false }
        Prefs.setScanStarts(ctx, starts + now)
        return true
    }
}

/** Woken by Android's Bluetooth system when it hears AirPods, even if the app was closed. */
class AirPodsScanReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val results = IntentCompat.getParcelableArrayListExtra(
            intent, BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult::class.java
        ) ?: return
        // Stay awake a moment so a delayed redraw can finish.
        val pending = goAsync()
        AirPods.onResults(ctx, results)
        AirPodsService.ensure(ctx) // bring the listener back if it was closed
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ pending.finish() }, 3000)
    }
}
