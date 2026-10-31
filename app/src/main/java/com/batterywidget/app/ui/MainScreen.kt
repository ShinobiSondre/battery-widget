package com.batterywidget.app.ui

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Build
import android.net.Uri
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.graphicsLayer
import android.provider.Settings
import android.content.ComponentName
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batterywidget.app.BuildConfig
import com.batterywidget.app.data.AirPods
import com.batterywidget.app.data.AirPodsService
import com.batterywidget.app.data.Devices
import com.batterywidget.app.data.IconKind
import com.batterywidget.app.data.Perms
import com.batterywidget.app.data.PodsConnector
import com.batterywidget.app.data.Prefs
import com.batterywidget.app.data.WidgetStyle
import com.batterywidget.app.update.Updater
import com.batterywidget.app.widget.BatteryWidgetProvider
import com.batterywidget.app.widget.Icons
import com.batterywidget.app.widget.Renderer
import com.batterywidget.app.widget.WidgetUpdater
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val Card = Color(0xFF17212C)
private val Muted = Color(0xFFB6C2CF)

@Composable
fun MainScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var opacity by remember { mutableIntStateOf(Prefs.opacity(ctx)) }
    var style by remember { mutableStateOf(Prefs.style(ctx)) }
    var items by remember { mutableStateOf(Devices.read(ctx)) }
    var hasPerms by remember { mutableStateOf(Perms.all(ctx)) }
    var locationOn by remember { mutableStateOf(Perms.locationOn(ctx)) }
    var bgLocation by remember { mutableStateOf(Perms.hasBackgroundLocation(ctx)) }
    var unrestricted by remember { mutableStateOf(batteryUnrestricted(ctx)) }
    var canConnect by remember { mutableStateOf(PodsConnector.canOffer(ctx)) }
    var autoConnect by remember { mutableStateOf(Prefs.autoConnect(ctx)) }
    var connectBlocked by remember { mutableStateOf(Prefs.connectBlocked(ctx)) }
    var autoNote by remember { mutableStateOf(Prefs.autoNote(ctx)) }
    var listen by remember { mutableStateOf(Prefs.alwaysListen(ctx)) }
    var status by remember { mutableStateOf(listenerStatus(ctx)) }
    var debug by remember { mutableStateOf(debugLine(ctx)) }
    var askedOnce by remember { mutableStateOf(false) }
    // Devices that just connected get a short spotlight, like on the widget.
    var joined by remember { mutableStateOf(emptySet<String>()) }
    var lastNames by remember { mutableStateOf(items.map { it.name }.toSet()) }
    LaunchedEffect(items) {
        val names = items.map { it.name }.toSet()
        val fresh = names - lastNames
        lastNames = names
        if (fresh.isNotEmpty()) {
            joined = fresh
            delay(1800)
            joined = emptySet()
        }
    }

    val askPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        askedOnce = true
        hasPerms = Perms.all(ctx)
        bgLocation = Perms.hasBackgroundLocation(ctx)
        AirPodsService.restart(ctx)
        AirPods.sync(ctx)
        items = Devices.read(ctx)
        WidgetUpdater.updateAll(ctx)
    }

    // Keep the list and preview live while the app is open.
    LaunchedEffect(Unit) {
        while (true) {
            items = Devices.read(ctx)
            status = listenerStatus(ctx)
            debug = debugLine(ctx)
            val nowPerms = Perms.all(ctx)
            if (nowPerms && !hasPerms) { AirPodsService.restart(ctx); AirPods.sync(ctx) }
            hasPerms = nowPerms
            locationOn = Perms.locationOn(ctx)
            val nowBg = Perms.hasBackgroundLocation(ctx)
            if (nowBg && !bgLocation) AirPodsService.restart(ctx)
            bgLocation = nowBg
            unrestricted = batteryUnrestricted(ctx)
            canConnect = PodsConnector.canOffer(ctx)
            connectBlocked = Prefs.connectBlocked(ctx)
            autoNote = Prefs.autoNote(ctx)
            delay(3000)
        }
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF0F1720)).systemBarsPadding()
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Battery Widget", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)

        // Live preview over a busy "wallpaper", so the transparency is easy to judge.
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(22.dp))
                .background(Color(0xFF1B2A3A))
                .drawBehind {
                    drawCircle(Color(0xFFE0855C), radius = size.height * 0.6f, center = Offset(size.width * 0.1f, 0f))
                    drawCircle(Color(0xFF3F8F8A), radius = size.height * 0.55f, center = Offset(size.width * 0.95f, size.height))
                    drawCircle(Color(0xFFF2C14E), radius = size.height * 0.3f, center = Offset(size.width * 0.55f, size.height * 1.05f))
                },
            contentAlignment = Alignment.Center
        ) {
            val wDp = (maxWidth.value - 32f).coerceAtMost(340f)
            val hDp = 170f
            val density = LocalDensity.current.density
            val preview = remember(items, style, opacity, wDp, joined) {
                Renderer.render(items, style, opacity, wDp, hDp, density, highlight = joined).asImageBitmap()
            }
            val glow = remember(items, style, opacity, wDp) {
                if (Renderer.anyGlow(items))
                    Renderer.render(items, style, opacity, wDp, hDp, density, Renderer.Layer.GLOW).asImageBitmap()
                else null
            }
            val breathe = rememberInfiniteTransition(label = "charging")
            val glowAlpha by breathe.animateFloat(
                initialValue = 0f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "glow"
            )
            Box(Modifier.width(wDp.dp).height(hDp.dp)) {
                Crossfade(targetState = preview, animationSpec = tween(420), label = "preview") { img ->
                    Image(img, contentDescription = "Widget preview", modifier = Modifier.fillMaxSize())
                }
                if (glow != null) {
                    Image(glow, contentDescription = null, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = glowAlpha })
                }
            }
        }

        Section {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Background", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("$opacity% solid", color = Muted, fontSize = 15.sp)
            }
            Slider(
                value = opacity.toFloat(),
                onValueChange = { opacity = (it / 5f).roundToInt() * 5 },
                onValueChangeFinished = {
                    Prefs.setOpacity(ctx, opacity)
                    WidgetUpdater.updateAll(ctx)
                },
                valueRange = 0f..100f
            )
            Row(Modifier.fillMaxWidth()) {
                Text("See-through", color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("Solid", color = Muted, fontSize = 13.sp)
            }
        }

        Section {
            Text("Style", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WidgetStyle.entries.forEach { s ->
                    val pick = {
                        style = s
                        Prefs.setStyle(ctx, s)
                        WidgetUpdater.updateAll(ctx)
                    }
                    if (s == style) {
                        Button(onClick = pick, modifier = Modifier.weight(1f).height(48.dp)) { Text(s.label) }
                    } else {
                        OutlinedButton(onClick = pick, modifier = Modifier.weight(1f).height(48.dp)) { Text(s.label, color = Color.White) }
                    }
                }
            }
        }

        if (!hasPerms) {
            Section {
                Text("Show AirPods and Bluetooth devices", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Allow Location and Nearby devices. Android only passes on AirPods battery signals to apps with location access " +
                        "(MaterialPods asks for the same). The app never reads or saves where you are.",
                    color = Muted, fontSize = 14.sp
                )
                Button(
                    onClick = {
                        // After a "Don't allow", Android stops showing the pop-up, so open the
                        // app's settings page instead.
                        if (askedOnce) {
                            ctx.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                            )
                        } else askPerms.launch(Perms.needed)
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text(if (askedOnce) "Open permission settings" else "Allow") }
                if (askedOnce) {
                    Text("Tap Permissions → Location → Allow all the time, with precise location on.", color = Muted, fontSize = 13.sp)
                }
            }
        } else if (!locationOn) {
            Section {
                Text("Turn on Location", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("Android blocks Bluetooth scans while the phone's Location switch is off.", color = Muted, fontSize = 14.sp)
                Button(
                    onClick = { ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("Open Location settings") }
            }
        } else if (!bgLocation) {
            Section {
                Text("Keep it updated in the background", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("Choose \"Allow all the time\" so AirPods battery keeps updating while the app is closed.", color = Muted, fontSize = 14.sp)
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            askPerms.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("Allow all the time") }
            }
        }

        // Put back to sleep? Bring up Android's Allow prompt right away (once each time the
        // app opens), so it's one tap instead of finding this card.
        var autoAsked by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(hasPerms, unrestricted) {
            if (hasPerms && !unrestricted && !autoAsked) {
                autoAsked = true
                delay(600)
                com.batterywidget.app.data.SleepGuard.openPrompt(ctx)
            }
        }

        AnimatedVisibility(hasPerms && !unrestricted) {
            Section {
                Text("Let it run in the background", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Samsung puts apps to sleep to save battery, which stops AirPods updates while the app is closed. " +
                        "Tap below and choose Allow (or set Battery to Unrestricted).",
                    color = Muted, fontSize = 14.sp
                )
                Button(
                    onClick = { com.batterywidget.app.data.SleepGuard.openPrompt(ctx) },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("Don't put it to sleep") }
            }
        }

        Section {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Always listen for AirPods", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("Keeps the widget's AirPods battery up to date. Shows a small silent notification.",
                        color = Muted, fontSize = 13.sp)
                }
                Switch(checked = listen, onCheckedChange = {
                    listen = it
                    Prefs.setAlwaysListen(ctx, it)
                    if (it) AirPods.sync(ctx) else AirPodsService.stop(ctx)
                    status = listenerStatus(ctx)
                })
            }
            Text(status, color = Muted, fontSize = 13.sp)
            if (debug.isNotBlank()) Text(debug, color = Muted.copy(alpha = 0.7f), fontSize = 11.sp)
        }

        Section {
            Text("Devices", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Column(Modifier.animateContentSize(tween(350))) {
                items.forEach { item ->
                    key(item.name) { DeviceRow(item, spotlight = item.name in joined) }
                }
            }
            AnimatedVisibility(canConnect) {
                OutlinedButton(
                    onClick = {
                        PodsConnector.prepare(ctx)
                        scope.launch {
                            delay(400)
                            if (!PodsConnector.connect(ctx)) ctx.startActivity(PodsConnector.bluetoothSettings())
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("Connect AirPods", color = Color.White) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-connect AirPods", color = Color.White, fontSize = 15.sp)
                    Text(
                        if (connectBlocked) "This phone doesn't let apps connect Bluetooth devices. The widget button opens Bluetooth settings instead."
                        else "Connects them by themselves when you open the case next to the phone.",
                        color = Muted, fontSize = 13.sp
                    )
                }
                Switch(checked = autoConnect, onCheckedChange = { autoConnect = it; Prefs.setAutoConnect(ctx, it) })
            }
            if (autoConnect && autoNote.isNotBlank()) {
                Text("Last auto-connect: $autoNote", color = Muted.copy(alpha = 0.7f), fontSize = 11.sp)
            }
            if (items.size == 1) {
                Text(
                    "Connect your AirPods or other Bluetooth devices and they'll show up here. AirPods appear when they're out of the case or the lid is open.",
                    color = Muted, fontSize = 13.sp
                )
            }
        }

        Button(
            onClick = {
                val mgr = AppWidgetManager.getInstance(ctx)
                if (mgr.isRequestPinAppWidgetSupported) {
                    mgr.requestPinAppWidget(ComponentName(ctx, BatteryWidgetProvider::class.java), null, null)
                } else {
                    Toast.makeText(ctx, "Long-press your home screen → Widgets → Battery Widget", Toast.LENGTH_LONG).show()
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4ADE80), contentColor = Color(0xFF0B1220))
        ) { Text("Add widget to home screen", fontWeight = FontWeight.Bold) }
        Text("Tap the widget any time to refresh it.", color = Muted, fontSize = 13.sp)

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Version ${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                scope.launch {
                    val msg = Updater.checkNow(ctx)
                    if (msg != null) Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                }
            }) { Text("Check for update") }
        }
    }
}

