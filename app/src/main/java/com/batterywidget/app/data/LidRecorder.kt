package com.batterywidget.app.data

/**
 * The lid test: records every Apple message for up to 60 s, plus the moments you tap
 * "Lid opened" / "Lid closed", so we can see whether anything the AirPods send changes when
 * the lid opens (a better trigger than signal strength).
 */
object LidRecorder {
    private const val MAX_MS = 60_000L

    private data class Entry(val t: Long, val rssi: Int, val sig: AppleSig)

    private val entries = mutableListOf<Entry>()
    private val marks = mutableListOf<Pair<Long, String>>()
    @Volatile var startedAt = 0L; private set
    @Volatile var active = false; private set

    @Synchronized fun start() {
        entries.clear(); marks.clear()
        startedAt = System.currentTimeMillis()
        active = true
    }

    @Synchronized fun stop() { active = false }

    @Synchronized fun mark(label: String) {
        if (active) marks += (System.currentTimeMillis() - startedAt) to label
    }

    @Synchronized fun onResult(rssi: Int, data: ByteArray) {
        if (!active) return
        val t = System.currentTimeMillis() - startedAt
        if (t > MAX_MS) { active = false; return }
        for (sig in AppleSig.all(data)) entries += Entry(t, rssi, sig)
    }

    fun secondsLeft(): Int =
        if (!active) 0 else ((MAX_MS - (System.currentTimeMillis() - startedAt)) / 1000).toInt().coerceAtLeast(0)

    /**
     * A timeline in 2-second steps: which kinds of messages came in (count and median strength),
     * with your lid taps in between. Ready to copy and paste.
     */
    @Synchronized fun summary(): String {
        if (entries.isEmpty() && marks.isEmpty()) return "Nothing recorded yet."
        val sb = StringBuilder("Lid test (${entries.size} messages)\n")
        val buckets = entries.groupBy { it.t / 2_000 }
        val lastBucket = maxOf(buckets.keys.maxOrNull() ?: 0L, (marks.maxOfOrNull { it.first } ?: 0L) / 2_000)
        for (b in 0..lastBucket) {
            for ((t, label) in marks.filter { it.first / 2_000 == b }) sb.append("--- ${label} at ${t / 1000.0}s ---\n")
            val list = buckets[b] ?: continue
            sb.append("${b * 2}-${b * 2 + 2}s:")
            for ((sig, es) in list.groupBy { it.sig }.toList().sortedBy { it.first.type }) {
                val known = es.map { it.rssi }.filter { it != 127 }.sorted()
                val med = if (known.isEmpty()) "?" else known[known.size / 2].toString()
                sb.append("  [$sig] x${es.size} @$med")
            }
            sb.append('\n')
        }
        return sb.toString()
    }
}
