package com.batterywidget.app.ui

import android.appwidget.AppWidgetManager
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
    var listen by remember { mutableStateOf(Prefs.alwaysListen(ctx)) }
    var status by remember { mutableStateOf(listenerStatus(ctx)) }
    var debug by remember { mutableStateOf(debugLine(ctx)) }

    val askPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPerms = Perms.all(ctx)
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
            val preview = remember(items, style, opacity, wDp) {
                Renderer.render(items, style, opacity, wDp, hDp, density).asImageBitmap()
            }
            Image(preview, contentDescription = "Widget preview", modifier = Modifier.width(wDp.dp).height(hDp.dp))
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
                    "Allow \"Nearby devices\" so the widget can read the battery of your AirPods, watch and other Bluetooth gear.",
                    color = Muted, fontSize = 14.sp
                )
                Button(onClick = { askPerms.launch(Perms.needed) }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Allow") }
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
            items.forEach { item ->
                Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                    DeviceIcon(item.icon)
                    Spacer(Modifier.width(12.dp))
                    Text(item.name, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text(
                        (if (item.level < 0) "–" else "${item.level}%") + (if (item.charging) "  ⚡" else ""),
                        color = Color(Renderer.levelColor(item)), fontSize = 15.sp, fontWeight = FontWeight.Bold
                    )
                }
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

private fun debugLine(ctx: android.content.Context): String {
    val at = Prefs.debugAt(ctx)
    if (at == 0L) return if (AirPodsService.running) "Debug: no Apple Bluetooth signals received yet" else ""
    val secs = (System.currentTimeMillis() - at) / 1000
    return "Debug (${secs}s ago): ${Prefs.debug(ctx)}"
}

private fun listenerStatus(ctx: android.content.Context): String {
    if (!Prefs.alwaysListen(ctx)) return "Off"
    if (!Perms.all(ctx)) return "Tap Allow above first"
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
