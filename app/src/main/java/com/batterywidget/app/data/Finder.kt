package com.batterywidget.app.data

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.batterywidget.app.MainActivity
import com.batterywidget.app.R
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * The app's own "Find my AirPods", for AirPods no longer in Apple's Find My:
 *  - where you last had them (the phone's location when they disconnected),
 *  - a loud sound played in the AirPods,
 *  - a warmer/colder meter from their Bluetooth signal,
 *  - an optional alert when you seem to have left them behind.
 * Apple's network of other people's iPhones isn't available, so it can't find them far away.
 */
object Finder {
    private val main = Handler(Looper.getMainLooper())
    private fun sp(c: Context) = c.getSharedPreferences("finder", Context.MODE_PRIVATE)

    // ---------------- Last seen ----------------

    data class Spot(val lat: Double, val lon: Double, val accuracy: Float, val at: Long, val why: String)

    fun lastSpot(c: Context): Spot? {
        val s = sp(c)
        val at = s.getLong("at", 0L)
        if (at == 0L) return null
        return Spot(
            java.lang.Double.longBitsToDouble(s.getLong("lat", 0L)),
            java.lang.Double.longBitsToDouble(s.getLong("lon", 0L)),
            s.getFloat("acc", 0f), at, s.getString("why", "") ?: ""
        )
    }

    private fun saveSpot(c: Context, loc: Location, why: String) {
        sp(c).edit()
            .putLong("lat", java.lang.Double.doubleToRawLongBits(loc.latitude))
            .putLong("lon", java.lang.Double.doubleToRawLongBits(loc.longitude))
            .putFloat("acc", loc.accuracy)
            .putLong("at", System.currentTimeMillis())
            .putString("why", why)
            .apply()
    }

    /** Opens Google Maps (or any maps app) at the last spot. */
    fun mapIntent(spot: Spot): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("geo:${spot.lat},${spot.lon}?q=${spot.lat},${spot.lon}(AirPods)"))

    /** The phone's current location, or the best recent one if a fresh fix isn't possible. */
    @SuppressLint("MissingPermission")
    fun locate(ctx: Context, done: (Location?) -> Unit) {
        val app = ctx.applicationContext
        if (!Perms.hasLocation(app)) { done(null); return }
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val last = try {
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        } catch (_: Exception) { null }
        val provider = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lm.allProviders.contains(LocationManager.FUSED_PROVIDER) &&
                lm.isProviderEnabled(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { done(last); return }
        var answered = false
        val finish = { l: Location? -> if (!answered) { answered = true; done(l ?: last) } }
        try {
            lm.getCurrentLocation(provider, null, app.mainExecutor) { l -> finish(l) }
        } catch (_: Exception) { finish(null) }
        main.postDelayed({ finish(null) }, 20_000) // no fix in time: use the last known one
    }

    // ---------------- Connection events ----------------

    @Volatile private var disconnectedAt = 0L
    @Volatile private var heardSinceDisconnect = false

    /** The AirPods just disconnected: remember where you were. */
    fun onDisconnected(ctx: Context) {
        val app = ctx.applicationContext
        disconnectedAt = System.currentTimeMillis()
        heardSinceDisconnect = false
        locate(app) { loc ->
            if (loc != null) {
                saveSpot(app, loc, "when they disconnected")
                if (leftBehindAlerts(app)) {
                    val at = disconnectedAt
                    main.postDelayed({ checkLeftBehind(app, loc, at) }, 3 * 60_000L)
                    main.postDelayed({ checkLeftBehind(app, loc, at) }, 10 * 60_000L)
                }
            }
        }
    }

    fun onConnected(ctx: Context) {
        disconnectedAt = 0L
        sp(ctx.applicationContext).edit().putBoolean("alerted", false).apply()
    }

    // ---------------- Warmer / colder ----------------

    private val smoother = SignalSmoother(windowMs = 3_000, minSamples = 1)
    @Volatile var lastHeardAt = 0L; private set

    /** A message from the AirPods themselves (status message), with its signal strength. */
    fun onPodsHeard(rssi: Int) {
        if (rssi == 127) return
        val now = System.currentTimeMillis()
        smoother.add(now, rssi)
        lastHeardAt = now
        if (disconnectedAt != 0L) heardSinceDisconnect = true
    }

    /** The AirPods' signal over the last 3 seconds, or null if they're not being heard. */
    fun signal(): Int? = smoother.median(System.currentTimeMillis())

    // ---------------- Play sound ----------------

    @Volatile private var track: AudioTrack? = null
    @Volatile private var restoreVolume = -1

    /**
     * Connects the AirPods if needed, then plays a loud chirp in them for 6 s. [status] gets short
     * updates for the app screen.
     */
    fun playSound(ctx: Context, status: (String) -> Unit) {
        val app = ctx.applicationContext
        if (PodsConnector.soundConnected(app)) { chirp(app); status("Playing…"); return }
        status("Connecting to the AirPods…")
        PodsConnector.pressConnect(app) { }
        var waited = 0L
        fun poll() {
            if (PodsConnector.soundConnected(app)) {
                main.postDelayed({ chirp(app); status("Playing…") }, 700) // let audio settle
                return
            }
            waited += 500
            if (waited >= 9_000) { status("Couldn't reach them: they may be in the closed case or out of range."); return }
            main.postDelayed({ poll() }, 500)
        }
        poll()
    }

    fun stopSound(ctx: Context) {
        main.removeCallbacksAndMessages(STOP_TOKEN)
        try { track?.stop() } catch (_: Exception) { }
        try { track?.release() } catch (_: Exception) { }
        track = null
        if (restoreVolume >= 0) {
            val am = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            try { am.setStreamVolume(AudioManager.STREAM_MUSIC, restoreVolume, 0) } catch (_: Exception) { }
            restoreVolume = -1
        }
    }

    private val STOP_TOKEN = Any()

    private fun chirp(app: Context) {
        stopSound(app)
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            restoreVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        } catch (_: Exception) { restoreVolume = -1 }
        val rate = 44_100
        val pcm = chirpPcm(rate, seconds = 6.0)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        t.write(pcm, 0, pcm.size)
        t.play()
        track = t
        main.postAtTime({ stopSound(app) }, STOP_TOKEN, android.os.SystemClock.uptimeMillis() + 6_300)
    }

    /** Two quick high beeps, a pause, repeated: easy to hear and to follow. */
    private fun chirpPcm(rate: Int, seconds: Double): ShortArray {
        val out = ShortArray((rate * seconds).toInt())
        val pattern = listOf(2_800.0 to 0.15, 0.0 to 0.08, 3_400.0 to 0.15, 0.0 to 0.42)
        var i = 0
        while (i < out.size) {
            for ((freq, len) in pattern) {
                val n = (rate * len).toInt()
                val fade = (rate * 0.005).toInt()
                for (k in 0 until n) {
                    if (i >= out.size) break
                    if (freq > 0) {
                        val env = min(1.0, min(k, n - k).toDouble() / fade)
                        out[i] = (sin(2 * PI * freq * k / rate) * env * 0.9 * Short.MAX_VALUE).toInt().toShort()
                    }
                    i++
                }
            }
        }
        return out
    }

    // ---------------- Left-behind alert ----------------

    fun leftBehindAlerts(c: Context) = sp(c).getBoolean("left_behind", false)
    fun setLeftBehindAlerts(c: Context, v: Boolean) = sp(c).edit().putBoolean("left_behind", v).apply()

    private fun checkLeftBehind(app: Context, spot: Location, since: Long) {
        if (disconnectedAt != since) return // reconnected (or disconnected again) meanwhile
        if (PodsConnector.connected(app) || heardSinceDisconnect) return // they're with you
        if (sp(app).getBoolean("alerted", false)) return
        locate(app) { here ->
            if (here == null || disconnectedAt != since) return@locate
            val meters = here.distanceTo(spot)
            if (meters < 200) return@locate
            sp(app).edit().putBoolean("alerted", true).apply()
            notifyLeftBehind(app, meters.toInt())
        }
    }

    private fun notifyLeftBehind(app: Context, meters: Int) {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel("left_behind") == null) {
            nm.createNotificationChannel(NotificationChannel("left_behind", "Left-behind alerts", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(app, 9, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(app, "left_behind")
            .setSmallIcon(R.drawable.ic_stat_buds)
            .setContentTitle("Did you leave your AirPods behind?")
            .setContentText("They disconnected ${meters} m from here. Tap to see where.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { nm.notify(88, n) } catch (_: SecurityException) { }
    }
}
