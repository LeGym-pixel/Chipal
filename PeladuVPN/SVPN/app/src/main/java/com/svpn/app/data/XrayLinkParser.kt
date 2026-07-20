package com.svpn.app.data

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.net.URLDecoder

/**
 * Parses the standard share-link formats for the four Xray-family
 * protocols. These are the same formats produced by v2rayN, v2rayNG,
 * NekoBox, and similar tools, so links copied from any of those should
 * work here too.
 *
 * Auto-detection is by prefix, same idea AmneziaVPN uses (it's open
 * source): vless://, vmess://, trojan://, ss:// each get routed to their
 * own parser; anything else falls through to the WireGuard/.conf parser.
 */
object XrayLinkParser {

    class ParseException(message: String) : Exception(message)

    fun detect(raw: String): Protocol? {
        val t = raw.trim()
        return when {
            t.startsWith("vless://") -> Protocol.VLESS
            t.startsWith("vmess://") -> Protocol.VMESS
            t.startsWith("trojan://") -> Protocol.TROJAN
            t.startsWith("ss://") -> Protocol.SHADOWSOCKS
            else -> null
        }
    }

    fun parse(raw: String, defaultName: String): ServerProfile {
        return when (detect(raw)) {
            Protocol.VLESS -> parseVless(raw, defaultName)
            Protocol.VMESS -> parseVmess(raw, defaultName)
            Protocol.TROJAN -> parseTrojan(raw, defaultName)
            Protocol.SHADOWSOCKS -> parseShadowsocks(raw, defaultName)
            else -> throw ParseException("Не удалось определить протокол ссылки")
        }
    }

    // --- VLESS: vless://uuid@host:port?params#name ---
    private fun parseVless(raw: String, defaultName: String): ServerProfile {
        val uri = safeParseUri(raw)
        val uuid = uri.userInfo?.takeIf { it.isNotBlank() }
            ?: throw ParseException("В vless:// ссылке отсутствует UUID")
        val host = uri.host ?: throw ParseException("В vless:// ссылке отсутствует адрес сервера")
        val port = if (uri.port != -1) uri.port.toString() else "443"

        fun p(key: String) = uri.getQueryParameter(key)?.trim().orEmpty()

        return ServerProfile(
            name = fragmentName(uri, defaultName),
            protocol = Protocol.VLESS,
            endpoint = "$host:$port",
            xrayId = uuid,
            xrayHost = host,
            xrayPort = port,
            xrayNetwork = p("type").ifBlank { "tcp" },
            xraySecurity = p("security").ifBlank { "none" },
            xrayFlow = p("flow"),
            xraySni = p("sni"),
            xrayPublicKey = p("pbk"),
            xrayShortId = p("sid"),
            xrayFingerprint = p("fp").ifBlank { "chrome" },
            xraySpiderX = p("spx"),
            xrayWsPath = p("path"),
            xrayWsHost = p("host")
        )
    }

    // --- Trojan: trojan://password@host:port?params#name (same shape as vless, no UUID/flow) ---
    private fun parseTrojan(raw: String, defaultName: String): ServerProfile {
        val uri = safeParseUri(raw)
        val password = uri.userInfo?.takeIf { it.isNotBlank() }
            ?: throw ParseException("В trojan:// ссылке отсутствует пароль")
        val host = uri.host ?: throw ParseException("В trojan:// ссылке отсутствует адрес сервера")
        val port = if (uri.port != -1) uri.port.toString() else "443"

        fun p(key: String) = uri.getQueryParameter(key)?.trim().orEmpty()

        return ServerProfile(
            name = fragmentName(uri, defaultName),
            protocol = Protocol.TROJAN,
            endpoint = "$host:$port",
            xrayId = password,
            xrayHost = host,
            xrayPort = port,
            xrayNetwork = p("type").ifBlank { "tcp" },
            xraySecurity = p("security").ifBlank { "tls" },
            xraySni = p("sni").ifBlank { p("peer") },
            xrayFingerprint = p("fp").ifBlank { "chrome" },
            xrayWsPath = p("path"),
            xrayWsHost = p("host")
        )
    }