private fun batteryUnrestricted(ctx: android.content.Context): Boolean {
    val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
    return pm.isIgnoringBatteryOptimizations(ctx.packageName)
}

private fun debugLine(ctx: android.content.Context): String {
    val at = Prefs.debugAt(ctx)
    val heard = if (at == 0L) {
        if (AirPodsService.running) "Debug: no Apple Bluetooth signals received yet" else ""
    } else {
        val secs = (System.currentTimeMillis() - at) / 1000
        "Direct: ${Prefs.aapNote(ctx).ifBlank { "not started" }}\nAndroid: ${Devices.podsSystemBattery(ctx)}\nDebug (${secs}s ago): ${Prefs.debug(ctx)}"
    }
    // What happened lately (lid, connects, scans), newest at the bottom.
    val events = Prefs.events(ctx)
    return if (events.isEmpty()) heard else "$heard\n\nRecent events:\n${events.joinToString("\n")}"
}

private fun listenerStatus(ctx: android.content.Context): String {
    if (!Prefs.alwaysListen(ctx)) return "Off"
    if (!Perms.all(ctx)) return "Tap Allow above first"
    if (!Perms.locationOn(ctx)) return "Turn on Location above first"
    val heard = Prefs.lastHeard(ctx)
    val note = Prefs.listenerNote(ctx)
    val on = if (AirPodsService.running) note.ifBlank { "Listening" } else "Not running"
    return if (heard > 0L) {
        val secs = (System.currentTimeMillis() - heard) / 1000
        val ago = when { secs < 60 -> "${secs}s"; secs < 3600 -> "${secs / 60} min"; else -> "${secs / 3600} h" }
        "$on · AirPods last heard $ago ago (signal ${Prefs.lastRssi(ctx)})"
    } else if (AirPodsService.running) "$on · not heard yet. Take the AirPods out or open the case lid near the phone."
    else "$on · ${note.ifBlank { "open the app to start it" }}"
}

