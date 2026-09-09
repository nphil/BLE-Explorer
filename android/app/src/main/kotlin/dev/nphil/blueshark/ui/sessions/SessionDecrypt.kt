package dev.nphil.blueshark.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nphil.blueshark.crypto.CipherPreset
import dev.nphil.blueshark.crypto.DecryptCache
import dev.nphil.blueshark.crypto.DecryptResult
import dev.nphil.blueshark.crypto.HandshakePart
import dev.nphil.blueshark.crypto.HandshakeRule
import dev.nphil.blueshark.crypto.KeyEncoding
import dev.nphil.blueshark.crypto.KeyTools
import dev.nphil.blueshark.crypto.Presets
import dev.nphil.blueshark.crypto.SecretRedaction
import dev.nphil.blueshark.model.BleEvent
import dev.nphil.blueshark.model.ByteSource
import dev.nphil.blueshark.model.CaptureSession
import dev.nphil.blueshark.model.CipherByteOrder
import dev.nphil.blueshark.model.CipherPrimitive
import dev.nphil.blueshark.model.CipherScheme
import dev.nphil.blueshark.model.EventDirection
import dev.nphil.blueshark.model.KeyDerivation
import dev.nphil.blueshark.ui.theme.MonoFamily

/**
 * The decrypted view of a session, and the editor that describes it.
 *
 * The whole tab is one idea: the operator owns the key, the app owns nothing, and the ciphertext
 * is never touched. Every scheme here is data - primitive plus byte sources - so an ecosystem
 * nobody has written about is as expressible as the ones with presets.
 */
@Composable
internal fun DecryptTab(
    state: SessionsUiState,
    session: CaptureSession,
    viewModel: SessionsViewModel,
    cache: DecryptCache,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 40.dp)) {
        item(key = "header") {
            Column {
                SectionHeader(
                    "Application-layer decryption",
                    "Describe the cipher and paste the key you obtained; the captured bytes are " +
                        "never altered and the key never leaves this device",
                )
                AddPresetButton(viewModel, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                if (session.ciphers.isNotEmpty()) {
                    SwitchRow(
                        label = "Apply to timeline, inspector and compare",
                        checked = state.applyDecryption,
                        onCheckedChange = viewModel::setApplyDecryption,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Text(
                        text = "${cache.matchedCount} of ${session.events.size} captured frames are " +
                            "claimed by an enabled scheme",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                HorizontalDivider(Modifier.padding(top = 8.dp))
            }
        }
        if (session.ciphers.isEmpty()) {
            item(key = "empty") {
                EmptyHint(
                    title = "No cipher described yet",
                    body = "Start from a preset for a known ecosystem, or from a raw primitive and " +
                        "point the nonce, AAD and ciphertext at the right bytes yourself.",
                )
            }
            return@LazyColumn
        }
        items(session.ciphers.size, key = { index -> session.ciphers[index].id }) { index ->
            SchemeCard(
                scheme = session.ciphers[index],
                cache = cache,
                viewModel = viewModel,
            )
        }
    }
    state.editingCipherId
        ?.let { id -> session.ciphers.firstOrNull { it.id == id } }
        ?.let { scheme ->
            SchemeEditorSheet(
                scheme = scheme,
                session = session,
                state = state,
                cache = cache,
                viewModel = viewModel,
                expanded = expanded,
            )
        }
}

@Composable
private fun AddPresetButton(viewModel: SessionsViewModel, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Button(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Default.Add, contentDescription = null, Modifier.size(18.dp))
            Text("  Add from preset")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            var ecosystem: String? = null
            Presets.all.forEach { preset ->
                if (preset.ecosystem != ecosystem) {
                    ecosystem = preset.ecosystem
                    Text(
                        preset.ecosystem,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp),
                    )
                }
                DropdownMenuItem(
                    text = { PresetLabel(preset) },
                    onClick = {
                        open = false
                        viewModel.addCipherFromPreset(preset)
                    },
                )
            }
        }
    }
}

