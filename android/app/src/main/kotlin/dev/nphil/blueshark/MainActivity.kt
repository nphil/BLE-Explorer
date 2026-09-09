package dev.nphil.blueshark

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
import dev.nphil.blueshark.ui.shell.AppShell
import dev.nphil.blueshark.ui.theme.BlueSharkTheme
import dev.nphil.blueshark.ui.theme.ThemeSettings

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as BlueSharkApp).container
        setContent {
            val settings by container.themes.settings.collectAsStateWithLifecycle(ThemeSettings())
            BlueSharkTheme(settings) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppShell(container, settings)
                }
            }
        }
    }
}
