package com.svpn.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = "svpn_store")

/**
 * Very small JSON-based persistence layer. Kept dependency-free (no Gson/Moshi)
 * to keep the project easy to build.
 */
class SvpnRepository(private val context: Context) {

    private val KEY_SERVERS = stringPreferencesKey("servers_json")
    private val KEY_ACTIVE = stringPreferencesKey("active_server_id")
    private val KEY_LANGUAGE = stringPreferencesKey("language")
    private val KEY_BUTTON_DESIGN = stringPreferencesKey("button_design")

    val servers: Flow<List<ServerProfile>> = context.dataStore.data.map { prefs ->
        val json = prefs[KEY_SERVERS] ?: "[]"
        fromJsonArray(json)
    }

    val activeServerId: Flow<String?> = context.dataStore.data.map { it[KEY_ACTIVE] }

    val language: Flow<String> = context.dataStore.data.map { it[KEY_LANGUAGE] ?: "ru" }

    val buttonDesign: Flow<ButtonDesign> = context.dataStore.data.map {
        ButtonDesign.fromStorageValue(it[KEY_BUTTON_DESIGN])
    }

    suspend fun setButtonDesign(design: ButtonDesign) {
        context.dataStore.edit { prefs -> prefs[KEY_BUTTON_DESIGN] = design.name }
    }

    suspend fun addServer(profile: ServerProfile) {
        context.dataStore.edit { prefs ->
            val current = fromJsonArray(prefs[KEY_SERVERS] ?: "[]").toMutableList()
            current.add(profile)
            prefs[KEY_SERVERS] = toJsonArray(current)
        }
    }

    suspend fun updateServer(profile: ServerProfile) {
        context.dataStore.edit { prefs ->
            val current = fromJsonArray(prefs[KEY_SERVERS] ?: "[]").toMutableList()
            val idx = current.indexOfFirst { it.id == profile.id }
            if (idx >= 0) current[idx] = profile
            prefs[KEY_SERVERS] = toJsonArray(current)
        }
    }

    suspend fun removeServer(id: String) {
        context.dataStore.edit { prefs ->
            val current = fromJsonArray(prefs[KEY_SERVERS] ?: "[]").toMutableList()
            current.removeAll { it.id == id }
            prefs[KEY_SERVERS] = toJsonArray(current)
            if (prefs[KEY_ACTIVE] == id) {
                prefs.remove(KEY_ACTIVE)
            }
        }
    }

    suspend fun setActiveServer(id: String?) {
        context.dataStore.edit { prefs ->
            if (id == null) prefs.remove(KEY_ACTIVE) else prefs[KEY_ACTIVE] = id
        }
    }

    suspend fun setLanguage(code: String) {
        context.dataStore.edit { prefs -> prefs[KEY_LANGUAGE] = code }
    }

    private fun toJsonArray(list: List<ServerProfile>): String {
        val arr = JSONArray()
        list.forEach { p ->
            val o = JSONObject()
            o.put("id", p.id)
            o.put("name", p.name)
            o.put("protocol", p.protocol.name)
            o.put("privateKey", p.privateKey)
            o.put("address", p.address)
            o.put("dns", p.dns)
            o.put("mtu", p.mtu)
            o.put("publicKey", p.publicKey)
            o.put("presharedKey", p.presharedKey)
            o.put("endpoint", p.endpoint)
            o.put("allowedIps", p.allowedIps)
            o.put("persistentKeepalive", p.persistentKeepalive)
            o.put("isPeladuOfficial", p.isPeladuOfficial)
            val extras = JSONObject()
            // Preserve insertion order using JSONObject's natural behavior
            // isn't guaranteed pre-API 19, but on all supported API levels
            // (minSdk 24+) org.json.JSONObject preserves insertion order.
            p.extraInterfaceFields.forEach { (k, v) -> extras.put(k, v) }
            o.put("extraInterfaceFields", extras)
            // Xray family fields (VLESS/VMess/Trojan/Shadowsocks)
            o.put("xrayId", p.xrayId)
            o.put("xrayHost", p.xrayHost)
            o.put("xrayPort", p.xrayPort)
            o.put("xrayNetwork", p.xrayNetwork)
            o.put("xraySecurity", p.xraySecurity)
            o.put("xrayFlow", p.xrayFlow)
            o.put("xraySni", p.xraySni)
            o.put("xrayPublicKey", p.xrayPublicKey)
            o.put("xrayShortId", p.xrayShortId)
            o.put("xrayFingerprint", p.xrayFingerprint)
            o.put("xraySpiderX", p.xraySpiderX)
            o.put("xrayAlterId", p.xrayAlterId)
            o.put("xrayMethod", p.xrayMethod)
            o.put("xrayWsPath", p.xrayWsPath)
            o.put("xrayWsHost", p.xrayWsHost)
            arr.put(o)
        }
        return arr.toString()
    }

    private fun fromJsonArray(json: String): List<ServerProfile> {
        val arr = JSONArray(json)
        val out = mutableListOf<ServerProfile>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val extras = LinkedHashMap<String, String>()
            o.optJSONObject("extraInterfaceFields")?.let { extrasJson ->
                val keys = extrasJson.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    extras[k] = extrasJson.getString(k)
                }
            }
            out.add(
                ServerProfile(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    protocol = Protocol.fromStorageValue(o.optString("protocol")),
                    privateKey = o.optString("privateKey"),
                    address = o.optString("address"),
                    dns = o.optString("dns"),
                    mtu = o.optString("mtu", "1420"),
                    publicKey = o.optString("publicKey"),
                    presharedKey = o.optString("presharedKey"),
                    endpoint = o.optString("endpoint"),
                    allowedIps = o.optString("allowedIps", "0.0.0.0/0, ::/0"),
                    persistentKeepalive = o.optString("persistentKeepalive", "25"),
                    extraInterfaceFields = extras,
                    isPeladuOfficial = o.optBoolean("isPeladuOfficial", false),
                    xrayId = o.optString("xrayId"),
                    xrayHost = o.optString("xrayHost"),
                    xrayPort = o.optString("xrayPort", "443"),
                    xrayNetwork = o.optString("xrayNetwork", "tcp"),
                    xraySecurity = o.optString("xraySecurity", "none"),
                    xrayFlow = o.optString("xrayFlow"),
                    xraySni = o.optString("xraySni"),
                    xrayPublicKey = o.optString("xrayPublicKey"),
                    xrayShortId = o.optString("xrayShortId"),
                    xrayFingerprint = o.optString("xrayFingerprint", "chrome"),
                    xraySpiderX = o.optString("xraySpiderX"),
                    xrayAlterId = o.optString("xrayAlterId", "0"),
                    xrayMethod = o.optString("xrayMethod"),
                    xrayWsPath = o.optString("xrayWsPath"),
                    xrayWsHost = o.optString("xrayWsHost")
                )
            )
        }
        return out
    }
}
