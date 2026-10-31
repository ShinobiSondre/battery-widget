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

    fun savePods(c: Context, p: PodsReading) = sp(c).edit()
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

    fun lastWidgetDraw(c: Context) = sp(c).getLong("last_draw", 0L)
    fun setLastWidgetDraw(c: Context, t: Long) = sp(c).edit().putLong("last_draw", t).apply()
}
