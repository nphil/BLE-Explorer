package dev.nphil.blueshark.ui.project

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.nphil.blueshark.AppContainer
import dev.nphil.blueshark.guide.countersLine
import dev.nphil.blueshark.guide.quietLine
import dev.nphil.blueshark.guide.quietSeconds
import dev.nphil.blueshark.guide.sessionLine
import dev.nphil.blueshark.guide.targetLine
import dev.nphil.blueshark.hci.CollectStage
import dev.nphil.blueshark.learn.ChecklistAction
import dev.nphil.blueshark.learn.LearnReadiness
import dev.nphil.blueshark.learn.TrafficSource
import dev.nphil.blueshark.model.EvidenceStage
import dev.nphil.blueshark.probe.FrameCodecs
import dev.nphil.blueshark.probe.ProbeVerdict
import dev.nphil.blueshark.probe.RawCodec
import dev.nphil.blueshark.relay.RelaySession
import dev.nphil.blueshark.ui.capture.CaptureViewModel
import dev.nphil.blueshark.ui.capture.ProgressPanel
import dev.nphil.blueshark.ui.capture.VendorApp
import dev.nphil.blueshark.ui.probe.OutcomeRow
import dev.nphil.blueshark.ui.probe.PROBE_RUNNER_KEY
import dev.nphil.blueshark.ui.probe.ProbeViewModel
import dev.nphil.blueshark.ui.probe.SweepConfirmDialog
import dev.nphil.blueshark.ui.probe.SweepProgress
import dev.nphil.blueshark.ui.probe.VerdictChip
import dev.nphil.blueshark.ui.scan.Pill
import dev.nphil.blueshark.ui.signal.SectionTitle
import dev.nphil.blueshark.ui.theme.MonoFamily

/**
 * One device project: four stage cards, each with one primary action, and nothing else competing
 * for the operator's next tap.
 *
 * This screen composes rather than reimplements. The probe stage is the real
 * [ProbeViewModel] - the same sweep runner, the same one-response-per-step correlation, the same
 * confirmation gate - hosted here instead of on its own page. Collection, import, the vendor-app
 * launch and the adapter restart are the real [CaptureViewModel], pointed at this project's
 * session so that every marker and every parsed event lands in it. The correlation and the
 * session's lifecycle are [LearnSessionCoordinator], which outlives this composition because the
 * operator spends the middle of a learning session inside somebody else's app.
 */
