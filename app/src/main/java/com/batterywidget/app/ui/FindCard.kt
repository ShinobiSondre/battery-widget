package com.batterywidget.app.ui

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batterywidget.app.data.Finder
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

private val Soft = Color(0xFFB6C2CF)

/** "Find my AirPods": last seen on a map, play a sound, warmer/colder, left-behind alerts. */
@Composable
fun ColumnScope.FindCard() {
    val ctx = LocalContext.current
    var spot by remember { mutableStateOf(Finder.lastSpot(ctx)) }
    var soundStatus by remember { mutableStateOf("") }
    var finding by remember { mutableStateOf(false) }
    var signal by remember { mutableStateOf<Int?>(null) }
    var trend by remember { mutableStateOf("") }
    var alerts by remember { mutableStateOf(Finder.leftBehindAlerts(ctx)) }

    LaunchedEffect(Unit) {
        while (true) { spot = Finder.lastSpot(ctx); delay(5_000) }
    }
    LaunchedEffect(finding) {
        var previous: Int? = null
        while (finding) {
            val s = Finder.signal()
            trend = when {
                s == null -> "Not hearing them. Open the lid, or they may be in the closed case."
                previous == null -> "Walk around slowly…"
                s >= previous + 3 -> "Warmer"
                s <= previous - 3 -> "Colder"
                else -> "Same"
            }
            if (s != null) previous = s
            signal = s
            delay(1_000)
        }
    }

    Text("Find my AirPods", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)

    // Last seen
    val s = spot
    if (s == null) {
        Text("No location yet. It's saved every time the AirPods disconnect.", color = Soft, fontSize = 13.sp)
    } else {
        val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.at))
        Text("Last with you: $time (${s.why}), within about ${s.accuracy.toInt()} m",
            color = Soft, fontSize = 13.sp)
        OutlinedButton(
            onClick = {
                try { ctx.startActivity(Finder.mapIntent(s)) }
                catch (_: Exception) { Toast.makeText(ctx, "No maps app found", Toast.LENGTH_SHORT).show() }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) { Text("Show on map", color = Color.White) }
    }

    // Play sound
    Text("Take them out of your ears first: the sound is loud.", color = Soft, fontSize = 13.sp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { Finder.playSound(ctx) { soundStatus = it } },
            modifier = Modifier.weight(1f).height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFB923C), contentColor = Color(0xFF0B1220))
        ) { Text("Play sound") }
        OutlinedButton(
            onClick = { Finder.stopSound(ctx); soundStatus = "" },
            modifier = Modifier.weight(1f).height(48.dp)
        ) { Text("Stop", color = Color.White) }
    }
    if (soundStatus.isNotBlank()) Text(soundStatus, color = Soft, fontSize = 13.sp)

    // Warmer / colder
    OutlinedButton(
        onClick = { finding = !finding },
        modifier = Modifier.fillMaxWidth().height(48.dp)
    ) { Text(if (finding) "Stop searching" else "Search nearby (warmer / colder)", color = Color.White) }
    if (finding) {
        val sig = signal
        // -95 dBm (far) .. -40 dBm (right here) mapped to 0..1
        val target = if (sig == null) 0f else ((sig + 95) / 55f).coerceIn(0f, 1f)
        val level by animateFloatAsState(target, tween(600), label = "near")
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LinearProgressIndicator(
                progress = { level },
                modifier = Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)),
                color = if (level > 0.7f) Color(0xFF4ADE80) else if (level > 0.4f) Color(0xFFFBBF24) else Color(0xFFF87171),
                trackColor = Color.White.copy(alpha = 0.12f)
            )
            Text(
                (if (sig != null) "$trend · $sig dBm" else trend),
                color = Color.White, fontSize = 14.sp
            )
        }
    }

    // Left-behind alerts
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Left-behind alert", color = Color.White, fontSize = 15.sp)
            Text("Notify me if they disconnect and I've moved 200 m away without them.", color = Soft, fontSize = 13.sp)
        }
        Switch(checked = alerts, onCheckedChange = { alerts = it; Finder.setLeftBehindAlerts(ctx, it) })
    }
}
