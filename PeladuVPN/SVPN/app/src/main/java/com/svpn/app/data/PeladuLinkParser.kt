package com.svpn.app.data

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Handles PeladuVPN's two supported link formats.
 *
 * "peladu://:peladu:<payload>" is read directly as PeladuVPN's own format —
 * the "peladu://:peladu:" marker is just recognized and stripped (no
 * intermediate "vpn://" string is ever constructed), and its payload is
 * AES-encrypted (real encryption, not just encoding) with a key embedded
 * in the app.
 *
 * "vpn://<payload>" typed/pasted directly is treated as a plain, unofficial
 * link: its payload is just base64 of the config text, no encryption. It
 * still gets imported — the user just gets a warning that it's not an
 * official PeladuVPN config.
 *
 * Being upfront about what this "encryption" actually buys you: the AES key
 * ships inside the APK, so this can't stop someone who's willing to
 * decompile the app from generating their own "official-looking" links.
 * What it does do is stop a link that's just plain base64 (or garbage) from
 * silently being accepted as official — the WireGuard keys inside the
 * config are protected by WireGuard's own crypto regardless of any of this.
 */
object PeladuLinkParser {

    private const val OFFICIAL_PREFIX = "peladu://:peladu:"
    private const val VPN_PREFIX = "vpn://"

    // App-embedded key — see the class-level note above.
    private const val SIGNING_SECRET = "peladu-ouroboros-v1-do-not-treat-as-secure"

    class LinkParseException(message: String) : Exception(message)

    data class LinkImportResult(val profile: ServerProfile, val isOfficial: Boolean)

    fun looksLikeLink(raw: String): Boolean {
        val t = raw.trim()
        return t.startsWith(OFFICIAL_PREFIX) || t.startsWith(VPN_PREFIX)
    }

    fun parse(raw: String, defaultName: String): LinkImportResult {
        val text = raw.trim()

        val isOfficial: Boolean
        val payload: String

        when {
            text.startsWith(OFFICIAL_PREFIX) -> {
                // peladu://:peladu:<payload> is read directly as meaning
                // "this is a vpn:// link, official flavor" — no intermediate
                // string is built, we just take the part after the marker.
                isOfficial = true
                payload = text.removePrefix(OFFICIAL_PREFIX)
            }
            text.startsWith(VPN_PREFIX) -> {
                isOfficial = false
                payload = text.removePrefix(VPN_PREFIX)
            }
            else -> throw LinkParseException("Не похоже на ссылку PeladuVPN (vpn:// или peladu://:peladu:)")
        }

        val configText = if (isOfficial) {
            try {
                decryptAes(payload)
            } catch (e: Exception) {
                throw LinkParseException("Официальная ссылка повреждена или подделана")
            }
        } else {
            try {
                b64Decode(payload)
            } catch (e: Exception) {
                throw LinkParseException("Не удалось разобрать vpn:// ссылку")
            }
        }

        val profile = try {
            WgConfigParser.parse(configText, defaultName)
        } catch (e: WgConfigParser.ParseException) {
            throw LinkParseException("Ссылка распознана, но внутри неё не валидный WireGuard-конфиг")
        }
        return LinkImportResult(profile, isOfficial)
    }

    /** For your own server/tooling to generate real "peladu://:peladu:" links. */
    fun buildOfficialLink(configText: String): String {
        return OFFICIAL_PREFIX + encryptAes(configText)
    }

    // --- AES-GCM, key derived from the embedded passphrase, random IV prefixed to ciphertext ---

    private fun aesKey(): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256").digest(SIGNING_SECRET.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(digest, "AES") // 32 bytes -> AES-256
    }

    private fun encryptAes(plainText: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey(), GCMParameterSpec(128, iv))
        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = iv + cipherBytes
        return Base64.encodeToString(combined, Base64.NO_WRAP or Base64.URL_SAFE)
    }

    private fun decryptAes(payloadB64: String): String {
        val combined = Base64.decode(payloadB64, Base64.NO_WRAP or Base64.URL_SAFE)
        require(combined.size > 12) { "payload too short" }
        val iv = combined.copyOfRange(0, 12)
        val cipherBytes = combined.copyOfRange(12, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aesKey(), GCMParameterSpec(128, iv))
        val plainBytes = cipher.doFinal(cipherBytes)
        return String(plainBytes, Charsets.UTF_8)
    }

    private fun b64Decode(s: String): String =
        String(Base64.decode(s, Base64.NO_WRAP or Base64.URL_SAFE), Charsets.UTF_8)
}