    // --- VMess: vmess://base64(json) ---
    private fun parseVmess(raw: String, defaultName: String): ServerProfile {
        val b64 = raw.trim().removePrefix("vmess://")
        val jsonText = try {
            flexibleBase64Decode(b64)
        } catch (e: Exception) {
            throw ParseException("Не удалось декодировать vmess:// ссылку")
        }
        val json = try {
            JSONObject(jsonText)
        } catch (e: Exception) {
            throw ParseException("vmess:// ссылка повреждена (не JSON внутри)")
        }

        val host = json.optString("add").takeIf { it.isNotBlank() }
            ?: throw ParseException("В vmess:// ссылке отсутствует адрес сервера")
        val port = json.optString("port", "443").ifBlank { "443" }
        val id = json.optString("id").takeIf { it.isNotBlank() }
            ?: throw ParseException("В vmess:// ссылке отсутствует id")
        val name = json.optString("ps").takeIf { it.isNotBlank() } ?: defaultName
        val tls = json.optString("tls")

        return ServerProfile(
            name = name,
            protocol = Protocol.VMESS,
            endpoint = "$host:$port",
            xrayId = id,
            xrayHost = host,
            xrayPort = port,
            xrayNetwork = json.optString("net", "tcp").ifBlank { "tcp" },
            xraySecurity = if (tls.equals("tls", true)) "tls" else "none",
            xraySni = json.optString("sni").ifBlank { json.optString("host") },
            xrayFingerprint = json.optString("fp").ifBlank { "chrome" },
            xrayAlterId = json.optString("aid", "0").ifBlank { "0" },
            xrayWsPath = json.optString("path"),
            xrayWsHost = json.optString("host")
        )
    }

    // --- Shadowsocks: ss://base64(method:password)@host:port#name, or fully-encoded legacy form ---
    private fun parseShadowsocks(raw: String, defaultName: String): ServerProfile {
        val withoutPrefix = raw.trim().removePrefix("ss://")
        val hashIdx = withoutPrefix.indexOf('#')
        val mainPart = if (hashIdx >= 0) withoutPrefix.substring(0, hashIdx) else withoutPrefix
        val name = if (hashIdx >= 0) {
            try { URLDecoder.decode(withoutPrefix.substring(hashIdx + 1), "UTF-8") } catch (_: Exception) { defaultName }
        } else defaultName

        val atIdx = mainPart.lastIndexOf('@')
        val method: String
        val password: String
        val host: String
        val port: String

        if (atIdx == -1) {
            // Legacy fully-encoded form: base64("method:password@host:port")
            val decoded = try { flexibleBase64Decode(mainPart) } catch (e: Exception) {
                throw ParseException("Не удалось декодировать ss:// ссылку")
            }
            val decAt = decoded.lastIndexOf('@')
            if (decAt == -1) throw ParseException("ss:// ссылка не в ожидаемом формате")
            val methodPass = decoded.substring(0, decAt).split(":", limit = 2)
            val hostPort = decoded.substring(decAt + 1).split(":", limit = 2)
            if (methodPass.size < 2 || hostPort.size < 2) throw ParseException("ss:// ссылка не в ожидаемом формате")
            method = methodPass[0]; password = methodPass[1]
            host = hostPort[0]; port = hostPort[1]
        } else {
            val userInfoRaw = mainPart.substring(0, atIdx)
            val hostPortRaw = mainPart.substring(atIdx + 1).substringBefore('/').substringBefore('?')
            val userInfo = try { flexibleBase64Decode(userInfoRaw) } catch (_: Exception) { userInfoRaw }
            val methodPass = userInfo.split(":", limit = 2)
            val hostPort = hostPortRaw.split(":", limit = 2)
            if (methodPass.size < 2 || hostPort.size < 2) throw ParseException("ss:// ссылка не в ожидаемом формате")
            method = methodPass[0]; password = methodPass[1]
            host = hostPort[0]; port = hostPort[1]
        }

        return ServerProfile(
            name = name,
            protocol = Protocol.SHADOWSOCKS,
            endpoint = "$host:$port",
            xrayId = password,
            xrayHost = host,
            xrayPort = port,
            xrayMethod = method,
            xraySecurity = "none"
        )
    }

    private fun fragmentName(uri: Uri, defaultName: String): String =
        uri.fragment?.let {
            try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
        }?.takeIf { it.isNotBlank() } ?: defaultName

    private fun safeParseUri(raw: String): Uri = try {
        Uri.parse(raw.trim())
    } catch (e: Exception) {
        throw ParseException("Не удалось разобрать ссылку")
    }

    /** Base64 decode tolerant of missing padding and both standard/URL-safe alphabets. */
    private fun flexibleBase64Decode(input: String): String {
        val cleaned = input.trim()
        val attempts = listOf(
            Base64.NO_WRAP or Base64.DEFAULT,
            Base64.NO_WRAP or Base64.URL_SAFE
        )
        var lastError: Exception? = null
        for (flags in attempts) {
            try {
                val padded = padBase64(cleaned)
                return String(Base64.decode(padded, flags), Charsets.UTF_8)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: ParseException("Не удалось декодировать base64")
    }

    private fun padBase64(s: String): String {
        val remainder = s.length % 4
        return if (remainder == 0) s else s + "=".repeat(4 - remainder)
    }
}