@Composable
private fun PresetLabel(preset: CipherPreset) {
    Column {
        Text(preset.label, style = MaterialTheme.typography.bodyMedium)
        Text(
            if (preset.verified) preset.keyHint else "EXPERIMENTAL · ${preset.keyHint}",
            style = MaterialTheme.typography.labelSmall,
            color = if (preset.verified) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
            maxLines = 2,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SchemeCard(scheme: CipherScheme, cache: DecryptCache, viewModel: SessionsViewModel) {
    // Matching is indexed, so the count is free; whether the key is right is answered from a
    // bounded sample rather than by decrypting the whole capture to draw one card.
    val matched = cache.matchCountOf(scheme.id)
    val sample = remember(cache, scheme.id) { cache.matchesOf(scheme.id, limit = HEALTH_SAMPLE) }
    val decrypted = remember(cache, scheme.id, sample) {
        sample.count { cache.frameFor(it)?.result?.decrypted == true }
    }
    val firstError = remember(cache, scheme.id, sample) {
        sample.firstNotNullOfOrNull { cache.frameFor(it)?.result?.error }
    }
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        scheme.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        primitiveLabel(scheme.primitive) + " · " + matchSummary(scheme),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = scheme.enabled,
                    onCheckedChange = { viewModel.setCipherEnabled(scheme.id, it) },
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SchemeBadge(
                    text = if (scheme.keyHex.isEmpty()) "No key yet" else "${scheme.keyHex.length / 2}-byte key",
                    warning = scheme.keyHex.isEmpty(),
                )
                SchemeBadge(text = "$matched matched")
                SchemeBadge(
                    text = if (matched > sample.size) {
                        "$decrypted of the first ${sample.size} decrypted"
                    } else {
                        "$decrypted decrypted"
                    },
                    warning = sample.isNotEmpty() && decrypted == 0,
                )
                Presets.byId(scheme.presetId)?.takeIf { !it.verified }?.let {
                    SchemeBadge(text = "EXPERIMENTAL", warning = true)
                }
            }
            if (firstError != null && decrypted == 0) {
                Text(
                    firstError,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { viewModel.editCipher(scheme.id) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, Modifier.size(16.dp))
                    Text(" Edit")
                }
                TextButton(
                    onClick = { viewModel.duplicateCipher(scheme.id) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, Modifier.size(16.dp))
                    Text(" Duplicate")
                }
                TextButton(
                    onClick = { viewModel.deleteCipher(scheme.id) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, Modifier.size(16.dp))
                    Text(" Delete")
                }
            }
        }
    }
}