/** One device in the list: slides in when it connects, its number counts up or down smoothly,
 *  and a small bolt pulses while it charges. */
@Composable
private fun DeviceRow(item: com.batterywidget.app.data.Item, spotlight: Boolean) {
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    val level by animateIntAsState(item.level.coerceAtLeast(0), tween(600), label = "level")
    val color by animateColorAsState(Color(Renderer.levelColor(item)), tween(400), label = "color")
    val bg by animateColorAsState(if (spotlight) Color.White.copy(alpha = 0.10f) else Color.Transparent, tween(500), label = "spot")
    val pulse = rememberInfiniteTransition(label = "bolt")
    val boltAlpha by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a")
    AnimatedVisibility(
        visibleState = shown,
        enter = fadeIn(tween(400)) + expandVertically(tween(400)) + slideInHorizontally(tween(400)) { it / 6 }
    ) {
        Row(
            Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp)).background(bg).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DeviceIcon(item.icon)
            Spacer(Modifier.width(12.dp))
            Text(item.name, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
            // Charging, low and nearing-low numbers pulse, in the same colour as on the widget.
            val pulsing = Renderer.glowColor(item) != null
            Text(
                if (item.level < 0) "–" else "$level%", color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.graphicsLayer { alpha = if (pulsing) 0.55f + 0.45f * boltAlpha else 1f }
            )
            AnimatedVisibility(item.charging, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                Text("  ⚡", color = Color(0xFFFB923C), fontSize = 15.sp, modifier = Modifier.graphicsLayer { alpha = boltAlpha })
            }
        }
    }
}

@Composable
private fun Section(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

@Composable
private fun DeviceIcon(kind: IconKind) {
    val px = (24 * LocalDensity.current.density).roundToInt()
    val img = remember(kind, px) { Icons.bitmap(kind, px, android.graphics.Color.WHITE).asImageBitmap() }
    Image(img, contentDescription = null, modifier = Modifier.size(24.dp))
}
