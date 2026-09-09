package dev.nphil.blueshark.ui.settings

import androidx.compose.foundation.background
import dev.nphil.blueshark.debug.DebugSettings
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedTextField
import android.content.ClipboardManager
import android.content.ClipData
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.nphil.blueshark.AppContainer
import dev.nphil.blueshark.BuildConfig
import dev.nphil.blueshark.ui.theme.Palette
import dev.nphil.blueshark.ui.theme.Palettes
import dev.nphil.blueshark.ui.theme.ThemeMode
import dev.nphil.blueshark.ui.theme.ThemeSettings
import dev.nphil.blueshark.ui.theme.isDark
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(container: AppContainer, settings: ThemeSettings, expanded: Boolean) {
    val scope = container.appScope
    val dark = settings.isDark()
    val columns = if (expanded) 4 else 2

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            Column {
                Text("Appearance", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.mode == mode,
                            onClick = { scope.launch { container.themes.setMode(mode) } },
                            label = { Text(mode.name.lowercase().replaceFirstChar(Char::uppercase)) },
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text("Palette", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Twenty palettes from popular editor themes, each with a light and a dark variant. Material You follows your wallpaper.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }
        }
        item {
            DynamicPaletteCard(
                selected = settings.usesDynamicColor,
                dark = dark,
                onClick = { scope.launch { container.themes.setPalette(null) } },
            )
        }
        items(Palettes.all, key = Palette::id) { palette ->
            PaletteCard(
                palette = palette,
                dark = dark,
                selected = settings.paletteId == palette.id,
                onClick = { scope.launch { container.themes.setPalette(palette.id) } },
            )
        }
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            DebugSection(container)
        }
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(top = 24.dp)) {
                Text("About", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "BlueShark ${BuildConfig.VERSION_NAME}. Local-first Bluetooth LE reverse-engineering workbench for Home Assistant. Nothing leaves this device unless you share an export.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DebugSection(container: AppContainer) {
    val scope = container.appScope
    val settings by container.debug.settings.collectAsStateWithLifecycle(DebugSettings())
    val status by container.debug.status.collectAsStateWithLifecycle()
    var topicDraft by remember(settings.topic) { mutableStateOf(settings.topic) }
    val clipboard = LocalContext.current.getSystemService(ClipboardManager::class.java)

    Column(Modifier.padding(top = 24.dp)) {
        Text("Debug logging", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Send debug log to ntfy.sh", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Publishes probe results, step outcomes and errors (never packet payloads) to the topic below so " +
                        "they can be read remotely. Batched every 10 s, at most 200 messages a day, well under ntfy's " +
                        "rate limits. Anyone who knows the topic can read it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(16.dp))
            Switch(
                checked = settings.ntfyEnabled,
                onCheckedChange = { on -> scope.launch { container.debug.setEnabled(on) } },
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = topicDraft,
                onValueChange = { topicDraft = it.filter { c -> c.isLetterOrDigit() || c == '-' || c == '_' }.take(64) },
                label = { Text("ntfy topic") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { scope.launch { container.debug.setTopic(topicDraft) } },
                enabled = topicDraft != settings.topic && topicDraft.isNotBlank(),
            ) { Text("Save") }
            TextButton(onClick = {
                clipboard.setPrimaryClip(ClipData.newPlainText("ntfy topic", settings.topicUrl))
            }) { Text("Copy URL") }
        }
        Text(
            settings.topicUrl + if (settings.ntfyEnabled) "  ·  queued ${status.queuedLines}  ·  sent today ${status.sentToday}" +
                (status.lastResult.takeIf { it.isNotBlank() }?.let { "  ·  $it" } ?: "") else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PaletteCard(palette: Palette, dark: Boolean, selected: Boolean, onClick: () -> Unit) {
    val scheme = palette.scheme(dark)
    SwatchCard(
        title = palette.name,
        subtitle = if (dark) palette.darkVariant else palette.lightVariant,
        scheme = scheme,
        selected = selected,
        onClick = onClick,
    )
}

@Composable
private fun DynamicPaletteCard(selected: Boolean, dark: Boolean, onClick: () -> Unit) {
    SwatchCard(
        title = "Material You",
        subtitle = "Dynamic wallpaper color",
        scheme = MaterialTheme.colorScheme.takeIf { selected } ?: Palettes.byId(Palettes.DEFAULT_ID).scheme(dark),
        selected = selected,
        onClick = onClick,
    )
}

@Composable
private fun SwatchCard(title: String, subtitle: String, scheme: ColorScheme, selected: Boolean, onClick: () -> Unit) {
    val outline = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .border(if (selected) 2.dp else 1.dp, outline, RoundedCornerShape(16.dp))
            .background(scheme.surface)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "$title, $subtitle${if (selected) ", selected" else ""}" }
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, modifier = Modifier.weight(1f))
            if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(18.dp))
        }
        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(scheme.primary, scheme.secondary, scheme.tertiary, scheme.error, scheme.surfaceContainerHighest).forEach { c ->
                Dot(c)
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(scheme.primaryContainer),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text("  Aa 0x1F", style = MaterialTheme.typography.labelMedium, color = scheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(16.dp).clip(CircleShape).background(color))
}
