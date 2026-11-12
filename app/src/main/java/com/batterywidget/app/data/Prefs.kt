package com.batterywidget.app.data

import android.content.Context

enum class WidgetStyle(val label: String) { RINGS("Rings"), BARS("Bars"), BIG("Big #") }

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("battery_widget", Context.MODE_PRIVATE)

    /** How solid the widget background is, 0 (see-through) to 100 (solid). */
    fun opacity(c: Context) = sp(c).getInt("opacity", 45)
    fun setOpacity(c: Context, v: Int) = sp(c).edit().putInt("opacity", v.coerceIn(0, 100)).apply()

    fun style(c: Context): WidgetStyle =
        runCatching { WidgetStyle.valueOf(sp(c).getString("style", null) ?: "BARS") }.getOrDefault(WidgetStyle.BARS)
    fun setStyle(c: Context, s: WidgetStyle) = sp(c).edit().putString("style", s.name).apply()

    /** Glow animations on the widget (charging / low battery). */
    fun animations(c: Context) = sp(c).getBoolean("animations", true)
    fun setAnimations(c: Context, v: Boolean) = sp(c).edit().putBoolean("animations", v).apply()
    /** Each animation on its own. */
    fun anims(c: Context) = com.batterywidget.app.widget.Renderer.Anims(
        charge = sp(c).getBoolean("anim_charge", true),
        low = sp(c).getBoolean("anim_low", true),
        near = sp(c).getBoolean("anim_near", true),
        shine = sp(c).getBoolean("animate_always", false),
        ripple = sp(c).getBoolean("anim_always_ripple", false),
        breathe = sp(c).getBoolean("anim_always_breathe", false),
    )
    fun setAnims(c: Context, a: com.batterywidget.app.widget.Renderer.Anims) = sp(c).edit()
        .putBoolean("anim_charge", a.charge).putBoolean("anim_low", a.low).putBoolean("anim_near", a.near)
        .putBoolean("animate_always", a.shine).putBoolean("anim_always_ripple", a.ripple)
        .putBoolean("anim_always_breathe", a.breathe).apply()

    fun savePods(c: Context, p: PodsReading, source: PodsSource) = sp(c).edit()
        .putString("pods_src", source.name)
        .putInt("pods_l", p.left).putInt("pods_r", p.right).putInt("pods_c", p.case)
        .putBoolean("pods_cl", p.chargingLeft).putBoolean("pods_cr", p.chargingRight)
        .putBoolean("pods_cc", p.chargingCase).putString("pods_model", p.model)
        .putLong("pods_at", p.at).apply()

    fun loadPods(c: Context): PodsReading? {
        val s = sp(c)
        val at = s.getLong("pods_at", 0L)
        if (at == 0L) return null
        return PodsReading(
            s.getInt("pods_l", -1), s.getInt("pods_r", -1), s.getInt("pods_c", -1),
            s.getBoolean("pods_cl", false), s.getBoolean("pods_cr", false), s.getBoolean("pods_cc", false),
            s.getString("pods_model", "AirPods") ?: "AirPods", at
        )
    }

    /** Where the saved AirPods reading came from (broadcast, or the AirPods' own channel). */
    fun podsSource(c: Context): PodsSource =
        runCatching { PodsSource.valueOf(sp(c).getString("pods_src", null) ?: "BROADCAST") }.getOrDefault(PodsSource.BROADCAST)

    /** Recent auto-connect and Bluetooth events, newest last, shown in the app to find problems. */
    fun events(c: Context): List<String> = (sp(c).getString("events", "") ?: "").split("\n").filter { it.isNotBlank() }
    @Synchronized
    fun addEvent(c: Context, msg: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val list = (events(c) + "$t $msg").takeLast(25)
        sp(c).edit().putString("events", list.joinToString("\n")).apply()
    }

    /** When Bluetooth scans were started lately (Android ignores more than 5 in 30 seconds). */
    fun scanStarts(c: Context): List<Long> =
        (sp(c).getString("scan_starts", "") ?: "").split(",").mapNotNull { it.toLongOrNull() }
    fun setScanStarts(c: Context, v: List<Long>) = sp(c).edit().putString("scan_starts", v.joinToString(",")).commit()

    /** When the background (system) AirPods scan was last started; 0 = needs starting. */
    fun systemScanAt(c: Context) = sp(c).getLong("sys_scan_at", 0L)
    fun setSystemScanAt(c: Context, v: Long) = sp(c).edit().putLong("sys_scan_at", v).apply()
    fun systemScanMode(c: Context) = sp(c).getInt("sys_scan_mode", -99)
    fun setSystemScanMode(c: Context, v: Int) = sp(c).edit().putInt("sys_scan_mode", v).apply()

    /** Keep listening for AirPods in the background (with a small ongoing notification). */
    fun alwaysListen(c: Context) = sp(c).getBoolean("always_listen", true)
    fun setAlwaysListen(c: Context, v: Boolean) = sp(c).edit().putBoolean("always_listen", v).apply()

    /** Last time any AirPods message was heard, and how strong it was. */
    fun lastHeard(c: Context) = sp(c).getLong("heard_at", 0L)
    fun lastRssi(c: Context) = sp(c).getInt("heard_rssi", 0)
    fun setLastHeard(c: Context, at: Long, rssi: Int) =
        sp(c).edit().putLong("heard_at", at).putInt("heard_rssi", rssi).apply()

    /** A short status line from the listener, shown in the app. */
    fun listenerNote(c: Context) = sp(c).getString("listener_note", "") ?: ""
    fun setListenerNote(c: Context, v: String) = sp(c).edit().putString("listener_note", v).apply()

    /** Battery levels Android announced for Bluetooth devices, by address. */
    fun saveDeviceBattery(c: Context, address: String, level: Int) = sp(c).edit()
        .putInt("bt_lvl_$address", level).putLong("bt_at_$address", System.currentTimeMillis()).apply()
    fun deviceBattery(c: Context, address: String): Pair<Int, Long> =
        sp(c).getInt("bt_lvl_$address", -1) to sp(c).getLong("bt_at_$address", 0L)

    /** What the listener last received, shown small in the app to help fix problems. */
    fun debug(c: Context) = sp(c).getString("debug", "") ?: ""
    fun debugAt(c: Context) = sp(c).getLong("debug_at", 0L)
    fun setDebug(c: Context, v: String) =
        sp(c).edit().putString("debug", v).putLong("debug_at", System.currentTimeMillis()).apply()

    /** Device names shown last time, to spot ones that just connected. Null before the first draw. */
    fun lastNames(c: Context): Set<String>? = sp(c).getStringSet("last_names", null)?.toSet()
    fun setLastNames(c: Context, v: Set<String>) = sp(c).edit().putStringSet("last_names", v).apply()

    // Per widget: which of its two pictures is showing, what was drawn, and whether it's set up.
    fun widgetShown(c: Context, id: Int) = sp(c).getInt("w_shown_$id", 0)
    fun setWidgetShown(c: Context, id: Int, v: Int) = sp(c).edit().putInt("w_shown_$id", v).apply()
    fun widgetSig(c: Context, id: Int) = sp(c).getString("w_sig_$id", "") ?: ""
    fun setWidgetSig(c: Context, id: Int, v: String) = sp(c).edit().putString("w_sig_$id", v).apply()
    fun widgetOffer(c: Context, id: Int) = sp(c).getBoolean("w_offer_$id", false)
    fun setWidgetOffer(c: Context, id: Int, v: Boolean) = sp(c).edit().putBoolean("w_offer_$id", v).apply()
    fun widgetDrawn(c: Context, id: Int) = sp(c).getBoolean("w_drawn_$id", false)
    fun setWidgetDrawn(c: Context, id: Int) = sp(c).edit().putBoolean("w_drawn_$id", true).apply()

    /** True while AirPods are connected (set the moment Bluetooth connects or disconnects). */
    fun podsLinked(c: Context) = sp(c).getBoolean("pods_linked", false)
    fun setPodsLinked(c: Context, v: Boolean) = sp(c).edit().putBoolean("pods_linked", v).commit()

    /** The AirPods' Bluetooth address, remembered the first time they connect. */
    fun podsAddress(c: Context) = sp(c).getString("pods_address", null)
    fun setPodsAddress(c: Context, v: String) = sp(c).edit().putString("pods_address", v).apply()

    /** Connect the AirPods by themselves when the case opens next to the phone. */
    fun autoConnect(c: Context) = sp(c).getBoolean("auto_connect", true)
    fun setAutoConnect(c: Context, v: Boolean) = sp(c).edit().putBoolean("auto_connect", v).apply()

    /** This phone doesn't let apps connect Bluetooth devices (the button opens settings instead). */
    fun connectBlocked(c: Context) = sp(c).getBoolean("connect_blocked", false)
    fun setConnectBlocked(c: Context, v: Boolean) = sp(c).edit().putBoolean("connect_blocked", v).apply()

    /** What auto-connect did last, shown in the app. */
    fun autoNote(c: Context) = sp(c).getString("auto_note", "") ?: ""
    fun setAutoNote(c: Context, v: String) = sp(c).edit().putString("auto_note", v).apply()

    /** Status of the direct AirPods channel, shown in the app. */
    fun aapNote(c: Context) = sp(c).getString("aap_note", "") ?: ""
    fun setAapNote(c: Context, v: String) = sp(c).edit().putString("aap_note", v).apply()

    fun lastWidgetDraw(c: Context) = sp(c).getLong("last_draw", 0L)
    fun setLastWidgetDraw(c: Context, t: Long) = sp(c).edit().putLong("last_draw", t).apply()
}
