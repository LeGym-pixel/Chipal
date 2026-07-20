package com.svpn.app.data

/**
 * Parses / serializes standard WireGuard ".conf" text (also used by AmneziaWG).
 *
 * IMPORTANT: this does NOT hardcode which AmneziaWG obfuscation keys exist
 * (Jc, Jmin, Jmax, S1-S4, H1-H4, I1-I5, ...). Real-world configs vary — some
 * servers require fields this app's author never explicitly knew about — so
 * every [Interface] key that isn't one of the standard WireGuard core fields
 * (PrivateKey/Address/DNS/MTU) is preserved verbatim, in its original casing
 * and order, and written back out unchanged. Dropping even one of these
 * fields can make the server silently ignore the handshake, which looks
 * exactly like "connects but doesn't actually work".
 */
object WgConfigParser {

    private val CORE_INTERFACE_KEYS = setOf("privatekey", "address", "dns", "mtu")

    class ParseException(message: String) : Exception(message)

    fun parse(raw: String, defaultName: String): ServerProfile {
        val text = raw.trim()
        if (text.isEmpty()) throw ParseException("Пустой конфиг")

        if (text.contains("[Interface]", ignoreCase = true)) {
            val nameFromConfig = extractNameFromComments(text) ?: extractNameField(text)
            return parseFullConfig(text, nameFromConfig ?: defaultName)
        }

        throw ParseException(
            "Не удалось распознать конфигурацию. Ожидается файл .conf с секциями [Interface] и [Peer]."
        )
    }

    /**
     * Best-effort recovery of the server name the person originally gave it
     * (e.g. in AmneziaVPN), so importing the same server elsewhere doesn't
     * turn it into "Server 482". Looks for a leading comment line like
     * "# frankfurt" or "# Name: frankfurt" before the first section header.
     */
    private fun extractNameFromComments(text: String): String? {
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[")) break
            if (!line.startsWith("#") && !line.startsWith(";")) continue

            var candidate = line.trimStart('#', ';').trim()
            val prefixes = listOf("name:", "name=", "profile:", "profile=", "description:", "description=")
            for (prefix in prefixes) {
                if (candidate.lowercase().startsWith(prefix)) {
                    candidate = candidate.substring(prefix.length).trim()
                    break
                }
            }
            if (candidate.isEmpty() || candidate.length > 60) continue
            if (candidate.all { it == '-' || it == '=' || it == '*' }) continue
            return candidate
        }
        return null
    }

    /** Some exporters put a non-standard "Name = X" key inside [Interface] itself. */
    private fun extractNameField(text: String): String? {
        var inInterface = false
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.startsWith("[")) {
                inInterface = line.trim('[', ']').equals("interface", ignoreCase = true)
                continue
            }
            if (!inInterface) continue
            val idx = line.indexOf('=')
            if (idx == -1) continue
            val key = line.substring(0, idx).trim().lowercase()
            if (key == "name" || key == "profilename") {
                val value = line.substring(idx + 1).trim()
                if (value.isNotEmpty()) return value
            }
        }
        return null
    }

    private fun parseFullConfig(text: String, defaultName: String): ServerProfile {
        val lines = text.lines().map { it.trim() }
        var section = ""
        val iface = mutableMapOf<String, String>()          // lowercased key -> value, for known-field lookups
        val extraInterface = LinkedHashMap<String, String>() // ORIGINAL-case key -> value, for everything else
        val peer = mutableMapOf<String, String>()
        val peladu = mutableMapOf<String, String>()          // lowercased key -> value, [Peladu] block only
        var sawPeladuSection = false

        for (line in lines) {
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            if (line.startsWith("[")) {
                section = line.trim('[', ']').lowercase()
                if (section == "peladu") sawPeladuSection = true
                continue
            }
            val idx = line.indexOf('=')
            if (idx == -1) continue
            val originalKey = line.substring(0, idx).trim()
            val lowerKey = originalKey.lowercase()
            val value = line.substring(idx + 1).trim()

            when (section) {
                "interface" -> {
                    iface[lowerKey] = value
                    if (lowerKey !in CORE_INTERFACE_KEYS) {
                        extraInterface[originalKey] = value
                    }
                }
                "peer" -> peer[lowerKey] = value
                // [Peladu] is not part of WireGuard/AmneziaWG at all — it's
                // app-side metadata only. Everything in it except SSK (the
                // display name) is intentionally ignored; it's never sent
                // to the tunnel backend (see serialize() below).
                "peladu" -> peladu[lowerKey] = value
            }
        }

        val privateKey = iface["privatekey"]
            ?: throw ParseException("В [Interface] отсутствует PrivateKey")
        val publicKey = peer["publickey"]
            ?: throw ParseException("В [Peer] отсутствует PublicKey")
        val endpoint = peer["endpoint"]
            ?: throw ParseException("В [Peer] отсутствует Endpoint")

        // [Peladu]'s SSK wins over every other name source — it's the one
        // deliberate, explicit "this is the name" signal in the whole file.
        val resolvedName = peladu["ssk"]?.takeIf { it.isNotBlank() } ?: defaultName

        return ServerProfile(
            name = resolvedName,
            privateKey = privateKey,
            address = iface["address"] ?: "",
            dns = iface["dns"] ?: "",
            mtu = iface["mtu"] ?: "1420",
            publicKey = publicKey,
            presharedKey = peer["presharedkey"] ?: "",
            endpoint = endpoint,
            allowedIps = peer["allowedips"] ?: "0.0.0.0/0, ::/0",
            persistentKeepalive = peer["persistentkeepalive"] ?: "25",
            extraInterfaceFields = extraInterface,
            isPeladuOfficial = sawPeladuSection
        )
    }

    /** Rebuilds a standard .conf text from a profile, e.g. to hand to the tunnel backend. */
    fun serialize(p: ServerProfile): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = ${p.privateKey}")
        if (p.address.isNotBlank()) appendLine("Address = ${p.address}")
        if (p.dns.isNotBlank()) appendLine("DNS = ${p.dns}")
        if (p.mtu.isNotBlank()) appendLine("MTU = ${p.mtu}")
        // Every other [Interface] field the config had — obfuscation params
        // and anything else — written back exactly as received, EXCEPT
        // fields with no value at all (e.g. "I2 = " with nothing after it).
        // Real-world AmneziaWG exports often include empty I2-I5 placeholders,
        // and the backend library throws BadConfigException on those rather
        // than treating them as "not set" — so they need to be dropped
        // entirely, not just passed through blank.
        for ((key, value) in p.extraInterfaceFields) {
            if (value.isNotBlank()) appendLine("$key = $value")
        }
        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = ${p.publicKey}")
        if (p.presharedKey.isNotBlank()) appendLine("PresharedKey = ${p.presharedKey}")
        appendLine("Endpoint = ${p.endpoint}")
        appendLine("AllowedIPs = ${p.allowedIps}")
        if (p.persistentKeepalive.isNotBlank()) appendLine("PersistentKeepalive = ${p.persistentKeepalive}")
    }
}
