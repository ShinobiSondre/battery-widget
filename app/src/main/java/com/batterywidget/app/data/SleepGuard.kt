package com.batterywidget.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.batterywidget.app.R

/**
 * Samsung can put the app back to sleep (after updates or when unused), which stops auto-connect.
 * Android never lets an app allow itself; only the person can tap Allow. So the moment the app
 * notices it's restricted, it brings up Android's Allow prompt (when the app is open) or a
 * notification that opens it with one tap (when it isn't).
 */
object SleepGuard {
    private const val CHANNEL = "keep_awake"
    private const val NOTE_ID = 77

    fun unrestricted(ctx: Context): Boolean =
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

    /** Android's "Let app always run in background?" prompt for this app. */
    fun promptIntent(ctx: Context) =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))

    /** Opens the prompt (falls back to the app's settings page). */
    fun openPrompt(ctx: Context) {
        try { ctx.startActivity(promptIntent(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) { }
        }
    }

    /** Called regularly from the background: shows or clears the one-tap notification. */
    fun check(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (unrestricted(ctx)) { nm.cancel(NOTE_ID); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Keep running", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Tells you when the phone has put the app to sleep"
                }
            )
        }
        val tap = PendingIntent.getActivity(ctx, 77, promptIntent(ctx), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_buds)
            .setContentTitle("The phone put Battery Widget to sleep")
            .setContentText("Tap, then Allow, so AirPods keep auto-connecting.")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        try { nm.notify(NOTE_ID, n) } catch (_: SecurityException) { }
    }
}
