package dev.nphil.blueshark.ui.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Every reachable top-level route, and which of them the navigation surface offers.
 *
 * [inNavigation] is the whole point of this enum having eight members and the bar having five.
 * Capture, Probe and Sessions are no longer destinations an operator chooses out of the blue -
 * they are places a device project sends you: "Advanced capture" from the Learn stage, the full
 * probe page from the Try-known-commands stage, "Evidence & timeline" from Export. Their routes
 * stay so those links, and every existing deep link, keep working; only their tabs are gone,
 * because a front door with seven equal doors is not a front door.
 */
enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    val inNavigation: Boolean = true,
) {
    DEVICES("devices", "Devices", Icons.Outlined.Devices, Icons.Filled.Devices),
    SCAN("scan", "Scan", Icons.Outlined.Bluetooth, Icons.Filled.Bluetooth),
    SIGNAL("signal", "Signal", Icons.Outlined.Radar, Icons.Filled.Radar),
    RELAY(
        "relay",
        "Relay",
        Icons.AutoMirrored.Outlined.CompareArrows,
        Icons.AutoMirrored.Filled.CompareArrows,
    ),
    SETTINGS("settings", "Settings", Icons.Outlined.Tune, Icons.Filled.Tune),

    CAPTURE(
        "capture",
        "Capture",
        Icons.Outlined.RadioButtonChecked,
        Icons.Filled.RadioButtonChecked,
        inNavigation = false,
    ),
    PROBE("probe", "Probe", Icons.Outlined.Science, Icons.Filled.Science, inNavigation = false),
    SESSIONS("sessions", "Sessions", Icons.Outlined.Folder, Icons.Filled.Folder, inNavigation = false),
    ;

    companion object {
        /** The navigation bar, rail and drawer all show exactly these, in this order. */
        val navigation: List<Destination> = entries.filter { it.inNavigation }

        /**
         * A project's own route (`project/<id>`) resolves to [DEVICES]: it is reached from that
         * tab and going back lands there, so highlighting anything else - or nothing - would lie
         * about where the operator is.
         */
        fun fromRoute(route: String?): Destination = when {
            route == null -> DEVICES
            route.startsWith(PROJECT_PREFIX) -> DEVICES
            else -> entries.firstOrNull { route.startsWith(it.route) } ?: DEVICES
        }

        internal const val PROJECT_PREFIX = "project/"
    }
}
