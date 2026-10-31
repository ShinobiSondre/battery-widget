package com.batterywidget.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.batterywidget.app.data.AirPods
import com.batterywidget.app.ui.MainScreen
import com.batterywidget.app.update.Installer
import com.batterywidget.app.update.UpdateOverlay
import com.batterywidget.app.update.UpdateWorker
import com.batterywidget.app.update.Updater
import com.batterywidget.app.widget.WidgetUpdater

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WidgetUpdater.schedule(this)
        Updater.start(this)
        UpdateWorker.schedule(this)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF60A5FA),
                    onPrimary = Color(0xFF0B1220),
                    background = Color(0xFF0F1720),
                    surface = Color(0xFF17212C)
                )
            ) {
                Box(Modifier.fillMaxSize()) {
                    MainScreen()
                    UpdateOverlay()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Installer.appVisible = true
        Updater.onResume(this)
        AirPods.sync(this) // start the always-on AirPods listener
    }

    override fun onPause() {
        super.onPause()
        Installer.appVisible = false
        WidgetUpdater.updateAll(this)
    }
}