@Composable
fun ProjectScreen(
    container: AppContainer,
    sessionId: String,
    expanded: Boolean,
    bluetoothGranted: Boolean,
    requestPermissions: () -> Unit,
    onOpenProbe: (String) -> Unit,
    onOpenCapture: () -> Unit,
    onOpenRelay: () -> Unit,
    onOpenEvidence: (String) -> Unit,
) {
    if (!bluetoothGranted) {
        ProjectPermissionGate(requestPermissions)
        return
    }
    val project: ProjectViewModel = viewModel(
        key = "project-$sessionId",
        factory = remember(container, sessionId) { ProjectViewModel.factory(container, sessionId) },
    )
    val capture: CaptureViewModel = viewModel(
        key = "project-capture-$sessionId",
        factory = remember(container) { CaptureViewModel.factory(container) },
    )
    // Scoped to the Activity rather than to this back-stack entry, and keyed with the same
    // PROBE_RUNNER_KEY the full probe page uses: the two are then literally one runner, with one
    // outcome list and one session selection instead of two that silently disagree.
    //
    // LocalActivity, not LocalContext: the composition context is routinely a
    // ContextThemeWrapper, which is not a ViewModelStoreOwner and would fail the cast.
    val probe: ProbeViewModel = viewModel(
        viewModelStoreOwner = LocalActivity.current as? ViewModelStoreOwner
            ?: checkNotNull(LocalViewModelStoreOwner.current) { "no ViewModelStoreOwner in scope" },
        key = PROBE_RUNNER_KEY,
        factory = remember(container) { ProbeViewModel.factory(container) },
    )

    val ui by project.state.collectAsStateWithLifecycle()
    val captureUi by capture.state.collectAsStateWithLifecycle()
    val probeUi by probe.state.collectAsStateWithLifecycle()
    val guide by container.guide.state.collectAsStateWithLifecycle()
    val targetStatus by container.guide.targetStatus.collectAsStateWithLifecycle()
    val relay by RelaySession.state.collectAsStateWithLifecycle()
    val learn by container.learning.state.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var appPicker by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) capture.importFile(uri)
    }

    // Every marker the observer produces and every event a collection parses is persisted by the
    // capture view model into whichever session it has open, so pointing it at this project is
    // what makes a learning session land in this project rather than in a fresh one.
    LaunchedEffect(sessionId) { capture.chooseSession(sessionId) }

    // One runner is shared with the full probe page, so opening this project while a sweep runs on
    // another device must not retarget it. The runner refuses either way, but attempting a
    // retarget that is silently ignored is worse than not attempting one: the card would then
    // claim to be pointed at this project while the sweep wrote somewhere else. Both keys include
    // `running`, so the moment the sweep ends this re-runs and the retarget lands.
    val sweepElsewhere = probeUi.running && probeUi.address != ui.device.address

    LaunchedEffect(sessionId, probeUi.running) {
        // Without this the sweep runner would save its records into a session of its own making,
        // and the raw probe evidence would sit next to the project instead of in it.
        if (!probeUi.running) probe.selectSession(sessionId)
    }

    LaunchedEffect(ui.device.address, ui.family?.codecId, probeUi.running) {
        val address = ui.device.address
        if (address.isNotBlank() && !probeUi.running) {
            probe.setTarget(address, ui.device.name)
            // A matched family brings its own framing; without one the sweep goes out unframed,
            // which is the honest generic case rather than a guess at somebody else's protocol.
            val codec = ui.family?.codecId?.takeIf { FrameCodecs.byId(it) != null } ?: RawCodec.id
            probe.setCodec(codec)
        }
    }

    LifecycleResumeEffect(sessionId) {
        project.reload()
        capture.refreshShizuku()
        capture.refreshGuide()
        capture.probe()
        onPauseOrDispose { }
    }

    LaunchedEffect(project) {
        project.effects.collect { effect ->
            when (effect) {
                is ProjectEffect.Notice -> snackbar.showSnackbar(effect.message)
                is ProjectEffect.Share -> runCatching { context.startActivity(effect.intent) }
                    .onFailure { snackbar.showSnackbar("No installed app can receive the export") }

                ProjectEffect.CollectNow -> capture.collect()
            }
        }
    }
    LaunchedEffect(capture) { capture.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(probe) { probe.messages.collect { snackbar.showSnackbar(it) } }

    // Finish pressed in the overlay: the intent extra reached MainActivity, which routed here.
    LaunchedEffect(learn.finishRequestedFor) {
        if (learn.finishRequestedFor == sessionId) {
            container.learning.consumeFinishRequest()
            project.finishLearning(captureUi.shellReady)
        }
    }

    // A finished collection or import is the only moment the writes exist to be correlated.
    LaunchedEffect(captureUi.progress?.stage, captureUi.progress?.startedAtMs) {
        val progress = captureUi.progress ?: return@LaunchedEffect
        if (progress.stage == CollectStage.DONE) project.mergeLearning(progress.startedAtMs)
    }

    // A finished sweep folds itself in, from the records it just persisted rather than from this
    // screen's memory of it - so a sweep driven from the full probe page lands here too.
    LaunchedEffect(probeUi.runId, probeUi.running) {
        if (!probeUi.running && probeUi.runId != null) project.foldProbes(runId = probeUi.runId)
    }

    val readiness = remember(
        ui.learnSource,
        captureUi.capabilities,
        captureUi.shizuku,
        captureUi.guideEnabledInSettings,
        captureUi.vendorApp,
        relay.phase,
        ui.snoopConfirmed,
    ) {
        LearnReadiness.evaluate(
            source = ui.learnSource,
            capabilities = captureUi.capabilities,
            shizuku = captureUi.shizuku,
            observerEnabled = captureUi.guideEnabledInSettings,
            vendorAppLabel = captureUi.vendorApp?.label,
            relayRunning = relay.phase.isActive,
            snoopConfirmedByOperator = ui.snoopConfirmed,
        )
    }

    val overrides = remember { mutableStateMapOf<ProjectStage, Boolean>() }
    val isOpen: (ProjectStage) -> Boolean = { stage -> overrides[stage] ?: !ui.satisfied(stage) }
    val toggle: (ProjectStage) -> Unit = { stage -> overrides[stage] = !isOpen(stage) }

    val onChecklistAction: (ChecklistAction) -> Unit = { action ->
        when (action) {
            ChecklistAction.PICK_APP -> appPicker = true
            ChecklistAction.OPEN_ACCESSIBILITY -> capture.openAccessibilitySettings()
            ChecklistAction.OPEN_DEVELOPER_OPTIONS -> capture.openDeveloperOptions()
            ChecklistAction.RESTART_BLUETOOTH -> capture.restartBluetooth()
            ChecklistAction.INSTALL_SHIZUKU -> capture.openShizukuDownload()
            ChecklistAction.START_SHIZUKU -> capture.openShizuku()
            ChecklistAction.GRANT_SHIZUKU -> capture.requestShizukuPermission()
            ChecklistAction.OPEN_RELAY -> onOpenRelay()
            // The operator's tick is a checkbox on the row, not a button, so it never arrives here.
            ChecklistAction.CONFIRM_SNOOP, ChecklistAction.NONE -> Unit
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        if (ui.missing) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("This project is no longer on disk.", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "It was deleted while the screen was open. Go back to Devices.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") { ProjectHeader(ui, expanded) }

            item(key = "identify") {
                IdentifyCard(
                    ui = ui,
                    open = isOpen(ProjectStage.IDENTIFY),
                    onToggle = { toggle(ProjectStage.IDENTIFY) },
                    onConnect = project::connectAndEnumerate,
                    onIdentify = project::identify,
                    onDisconnect = project::disconnect,
                    onClearError = project::clearConnectError,
                    onOpenUrl = { url -> openUrl(context, url) },
                )
            }

            item(key = "probe") {
                ProbeStageCard(
                    ui = ui,
                    probeUi = probeUi,
                    expanded = expanded,
                    open = isOpen(ProjectStage.PROBE),
                    onToggle = { toggle(ProjectStage.PROBE) },
                    onConnect = probe::connect,
                    onSweep = probe::requestOpcodeSweep,
                    onStop = probe::stopSweep,
                    onEffect = probe::setObservedEffect,
                    onRefold = {
                        // The typed effects are evidence: they are saved with the run first, and
                        // the map is then rebuilt from the records rather than from this screen.
                        probe.saveRun()
                        project.foldProbes(force = true)
                    },
                    onOpenFullPage = { onOpenProbe(ui.device.address) },
                )
            }

            item(key = "learn") {
                LearnCard(
                    ui = ui,
                    captureUi = captureUi,
                    readiness = readiness,
                    // Live coordinator state as well as the persisted record: the record is
                    // written asynchronously, and the button has to change the moment the
                    // operator presses Start rather than on the next resume.
                    sessionRunning = ui.learningOpen ||
                        (learn.running && learn.sessionId == sessionId),
                    observerConnected = guide.serviceConnected,
                    sessionStrip = if (guide.targetPackage == null) {
                        emptyList()
                    } else {
                        listOf(
                            sessionLine(guide.targetLabel.orEmpty(), System.currentTimeMillis() - guide.sessionStartedAtMs),
                            targetLine(targetStatus, guide.sessionStartedAtMs),
                            countersLine(guide.taps),
                            quietLine(quietSeconds(guide.lastEventAtMs, System.currentTimeMillis())),
                        ).filter { it.isNotEmpty() }
                    },
                    expanded = expanded,
                    open = isOpen(ProjectStage.LEARN),
                    onToggle = { toggle(ProjectStage.LEARN) },
                    onSource = project::setLearnSource,
                    onAction = onChecklistAction,
                    onConfirm = project::setSnoopConfirmed,
                    onStart = {
                        val app = captureUi.vendorApp
                        project.startLearning(app?.packageName, app?.label)
                        if (app != null) capture.launchApp(app)
                    },
                    onFinish = { project.finishLearning(captureUi.shellReady) },
                    onImport = { importLauncher.launch(IMPORT_TYPES) },
                    onCollect = { capture.collect() },
                    onMerge = { project.mergeLearning() },
                    onTest = project::requestTest,
                    onOpenCapture = onOpenCapture,
                )
            }

            item(key = "export") {
                ExportCard(
                    ui = ui,
                    open = isOpen(ProjectStage.EXPORT),
                    onToggle = { toggle(ProjectStage.EXPORT) },
                    onCopy = project::copyDraft,
                    onShare = project::shareDraft,
                    onSend = project::sendDraft,
                    onEvidence = project::shareEvidence,
                    onOpenEvidence = { onOpenEvidence(sessionId) },
                )
            }
        }
    }

    probeUi.confirm?.let { confirm ->
        SweepConfirmDialog(confirm, onConfirm = probe::confirmSweep, onDismiss = probe::dismissConfirmation)
    }
    ui.test?.let { test ->
        CommandTestDialog(
            test = test,
            onConfirm = project::confirmTest,
            onEffect = project::recordEffect,
            onDismiss = project::dismissTest,
        )
    }
    if (appPicker) {
        VendorAppDialog(
            apps = captureUi.apps,
            query = captureUi.appQuery,
            onQuery = capture::setAppQuery,
            onPick = { app ->
                capture.selectApp(app)
                appPicker = false
            },
            onDismiss = { appPicker = false },
        )
    }
}

