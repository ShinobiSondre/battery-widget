package com.batterywidget.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.batterywidget.app.R
import com.batterywidget.app.data.AirPods
import com.batterywidget.app.data.ConnectReceiver
import com.batterywidget.app.data.PodsConnector
import com.batterywidget.app.data.Devices
import com.batterywidget.app.data.Item
import com.batterywidget.app.data.Prefs
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

class BatteryWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        WidgetUpdater.schedule(ctx)
        WidgetUpdater.updateAll(ctx, full = true)
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, opts: Bundle) {
        WidgetUpdater.updateAll(ctx, full = true) // resized
    }

    override fun onEnabled(ctx: Context) = WidgetUpdater.schedule(ctx)

    override fun onDisabled(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(WidgetUpdater.PERIODIC)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == WidgetUpdater.ACTION_REFRESH) {
            AirPods.sync(ctx)
            WidgetUpdater.updateAll(ctx)
            WidgetUpdater.refreshSoon(ctx) // AirPods readings arrive a few seconds later
            return
        }
        super.onReceive(ctx, intent)
    }
}

/** Redraws the widget when a Bluetooth device connects or disconnects, and restarts the
 *  AirPods listener after the phone restarts or the app updates. */
class BluetoothEventReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        // Stay alive a moment so a newly connected device's spotlight can fade out.
        val pending = goAsync()
        AirPods.onConnectionChange(ctx, intent)
        AirPods.sync(ctx)
        val fresh = intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        WidgetUpdater.updateAll(ctx, full = fresh)
        WidgetUpdater.refreshSoon(ctx) // battery levels are reported a little after connecting
        Handler(Looper.getMainLooper()).postDelayed({ pending.finish() }, 2500)
    }
}

class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        AirPods.sync(applicationContext)
        WidgetUpdater.updateAll(applicationContext)
        return Result.success()
    }
}

object WidgetUpdater {
    const val ACTION_REFRESH = "com.batterywidget.app.REFRESH"
    const val PERIODIC = "battery-refresh"
    private const val SOON = "battery-refresh-soon"
    private const val SPOTLIGHT_MS = 1800L

    private val main = Handler(Looper.getMainLooper())

