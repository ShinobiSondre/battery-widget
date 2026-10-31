package com.batterywidget.app.data

import android.bluetooth.BluetoothManager
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
    private const val FRESH_MS = 5 * 60 * 1000L
    private const val NEAR_RSSI = -82 // ignores other people's AirPods far away

    /** The latest reading, if it's recent enough to trust. */
    fun current(ctx: Context): PodsReading? {
        val p = Prefs.loadPods(ctx) ?: return null
        return if (System.currentTimeMillis() - p.at < FRESH_MS) p else null
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
        if (d.size < 9 || d[0] != 0x07.toByte()) return null
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
    fun sync(ctx: Context) = AirPodsService.ensure(ctx)

    /** Handles AirPods messages. Returns true when the battery changed. */
    fun onResults(ctx: Context, results: List<ScanResult>): Boolean {
        // Debug line: remember the latest Apple broadcast with a status message in it, if any.
        for (r in results) {
            val data = r.scanRecord?.getManufacturerSpecificData(APPLE) ?: continue
            val t = types(data)
            val since = System.currentTimeMillis() - Prefs.debugAt(ctx)
            if ("07" in t.split(" ")) {
                if (since < 5_000) continue
                Prefs.setDebug(ctx, "07 heard ${r.rssi} dBm · " + data.joinToString("") { "%02X".format(it.toInt() and 0xFF) })
            } else if (since > 60_000) {
                Prefs.setDebug(ctx, "Apple signals only (types $t)")
            }
        }
        val best = results
            .filter { it.rssi >= NEAR_RSSI }
            .mapNotNull { r -> r.scanRecord?.getManufacturerSpecificData(APPLE)?.let { parse(it)?.let { p -> r to p } } }
            .maxByOrNull { it.first.rssi } ?: return false
        val (result, reading) = best
        Prefs.setLastHeard(ctx, reading.at, result.rssi)
        val old = Prefs.loadPods(ctx)
        val changed = !reading.sameLevels(old)
        val stale = old == null || reading.at - old.at > 30_000
        if (!changed && !stale) return false
        val wasHidden = old == null || reading.at - old.at > FRESH_MS
        Prefs.savePods(ctx, reading)
        if (changed || wasHidden) WidgetUpdater.updateAllThrottled(ctx, force = wasHidden)
        return changed || wasHidden
    }
}
