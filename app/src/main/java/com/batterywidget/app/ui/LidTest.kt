package com.batterywidget.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batterywidget.app.data.LidRecorder
import kotlinx.coroutines.delay

/** The lid test card: record what the AirPods send while you open and close the lid. */
@Composable
fun ColumnScope.LidTest() {
    val ctx = LocalContext.current
    var recording by remember { mutableStateOf(LidRecorder.active) }
    var left by remember { mutableIntStateOf(LidRecorder.secondsLeft()) }
    var result by remember { mutableStateOf("") }

    LaunchedEffect(recording) {
        while (recording) {
            left = LidRecorder.secondsLeft()
            if (!LidRecorder.active) { recording = false; result = LidRecorder.summary() }
            delay(500)
        }
    }

    Text("Lid test", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    if (!recording) {
        Text(
            "Disconnect the AirPods first. Tap Start, then open and close the lid next to the phone a few " +
                "times, tapping \"Lid opened\" / \"Lid closed\" each time. Then tap Copy and paste it to Claude.",
            color = Color(0xFFB6C2CF), fontSize = 13.sp
        )
        Button(
            onClick = { LidRecorder.start(); result = ""; recording = true },
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) { Text("Start (60 s)") }
    } else {
        Text("Recording… ${left}s left", color = Color(0xFFFB923C), fontSize = 14.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { LidRecorder.mark("Lid opened") }, modifier = Modifier.weight(1f).height(56.dp)) { Text("Lid opened") }
            Button(onClick = { LidRecorder.mark("Lid closed") }, modifier = Modifier.weight(1f).height(56.dp)) { Text("Lid closed") }
        }
        OutlinedButton(
            onClick = { LidRecorder.stop(); recording = false; result = LidRecorder.summary() },
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) { Text("Stop", color = Color.White) }
    }
    if (result.isNotBlank()) {
        OutlinedButton(
            onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Lid test", result))
                Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) { Text("Copy results", color = Color.White) }
        Text(result, color = Color(0xFFB6C2CF), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}
