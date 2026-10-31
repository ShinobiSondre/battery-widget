package com.batterywidget.app.data

import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.IntentCompat
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
 * left, right and case battery in steps of 10%. Android's own Bluetooth chip listens for those
 * messages for us while a Bluetooth audio device is connected, and wakes [AirPodsScanReceiver].
 */
object AirPods {
    private const val APPLE = 76
    private const val FRESH_MS = 5 * 60 * 1000L
    private const val NEAR_RSSI = -67 // ignores other people's AirPods further away

    /** The latest reading, if it's recent enough to trust. */
    fun current(ctx: Context): PodsReading? {
        val p = Prefs.loadPods(ctx) ?: return null
        return if (System.currentTimeMillis() - p.at < FRESH_MS) p else null
    }

    fun parse(d: ByteArray): PodsReading? {
        if (d.size != 27 || d[0] != 0x07.toByte() || d[1] != 0x19.toByte()) return null
        // Digit i of the message written out in hex: even = high half of a byte, odd = low half.
        fun nib(i: Int): Int {
            val b = d[i / 2].toInt() and 0xFF
            return if (i % 2 == 0) b shr 4 else b and 0x0F
        }
        val flipped = (nib(10) and 0x02) == 0
        fun level(v: Int) = when (v) { 10 -> 100; in 0..9 -> v * 10 + 5; else -> -1 }
        val charge = nib(14)
        val model = when (((d[3].toInt() and 0xFF) shl 8) or (d[4].toInt() and 0xFF)) {
            0x0E20, 0x1420, 0x2420 -> "AirPods Pro"
            0x0A20 -> "AirPods Max"
            else -> "AirPods"
        }
        return PodsReading(
            left = level(nib(if (flipped) 12 else 13)),
            right = level(nib(if (flipped) 13 else 12)),
            case = level(nib(15)),
            chargingLeft = (charge and (if (flipped) 0b10 else 0b01)) != 0,
            chargingRight = (charge and (if (flipped) 0b01 else 0b10)) != 0,
            chargingCase = (charge and 0b100) != 0,
            model = model,
            at = System.currentTimeMillis()
        )
    }

    private fun scanner(ctx: Context): BluetoothLeScanner? = try {
        val a = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (a != null && a.isEnabled) a.bluetoothLeScanner else null
    } catch (_: Exception) { null }

    private fun pendingIntent(ctx: Context): PendingIntent {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(ctx, 7, Intent(ctx, AirPodsScanReceiver::class.java), flags)
    }

    /** True while a Bluetooth headset or speaker is connected. */
    fun audioConnected(ctx: Context): Boolean = try {
        val a = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        a != null && a.isEnabled && (
            a.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED ||
                a.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED)
    } catch (_: SecurityException) { false }

    /** Listens for AirPods while audio is connected (or when [force]d), and stops otherwise. */
    fun sync(ctx: Context, force: Boolean = false) {
        if (force || audioConnected(ctx)) start(ctx) else stop(ctx)
    }

    private fun start(ctx: Context) {
        if (!Perms.hasScan(ctx)) return
        val s = scanner(ctx) ?: return
        val filter = ScanFilter.Builder()
            .setManufacturerData(APPLE, byteArrayOf(0x07, 0x19), byteArrayOf(-1, -1))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        try {
            val pi = pendingIntent(ctx)
            s.stopScan(pi)
            s.startScan(listOf(filter), settings, pi)
        } catch (_: Exception) { }
    }

    fun stop(ctx: Context) {
        if (!Perms.hasScan(ctx)) return
        try { scanner(ctx)?.stopScan(pendingIntent(ctx)) } catch (_: Exception) { }
    }

    fun onResults(ctx: Context, results: List<ScanResult>) {
        val best = results
            .filter { it.rssi >= NEAR_RSSI }
            .maxByOrNull { it.rssi } ?: return
        val data = best.scanRecord?.getManufacturerSpecificData(APPLE) ?: return
        val reading = parse(data) ?: return
        val old = Prefs.loadPods(ctx)
        val changed = !reading.sameLevels(old)
        val stale = old == null || reading.at - old.at > 60_000
        if (!changed && !stale) return
        Prefs.savePods(ctx, reading)
        if (changed || old == null || reading.at - old.at > FRESH_MS) WidgetUpdater.updateAllThrottled(ctx)
    }
}

class AirPodsScanReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val results = IntentCompat.getParcelableArrayListExtra(
            intent, BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult::class.java
        ) ?: return
        AirPods.onResults(ctx, results)
    }
}
