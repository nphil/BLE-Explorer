package dev.nphil.blueshark.ui.sessions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.nphil.blueshark.AppContainer
import dev.nphil.blueshark.crypto.DecryptCache
import dev.nphil.blueshark.model.CaptureSession
import dev.nphil.blueshark.ui.theme.MonoFamily

/**
 * Session catalogue and evidence workbench.
 *
 * On a tablet the catalogue and the selected session share the window through
 * [ListDetailPaneScaffold]; on a phone the detail replaces the list and system back returns to
 * it. Both paths render the same panes, so nothing is phone-only or tablet-only.
 */
@Composable
fun SessionsScreen(
    container: AppContainer,
    expanded: Boolean,
    initialSessionId: String? = null,
) {
    val viewModel: SessionsViewModel = viewModel(
        factory = remember(container) {
            viewModelFactory { initializer { SessionsViewModel(container) } }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LifecycleResumeEffect(viewModel) {
        // Another screen may have appended traffic to these sessions while this one was hidden.
        viewModel.reload()
        onPauseOrDispose { }
    }
    LaunchedEffect(initialSessionId) {
        if (initialSessionId != null) viewModel.select(initialSessionId)
    }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is SessionEffect.Notice -> snackbar.showSnackbar(effect.message)
                is SessionEffect.Share -> runCatching { context.startActivity(effect.intent) }
                    .onFailure { snackbar.showSnackbar("No installed app can receive the export") }
            }
        }
    }

    // One cache per (session, schemes, events): every pane reads the same memoised decryption,
    // and editing a scheme re-indexes once rather than once per pane.
    val decrypt = rememberDecryptCache(state.selected)

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { insets ->
        Box(Modifier.padding(insets).fillMaxSize()) {
            if (expanded) {
                SessionsTwoPane(state, viewModel, decrypt)
            } else {
                SessionsSinglePane(state, viewModel, decrypt)
            }
        }
    }

    SessionDialogs(state, viewModel)
    state.inspecting?.let { event ->
        EventInspectorSheet(
            event = event,
            session = state.selected,
            decrypted = decrypt?.takeIf { state.applyDecryption }?.frameFor(event),
            onDismiss = { viewModel.inspect(null) },
            onCreateCommand = { viewModel.createCommandFromEvent(event) },
        )
    }
}

/**
 * The session's decrypted view, rebuilt only when the schemes or the traffic change.
 *
 * Indexing is a metadata pass over the events; the ciphers themselves run lazily per row, so this
 * stays cheap even while the operator is typing a nonce offset.
 */