@Composable
private fun SchemeBadge(text: String, warning: Boolean = false) {
    Surface(
        color = if (warning) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (warning) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Editor
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SchemeEditorSheet(
    scheme: CipherScheme,
    session: CaptureSession,
    state: SessionsUiState,
    cache: DecryptCache,
    viewModel: SessionsViewModel,
    expanded: Boolean,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val edit: ((CipherScheme) -> CipherScheme) -> Unit = { transform ->
        viewModel.updateCipher(scheme.id, transform)
    }
    ModalBottomSheet(onDismissRequest = { viewModel.editCipher(null) }, sheetState = sheetState) {
        LazyColumn(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item(key = "identity") {
                Column {
                    Text("Cipher scheme", style = MaterialTheme.typography.titleLarge)
                    Presets.byId(scheme.presetId)?.let { preset ->
                        Text(
                            (if (preset.verified) "Preset: " else "EXPERIMENTAL preset: ") + preset.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (preset.verified) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                        Text(
                            "Source: ${preset.source}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    EvidenceTextField(
                        resetKey = scheme.id,
                        label = "Name",
                        value = scheme.name,
                        onChange = { name -> edit { it.copy(name = name) } },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    PrimitivePicker(scheme.primitive) { primitive -> edit { it.copy(primitive = primitive) } }
                }
            }
            item(key = "key") { KeySection(scheme, edit) }
            item(key = "handshake") { HandshakeSection(scheme, session, viewModel) }
            item(key = "bytes") {
                Column {
                    SectionHeader("Bytes", "Where the nonce, AAD, tag and ciphertext live in each frame")
                    ByteSourceField(
                        label = nonceLabel(scheme.primitive),
                        value = scheme.nonce,
                        optional = false,
                        onChange = { source -> edit { it.copy(nonce = source ?: ByteSource.Constant("")) } },
                    )
                    ByteSourceField(
                        label = "Associated data (AAD)",
                        value = scheme.aad,
                        optional = true,
                        onChange = { source -> edit { it.copy(aad = source) } },
                    )
                    if (scheme.primitive.authenticated) {
                        IntField(
                            resetKey = scheme.id,
                            label = "Tag length in bytes",
                            value = scheme.tagLength,
                            onChange = { length -> edit { it.copy(tagLength = length) } },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        ByteSourceField(
                            label = "Tag, when it is not appended to the ciphertext",
                            value = scheme.tag,
                            optional = true,
                            onChange = { source -> edit { it.copy(tag = source) } },
                        )
                    }
                    CiphertextRangeField(scheme, edit)
                }
            }
            item(key = "options") {
                Column {
                    SectionHeader("Options")
                    if (scheme.primitive == CipherPrimitive.AES_CBC || scheme.primitive == CipherPrimitive.AES_ECB) {
                        SwitchRow(
                            label = "Plaintext is PKCS#5 padded",
                            checked = scheme.padded,
                            onCheckedChange = { padded -> edit { it.copy(padded = padded) } },
                        )
                    }
                    SwitchRow(
                        label = "Counters are little-endian by default",
                        checked = scheme.counterLittleEndian,
                        onCheckedChange = { little -> edit { it.copy(counterLittleEndian = little) } },
                    )
                    SwitchRow(
                        label = "Reversed block order (Telink-style)",
                        checked = scheme.byteOrder == CipherByteOrder.REVERSED_BLOCKS,
                        onCheckedChange = { reversed ->
                            edit {
                                it.copy(
                                    byteOrder = if (reversed) {
                                        CipherByteOrder.REVERSED_BLOCKS
                                    } else {
                                        CipherByteOrder.NATURAL
                                    },
                                )
                            }
                        },
                    )
                    EvidenceTextField(
                        resetKey = scheme.id,
                        label = "Notes",
                        value = scheme.notes,
                        onChange = { notes -> edit { it.copy(notes = notes) } },
                        lines = 3,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
            item(key = "match") { MatchSection(scheme, session, edit) }
            item(key = "try") { TryItPanel(scheme, session, state, cache, viewModel, expanded) }
            item(key = "close") {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = { viewModel.editCipher(null) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        Text("Done")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrimitivePicker(selected: CipherPrimitive, onSelect: (CipherPrimitive) -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            "Primitive",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CipherPrimitive.entries.forEach { primitive ->
                androidx.compose.material3.FilterChip(
                    selected = primitive == selected,
                    onClick = { onSelect(primitive) },
                    label = { Text(primitiveLabel(primitive)) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeySection(scheme: CipherScheme, edit: ((CipherScheme) -> CipherScheme) -> Unit) {
    var encoding by remember(scheme.id) { mutableStateOf(KeyEncoding.HEX) }
    var typed by remember(scheme.id) { mutableStateOf("") }
    val normalized = KeyTools.normalize(typed, encoding)
    Column {
        SectionHeader("Key", "Stays on this device; stripped from a shared bundle unless you opt in")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyEncoding.entries.forEach { option ->
                androidx.compose.material3.FilterChip(
                    selected = option == encoding,
                    onClick = { encoding = option },
                    label = { Text(option.label) },
                )
            }
        }
        EvidenceTextField(
            resetKey = scheme.id,
            label = "Paste the key (${encoding.label.lowercase()})",
            value = typed,
            onChange = { raw ->
                typed = raw
                KeyTools.normalize(raw, encoding)?.takeIf { it.isNotEmpty() }?.let { hex ->
                    edit { it.copy(keyHex = hex) }
                }
            },
            mono = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            when {
                typed.isBlank() && scheme.keyHex.isEmpty() -> "No key yet, so nothing decrypts."
                typed.isBlank() -> "Holding a ${scheme.keyHex.length / 2}-byte key."
                normalized == null -> "That is not valid ${encoding.label.lowercase()}."
                else -> "${normalized.length / 2} bytes: ${hexGrouped(normalized, 8)}"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (typed.isNotBlank() && normalized == null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        DropdownField(
            label = "Derivation applied to that key",
            selected = derivationLabel(scheme.keyDerivation),
            options = KeyDerivation.entries.map(::derivationLabel),
            onSelect = { label ->
                val derivation = KeyDerivation.entries.firstOrNull { derivationLabel(it) == label }
                    ?: KeyDerivation.RAW
                edit { it.copy(keyDerivation = derivation) }
            },
            emptyLabel = derivationLabel(KeyDerivation.RAW),
            modifier = Modifier.padding(top = 8.dp),
        )
        if (scheme.keyDerivation == KeyDerivation.HMAC_SHA256_WITH_SALT) {
            HexField(
                resetKey = scheme.id,
                label = "Salt",
                value = scheme.keySaltHex,
                onChange = { hex -> edit { it.copy(keySaltHex = hex) } },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (scheme.keyDerivation == KeyDerivation.AES_ECB_OF_CONSTANT) {
            HexField(
                resetKey = scheme.id,
                label = "Constant block",
                value = scheme.keyConstantHex,
                onChange = { hex -> edit { it.copy(keyConstantHex = hex) } },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Builds a session key out of two captured frames.
 *
 * This is the general form of every handshake seen in the wild: each side contributes bytes, and
 * a digest or a block-cipher step under a long-term key turns them into the session key. Nothing
 * about it names an ecosystem.
 */
@Composable
private fun HandshakeSection(
    scheme: CipherScheme,
    session: CaptureSession,
    viewModel: SessionsViewModel,
) {
    var open by remember(scheme.id) { mutableStateOf(false) }
    var firstEventId by remember(scheme.id) { mutableStateOf<String?>(null) }
    var firstOffset by remember(scheme.id) { mutableStateOf(0) }
    var firstLength by remember(scheme.id) { mutableStateOf(8) }
    var secondEventId by remember(scheme.id) { mutableStateOf<String?>(null) }
    var secondOffset by remember(scheme.id) { mutableStateOf(0) }
    var secondLength by remember(scheme.id) { mutableStateOf(8) }
    var derivation by remember(scheme.id) { mutableStateOf(KeyDerivation.RAW) }
    var longTermHex by remember(scheme.id) { mutableStateOf<String?>(null) }
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Derive from handshake",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Show") }
        }
        if (!open) return@Column
        Text(
            "Pick the two frames that carry the randoms, then how they become a key.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HandshakePartRow(
            title = "Part 1",
            events = session.events,
            eventId = firstEventId,
            offset = firstOffset,
            length = firstLength,
            resetKey = scheme.id,
            onEvent = { firstEventId = it },
            onOffset = { firstOffset = it },
            onLength = { firstLength = it },
        )
        HandshakePartRow(
            title = "Part 2",
            events = session.events,
            eventId = secondEventId,
            offset = secondOffset,
            length = secondLength,
            resetKey = scheme.id + "-2",
            onEvent = { secondEventId = it },
            onOffset = { secondOffset = it },
            onLength = { secondLength = it },
        )
        DropdownField(
            label = "How the parts become a key",
            selected = derivationLabel(derivation),
            options = KeyDerivation.entries.map(::derivationLabel),
            onSelect = { label ->
                derivation = KeyDerivation.entries.firstOrNull { derivationLabel(it) == label }
                    ?: KeyDerivation.RAW
            },
            emptyLabel = derivationLabel(KeyDerivation.RAW),
        )
        if (derivation == KeyDerivation.AES_ECB_OF_CONSTANT ||
            derivation == KeyDerivation.HMAC_SHA256_WITH_SALT
        ) {
            HexField(
                resetKey = scheme.id,
                label = "Long-term key",
                value = longTermHex,
                onChange = { longTermHex = it },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedButton(
            onClick = {
                val parts = buildList {
                    firstEventId?.let { add(HandshakePart("part 1", it, ByteSource.FrameBytes(firstOffset, firstLength))) }
                    secondEventId?.let { add(HandshakePart("part 2", it, ByteSource.FrameBytes(secondOffset, secondLength))) }
                }
                viewModel.deriveCipherKey(
                    scheme.id,
                    HandshakeRule(parts = parts, derivation = derivation, keyHex = longTermHex.orEmpty()),
                )
            },
            enabled = firstEventId != null || secondEventId != null,
            modifier = Modifier.heightIn(min = 48.dp).padding(top = 4.dp),
        ) {
            Text("Derive and store as this scheme's key")
        }
    }
}

@Composable
private fun HandshakePartRow(
    title: String,
    events: List<BleEvent>,
    eventId: String?,
    offset: Int,
    length: Int,
    resetKey: Any?,
    onEvent: (String?) -> Unit,
    onOffset: (Int) -> Unit,
    onLength: (Int) -> Unit,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        EventPicker(title, events, eventId, onEvent)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IntField(
                resetKey = resetKey,
                label = "Offset",
                value = offset,
                onChange = { onOffset(it ?: 0) },
                modifier = Modifier.weight(1f),
            )
            IntField(
                resetKey = resetKey,
                label = "Length",
                value = length,
                onChange = { onLength(it ?: 0) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun EventPicker(
    label: String,
    events: List<BleEvent>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val selected = events.firstOrNull { it.id == selectedId }
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(
                    selected?.let { eventChoiceLabel(it) } ?: "Pick a frame",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                events.take(MAX_PICKER_EVENTS).forEach { event ->
                    DropdownMenuItem(
                        text = { Text(eventChoiceLabel(event), fontFamily = MonoFamily) },
                        onClick = {
                            open = false
                            onSelect(event.id)
                        },
                    )
                }
                if (events.size > MAX_PICKER_EVENTS) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                "…${events.size - MAX_PICKER_EVENTS} more; narrow the timeline filter first",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                        onClick = { open = false },
                        enabled = false,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MatchSection(
    scheme: CipherScheme,
    session: CaptureSession,
    edit: ((CipherScheme) -> CipherScheme) -> Unit,
) {
    Column {
        SectionHeader("Which frames", "An empty matcher claims every frame that carries a payload")
        val channels = remember(session.events) {
            session.events.mapNotNull { it.characteristicUuid }.distinct().sorted()
        }
        DropdownField(
            label = "Characteristic",
            selected = scheme.match.characteristicUuid?.let(::shortUuid),
            options = channels.map(::shortUuid),
            onSelect = { label ->
                val uuid = channels.firstOrNull { shortUuid(it) == label }
                edit { it.copy(match = it.match.copy(characteristicUuid = uuid)) }
            },
            emptyLabel = "Any characteristic",
        )
        Text(
            "Direction",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.FilterChip(
                selected = scheme.match.direction == null,
                onClick = { edit { it.copy(match = it.match.copy(direction = null)) } },
                label = { Text("Any") },
            )
            EventDirection.entries.forEach { direction ->
                androidx.compose.material3.FilterChip(
                    selected = scheme.match.direction == direction,
                    onClick = { edit { it.copy(match = it.match.copy(direction = direction)) } },
                    label = { Text(directionLabel(direction)) },
                )
            }
        }
        HexField(
            resetKey = scheme.id,
            label = "Payload starts with",
            value = scheme.match.payloadPrefixHex,
            onChange = { hex -> edit { it.copy(match = it.match.copy(payloadPrefixHex = hex)) } },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        IntField(
            resetKey = scheme.id,
            label = "Minimum length in bytes",
            value = scheme.match.minLength,
            onChange = { length -> edit { it.copy(match = it.match.copy(minLength = length ?: 0)) } },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CiphertextRangeField(scheme: CipherScheme, edit: ((CipherScheme) -> CipherScheme) -> Unit) {
    val range = scheme.ciphertextRange
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            "Ciphertext range",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Length -1 runs to the end of the frame; drop-from-end excludes a trailing counter or MIC.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SignedIntField(
                resetKey = scheme.id,
                label = "Offset",
                value = range.offset,
                onChange = { offset -> edit { it.copy(ciphertextRange = range.copy(offset = offset ?: 0)) } },
                modifier = Modifier.weight(1f),
            )
            SignedIntField(
                resetKey = scheme.id,
                label = "Length",
                value = range.length,
                onChange = { length -> edit { it.copy(ciphertextRange = range.copy(length = length ?: -1)) } },
                modifier = Modifier.weight(1f),
            )
            IntField(
                resetKey = scheme.id,
                label = "Drop end",
                value = range.dropFromEnd,
                onChange = { drop -> edit { it.copy(ciphertextRange = range.copy(dropFromEnd = drop ?: 0)) } },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Byte-source picker
// ---------------------------------------------------------------------------------------------

/** The kinds a picker offers; [ByteSource.Composite] is the list itself, never one row. */
private enum class SourceKind(val label: String) {
    CONSTANT("Constant"),
    FRAME("Frame bytes"),
    MAC("Address"),
    MAC_REVERSED("Address reversed"),
    COUNTER("Counter"),
    SLICE_OF_ADDRESS("Address slice"),
}

private fun kindOf(source: ByteSource): SourceKind = when (source) {
    is ByteSource.Constant -> SourceKind.CONSTANT
    is ByteSource.FrameBytes -> SourceKind.FRAME
    ByteSource.MacAddress -> SourceKind.MAC
    ByteSource.MacAddressReversed -> SourceKind.MAC_REVERSED
    is ByteSource.Counter -> SourceKind.COUNTER
    is ByteSource.Slice -> SourceKind.SLICE_OF_ADDRESS
    is ByteSource.Composite -> SourceKind.CONSTANT
}

private fun defaultFor(kind: SourceKind): ByteSource = when (kind) {
    // A real byte, not an empty one: an empty constant is "unset" and its editor row vanishes.
    SourceKind.CONSTANT -> ByteSource.Constant("00")
    SourceKind.FRAME -> ByteSource.FrameBytes(0, 1)
    SourceKind.MAC -> ByteSource.MacAddress
    SourceKind.MAC_REVERSED -> ByteSource.MacAddressReversed
    SourceKind.COUNTER -> ByteSource.Counter()
    SourceKind.SLICE_OF_ADDRESS -> ByteSource.Slice(ByteSource.MacAddressReversed, 0, 4)
}

/** A [ByteSource] is one row or a list of them; the list is what becomes a [ByteSource.Composite]. */
private fun partsOf(source: ByteSource?): List<ByteSource> = when (source) {
    null -> emptyList()
    is ByteSource.Composite -> source.parts
    is ByteSource.Constant -> if (source.hex.isEmpty()) emptyList() else listOf(source)
    else -> listOf(source)
}

private fun composeOf(parts: List<ByteSource>, optional: Boolean): ByteSource? = when {
    parts.isEmpty() -> if (optional) null else ByteSource.Constant("")
    parts.size == 1 -> parts.single()
    else -> ByteSource.Composite(parts)
}

/**
 * Edits one nonce, AAD or tag as an ordered list of parts.
 *
 * Every real scheme's nonce is a concatenation, so the list *is* the model rather than a
 * convenience over it: adding a part builds the [ByteSource.Composite] the engine resolves.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ByteSourceField(
    label: String,
    value: ByteSource?,
    optional: Boolean,
    onChange: (ByteSource?) -> Unit,
) {
    val parts = partsOf(value)
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (parts.isNotEmpty() && optional) {
                IconButton(
                    onClick = { onChange(null) },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear $label", Modifier.size(18.dp))
                }
            }
        }
        parts.forEachIndexed { index, part ->
            BytePartRow(
                part = part,
                index = index,
                total = parts.size,
                onChange = { updated ->
                    onChange(composeOf(parts.toMutableList().also { it[index] = updated }, optional))
                },
                onRemove = {
                    onChange(composeOf(parts.toMutableList().also { it.removeAt(index) }, optional))
                },
            )
        }
        AssistChip(
            onClick = { onChange(composeOf(parts + ByteSource.FrameBytes(0, 1), optional)) },
            label = { Text(if (parts.isEmpty()) "Add bytes" else "Add another part") },
            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, Modifier.size(16.dp)) },
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BytePartRow(
    part: ByteSource,
    index: Int,
    total: Int,
    onChange: (ByteSource) -> Unit,
    onRemove: () -> Unit,
) {
    val kind = kindOf(part)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (total > 1) "Part ${index + 1}" else "Bytes",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRemove, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Remove part", Modifier.size(16.dp))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SourceKind.entries.forEach { option ->
                androidx.compose.material3.FilterChip(
                    selected = option == kind,
                    onClick = { if (option != kind) onChange(defaultFor(option)) },
                    label = { Text(option.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        when (part) {
            is ByteSource.Constant -> HexField(
                resetKey = index,
                label = "Constant bytes",
                value = part.hex,
                onChange = { hex -> onChange(ByteSource.Constant(hex.orEmpty())) },
                modifier = Modifier.fillMaxWidth(),
            )

            is ByteSource.FrameBytes -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SignedIntField(
                    resetKey = index,
                    label = "Offset",
                    value = part.offset,
                    onChange = { offset -> onChange(part.copy(offset = offset ?: 0)) },
                    modifier = Modifier.weight(1f),
                )
                SignedIntField(
                    resetKey = index,
                    label = "Length",
                    value = part.length,
                    onChange = { length -> onChange(part.copy(length = length ?: -1)) },
                    modifier = Modifier.weight(1f),
                )
            }

            is ByteSource.Slice -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SignedIntField(
                    resetKey = index,
                    label = "From byte",
                    value = part.offset,
                    onChange = { offset -> onChange(part.copy(offset = offset ?: 0)) },
                    modifier = Modifier.weight(1f),
                )
                SignedIntField(
                    resetKey = index,
                    label = "Length",
                    value = part.length,
                    onChange = { length -> onChange(part.copy(length = length ?: -1)) },
                    modifier = Modifier.weight(1f),
                )
            }

            is ByteSource.Counter -> Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IntField(
                    resetKey = index,
                    label = "Width",
                    value = part.width,
                    onChange = { width -> onChange(part.copy(width = width ?: 4)) },
                    modifier = Modifier.width(110.dp),
                )
                TristateRow(
                    label = "Little-endian",
                    value = part.littleEndian,
                    onChange = { little -> onChange(part.copy(littleEndian = little)) },
                    modifier = Modifier.weight(1f),
                )
            }

            ByteSource.MacAddress, ByteSource.MacAddressReversed -> Text(
                "The session's device address, six bytes.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            is ByteSource.Composite -> Text(
                "Nested composites are flattened; remove this part and add its pieces instead.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Try it
// ---------------------------------------------------------------------------------------------

/**
 * Runs the scheme against one chosen frame and shows every intermediate value.
 *
 * Without the nonce and AAD the engine actually used, a scheme that produces noise is
 * undebuggable: the operator cannot tell a wrong key from an offset that is one byte out.
 */
@Composable
private fun TryItPanel(
    scheme: CipherScheme,
    session: CaptureSession,
    state: SessionsUiState,
    cache: DecryptCache,
    viewModel: SessionsViewModel,
    expanded: Boolean,
) {
    val matches = remember(cache, scheme.id) { cache.matchesOf(scheme.id) }
    val chosen = remember(state.tryEventId, matches, session.events) {
        state.tryEventId?.let { id -> session.events.firstOrNull { it.id == id } }
            ?: matches.firstOrNull()
    }
    Column {
        SectionHeader(
            "Try it",
            "Pick a frame and watch what the scheme makes of it",
        )
        if (session.events.isEmpty()) {
            Text(
                "This session has no captured traffic to try against yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        EventPicker(
            label = if (matches.isEmpty()) "Frame (this scheme matches none)" else "Frame",
            events = if (matches.isEmpty()) session.events else matches,
            selectedId = chosen?.id,
            onSelect = viewModel::setTryEvent,
        )
        if (chosen == null) return@Column
        val result = remember(scheme, chosen.id, session.device.address) {
            cache.preview(scheme, chosen, session.device.address)
        }
        TryItResult(result, chosen, expanded)
    }
}

@Composable
private fun TryItResult(result: DecryptResult, event: BleEvent, expanded: Boolean) {
    val bytesShown = if (expanded) 32 else 16
    Column(Modifier.padding(top = 8.dp)) {
        LabeledValue("Frame", hexGrouped(event.payloadHex, bytesShown), mono = true)
        LabeledValue("Ciphertext", hexGrouped(result.ciphertextHex, bytesShown).ifEmpty { "—" }, mono = true)
        LabeledValue("Nonce / IV", hexGrouped(result.nonceHex, bytesShown).ifEmpty { "—" }, mono = true)
        LabeledValue("AAD", hexGrouped(result.aadHex, bytesShown).ifEmpty { "—" }, mono = true)
        val plaintext = result.plaintextHex
        if (plaintext == null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(
                    result.error ?: "Nothing came out",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(10.dp),
                )
            }
            return@Column
        }
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Column(Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LockOpen, contentDescription = null, Modifier.size(16.dp))
                    Text(
                        "  " + verificationLabel(result.verified),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Text(
                    hexGrouped(plaintext, bytesShown * 2),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = MonoFamily,
                )
                Text(
                    asciiOf(plaintext, bytesShown * 2),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = MonoFamily,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Export hook
// ---------------------------------------------------------------------------------------------

/** The 'Include keys' checkbox and the sentence explaining what ticking it means. */
@Composable
internal fun IncludeSecretsRow(
    session: CaptureSession,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val secrets = remember(session.ciphers) { SecretRedaction.secretCount(session) }
    if (secrets == 0) return
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
            Text(
                "Include cipher keys in the bundle",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            if (checked) {
                "The bundle will carry $secrets key(s) in clear text. Anyone you send it to can " +
                    "decrypt and control this device."
            } else {
                "$secrets key(s) will be stripped; the scheme's shape still travels, so the " +
                    "recipient can decrypt with their own key."
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (checked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Labels
// ---------------------------------------------------------------------------------------------

internal fun primitiveLabel(primitive: CipherPrimitive): String = when (primitive) {
    CipherPrimitive.AES_ECB -> "AES-ECB"
    CipherPrimitive.AES_CBC -> "AES-CBC"
    CipherPrimitive.AES_CTR -> "AES-CTR"
    CipherPrimitive.AES_GCM -> "AES-GCM"
    CipherPrimitive.AES_CCM -> "AES-CCM"
    CipherPrimitive.CHACHA20_POLY1305 -> "ChaCha20-Poly1305"
    CipherPrimitive.XOR -> "XOR"
}

/** True when the primitive carries a tag, i.e. when "it decrypted" is evidence about the key. */
internal val CipherPrimitive.authenticated: Boolean
    get() = this == CipherPrimitive.AES_GCM ||
        this == CipherPrimitive.AES_CCM ||
        this == CipherPrimitive.CHACHA20_POLY1305

private fun nonceLabel(primitive: CipherPrimitive): String = when (primitive) {
    CipherPrimitive.AES_CBC -> "Initialisation vector (16 bytes)"
    CipherPrimitive.AES_CTR -> "Counter block"
    CipherPrimitive.AES_ECB, CipherPrimitive.XOR -> "Unused by this primitive"
    else -> "Nonce"
}

internal fun derivationLabel(derivation: KeyDerivation): String = when (derivation) {
    KeyDerivation.RAW -> "Use as-is"
    KeyDerivation.SHA256 -> "SHA-256 of the key"
    KeyDerivation.MD5 -> "MD5 of the key"
    KeyDerivation.HMAC_SHA256_WITH_SALT -> "HMAC-SHA256 over a salt"
    KeyDerivation.AES_ECB_OF_CONSTANT -> "AES-ECB of a constant"
}

internal fun verificationLabel(verified: Boolean?): String = when (verified) {
    true -> "Decrypted, tag verified"
    false -> "Tag did not verify"
    null -> "Decrypted, unauthenticated - judge it by the plaintext"
}

private fun matchSummary(scheme: CipherScheme): String {
    val parts = buildList {
        scheme.match.characteristicUuid?.let { add(shortUuid(it)) }
        scheme.match.direction?.let { add(directionLabel(it)) }
        scheme.match.payloadPrefixHex?.let { add("starts $it") }
        if (scheme.match.minLength > 0) add("≥${scheme.match.minLength} B")
    }
    return if (parts.isEmpty()) "every frame" else parts.joinToString(" · ")
}

private fun eventChoiceLabel(event: BleEvent): String =
    "${formatClockMicros(event.timestampEpochMicros)}  ${hexGrouped(event.payloadHex, 6)}"

private const val MAX_PICKER_EVENTS = 60

/** How many claimed frames a scheme card decrypts to report whether the key works. */
private const val HEALTH_SAMPLE = 24