    fun schedule(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES).build()
        )
    }

    fun refreshSoon(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            SOON, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<RefreshWorker>().setInitialDelay(20, TimeUnit.SECONDS).build()
        )
    }

    @Volatile private var trailing = false

    /**
     * Redraws now, or, if it just redrew, once more 2 seconds later, so a change is never lost
     * and the widget isn't redrawn many times a second.
     */
    fun updateAllThrottled(ctx: Context, force: Boolean = false) {
        val app = ctx.applicationContext
        val since = System.currentTimeMillis() - Prefs.lastWidgetDraw(app)
        if (force || since >= 2_000) { updateAll(app); return }
        if (trailing) return
        trailing = true
        main.postDelayed({ trailing = false; updateAll(app) }, (2_000 - since).coerceAtLeast(100))
    }

    /**
     * Redraws every widget. A device that wasn't there last time gets a soft spotlight that
     * fades away after a moment. [full] rebuilds the widget from scratch (no animation).
     */
    @Synchronized
    fun updateAll(ctx: Context, full: Boolean = false) {
        val app = ctx.applicationContext
        val mgr = AppWidgetManager.getInstance(app)
        val ids = mgr.getAppWidgetIds(ComponentName(app, BatteryWidgetProvider::class.java))
        val items = Devices.read(app)
        val names = items.map { it.name }.toSet()
        val last = Prefs.lastNames(app)
        val joined = if (last == null) emptySet() else names - last
        Prefs.setLastNames(app, names)
        if (ids.isEmpty()) return
        Prefs.setLastWidgetDraw(app, System.currentTimeMillis())
        draw(app, mgr, ids, items, joined, full)
        if (joined.isNotEmpty()) {
            main.postDelayed({ redrawPlain(app) }, SPOTLIGHT_MS)
        }
    }

    @Synchronized
    private fun redrawPlain(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, BatteryWidgetProvider::class.java))
        if (ids.isNotEmpty()) draw(ctx, mgr, ids, Devices.read(ctx), emptySet(), false)
    }

    private fun draw(ctx: Context, mgr: AppWidgetManager, ids: IntArray, items: List<Item>, highlight: Set<String>, full: Boolean) {
        val style = Prefs.style(ctx)
        val opacity = Prefs.opacity(ctx)
        val density = ctx.resources.displayMetrics.density
        val charging = Renderer.anyGlow(items)
        val offerConnect = PodsConnector.canOffer(ctx)
        for (id in ids) {
            val o = mgr.getAppWidgetOptions(id)
            // Portrait size: the narrowest width and the tallest height.
            val wDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 250
            val hDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 160
            // Nothing changed? Then don't redraw (and don't play a crossfade for nothing).
            val sig = "${items.joinToString { "${it.name}:${it.level}:${it.charging}" }}|$style|$opacity|$wDp|$hDp|$highlight|$offerConnect"
            val firstTime = !Prefs.widgetDrawn(ctx, id)
            if (!full && !firstTime && sig == Prefs.widgetSig(ctx, id)) continue

            // The connect button goes away first; the new picture (e.g. the AirPods appearing)
            // crossfades in a moment later, so the two don't happen on top of each other.
            if (!offerConnect && Prefs.widgetOffer(ctx, id) && !full && !firstTime) {
                val hide = RemoteViews(ctx.packageName, R.layout.widget_battery)
                hide.setViewVisibility(R.id.widget_connect, View.GONE)
                mgr.partiallyUpdateAppWidget(id, hide)
                Prefs.setWidgetOffer(ctx, id, false)
                main.postDelayed({ draw(ctx, mgr, intArrayOf(id), items, highlight, false) }, 250)
                continue
            }
            Prefs.setWidgetOffer(ctx, id, offerConnect)
            Prefs.setWidgetSig(ctx, id, sig)

            // Keep the pictures small enough for the launcher to accept.
            val scale = min(density, 800f / max(wDp, hDp))
            val bmp = Renderer.render(items, style, opacity, wDp.toFloat(), hDp.toFloat(), scale, highlight = highlight)
            val glow = if (charging) {
                Renderer.render(items, style, opacity, wDp.toFloat(), hDp.toFloat(), scale, Renderer.Layer.GLOW)
            } else null

            val views = RemoteViews(ctx.packageName, R.layout.widget_battery)
            views.setViewVisibility(R.id.widget_connect, if (offerConnect) View.VISIBLE else View.GONE)
            if (offerConnect) views.setOnClickPendingIntent(R.id.widget_connect, connectIntent(ctx))
            if (glow != null) {
                views.setImageViewBitmap(R.id.widget_glow_on, glow)
                views.setViewVisibility(R.id.widget_glow, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_glow, View.GONE)
            }

            if (full || firstTime) {
                // Fresh start: show the picture directly.
                views.setImageViewBitmap(R.id.widget_img0, bmp)
                views.setDisplayedChild(R.id.widget_content, 0)
                views.setOnClickPendingIntent(R.id.widget_root, refreshIntent(ctx))
                mgr.updateAppWidget(id, views)
                Prefs.setWidgetShown(ctx, id, 0)
                Prefs.setWidgetDrawn(ctx, id)
            } else {
                // Put the new picture on the hidden side and flip to it: the launcher crossfades.
                val next = 1 - Prefs.widgetShown(ctx, id)
                views.setImageViewBitmap(if (next == 0) R.id.widget_img0 else R.id.widget_img1, bmp)
                views.setDisplayedChild(R.id.widget_content, next)
                mgr.partiallyUpdateAppWidget(id, views)
                Prefs.setWidgetShown(ctx, id, next)
            }
        }
    }

    /** Connects the AirPods, or opens Bluetooth settings on phones that don't allow that. */
    private fun connectIntent(ctx: Context): PendingIntent =
        if (Prefs.connectBlocked(ctx)) PendingIntent.getActivity(
            ctx, 5, PodsConnector.bluetoothSettings(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ) else PendingIntent.getBroadcast(
            ctx, 6, Intent(ctx, ConnectReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun refreshIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 1,
        Intent(ctx, BatteryWidgetProvider::class.java).setAction(ACTION_REFRESH),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
