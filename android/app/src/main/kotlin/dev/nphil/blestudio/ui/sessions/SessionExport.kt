package dev.nphil.blestudio.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nphil.blestudio.export.EligibilityReport
import dev.nphil.blestudio.export.ExcludedCommand
import dev.nphil.blestudio.export.HaProfileBuilder
import dev.nphil.blestudio.model.HaCommand
import dev.nphil.blestudio.ui.theme.MonoFamily

@Composable
internal fun ExportTab(
    state: SessionsUiState,
    viewModel: SessionsViewModel,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val report = state.eligibility
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 32.dp)) {
        item(key = "actions") {
            Column {
                SectionHeader(
                    "Export",
                    "The bundle is the full evidence record; the profile is the small, strict file the " +
                        "Home Assistant integration accepts",
                )
                if (state.exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
                val profileReady = !state.exporting && report?.exportable == true
                if (expanded) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ShareBundleButton(!state.exporting, viewModel, Modifier.weight(1f))
                        ShareProfileButton(profileReady, viewModel, Modifier.weight(1f))
                        CopyProfileButton(profileReady, viewModel, Modifier.weight(1f))
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ShareBundleButton(!state.exporting, viewModel, Modifier.fillMaxWidth())
                        ShareProfileButton(profileReady, viewModel, Modifier.fillMaxWidth())
                        CopyProfileButton(profileReady, viewModel, Modifier.fillMaxWidth())
                    }
                }
            }
        }
        if (report == null) {
            item(key = "no-report") {
                EmptyHint(
                    title = "Checking eligibility…",
                    body = "The profile is validated against the integration's schema before it can be shared.",
                )
            }
            return@LazyColumn
        }
        item(key = "status") { EligibilityStatus(report) }
        item(key = "included-header") {
            SectionHeader(
                "Installable commands",
                "${report.included.size} of ${report.included.size + report.excluded.size} catalogued " +
                    "commands qualify",
            )
        }
        items(report.included.size, key = { index -> "included-${report.included[index].id}" }) { index ->
            IncludedCommandRow(report.included[index])
        }
        if (report.excluded.isNotEmpty()) {
            item(key = "excluded-header") {
                SectionHeader("Left out", "Every reason the integration would refuse these")
            }
            items(report.excluded.size, key = { index -> "excluded-${report.excluded[index].commandId}" }) { index ->
                ExcludedCommandRow(report.excluded[index])
            }
        }
    }
}

@Composable
private fun ShareBundleButton(enabled: Boolean, viewModel: SessionsViewModel, modifier: Modifier) {
    Button(
        onClick = viewModel::shareEvidenceBundle,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Icon(Icons.Default.Share, contentDescription = null, Modifier.size(18.dp))
        Text("  Share evidence bundle")
    }
}

@Composable
private fun ShareProfileButton(enabled: Boolean, viewModel: SessionsViewModel, modifier: Modifier) {
    Button(
        onClick = viewModel::shareHaProfile,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Icon(Icons.Default.Share, contentDescription = null, Modifier.size(18.dp))
        Text("  Share HA install profile")
    }
}

@Composable
private fun CopyProfileButton(enabled: Boolean, viewModel: SessionsViewModel, modifier: Modifier) {
    OutlinedButton(
        onClick = viewModel::copyHaProfile,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Icon(Icons.Default.ContentCopy, contentDescription = null, Modifier.size(18.dp))
        Text("  Copy HA profile")
    }
}

@Composable
private fun EligibilityStatus(report: EligibilityReport) {
    val ready = report.exportable
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (ready) "Ready to install" else "Not installable yet",
                style = MaterialTheme.typography.titleMedium,
                color = if (ready) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            LabeledValue("Device name", report.deviceName ?: "missing")
            LabeledValue("Address", report.deviceAddress ?: "missing", mono = true)
            LabeledValue(
                "Profile size",
                "${report.encodedSizeBytes} of ${HaProfileBuilder.MAX_PROFILE_BYTES} bytes",
            )
            LabeledValue(
                "Commands",
                "${report.included.size} of at most ${HaProfileBuilder.MAX_COMMANDS}",
            )
            if (report.blockers.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                report.blockers.forEach { blocker ->
                    Text(
                        "• ${blocker.message}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun IncludedCommandRow(command: HaCommand) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row {
            Text(
                command.id,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = MonoFamily,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (command.response) "acknowledged" else "no response",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "${shortUuid(command.characteristic)} ← ${hexGrouped(command.value.uppercase(), 16)}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = MonoFamily,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            command.notes,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExcludedCommandRow(excluded: ExcludedCommand) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(
            excluded.name.ifBlank { "(unnamed)" },
            style = MaterialTheme.typography.bodyMedium,
        )
        excluded.problems.forEach { problem ->
            Text(
                "• ${problem.reason.label} (${problem.detail})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
