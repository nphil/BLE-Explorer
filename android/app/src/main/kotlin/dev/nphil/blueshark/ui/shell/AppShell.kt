package dev.nphil.blueshark.ui.shell

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.window.core.layout.WindowSizeClass
import dev.nphil.blueshark.AppContainer
import dev.nphil.blueshark.R
import dev.nphil.blueshark.ui.capture.CaptureScreen
import dev.nphil.blueshark.ui.probe.ProbeScreen
import dev.nphil.blueshark.ui.project.ProjectScreen
import dev.nphil.blueshark.ui.project.ProjectsScreen
import dev.nphil.blueshark.ui.relay.RelayScreen
import dev.nphil.blueshark.ui.scan.ScanScreen
import dev.nphil.blueshark.ui.sessions.SessionsScreen
import dev.nphil.blueshark.ui.settings.SettingsScreen
import dev.nphil.blueshark.ui.signal.SignalScreen
import dev.nphil.blueshark.ui.theme.ThemeSettings

private enum class NavLayout { BAR, RAIL, DRAWER }

private fun WindowSizeClass.navLayout(): NavLayout = when {
    isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) -> NavLayout.DRAWER
    isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) -> NavLayout.RAIL
    else -> NavLayout.BAR
}

@Composable
fun AppShell(container: AppContainer, themeSettings: ThemeSettings) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val current = Destination.fromRoute(backStack?.destination?.route)
    val sizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val layout = sizeClass.navLayout()
    val expanded = layout != NavLayout.BAR
    val permissions = rememberBlePermissionState()
    val learning by container.learning.state.collectAsStateWithLifecycle()

    // The overlay's Finish button reaches MainActivity as an intent extra, which asks the
    // coordinator to close the session; the project it belongs to is where the collection has to
    // happen, so the shell routes there. The request is left set - the project screen consumes it,
    // which is what makes the navigation and the collection one action rather than two.
    LaunchedEffect(learning.finishRequestedFor) {
        val target = learning.finishRequestedFor ?: return@LaunchedEffect
        navController.navigate(projectRoute(target)) { launchSingleTop = true }
    }

    val navigate: (Destination) -> Unit = { dest ->
        navController.navigate(dest.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    when (layout) {
        NavLayout.DRAWER -> Row(Modifier.fillMaxSize()) {
            PermanentDrawerSheet(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                Brand(Modifier.padding(horizontal = 28.dp, vertical = 24.dp))
                Destination.navigation.forEach { dest ->
                    NavigationDrawerItem(
                        label = { Text(dest.label) },
                        selected = dest == current,
                        onClick = { navigate(dest) },
                        icon = { Icon(if (dest == current) dest.selectedIcon else dest.icon, contentDescription = null) },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
            Content(navController, container, themeSettings, expanded = true, permissions, Modifier.weight(1f))
        }

        NavLayout.RAIL -> Row(Modifier.fillMaxSize()) {
            NavigationRail(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                Spacer(Modifier.height(8.dp))
                Destination.navigation.forEach { dest ->
                    NavigationRailItem(
                        selected = dest == current,
                        onClick = { navigate(dest) },
                        icon = { Icon(if (dest == current) dest.selectedIcon else dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                    )
                }
            }
            Content(navController, container, themeSettings, expanded = true, permissions, Modifier.weight(1f))
        }

        NavLayout.BAR -> Scaffold(
            bottomBar = {
                NavigationBar {
                    Destination.navigation.forEach { dest ->
                        NavigationBarItem(
                            selected = dest == current,
                            onClick = { navigate(dest) },
                            icon = { Icon(if (dest == current) dest.selectedIcon else dest.icon, contentDescription = dest.label) },
                            label = { Text(dest.label) },
                        )
                    }
                }
            },
        ) { padding ->
            Content(
                navController, container, themeSettings, expanded = false, permissions,
                // The screens carry their own inset-aware Scaffold; consume what this outer one already applied.
                Modifier.padding(padding).consumeWindowInsets(padding),
            )
        }
    }
}

@Composable
private fun Brand(modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.height(44.dp),
        )
        Column {
            Text("BlueShark", style = MaterialTheme.typography.titleLarge)
            Text("Bluetooth LE for Home Assistant", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private const val SESSIONS_ARG = "sessionId"
private const val SESSIONS_ROUTE = "sessions?session={$SESSIONS_ARG}"

/** Deep link that opens the Sessions tab with [sessionId] already selected. */
private fun sessionsRoute(sessionId: String): String = "sessions?session=${Uri.encode(sessionId)}"

private const val SIGNAL_ARG = "address"
private const val SIGNAL_ROUTE = "signal?address={$SIGNAL_ARG}"

/** Deep link that opens the Signal tab already measuring [address]. */
private fun signalRoute(address: String): String = "signal?address=${Uri.encode(address)}"

private const val PROBE_ARG = "address"
private const val PROBE_ROUTE = "probe?address={$PROBE_ARG}"

/** Deep link that opens the Probe tab already targeting [address]. */
private fun probeRoute(address: String): String = "probe?address=${Uri.encode(address)}"

private const val PROJECT_ARG = "sessionId"

/**
 * The prefix is shared with [Destination.fromRoute], which maps it back onto the Devices tab; one
 * literal, so a rename cannot leave the navigation surface highlighting nothing.
 */
private const val PROJECT_ROUTE = "${Destination.PROJECT_PREFIX}{$PROJECT_ARG}"

/** The one route with a required argument: a project is meaningless without its session. */
private fun projectRoute(sessionId: String): String =
    Destination.PROJECT_PREFIX + Uri.encode(sessionId)

@Composable
private fun Content(
    navController: NavHostController,
    container: AppContainer,
    themeSettings: ThemeSettings,
    expanded: Boolean,
    permissions: PermissionState,
    modifier: Modifier,
) {
    Box(modifier.fillMaxSize()) {
        NavHost(navController, startDestination = Destination.DEVICES.route) {
            composable(Destination.DEVICES.route) {
                ProjectsScreen(
                    container = container,
                    expanded = expanded,
                    bluetoothGranted = permissions.granted,
                    requestPermissions = permissions.request,
                    onOpenProject = { sessionId ->
                        navController.navigate(projectRoute(sessionId)) { launchSingleTop = true }
                    },
                    onOpenSession = { sessionId ->
                        navController.navigate(sessionsRoute(sessionId)) { launchSingleTop = true }
                    },
                )
            }
            composable(
                route = PROJECT_ROUTE,
                arguments = listOf(navArgument(PROJECT_ARG) { type = NavType.StringType }),
            ) { entry ->
                val sessionId = entry.arguments?.getString(PROJECT_ARG)
                if (sessionId == null) {
                    navController.popBackStack()
                } else {
                    ProjectScreen(
                        container = container,
                        sessionId = sessionId,
                        expanded = expanded,
                        bluetoothGranted = permissions.granted,
                        requestPermissions = permissions.request,
                        onOpenProbe = { address ->
                            navController.navigate(probeRoute(address)) { launchSingleTop = true }
                        },
                        onOpenCapture = {
                            navController.navigate(Destination.CAPTURE.route) { launchSingleTop = true }
                        },
                        onOpenRelay = {
                            navController.navigate(Destination.RELAY.route) { launchSingleTop = true }
                        },
                        onOpenEvidence = { id ->
                            navController.navigate(sessionsRoute(id)) { launchSingleTop = true }
                        },
                    )
                }
            }
            composable(Destination.SCAN.route) {
                ScanScreen(
                    container = container,
                    expanded = expanded,
                    bluetoothGranted = permissions.granted,
                    requestPermissions = permissions.request,
                    onOpenInSession = { sessionId ->
                        navController.navigate(sessionsRoute(sessionId)) { launchSingleTop = true }
                    },
                    onSignal = { address ->
                        navController.navigate(signalRoute(address)) { launchSingleTop = true }
                    },
                    onProbe = { address ->
                        navController.navigate(probeRoute(address)) { launchSingleTop = true }
                    },
                )
            }
            composable(Destination.CAPTURE.route) {
                CaptureScreen(container, expanded, permissions.granted, permissions.request)
            }
            composable(Destination.RELAY.route) {
                RelayScreen(container, expanded, permissions.granted, permissions.request)
            }
            composable(
                route = PROBE_ROUTE,
                // Same optional-query-argument form as the sessions and signal routes: a bare
                // "probe" from the navigation bar matches, and `probe?address=<addr>` lands its
                // value in arguments.
                arguments = listOf(
                    navArgument(PROBE_ARG) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                ProbeScreen(
                    container = container,
                    expanded = expanded,
                    bluetoothGranted = permissions.granted,
                    requestPermissions = permissions.request,
                    initialAddress = entry.arguments?.getString(PROBE_ARG),
                )
            }
            composable(
                route = SIGNAL_ROUTE,
                // Same optional-query-argument form as the sessions route: a bare "signal" from the
                // navigation bar matches, and `signal?address=<addr>` lands its value in arguments.
                arguments = listOf(
                    navArgument(SIGNAL_ARG) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                SignalScreen(
                    container = container,
                    expanded = expanded,
                    bluetoothGranted = permissions.granted,
                    requestPermissions = permissions.request,
                    initialAddress = entry.arguments?.getString(SIGNAL_ARG),
                )
            }
            composable(
                route = SESSIONS_ROUTE,
                // Declared rather than inferred: this is the documented optional-query-argument
                // form, so a bare "sessions" from the navigation bar still matches while a
                // `sessions?session=<id>` deep link lands its value in the entry's arguments.
                arguments = listOf(
                    navArgument(SESSIONS_ARG) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                SessionsScreen(container, expanded, initialSessionId = entry.arguments?.getString(SESSIONS_ARG))
            }
            composable(Destination.SETTINGS.route) {
                SettingsScreen(container, themeSettings, expanded)
            }
        }
    }
}
