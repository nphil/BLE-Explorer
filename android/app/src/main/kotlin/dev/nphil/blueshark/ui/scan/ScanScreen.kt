package dev.nphil.blueshark.ui.scan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.nphil.blueshark.AppContainer
import dev.nphil.blueshark.ble.ScannedDevice
import dev.nphil.blueshark.ble.displayName
import dev.nphil.blueshark.ui.theme.MonoFamily
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun ScanScreen(
    container: AppContainer,
    expanded: Boolean,
    bluetoothGranted: Boolean,
    requestPermissions: () -> Unit,
    onOpenInSession: (sessionId: String) -> Unit,
) {
    val viewModel: ScanViewModel = viewModel(factory = remember(container) { ScanViewModel.factory(container) })
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message -> snackbarHostState.showSnackbar(message) }
    }
    // Keyed on the ViewModel only: the shell rebuilds `onOpenInSession` on every recomposition and
    // restarting the collector would drop the one-shot "saved" event emitted in between.
    val openInSession by rememberUpdatedState(onOpenInSession)
    LaunchedEffect(viewModel) {
        viewModel.saved.collect { saved -> openInSession(saved.sessionId) }
    }

    // On a phone the scaffold has a single horizontal partition, which turns list/detail into a
    // push/pop stack with predictive back; on a tablet both panes stay on screen.
    val baseDirective = calculatePaneScaffoldDirective(currentWindowAdaptiveInfo())
    val directive = remember(baseDirective, expanded) {
        if (expanded) baseDirective else baseDirective.copy(maxHorizontalPartitions = 1)
    }
    val navigator = rememberListDetailPaneScaffoldNavigator<String>(scaffoldDirective = directive)

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        if (!bluetoothGranted) {
            PermissionRationale(
                onRequest = requestPermissions,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
            return@Scaffold
        }

        NavigableListDetailPaneScaffold(
            navigator = navigator,
            modifier = Modifier.padding(padding),
            listPane = {
                AnimatedPane {
                    ScanListPane(
                        state = state,
                        onToggleScan = viewModel::toggleScan,
                        onContinuousChange = viewModel::setContinuous,
                        onQueryChange = viewModel::setQuery,
                        onConnectableOnlyChange = viewModel::setConnectableOnly,
                        onHideUnnamedChange = viewModel::setHideUnnamed,
                        onClear = viewModel::clearDevices,
                        onSelect = { address ->
                            viewModel.select(address)
                            coroutineScope.launch {
                                navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, address)
                            }
                        },
                    )
                }
            },
            detailPane = {
                AnimatedPane {
                    val address = navigator.currentDestination?.contentKey ?: state.selectedAddress
                    if (address == null) {
                        Scaffold(topBar = { TopAppBar(title = { Text("Device") }) }) { inner ->
                            DetailPlaceholder(Modifier.padding(inner))
                        }
                    } else {
                        DeviceDetailScaffold(
                            address = address,
                            device = state.allDevices[address],
                            connection = state.connection,
                            showBack = !expanded,
                            onBack = { coroutineScope.launch { navigator.navigateBack() } },
                            actions = DeviceDetailActions(
                                onConnect = { viewModel.connect(address) },
                                onDisconnect = viewModel::disconnect,
                                onToggleService = viewModel::toggleService,
                                onRead = viewModel::read,
                                onWrite = viewModel::openWriteDialog,
                                onSubscribe = viewModel::setSubscribed,
                                onSaveToSession = viewModel::openSessionPicker,
                            ),
                        )
                    }
                }
            },
        )
    }

    state.writeDialog?.let { dialog ->
        WriteDialog(
            dialog = dialog,
            onHexChange = viewModel::updateWriteHex,
            onResponseChange = viewModel::updateWriteResponse,
            onDismiss = viewModel::dismissWriteDialog,
            onConfirm = viewModel::submitWrite,
        )
    }

    state.sessionPicker?.let { picker ->
        SessionPickerDialog(
            picker = picker,
            onSelect = viewModel::selectSessionTarget,
            onNameChange = viewModel::updateNewSessionName,
            onDismiss = viewModel::dismissSessionPicker,
            onConfirm = viewModel::saveToSession,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScanListPane(
    state: ScanUiState,
    onToggleScan: () -> Unit,
    onContinuousChange: (Boolean) -> Unit,
    onQueryChange: (String) -> Unit,
    onConnectableOnlyChange: (Boolean) -> Unit,
    onHideUnnamedChange: (Boolean) -> Unit,
    onClear: () -> Unit,
    onSelect: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan") },
                actions = {
                    IconButton(onClick = onClear, enabled = state.totalSeen > 0) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear scan results")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onToggleScan,
                icon = {
                    Icon(
                        imageVector = if (state.scanning) Icons.Filled.Stop else Icons.Filled.BluetoothSearching,
                        contentDescription = null,
                    )
                },
                text = { Text(if (state.scanning) "Stop" else "Scan") },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            if (state.scanning) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                label = { Text("Name or address") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Search,
                ),
            )

            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(
                    selected = state.connectableOnly,
                    onClick = { onConnectableOnlyChange(!state.connectableOnly) },
                    label = { Text("Connectable") },
                )
                FilterChip(
                    selected = state.hideUnnamed,
                    onClick = { onHideUnnamedChange(!state.hideUnnamed) },
                    label = { Text("Named only") },
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Switch(
                        checked = state.continuous,
                        onCheckedChange = onContinuousChange,
                    )
                    Text("Continuous", style = MaterialTheme.typography.labelLarge)
                }
            }

            Text(
                text = "${state.visible.size} shown · ${state.totalSeen} seen",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider()

            if (state.visible.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (state.scanning) {
                            "Listening for advertisements…"
                        } else {
                            "Start a scan to discover nearby BLE devices."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items = state.visible, key = { it.address }) { device ->
                        DeviceRow(
                            device = device,
                            selected = device.address == state.selectedAddress,
                            onClick = { onSelect(device.address) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(device: ScannedDevice, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(onClickLabel = "Open device details", onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name?.takeIf { it.isNotBlank() } ?: "Unnamed",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = MonoFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (device.connectable) {
                        Pill(
                            text = "Connectable",
                            container = MaterialTheme.colorScheme.tertiaryContainer,
                            content = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                    if (device.serviceUuids.isNotEmpty()) {
                        Pill("${device.serviceUuids.size} services")
                        Pill(displayName(device.serviceUuids.first()), mono = true)
                    }
                    if (device.manufacturerData.isNotEmpty()) {
                        Pill("MFR 0x%04X".format(device.manufacturerData.keys.first()), mono = true)
                    }
                }
            }
            RssiChip(device.rssi)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceDetailScaffold(
    address: String,
    device: ScannedDevice?,
    connection: ConnectionUiState,
    showBack: Boolean,
    onBack: () -> Unit,
    actions: DeviceDetailActions,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = device?.name?.takeIf { it.isNotBlank() } ?: address,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to the device list")
                        }
                    }
                },
            )
        },
    ) { padding ->
        DeviceDetailPane(
            address = address,
            device = device,
            connection = connection,
            actions = actions,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
private fun PermissionRationale(onRequest: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.BluetoothDisabled,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Nearby devices permission needed", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "BlueShark needs the Nearby devices permission to list advertising gadgets and to " +
                    "open a GATT connection. Location is never requested and never used.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRequest, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Grant permission")
            }
        }
    }
}

@Composable
private fun WriteDialog(
    dialog: WriteDialogState,
    onHexChange: (String) -> Unit,
    onResponseChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Write to ${dialog.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = dialog.hex,
                    onValueChange = onHexChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Payload (hex)") },
                    placeholder = { Text("A5 01 FF") },
                    isError = dialog.error != null,
                    supportingText = {
                        Text(dialog.error ?: "${dialog.byteCount} bytes — spaces are ignored")
                    },
                    singleLine = false,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                )
                if (dialog.supportsWithResponse && dialog.supportsWithoutResponse) {
                    Column {
                        WriteTypeOption(
                            label = "With response (write request)",
                            selected = dialog.withResponse,
                            onSelect = { onResponseChange(true) },
                        )
                        WriteTypeOption(
                            label = "Without response (write command)",
                            selected = !dialog.withResponse,
                            onSelect = { onResponseChange(false) },
                        )
                    }
                } else {
                    Text(
                        text = if (dialog.supportsWithResponse) {
                            "This characteristic only accepts writes with a response."
                        } else {
                            "This characteristic only accepts writes without a response."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = dialog.valid) { Text("Write") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun WriteTypeOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun SessionPickerDialog(
    picker: SessionPickerState,
    onSelect: (String?) -> Unit,
    onNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save evidence to a session") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (picker.loading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Loading sessions…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(selected = picker.selectedId == null, onClick = { onSelect(null) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = picker.selectedId == null, onClick = null)
                    Text("New session", modifier = Modifier.padding(start = 8.dp))
                }
                if (picker.selectedId == null) {
                    OutlinedTextField(
                        value = picker.newName,
                        onValueChange = onNameChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Session name") },
                        singleLine = true,
                    )
                }
                for (session in picker.sessions) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = picker.selectedId == session.id, onClick = { onSelect(session.id) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = picker.selectedId == session.id, onClick = null)
                        Text(
                            text = session.name,
                            modifier = Modifier.padding(start = 8.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !picker.saving) {
                Text(if (picker.saving) "Saving…" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