@Composable
private fun rememberDecryptCache(session: CaptureSession?): DecryptCache? =
    remember(session?.id, session?.ciphers, session?.events) {
        session?.let { DecryptCache(it, it.ciphers, it.events) }
    }

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun SessionsTwoPane(
    state: SessionsUiState,
    viewModel: SessionsViewModel,
    decrypt: DecryptCache?,
) {
    val navigator = rememberListDetailPaneScaffoldNavigator<String>()
    val selectedId = state.selected?.id
    LaunchedEffect(selectedId) {
        if (selectedId != null && navigator.currentDestination?.contentKey != selectedId) {
            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, selectedId)
        }
    }
    ListDetailPaneScaffold(
        directive = navigator.scaffoldDirective,
        value = navigator.scaffoldValue,
        listPane = {
            AnimatedPane {
                SessionListPane(state, viewModel, Modifier.fillMaxSize())
            }
        },
        detailPane = {
            AnimatedPane {
                SessionDetailPane(
                    state = state,
                    viewModel = viewModel,
                    decrypt = decrypt,
                    expanded = true,
                    onBack = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        },
    )
}

@Composable
private fun SessionsSinglePane(
    state: SessionsUiState,
    viewModel: SessionsViewModel,
    decrypt: DecryptCache?,
) {
    val session = state.selected
    BackHandler(enabled = session != null) { viewModel.select(null) }
    if (session == null) {
        SessionListPane(state, viewModel, Modifier.fillMaxSize())
    } else {
        SessionDetailPane(
            state = state,
            viewModel = viewModel,
            decrypt = decrypt,
            expanded = false,
            onBack = { viewModel.select(null) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun SessionListPane(
    state: SessionsUiState,
    viewModel: SessionsViewModel,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Sessions", style = MaterialTheme.typography.titleLarge)
                Text(
                    "${state.sessions.size} captured",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(onClick = viewModel::startCreate) {
                Icon(Icons.Default.Add, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("New")
            }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!state.loading && state.sessions.isEmpty()) {
            EmptyHint(
                title = "No sessions yet",
                body = "Create a session, then capture advertisements, GATT traffic or a relay run into it.",
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            items(state.sessions, key = { it.id }) { summary ->
                SessionCard(
                    summary = summary,
                    selected = state.selected?.id == summary.id,
                    onOpen = { viewModel.select(summary.id) },
                    onRename = { viewModel.startRename(summary) },
                    onDelete = { viewModel.requestDelete(summary) },
                )
            }
        }
    }
}

@Composable
private fun SessionCard(
    summary: SessionSummary,
    selected: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    summary.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    summary.deviceLabel,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = MonoFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${summary.eventCount} events · ${summary.commandCount} commands " +
                        "(${summary.testedCommandCount} tested) · ${formatDateTime(summary.updatedAtEpochMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.size(48.dp).semantics {
                        contentDescription = "More actions for ${summary.name}"
                    },
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = null)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionDetailPane(
    state: SessionsUiState,
    viewModel: SessionsViewModel,
    decrypt: DecryptCache?,
    expanded: Boolean,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val session = state.selected
    if (session == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            EmptyHint(
                title = "Nothing selected",
                body = "Pick a session on the left to inspect its traffic, commands and export readiness.",
            )
        }
        return
    }
    // The screen already built this for the selected session; the fallback only exists because the
    // parameter has to be nullable for the "nothing selected" pane above.
    val cache = decrypt ?: rememberDecryptCache(session)!!
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to sessions")
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    session.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = deviceHeadline(session),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = MonoFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.analyzing) {
                Text(
                    "analysing…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        val tabs = SessionTab.entries
        val selectedIndex = tabs.indexOf(state.tab)
        if (expanded) {
            PrimaryTabRow(selectedTabIndex = selectedIndex) {
                tabs.forEach { tab ->
                    Tab(
                        selected = tab == state.tab,
                        onClick = { viewModel.setTab(tab) },
                        text = { Text(tab.label, fontWeight = if (tab == state.tab) FontWeight.SemiBold else null) },
                        modifier = Modifier.height(48.dp),
                    )
                }
            }
        } else {
            PrimaryScrollableTabRow(selectedTabIndex = selectedIndex, edgePadding = 8.dp) {
                tabs.forEach { tab ->
                    Tab(
                        selected = tab == state.tab,
                        onClick = { viewModel.setTab(tab) },
                        text = { Text(tab.label) },
                        modifier = Modifier.height(48.dp),
                    )
                }
            }
        }
        HorizontalDivider()
        when (state.tab) {
            SessionTab.TIMELINE -> TimelineTab(
                state = state,
                session = session,
                decrypt = cache.takeIf { state.applyDecryption },
                expanded = expanded,
                onFilter = viewModel::updateFilter,
                onClearFilter = viewModel::clearFilter,
                onInspect = { viewModel.inspect(it) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            SessionTab.COMMANDS -> CommandsTab(
                state = state,
                session = session,
                expanded = expanded,
                viewModel = viewModel,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            SessionTab.COMPARE -> CompareTab(
                state = state,
                viewModel = viewModel,
                decrypt = cache,
                expanded = expanded,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            SessionTab.DECRYPT -> DecryptTab(
                state = state,
                session = session,
                viewModel = viewModel,
                cache = cache,
                expanded = expanded,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            SessionTab.DEVICE -> DeviceTab(
                session = session,
                expanded = expanded,
                viewModel = viewModel,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            SessionTab.EXPORT -> ExportTab(
                state = state,
                session = session,
                viewModel = viewModel,
                expanded = expanded,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SessionDialogs(state: SessionsUiState, viewModel: SessionsViewModel) {
    if (state.creating) {
        NameDialog(
            title = "New session",
            confirmLabel = "Create",
            initial = "Capture ${formatDateTime(System.currentTimeMillis())}",
            onDismiss = viewModel::cancelCreate,
            onConfirm = viewModel::create,
        )
    }
    state.renameTarget?.let { target ->
        NameDialog(
            title = "Rename session",
            confirmLabel = "Rename",
            initial = target.name,
            onDismiss = viewModel::cancelRename,
            onConfirm = { name -> viewModel.rename(target.id, name) },
        )
    }
    state.pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text("Delete \"${target.name}\"?") },
            text = {
                Text(
                    "${target.eventCount} captured events and ${target.commandCount} commands are " +
                        "deleted from this device. Export the evidence bundle first if you want to keep it.",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDelete) { Text("Keep") }
            },
        )
    }
}

@Composable
private fun NameDialog(
    title: String,
    confirmLabel: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun EmptyHint(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun deviceHeadline(session: CaptureSession): String {
    val name = session.device.alias?.takeIf { it.isNotBlank() }
        ?: session.device.name?.takeIf { it.isNotBlank() }
    val address = session.device.address.takeIf { it.isNotBlank() }
    return listOfNotNull(name, address, "${session.events.size} events")
        .joinToString(" · ")
}
