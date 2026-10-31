package com.batterywidget.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
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
import com.batterywidget.app.data.Devices
import com.batterywidget.app.data.Prefs
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

class BatteryWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        WidgetUpdater.schedule(ctx)
        WidgetUpdater.updateAll(ctx)
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, opts: Bundle) {
        WidgetUpdater.updateAll(ctx) // resized
    }

    override fun onEnabled(ctx: Context) = WidgetUpdater.schedule(ctx)

    override fun onDisabled(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(WidgetUpdater.PERIODIC)
        AirPods.stop(ctx)
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

/** Redraws the widget when a Bluetooth device connects or disconnects. */
class BluetoothEventReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        AirPods.sync(ctx, force = intent.action == "android.bluetooth.device.action.ACL_CONNECTED")
        WidgetUpdater.updateAll(ctx)
        WidgetUpdater.refreshSoon(ctx) // battery levels are reported a little after connecting
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

    /** Redraws at most every 15 seconds, for the frequent AirPods readings. */
    fun updateAllThrottled(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - Prefs.lastWidgetDraw(ctx) < 15_000) return
        updateAll(ctx)
    }

    fun updateAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, BatteryWidgetProvider::class.java))
        if (ids.isEmpty()) return
        Prefs.setLastWidgetDraw(ctx, System.currentTimeMillis())
        val items = Devices.read(ctx)
        val style = Prefs.style(ctx)
        val opacity = Prefs.opacity(ctx)
        val density = ctx.resources.displayMetrics.density
        for (id in ids) {
            val o = mgr.getAppWidgetOptions(id)
            // Portrait size: the narrowest width and the tallest height.
            val wDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 250
            val hDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 160
            // Keep the picture small enough for the launcher to accept.
            val scale = min(density, 900f / max(wDp, hDp))
            val bmp = Renderer.render(items, style, opacity, wDp.toFloat(), hDp.toFloat(), scale)
            val views = RemoteViews(ctx.packageName, R.layout.widget_battery)
            views.setImageViewBitmap(R.id.widget_image, bmp)
            views.setOnClickPendingIntent(R.id.widget_image, refreshIntent(ctx))
            mgr.updateAppWidget(id, views)
        }
    }

    private fun refreshIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 1,
        Intent(ctx, BatteryWidgetProvider::class.java).setAction(ACTION_REFRESH),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
