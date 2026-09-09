package dev.nphil.blueshark.ui.project

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.nphil.blueshark.AppContainer
import dev.nphil.blueshark.ui.scan.Pill
import dev.nphil.blueshark.ui.scan.RssiChip
import dev.nphil.blueshark.ui.signal.SectionTitle
import dev.nphil.blueshark.ui.theme.MonoFamily

/**
 * The front door: one row per device project, and one button that starts a new one.
 *
 * A project is a [dev.nphil.blueshark.model.CaptureSession] whose device has an address, so this
 * list is the session catalogue seen from the operator's side of the problem: not "captures I have
 * taken" but "devices I am working out". Sessions that never named a device - a bare HCI capture,
 * an imported bundle - are grouped underneath and open in the evidence screen, because folding the
 * Sessions tab into this one must not hide anything that is already on disk.
 */
@Composable
fun ProjectsScreen(
    container: AppContainer,
    expanded: Boolean,
    bluetoothGranted: Boolean,
    requestPermissions: () -> Unit,
    onOpenProject: (String) -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val model: ProjectsViewModel = viewModel(factory = remember(container) { ProjectsViewModel.factory(container) })
    val ui by model.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LifecycleResumeEffect(model) {
        // Another screen may have appended traffic, probes or a command map while this was hidden.
        model.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(model) {
        model.messages.collect { message -> snackbar.showSnackbar(message) }
    }
    LaunchedEffect(model) {
        model.opened.collect(onOpenProject)
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        if (ui.picking) {
            DevicePicker(
                ui = ui,
                model = model,
                bluetoothGranted = bluetoothGranted,
                requestPermissions = requestPermissions,
                modifier = Modifier.padding(padding),
            )
        } else {
            ProjectList(ui, model, expanded, onOpenProject, onOpenSession, Modifier.padding(padding))
        }
    }
}

@Composable
private fun ProjectList(
    ui: ProjectsUiState,
    model: ProjectsViewModel,
    expanded: Boolean,
    onOpenProject: (String) -> Unit,
    onOpenSession: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "intro") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Devices", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "One project per gadget you are working out. Each one starts with what its " +
                        "advertisement already gives away and escalates only as far as it has to.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = model::startPicking,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("New project", Modifier.padding(start = 8.dp))
                }
            }
        }

        if (ui.loading) {
            item(key = "loading") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Text("Reading what is on disk…", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (!ui.loading && ui.projects.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = "No projects yet. Pick a device and BlueShark will tell you what it probably is " +
                        "before you connect to it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(ui.projects, key = { it.id }) { project ->
            ProjectCard(project, expanded) { onOpenProject(project.id) }
        }

        if (ui.loose.isNotEmpty()) {
            item(key = "loose-title") {
                SectionTitle(
                    text = "Sessions with no device",
                    trailing = "${ui.loose.size}",
                )
            }
            item(key = "loose-note") {
                Text(
                    text = "Captures and imports that never named a device. They open in the evidence " +
                        "screen, where the timeline, compare and decrypt tools live.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(ui.loose, key = { "loose-${it.id}" }) { session ->
                LooseSessionRow(session) { onOpenSession(session.id) }
            }
        }
    }
}

@Composable
private fun ProjectCard(project: ProjectRow, expanded: Boolean, onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = project.deviceName ?: project.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = project.address,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = MonoFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (project.familyName != null) {
                    Pill(
                        text = if (expanded) {
                            "${project.familyName} · ${confidenceWord(project.familyConfidence)}"
                        } else {
                            project.familyName
                        },
                        container = MaterialTheme.colorScheme.primaryContainer,
                        content = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            ProgressChips(
                identified = project.identified,
                probedFrames = project.probedFrames,
                commandCount = project.commandCount,
                testedCount = project.testedCount,
                exported = project.exported,
            )
            if (project.learningOpen) {
                Text(
                    text = "A learning session is still open on this project — finish it to collect and merge.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun LooseSessionRow(session: ProjectRow, onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(session.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = "${session.eventCount} events",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Filled.Devices, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- picker -------------------------------------------------------------------------------

/**
 * The same device picker the Signal and Probe screens use: one shared scan, filtered by a query.
 *
 * Nothing else in the process registers a second discovery - Android throttles scan starts to five
 * per thirty seconds, and a picker that competes with the Scan tab for the radio simply shows less.
 */
@Composable
private fun DevicePicker(
    ui: ProjectsUiState,
    model: ProjectsViewModel,
    bluetoothGranted: Boolean,
    requestPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "intro") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Pick the device", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "The project starts with this advertisement: its name, service uuids and " +
                        "manufacturer bytes are all the fingerprint needs to guess what it is.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!bluetoothGranted) {
            item(key = "gate") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Bluetooth, contentDescription = null)
                        Text("Nearby devices access is needed to hear advertisements.")
                    }
                    Button(onClick = requestPermissions, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
                        Text("Grant permissions")
                    }
                    TextButton(onClick = model::cancelPicking) { Text("Back to projects") }
                }
            }
            return@LazyColumn
        }
        item(key = "search") {
            OutlinedTextField(
                value = ui.query,
                onValueChange = model::setQuery,
                label = { Text("Name or address") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item(key = "status") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "${ui.candidates.size} of ${ui.totalSeen} heard" + if (ui.scanning) " · scanning" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (ui.creating) CircularProgressIndicator(Modifier.size(18.dp))
                TextButton(onClick = model::cancelPicking) { Text("Cancel") }
            }
        }
        items(ui.candidates, key = { it.address }) { candidate ->
            CandidateRow(candidate, ui.creating) { model.createProject(candidate.address) }
        }
        if (ui.candidates.isEmpty()) {
            item(key = "nothing") {
                Text(
                    text = "Nothing heard yet. Keep the gadget powered and within a few metres.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CandidateRow(candidate: ProjectCandidate, busy: Boolean, onPick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !busy, onClick = onPick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = candidate.name?.takeIf { it.isNotBlank() } ?: "Unnamed device",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = candidate.address,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = MonoFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (candidate.serviceCount > 0) {
                Pill(
                    text = if (candidate.serviceCount == 1) "1 service" else "${candidate.serviceCount} services",
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!candidate.connectable) Pill("Broadcast only")
            RssiChip(candidate.rssi)
        }
    }
}

@Composable
internal fun ProjectPermissionGate(requestPermissions: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(imageVector = Icons.Filled.Bluetooth, contentDescription = null, modifier = Modifier.height(48.dp))
        Text("Bluetooth permissions required", style = MaterialTheme.typography.titleMedium)
        Text(
            "A device project connects, enumerates and writes over a GATT link, and watches the " +
                "device's presence on a scan. All of that needs Nearby devices access.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = requestPermissions, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
            Text("Grant permissions")
        }
    }
}
