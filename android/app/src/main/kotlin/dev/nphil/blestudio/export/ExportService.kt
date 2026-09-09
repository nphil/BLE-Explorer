package dev.nphil.blestudio.export

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import dev.nphil.blestudio.BuildConfig
import dev.nphil.blestudio.crypto.SecretRedaction
import dev.nphil.blestudio.data.SessionStore
import dev.nphil.blestudio.model.CaptureSession
import dev.nphil.blestudio.model.EvidenceBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Writes shareable artefacts into the export cache and hands out content URIs for them.
 *
 * Files land in `cacheDir/exports` (declared as the `exports` cache-path of the
 * `${applicationId}.files` provider) and are written to a temporary file that is renamed into
 * place, so a share target never observes a half-written export. Anything older than a week is
 * pruned: the cache is a courier, not a store — `sessions/` is the store.
 */
class ExportService(context: Context, private val sessions: SessionStore) {
    private val appContext: Context = context.applicationContext
    private val authority = "${appContext.packageName}.files"
    private val directory = File(appContext.cacheDir, EXPORT_DIRECTORY)

    enum class ExportKind(val fileSuffix: String, val label: String) {
        EVIDENCE("evidence", "evidence bundle"),
        HA_PROFILE("ha-profile", "Home Assistant profile"),
    }

    data class Export(
        val kind: ExportKind,
        val file: File,
        val uri: Uri,
        val sizeBytes: Long,
    )

    /** Android 13+ shows its own clipboard confirmation; duplicating it is noise. */
    val showsSystemClipboardConfirmation: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * Full-fidelity evidence: what is on disk, plus the app version that captured it.
     *
     * @param includeSecrets when false - the default - every [dev.nphil.blestudio.model.CipherScheme]
     *   loses its key, salt and constant while keeping its shape, so the bundle can be mailed to
     *   whoever is writing the integration without handing them the operator's credential for
     *   someone else's device. The recipient sees the scheme is AES-CCM with this nonce layout and
     *   supplies their own key.
     */
    suspend fun exportEvidenceBundle(
        session: CaptureSession,
        includeSecrets: Boolean = false,
    ): Export = withContext(Dispatchers.IO) {
        val shared = if (includeSecrets) session else SecretRedaction.redact(session)
        val bundle = EvidenceBundle(appVersion = BuildConfig.VERSION_NAME, session = shared)
        write(session, ExportKind.EVIDENCE, sessions.json.encodeToString(bundle))
    }

    /**
     * The installable profile, or the typed [ProfileNotExportableException] describing every
     * command that was left out and why.
     */
    suspend fun exportHaProfile(session: CaptureSession): Result<Export> =
        HaProfileBuilder.build(session).mapCatching { profile ->
            withContext(Dispatchers.IO) {
                write(session, ExportKind.HA_PROFILE, HaProfileBuilder.encode(profile))
            }
        }

    fun shareIntent(export: Export): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_JSON
            putExtra(Intent.EXTRA_STREAM, export.uri)
            putExtra(Intent.EXTRA_TITLE, export.file.name)
            putExtra(Intent.EXTRA_SUBJECT, export.file.name)
            clipData = ClipData.newRawUri(export.file.name, export.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share ${export.kind.label}").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Puts the profile JSON on the clipboard; returns its character count.
     *
     * Validation and JSON encoding run on [Dispatchers.Default]; only the clipboard hand-off stays
     * on the caller's thread, because that is what the system attributes to the focused window.
     */
    suspend fun copyHaProfile(session: CaptureSession): Result<Int> {
        val encoded = withContext(Dispatchers.Default) {
            HaProfileBuilder.build(session).map(HaProfileBuilder::encode)
        }
        return encoded.mapCatching { text ->
            val clipboard = appContext.getSystemService(ClipboardManager::class.java)
                ?: throw IOException("Clipboard is unavailable")
            clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
            text.length
        }
    }

    suspend fun pruneOldExports(now: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        pruneOnDisk(now)
    }

    private fun write(session: CaptureSession, kind: ExportKind, text: String): Export {
        pruneOnDisk(System.currentTimeMillis())
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Could not create ${directory.absolutePath}")
        }
        val stamp = STAMP_FORMAT.format(Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZoneId.systemDefault()))
        val target = File(directory, "${fileSlug(session.name)}-$stamp.${kind.fileSuffix}.json")
        val temp = File(directory, ".${target.name}.tmp")
        try {
            temp.writeText(text)
            if (!temp.renameTo(target)) throw IOException("Could not publish ${target.name}")
        } finally {
            if (temp.exists()) temp.delete()
        }
        return Export(
            kind = kind,
            file = target,
            uri = FileProvider.getUriForFile(appContext, authority, target),
            sizeBytes = target.length(),
        )
    }

    private fun pruneOnDisk(now: Long) {
        val cutoff = now - RETENTION_MS
        directory.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) file.delete()
        }
    }

    private fun fileSlug(name: String): String {
        val slug = StringBuilder(name.length.coerceAtMost(MAX_SLUG_LENGTH))
        var pendingSeparator = false
        for (character in name.trim().lowercase()) {
            if (character.isLetterOrDigit() && character.code < 128) {
                if (pendingSeparator && slug.isNotEmpty()) slug.append('-')
                pendingSeparator = false
                if (slug.length >= MAX_SLUG_LENGTH) break
                slug.append(character)
            } else {
                pendingSeparator = true
            }
        }
        return slug.toString().ifEmpty { "session" }
    }

    private companion object {
        const val EXPORT_DIRECTORY = "exports"
        const val MIME_JSON = "application/json"
        const val CLIP_LABEL = "BLE Studio Home Assistant profile"
        const val MAX_SLUG_LENGTH = 40
        const val RETENTION_MS = 7L * 24 * 60 * 60 * 1_000
        val STAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
