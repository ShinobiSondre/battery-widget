package com.batterywidget.app.data

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import com.batterywidget.app.widget.WidgetUpdater
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.IOException

/**
 * Talks to connected AirPods directly over Apple's accessory channel (AAP, Bluetooth L2CAP
 * PSM 0x1001), the same way CAPod and LibrePods do. Newer AirPods (like Pro 3) stop
 * broadcasting their battery while connected, but over this channel they report exact
 * left, right and case percentages whenever they change.
 *
 * Only one app at a time can hold this channel, so CAPod or LibrePods running alongside would
 * block it.
 */
object AapClient {
    private const val PSM = 0x1001

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var thread: Thread? = null
    @Volatile var live = false; private set

    private val HANDSHAKE = bytes(0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    private val NOTIFY_1 = bytes(0x04, 0x00, 0x04, 0x00, 0x0f, 0x00, 0xff, 0xff, 0xef, 0xff)
    private val NOTIFY_2 = bytes(0x04, 0x00, 0x04, 0x00, 0x0f, 0x00, 0xff, 0xff, 0xff, 0xff)
    private val INIT_EXT = bytes(0x04, 0x00, 0x04, 0x00, 0x4d, 0x00, 0xd7, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** Opens the channel to the connected AirPods (does nothing if it's already open). */
    @Synchronized
    fun start(ctx: Context) {
        if (thread?.isAlive == true) return
        if (!Perms.hasConnect(ctx)) return
        val app = ctx.applicationContext
        thread = Thread({ run(app) }, "aap").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        live = false
        try { socket?.close() } catch (_: Exception) { }
        socket = null
        thread?.interrupt()
        thread = null
    }

    private fun note(ctx: Context, msg: String) = Prefs.setAapNote(ctx, msg)

    private fun run(ctx: Context) {
        var attempts = 0
        while (!Thread.currentThread().isInterrupted && attempts < 4) {
            attempts++
            val dev = PodsConnector.pairedPods(ctx) ?: run { note(ctx, "no paired AirPods"); return }
            if (!PodsConnector.soundConnected(ctx)) { note(ctx, "AirPods not connected"); return }
            try {
                val s = createSocket(dev)
                socket = s
                note(ctx, "connecting…")
                s.connect()
                val out = s.outputStream
                out.write(HANDSHAKE); out.flush()
                Thread.sleep(150)
                out.write(NOTIFY_1); out.write(NOTIFY_2); out.flush()
                Thread.sleep(100)
                out.write(INIT_EXT); out.flush()
                note(ctx, "connected, waiting for battery")
                attempts = 0
                readLoop(ctx, s, dev)
            } catch (e: InterruptedException) {
                return
            } catch (e: Throwable) {
                note(ctx, "failed: ${e.javaClass.simpleName} ${e.message ?: ""}".trim())
            } finally {
                live = false
                try { socket?.close() } catch (_: Exception) { }
                socket = null
            }
            try { Thread.sleep(4_000L * attempts) } catch (_: InterruptedException) { return }
        }
    }

    private fun readLoop(ctx: Context, s: BluetoothSocket, dev: BluetoothDevice) {
        val buf = ByteArray(2048)
        val input = s.inputStream
        while (true) {
            val len = input.read(buf)
            if (len < 0) throw IOException("channel closed")
            // A message: 04 00 04 00, then the command (2 bytes, little-endian), then data.
            if (len < 7 || buf[0] != 0x04.toByte() || buf[1] != 0x00.toByte()) continue
            val command = (buf[4].toInt() and 0xFF) or ((buf[5].toInt() and 0xFF) shl 8)
            if (command == 0x0004) onBattery(ctx, buf, len, dev)
        }
    }

    /** Battery message: a count, then per part 5 bytes: [part, ?, percent, charging state, ?]. */
    private fun onBattery(ctx: Context, b: ByteArray, len: Int, dev: BluetoothDevice) {
        val count = b[6].toInt() and 0xFF
        var left = -1; var right = -1; var case = -1
        var cl = false; var cr = false; var cc = false
        var off = 7
        repeat(count) {
            if (off + 5 > len) return@repeat
            val part = b[off].toInt() and 0xFF
            val pct = b[off + 2].toInt() and 0xFF
            val state = b[off + 3].toInt() and 0xFF
            val charging = state == 0x01 || state == 0x05
            off += 5
            if (pct > 100) return@repeat // 127/255 = not available (e.g. in a closed case)
            when (part) {
                0x04 -> { left = pct; cl = charging }
                0x02 -> { right = pct; cr = charging }
                0x08 -> { case = pct; cc = charging }
                0x01 -> { left = pct; right = pct; cl = charging; cr = charging } // single (e.g. Max)
            }
        }
        if (left < 0 && right < 0 && case < 0) return
        // Keep the case value from earlier when this message doesn't include it.
        val old = Prefs.loadPods(ctx)
        if (case < 0 && old != null) { case = old.case; cc = old.chargingCase }
        val name = try { dev.name } catch (_: SecurityException) { null } ?: "AirPods"
        val model = if (name.contains("Pro", true)) "AirPods Pro" else if (name.contains("Max", true)) "AirPods Max" else "AirPods"
        val reading = PodsReading(left, right, case, cl, cr, cc, model, System.currentTimeMillis())
        live = true
        val changed = !reading.sameLevels(old)
        Prefs.savePods(ctx, reading)
        note(ctx, "live · L ${left.pct()} R ${right.pct()} case ${case.pct()}")
        if (changed) WidgetUpdater.updateAllThrottled(ctx, force = true)
    }

    private fun Int.pct() = if (this < 0) "–" else "$this%"

    private fun createSocket(dev: BluetoothDevice): BluetoothSocket {
        // Android 17+ has a public way; older versions need the hidden one (as CAPod does).
        try {
            val settingsClass = Class.forName("android.bluetooth.BluetoothSocketSettings")
            val builderClass = Class.forName("android.bluetooth.BluetoothSocketSettings\$Builder")
            val builder = builderClass.getDeclaredConstructor().newInstance()
            builderClass.getMethod("setSocketType", Int::class.javaPrimitiveType).invoke(builder, 3)
            builderClass.getMethod("setL2capPsm", Int::class.javaPrimitiveType).invoke(builder, PSM)
            builderClass.getMethod("setAuthenticationRequired", Boolean::class.javaPrimitiveType).invoke(builder, false)
            builderClass.getMethod("setEncryptionRequired", Boolean::class.javaPrimitiveType).invoke(builder, false)
            val settings = builderClass.getMethod("build").invoke(builder)
            return BluetoothDevice::class.java.getMethod("createUsingSocketSettings", settingsClass)
                .invoke(dev, settings) as BluetoothSocket
        } catch (_: Throwable) { }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) HiddenApiBypass.addHiddenApiExemptions("Landroid/bluetooth/")
        val m = BluetoothDevice::class.java.getDeclaredMethod("createInsecureL2capSocket", Int::class.javaPrimitiveType)
        return try {
            m.invoke(dev, PSM) as BluetoothSocket
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.cause ?: e
        }
    }
}