private val IMPORT_TYPES = arrayOf("*/*")

private fun openUrl(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

// ---- header -------------------------------------------------------------------------------

@Composable
private fun ProjectHeader(ui: ProjectUiState, expanded: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(ui.deviceLabel, style = MaterialTheme.typography.headlineSmall)
            Text(
                text = ui.device.address,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = MonoFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ProgressChips(
                identified = ui.identified,
                probedFrames = ui.probeFrames,
                commandCount = ui.commands.size,
                testedCount = ui.testedCount,
                exported = ui.exported,
            )
            if (expanded) {
                Text(
                    text = "${ui.eventCount} events · ${ui.markerCount} markers · " +
                        "${ui.probeRuns} sweeps · ${ui.commands.size} commands",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ui.busy?.let { busy ->
                Text(busy, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// ---- identify -----------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdentifyCard(
    ui: ProjectUiState,
    open: Boolean,
    onToggle: () -> Unit,
    onConnect: () -> Unit,
    onIdentify: () -> Unit,
    onDisconnect: () -> Unit,
    onClearError: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val family = ui.family
    val status = buildString {
        append(
            if (family == null) {
                "Nothing in the advertisement matches a family BlueShark knows"
            } else {
                "${family.name} — ${confidenceWord(family.confidence)}"
            },
        )
        append(" · ")
        append(
            if (ui.gatt == null) {
                "not enumerated"
            } else {
                "${ui.gatt.services.size} services enumerated"
            },
        )
    }
    StageCard(
        stage = ProjectStage.IDENTIFY,
        satisfied = ui.satisfied(ProjectStage.IDENTIFY),
        status = status,
        open = open,
        onToggle = onToggle,
        primaryLabel = "Connect & enumerate",
        primaryEnabled = ui.device.address.isNotBlank() && !ui.connecting,
        onPrimary = onConnect,
        busy = ui.connecting,
    ) {
        if (family != null) {
            Text(family.name, style = MaterialTheme.typography.titleSmall)
            EvidenceBullets(family.evidence)
            if (family.commandCharacteristicHints.isNotEmpty()) {
                Text(
                    text = "Worth probing first: ${family.commandCharacteristicHints.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            family.publicDriverUrl?.let { url ->
                StageLink("Public driver for this family", onClick = { onOpenUrl(url) })
            }
        } else {
            Text(
                text = "That is not a failure: most gadgets advertise nothing distinctive. Connect and " +
                    "enumerate, and the attribute database may still name it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (ui.matches.size > 1) {
            HorizontalDivider()
            Text("Also matched", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (match in ui.matches.drop(1)) {
                    Pill(
                        text = "${match.name} · ${confidenceWord(match.confidence.name)}",
                        container = MaterialTheme.colorScheme.surfaceVariant,
                        content = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        ui.gatt?.let { database ->
            HorizontalDivider()
            Text("Writable channels", style = MaterialTheme.typography.labelLarge)
            val channels = database.services.flatMap { service ->
                service.characteristics
                    .filter { "WRITE" in it.properties || "WRITE_NO_RESPONSE" in it.properties }
                    .map { characteristic ->
                        "${dev.nphil.blueshark.ble.shortUuid(characteristic.uuid)} in " +
                            "${dev.nphil.blueshark.ble.shortUuid(service.uuid)} · " +
                            characteristic.properties.joinToString(", ")
                    }
            }
            if (channels.isEmpty()) {
                Text(
                    text = "None: nothing on this device can be written, so there is nothing to probe.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                EvidenceBullets(channels)
            }
        }

        ui.connectError?.let { error -> ConnectErrorCard(error, onClearError) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onIdentify, enabled = ui.busy == null) { Text("Re-run the fingerprint") }
            if (ui.connected) TextButton(onClick = onDisconnect) { Text("Disconnect") }
        }
    }
}

// ---- probe --------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProbeStageCard(
    ui: ProjectUiState,
    probeUi: dev.nphil.blueshark.ui.probe.ProbeUiState,
    expanded: Boolean,
    open: Boolean,
    onToggle: () -> Unit,
    onConnect: () -> Unit,
    onSweep: () -> Unit,
    onStop: () -> Unit,
    onEffect: (Int, String) -> Unit,
    onRefold: () -> Unit,
    onOpenFullPage: () -> Unit,
) {
    val codec = FrameCodecs.byId(probeUi.codecId)
    val counts = probeUi.outcomes.filterNot { it.step.canary }.groupingBy { it.verdict }.eachCount()
    val status = when {
        probeUi.running -> "Sweeping: step ${probeUi.stepNumber} of ${probeUi.stepTotal}"
        ui.probeFrames > 0 -> "${ui.probeFrames} frames probed · ${ui.probeAccepted} accepted"
        !probeUi.connected -> "Not connected — a sweep needs a link and a characteristic that answers"
        else -> "Ready on ${probeUi.target?.label ?: "no characteristic"}"
    }
    StageCard(
        stage = ProjectStage.PROBE,
        satisfied = ui.satisfied(ProjectStage.PROBE),
        status = status,
        open = open,
        onToggle = onToggle,
        primaryLabel = if (probeUi.connected) "Run the sweep" else "Connect & listen",
        primaryEnabled = if (probeUi.connected) probeUi.canStart else ui.device.address.isNotBlank(),
        onPrimary = if (probeUi.connected) onSweep else onConnect,
        busy = probeUi.running,
    ) {
        Text(
            text = if (ui.family?.codecId != null) {
                "Framing: ${codec?.label ?: probeUi.codecId} — preselected from the ${ui.family.name} match, " +
                    "so the sweep uses this family's own plan."
            } else {
                "Framing: ${codec?.label ?: probeUi.codecId} — no family matched, so the frames go out " +
                    "unframed and only a device that answers can rule an opcode in or out."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        probeUi.target?.let { target ->
            Text(
                text = "Target: ${target.label} · ${target.properties.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = MonoFamily,
            )
        }
        if (probeUi.running) {
            SweepProgress(probeUi.stepNumber, probeUi.stepTotal, probeUi.stepLabel)
            TextButton(onClick = onStop) { Text("Stop the sweep") }
        }
        if (counts.isNotEmpty()) {
            HorizontalDivider()
            Text("What the last run proved", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (verdict in ProbeVerdict.entries) {
                    val count = counts[verdict] ?: continue
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        VerdictChip(verdict)
                        Text("×$count", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            val accepted = probeUi.outcomes.withIndex()
                .filter { (_, outcome) -> outcome.verdict == ProbeVerdict.ACCEPTED && !outcome.step.canary }
            if (accepted.isEmpty()) {
                Text(
                    text = "Nothing was accepted, so nothing from this run is offered as a command.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "SUCCESS proves the opcode exists, never what it does. Each accepted frame is " +
                        "in the command map as a hypothesis until you say what you saw happen - and only " +
                        "these rows can be promoted, which is why only these are here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for ((index, outcome) in accepted) {
                    OutcomeRow(
                        index = index,
                        opcode = outcome.step.opcode,
                        label = outcome.step.label,
                        canary = false,
                        sentHex = outcome.sentHex,
                        responseHex = outcome.responseHex,
                        verdict = outcome.verdict,
                        statusByte = outcome.statusByte,
                        elapsedMs = outcome.elapsedMs,
                        note = outcome.note,
                        observedEffect = outcome.observedEffect,
                        lateCount = 0,
                        expanded = expanded,
                        resetKey = probeUi.runId,
                        onEffectChange = { text -> onEffect(index, text) },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRefold, enabled = ui.busy == null) { Text("Update from effects") }
                }
            }
        }
        StageLink("Open the full probe page", onClick = onOpenFullPage)
    }
}

// ---- learn --------------------------------------------------------------------------------

@Composable
private fun LearnCard(
    ui: ProjectUiState,
    captureUi: dev.nphil.blueshark.ui.capture.CaptureUiState,
    readiness: dev.nphil.blueshark.learn.Readiness,
    sessionRunning: Boolean,
    observerConnected: Boolean,
    sessionStrip: List<String>,
    expanded: Boolean,
    open: Boolean,
    onToggle: () -> Unit,
    onSource: (TrafficSource) -> Unit,
    onAction: (ChecklistAction) -> Unit,
    onConfirm: (Boolean) -> Unit,
    onStart: () -> Unit,
    onFinish: () -> Unit,
    onImport: () -> Unit,
    onCollect: () -> Unit,
    onMerge: () -> Unit,
    onTest: (String) -> Unit,
    onOpenCapture: () -> Unit,
) {
    val status = when {
        sessionRunning -> "Session running — press Finish when you have driven every control"
        ui.learnedCount > 0 -> "${ui.learnedCount} commands learned from taps"
        readiness.canStart -> "Ready to start"
        else -> "${readiness.blocking.size} thing(s) still to do"
    }
    StageCard(
        stage = ProjectStage.LEARN,
        satisfied = ui.satisfied(ProjectStage.LEARN),
        status = status,
        open = open,
        onToggle = onToggle,
        primaryLabel = if (sessionRunning) "Finish the session" else "Start learning session",
        primaryEnabled = if (sessionRunning) true else readiness.canStart,
        onPrimary = if (sessionRunning) onFinish else onStart,
        busy = captureUi.working || ui.busy != null,
    ) {
        SourceSwitch(ui.learnSource, enabled = !sessionRunning, onSource = onSource)

        for (item in readiness.items) {
            ChecklistRow(item, onAction, onConfirm)
        }
        if (captureUi.guideEnabledInSettings && !observerConnected) {
            Text(
                text = "The observer is enabled in Settings but is not bound right now. On this kind of " +
                    "build a battery optimiser can revoke a running accessibility service; open " +
                    "Accessibility settings and switch it off and on again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (sessionStrip.isNotEmpty()) {
            HorizontalDivider()
            Text("Live", style = MaterialTheme.typography.labelLarge)
            for (line in sessionStrip) {
                Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = MonoFamily)
            }
        }

        if (ui.learnSource == TrafficSource.RELAY) {
            Text(
                text = "A relay captures the writes as they pass through, so there is nothing to collect " +
                    "afterwards. Start the relay, drive the app on the other phone, then append that run " +
                    "to this project from the Relay tab before you finish here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (ui.manualCollection) {
            HorizontalDivider()
            ManualCollection(
                shellReady = captureUi.shellReady,
                onImport = onImport,
                onCollect = onCollect,
            )
        }

        ProgressPanel(
            progress = captureUi.progress,
            working = captureUi.working,
            busy = captureUi.busy,
            stages = true,
        )

        if (ui.mergeNote.isNotBlank()) {
            Text(ui.mergeNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onMerge, enabled = ui.busy == null) { Text("Correlate again") }
            OutlinedButton(onClick = onImport, enabled = !captureUi.working) { Text("Import a capture") }
        }

        if (ui.commands.isNotEmpty()) {
            HorizontalDivider()
            SectionTitle("Command map", trailing = "${ui.commands.size} · ${ui.testedCount} tested")
            if (expanded) CommandMapHeader()
            Column {
                for (command in ui.commands) {
                    CommandMapRow(
                        command = command,
                        expanded = expanded,
                        canTest = ui.busy == null && ui.device.address.isNotBlank() && command.characteristicUuid != null,
                        onTest = { onTest(command.id) },
                    )
                    HorizontalDivider()
                }
            }
        }

        UnattributedWrites(ui.unattributed)

        StageLink("Advanced capture", onClick = onOpenCapture)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceSwitch(source: TrafficSource, enabled: Boolean, onSource: (TrafficSource) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Where the app runs",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = source == TrafficSource.HCI_SNOOP,
                enabled = enabled,
                onClick = { onSource(TrafficSource.HCI_SNOOP) },
                label = { Text("App on this tablet") },
            )
            FilterChip(
                selected = source == TrafficSource.RELAY,
                enabled = enabled,
                onClick = { onSource(TrafficSource.RELAY) },
                label = { Text("App on another phone") },
            )
        }
        Text(
            text = if (source == TrafficSource.HCI_SNOOP) {
                "The traffic is read from the Android stack's own HCI log."
            } else {
                "BlueShark advertises as the device and the other phone's app connects to it. A radio " +
                    "never hears its own advertisements, so the app cannot be on this tablet."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The bug-report path, plus a way out of it.
 *
 * Finish decides between collecting and asking on whatever Shizuku's state was at that moment,
 * and on a cold start - which the overlay's Finish button can be - the gateway has not finished
 * looking yet. So when a privileged shell does turn up afterwards, the card offers it rather than
 * leaving the operator on the long road it guessed they were on.
 */
@Composable
private fun ManualCollection(shellReady: Boolean, onImport: () -> Unit, onCollect: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Take a bug report", style = MaterialTheme.typography.titleSmall)
        Text(
            text = "There is no privileged shell, so the log has to come out the long way:\n" +
                "1. Developer options > Bug report > Full report.\n" +
                "2. Wait for the notification saying it is ready, and share it here.\n" +
                "The zip holds the btsnoop; BlueShark extracts and merges every rotation in it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onImport) {
                Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("Import the bug report", Modifier.padding(start = 6.dp))
            }
            if (shellReady) {
                OutlinedButton(onClick = onCollect) { Text("Shizuku is ready — collect for me") }
            }
        }
    }
}

// ---- export -------------------------------------------------------------------------------

@Composable
private fun ExportCard(
    ui: ProjectUiState,
    open: Boolean,
    onToggle: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSend: () -> Unit,
    onEvidence: () -> Unit,
    onOpenEvidence: () -> Unit,
) {
    val tested = ui.commands.count { it.stage == EvidenceStage.DEVICE_TESTED }
    val hypotheses = ui.commands.count { it.stage == EvidenceStage.HYPOTHESIS }
    val observed = ui.commands.count { it.stage == EvidenceStage.OBSERVED }
    StageCard(
        stage = ProjectStage.EXPORT,
        satisfied = ui.satisfied(ProjectStage.EXPORT),
        status = if (ui.commands.isEmpty()) {
            "Nothing to export yet"
        } else {
            "$tested tested · $hypotheses hypotheses · $observed observed"
        },
        open = open,
        onToggle = onToggle,
        primaryLabel = "Copy the profile draft",
        primaryEnabled = ui.draft.isNotBlank() && ui.busy == null,
        onPrimary = onCopy,
    ) {
        Text(
            text = "Home Assistant creates a button only for a device-tested command. Everything weaker " +
                "is still in the draft, marked synthetic, so the integration installs it without wiring " +
                "it to anything.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (ui.draft.isBlank()) {
            Text(
                text = "The draft is empty. Probe the device or learn from its app first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
                Text(
                    text = ui.draft,
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = MonoFamily,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onShare, enabled = ui.draft.isNotBlank() && ui.busy == null) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("Share", Modifier.padding(start = 6.dp))
            }
            if (ui.ntfyEnabled) {
                OutlinedButton(onClick = onSend, enabled = ui.draft.isNotBlank() && ui.busy == null) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("ntfy", Modifier.padding(start = 6.dp))
                }
            }
        }
        HorizontalDivider()
        Text("Everything else this project holds", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onEvidence, enabled = ui.busy == null) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("Evidence bundle", Modifier.padding(start = 6.dp))
            }
        }
        StageLink("Evidence & timeline", onClick = onOpenEvidence)
    }
}

// ---- vendor app picker --------------------------------------------------------------------

/**
 * The installed-app list, filtered.
 *
 * Naming the app is what lets the observer watch exactly one package and nothing else, which is
 * the whole reason the accessibility service is narrow enough to be worth enabling.
 */
@Composable
private fun VendorAppDialog(
    apps: List<VendorApp>,
    query: String,
    onQuery: (String) -> Unit,
    onPick: (VendorApp) -> Unit,
    onDismiss: () -> Unit,
) {
    val needle = query.trim().lowercase()
    val shown = remember(apps, needle) {
        if (needle.isEmpty()) apps else apps.filter {
            it.label.lowercase().contains(needle) || it.packageName.lowercase().contains(needle)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which app drives this device?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQuery,
                    label = { Text("Search installed apps") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(shown, key = { it.packageName }) { app ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(app) }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(app.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = app.packageName,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = MonoFamily,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
