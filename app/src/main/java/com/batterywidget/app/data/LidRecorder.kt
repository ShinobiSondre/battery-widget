package com.batterywidget.app.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * The lid test. Runs its own scan in three 20-second phases, each asking the Bluetooth chip to
 * filter differently, and records every Apple message plus your "Lid opened / closed" taps.
 * Samsung phones are reported to drop AirPods lid messages (type 07) with some filters; this
 * shows which way of scanning lets them through.
 */
object LidRecorder {
    const val PHASE_MS = 20_000L
    val phases = listOf("No filter", "Full-length AirPods filter", "Apple filter (current)")

    private data class Entry(val t: Long, val phase: Int, val rssi: Int, val sig: AppleSig)
    private data class Mark(val t: Long, val phase: Int, val label: String)

    private val entries = mutableListOf<Entry>()
    private val marks = mutableListOf<Mark>()
    private val handler = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false

    @Volatile var startedAt = 0L; private set
    @Volatile var active = false; private set
    @Volatile var phase = 0; private set

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = record(listOf(result))
        override fun onBatchScanResults(results: MutableList<ScanResult>) = record(results)
        override fun onScanFailed(errorCode: Int) = mark("(scan failed, code $errorCode)")
    }

    @Synchronized fun start(ctx: Context) {
        val app = ctx.applicationContext
        stop()
        entries.clear(); marks.clear()
        startedAt = System.currentTimeMillis()
        active = true
        phase = -1
        nextPhase(app)
    }

    @Synchronized fun stop() {
        active = false
        handler.removeCallbacksAndMessages(null)
        stopScan()
    }

    private fun nextPhase(app: Context) {
        stopScan()
        phase++
        if (phase >= phases.size) { active = false; return }
        startPhaseScan(app, phase)
        handler.postDelayed({ if (active) nextPhase(app) }, PHASE_MS)
    }

    @SuppressLint("MissingPermission")
    private fun startPhaseScan(app: Context, p: Int) {
        if (!active || p != phase) return
        if (!ScanGate.tryStart(app, "lid test")) {
            handler.postDelayed({ startPhaseScan(app, p) }, 2_000)
            return
        }
        val s = try {
            val a = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            if (a != null && a.isEnabled) a.bluetoothLeScanner else null
        } catch (_: Exception) { null }
        if (s == null) { mark("(Bluetooth off)"); return }
        val filters = when (p) {
            0 -> emptyList()
            // How CAPod asks: the full 27-byte message, only the first two bytes (07 19) must match.
            1 -> listOf(
                ScanFilter.Builder().setManufacturerData(
                    AirPods.APPLE,
                    ByteArray(27).also { it[0] = 0x07; it[1] = 0x19 },
                    ByteArray(27).also { it[0] = 1; it[1] = 1 }
                ).build()
            )
            else -> listOf(ScanFilter.Builder().setManufacturerData(AirPods.APPLE, ByteArray(0)).build())
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        try {
            s.startScan(filters, settings, callback)
            scanner = s; scanning = true
        } catch (e: Exception) {
            mark("(couldn't scan: ${e.javaClass.simpleName})")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { scanner?.stopScan(callback) } catch (_: Exception) { }
    }

    @Synchronized private fun record(results: List<ScanResult>) {
        if (!active) return
        val t = System.currentTimeMillis() - startedAt
        for (r in results) {
            val data = r.scanRecord?.getManufacturerSpecificData(AirPods.APPLE) ?: continue
            for (sig in AppleSig.all(data)) entries += Entry(t, phase, r.rssi, sig)
        }
    }

    @Synchronized fun mark(label: String) {
        if (active) marks += Mark(System.currentTimeMillis() - startedAt, phase, label)
    }

    fun phaseSecondsLeft(): Int {
        if (!active) return 0
        val inPhase = (System.currentTimeMillis() - startedAt) - phase * PHASE_MS
        return ((PHASE_MS - inPhase) / 1000).toInt().coerceIn(0, 20)
    }

    /** Per phase: how many messages of each type, then a 2-second timeline with your taps. */
    @Synchronized fun summary(): String {
        if (entries.isEmpty() && marks.isEmpty()) return "Nothing recorded yet."
        val sb = StringBuilder("Lid test v2 (${entries.size} Apple messages)\n")
        for ((p, name) in phases.withIndex()) {
            val es = entries.filter { it.phase == p }
            val ms = marks.filter { it.phase == p }
            if (es.isEmpty() && ms.isEmpty()) continue
            sb.append("\n=== ${p + 1}. $name ===\n")
            val byType = es.groupBy { it.sig.type }.toSortedMap()
            sb.append("types: ").append(byType.entries.joinToString(", ") { "%02X x%d".format(it.key, it.value.size) }).append('\n')
            val buckets = es.groupBy { it.t / 2_000 }
            val keys = (buckets.keys + ms.map { it.t / 2_000 }).toSortedSet()
            for (b in keys) {
                for (m in ms.filter { it.t / 2_000 == b }) sb.append("--- ${m.label} at ${m.t / 1000.0}s ---\n")
                val list = buckets[b] ?: continue
                sb.append("${b * 2}-${b * 2 + 2}s:")
                for ((sig, group) in list.groupBy { it.sig }.toList().sortedBy { it.first.type }) {
                    val known = group.map { it.rssi }.filter { it != 127 }.sorted()
                    val med = if (known.isEmpty()) "?" else known[known.size / 2].toString()
                    sb.append("  [$sig] x${group.size} @$med")
                }
                sb.append('\n')
            }
        }
        return sb.toString()
    }
}
