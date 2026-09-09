package dev.nphil.blueshark.crypto

import dev.nphil.blueshark.model.CaptureSession
import dev.nphil.blueshark.model.CipherScheme

/**
 * Strips key material from a session that is about to leave the device.
 *
 * The shape of a scheme is evidence and travels: a recipient who knows the frame is AES-CCM with
 * a 4-byte MIC and this nonce layout can do the work with their own key. The key itself is not
 * evidence, it is the operator's credential for someone else's device, and an evidence bundle
 * gets mailed around. So the default is to publish the shape and withhold the secret.
 *
 * Redaction is deliberately not reversible and not marked in a way a tool could undo: the
 * exported bundle carries an empty key, which the app's own importer treats as "needs a key".
 */
object SecretRedaction {

    /** The fields that are the operator's secret rather than the device's behaviour. */
    fun redact(scheme: CipherScheme): CipherScheme = scheme.copy(
        keyHex = "",
        keySaltHex = null,
        keyConstantHex = null,
    )

    /** True when [scheme] actually holds something worth withholding. */
    fun hasSecret(scheme: CipherScheme): Boolean =
        scheme.keyHex.isNotEmpty() || scheme.keySaltHex != null || scheme.keyConstantHex != null

    /**
     * [session] with every scheme's key material removed.
     *
     * Returns the same instance when there is nothing to strip, so exporting a session with no
     * schemes copies nothing.
     */
    fun redact(session: CaptureSession): CaptureSession {
        if (session.ciphers.none(::hasSecret)) return session
        return session.copy(ciphers = session.ciphers.map(::redact))
    }

    /** How many schemes in [session] would lose key material on export. */
    fun secretCount(session: CaptureSession): Int = session.ciphers.count(::hasSecret)
}
