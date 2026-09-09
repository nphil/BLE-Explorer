package dev.nphil.blueshark.ui.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.CompareArrows
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.vector.ImageVector

enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    SCAN("scan", "Scan", Icons.Outlined.Bluetooth, Icons.Filled.Bluetooth),
    CAPTURE("capture", "Capture", Icons.Outlined.RadioButtonChecked, Icons.Filled.RadioButtonChecked),
    RELAY("relay", "Relay", Icons.Outlined.CompareArrows, Icons.Filled.CompareArrows),
    SIGNAL("signal", "Signal", Icons.Outlined.Radar, Icons.Filled.Radar),
    SESSIONS("sessions", "Sessions", Icons.Outlined.Folder, Icons.Filled.Folder),
    SETTINGS("settings", "Settings", Icons.Outlined.Tune, Icons.Filled.Tune),
    ;

    companion object {
        fun fromRoute(route: String?): Destination = entries.firstOrNull { route?.startsWith(it.route) == true } ?: SCAN
    }
}
