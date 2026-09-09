package dev.nphil.blestudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nphil.blestudio.ui.shell.AppShell
import dev.nphil.blestudio.ui.theme.BleStudioTheme
import dev.nphil.blestudio.ui.theme.ThemeSettings

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as BleStudioApp).container
        setContent {
            val settings by container.themes.settings.collectAsStateWithLifecycle(ThemeSettings())
            BleStudioTheme(settings) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppShell(container, settings)
                }
            }
        }
    }
}
