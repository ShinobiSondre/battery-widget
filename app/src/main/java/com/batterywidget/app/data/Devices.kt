package com.batterywidget.app.data

import android.Manifest
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import androidx.core.content.ContextCompat

enum class IconKind { PHONE, EARBUDS, BUD_LEFT, BUD_RIGHT, CASE, WATCH, HEADPHONES, BLUETOOTH }

/** One line in the widget: a device (or part of one, like the AirPods case) and its battery. */
data class Item(val name: String, val level: Int, val icon: IconKind, val charging: Boolean = false)

object Perms {
    /** What to ask Android for so the app can see Bluetooth devices and AirPods. */
    val needed: Array<String>
        get() {
            val list = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                list += Manifest.permission.BLUETOOTH_CONNECT
                list += Manifest.permission.BLUETOOTH_SCAN
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) list += Manifest.permission.POST_NOTIFICATIONS
            return list.toTypedArray()
        }

    fun granted(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun hasConnect(ctx: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted(ctx, Manifest.permission.BLUETOOTH_CONNECT)

    fun hasLocation(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasScan(ctx: Context) = hasLocation(ctx) &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted(ctx, Manifest.permission.BLUETOOTH_SCAN))

    /** "Allow all the time": lets the listener keep hearing AirPods while the app is closed. */
    fun hasBackgroundLocation(ctx: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    /** The phone's Location switch. Bluetooth scans return nothing while it's off. */
    fun locationOn(ctx: Context): Boolean = try {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled
        else lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) ||
            lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
    } catch (_: Exception) { true }

    fun all(ctx: Context) = hasConnect(ctx) && hasScan(ctx)
}

object Devices {

    /** Everything to show: the phone first, then AirPods, then other Bluetooth devices. */
    fun read(ctx: Context): List<Item> {
        val out = mutableListOf(phone(ctx))
        val pods = AirPods.current(ctx)
        if (pods != null) out += pods.toItems()
        out += bluetooth(ctx, skipPods = pods != null)
        return out
    }

    private fun phone(ctx: Context): Item {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        var level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val sticky = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (level !in 0..100 && sticky != null) {
            val l = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val s = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (l >= 0 && s > 0) level = l * 100 / s
        }
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return Item("Phone", level.coerceIn(-1, 100), IconKind.PHONE, charging)
    }

    /** Connected Bluetooth devices that tell Android their battery level. */
    private fun bluetooth(ctx: Context, skipPods: Boolean): List<Item> {
        if (!Perms.hasConnect(ctx)) return emptyList()
        return try {
            val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                ?: return emptyList()
            if (!adapter.isEnabled) return emptyList()
            val now = System.currentTimeMillis()
            adapter.bondedDevices.orEmpty()
                .mapNotNull { d ->
                    // Battery Android announced recently (heard by the AirPods listener).
                    val (cached, at) = Prefs.deviceBattery(ctx, d.address)
                    val recent = cached >= 0 && now - at < 30 * 60_000L
                    val alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) d.alias else null
                    val name = alias ?: d.name ?: "Device"
                    val isPods = name.contains("pods", ignoreCase = true)
                    val connected = when (isConnected(d)) {
                        true -> true
                        false -> false
                        null -> recent || (isPods && Prefs.podsLinked(ctx) && Prefs.podsAddress(ctx) == d.address)
                    }
                    if (!connected) return@mapNotNull null
                    if (skipPods && isPods) return@mapNotNull null
                    // The battery the device itself reports to Android (what Samsung shows in
                    // Bluetooth settings); while connected, the last one it announced still counts.
                    val level = batteryLevel(d).takeIf { it >= 0 } ?: if (cached >= 0) cached else -1
                    when {
                        level >= 0 -> Item(name, level.coerceIn(0, 100), iconFor(d, name))
                        isPods -> Item(name, -1, IconKind.EARBUDS) // connected, battery not known yet
                        else -> null
                    }
                }
                .sortedBy { it.name }
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    // These two methods exist on every Android phone but aren't in the public SDK, so they're
    // looked up by name. If a phone ever removes them, the device is simply left out.
    /** For the debug line: what Android itself says about the AirPods' battery. */
    fun podsSystemBattery(ctx: Context): String {
        if (!Perms.hasConnect(ctx)) return "no Bluetooth permission"
        return try {
            val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return "Bluetooth off"
            val d = adapter.bondedDevices.orEmpty().firstOrNull { (it.name ?: "").contains("pods", true) } ?: return "no paired AirPods"
            val (cached, at) = Prefs.deviceBattery(ctx, d.address)
            val ago = if (at > 0) "${(System.currentTimeMillis() - at) / 60_000} min ago" else "never"
            "connected=${isConnected(d)}, battery=${batteryLevel(d)}, announced=$cached ($ago)"
        } catch (e: Exception) { e.javaClass.simpleName }
    }

    private fun isConnected(d: BluetoothDevice): Boolean? = try {
        d.javaClass.getMethod("isConnected").invoke(d) as Boolean
    } catch (_: Exception) { null }

    private fun batteryLevel(d: BluetoothDevice): Int = try {
        d.javaClass.getMethod("getBatteryLevel").invoke(d) as Int
    } catch (_: Exception) { -1 }

    private fun iconFor(d: BluetoothDevice, name: String): IconKind {
        val n = name.lowercase()
        if ("watch" in n || "band" in n) return IconKind.WATCH
        if ("pods" in n || "buds" in n || "earbud" in n) return IconKind.EARBUDS
        val major = try { d.bluetoothClass?.majorDeviceClass } catch (_: SecurityException) { null }
        return when (major) {
            BluetoothClass.Device.Major.WEARABLE -> IconKind.WATCH
            BluetoothClass.Device.Major.AUDIO_VIDEO -> IconKind.HEADPHONES
            BluetoothClass.Device.Major.PHONE -> IconKind.PHONE
            else -> IconKind.BLUETOOTH
        }
    }
}
