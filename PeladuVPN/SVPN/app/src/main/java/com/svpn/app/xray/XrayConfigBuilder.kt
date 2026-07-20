package com.svpn.app.xray

import com.svpn.app.data.Protocol
import com.svpn.app.data.ServerProfile
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the JSON config string handed to `CoreController.startLoop()`.
 *
 * The schema here is reverse-engineered from 2dust/v2rayNG's own source
 * (CoreOutboundBuilder.kt + CoreConfigManager.kt + the bundled
 * v2ray_config_with_tun.json asset) — NOT the vanilla community-documented
 * Xray-core schema. 2dust's Xray fork (the same one this app's
 * libv2ray.aar is built from) accepts a flatter "settings" object per
 * outbound (address/port/id/password/method directly, no vnext/servers
 * wrapper arrays) and adds a custom "tun" inbound protocol that binds
 * directly to whatever raw fd the app hands it — that's what lets Android
 * pass its own already-established VpnService TUN fd straight through,
 * with no separate tun2socks needed.
 */
object XrayConfigBuilder {

    /** MTU here just needs to match whatever the VpnService.Builder used. */
    fun build(profile: ServerProfile, mtu: Int = 1500): String {
        val root = JSONObject()
        root.put("log", JSONObject().put("loglevel", "warning"))

        val tunInbound = JSONObject().apply {
            put("tag", "tun")
            put("protocol", "tun")
            put("settings", JSONObject().apply {
                put("name", "xray0")
                put("MTU", mtu)
                put("userLevel", 8)
            })
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray(listOf("http", "tls", "quic")))
            })
        }
        root.put("inbounds", JSONArray().put(tunInbound))

        val outbounds = JSONArray()
        outbounds.put(buildProxyOutbound(profile))
        outbounds.put(
            JSONObject().apply {
                put("protocol", "freedom")
                put("tag", "direct")
                put("streamSettings", JSONObject().apply {
                    put("sockopt", JSONObject().put("domainStrategy", "UseIP"))
                })
            }
        )
        outbounds.put(
            JSONObject().apply {
                put("protocol", "blackhole")
                put("tag", "block")
                put("settings", JSONObject().put("response", JSONObject().put("type", "http")))
            }
        )
        root.put("outbounds", outbounds)

        // Empty rules + "proxy" listed first in outbounds -> everything that
        // doesn't match a rule falls through to "proxy" (Xray's documented
        // default-outbound behavior), i.e. full-tunnel by default.
        root.put("routing", JSONObject().apply {
            put("domainStrategy", "AsIs")
            put("rules", JSONArray())
        })
        root.put("dns", JSONObject().apply {
            put("hosts", JSONObject())
            put("servers", JSONArray())
        })

        return root.toString()
    }

    private fun buildProxyOutbound(p: ServerProfile): JSONObject {
        val settings = JSONObject().apply {
            put("address", p.xrayHost)
            put("port", p.xrayPort.toIntOrNull() ?: 443)
            put("level", 0)
        }

        when (p.protocol) {
            Protocol.VLESS -> {
                settings.put("id", p.xrayId)
                settings.put("encryption", "none")
                if (p.xrayFlow.isNotBlank()) settings.put("flow", p.xrayFlow)
            }
            Protocol.VMESS -> {
                settings.put("id", p.xrayId)
                settings.put("security", "auto")
            }
            Protocol.TROJAN -> {
                settings.put("password", p.xrayId)
                if (p.xrayFlow.isNotBlank()) settings.put("flow", p.xrayFlow)
            }
            Protocol.SHADOWSOCKS -> {
                settings.put("password", p.xrayId)
                settings.put("method", p.xrayMethod)
            }
            Protocol.AMNEZIAWG -> { /* never reached, AmneziaWG doesn't go through this builder */ }
        }

        val outbound = JSONObject().apply {
            put("tag", "proxy")
            put("protocol", p.protocol.name.lowercase())
            put("settings", settings)
            put("mux", JSONObject().put("enabled", false))
        }

        val stream = JSONObject().apply {
            put("network", p.xrayNetwork.ifBlank { "tcp" })
        }

        if (p.xrayNetwork == "ws") {
            stream.put("wsSettings", JSONObject().apply {
                if (p.xrayWsPath.isNotBlank()) put("path", p.xrayWsPath)
                if (p.xrayWsHost.isNotBlank()) put("host", p.xrayWsHost)
            })
        }

        val security = p.xraySecurity.ifBlank { "none" }
        if (security != "none") {
            stream.put("security", security)
            val tlsLike = JSONObject().apply {
                put("allowInsecure", false)
                if (p.xraySni.isNotBlank()) put("serverName", p.xraySni)
                if (p.xrayFingerprint.isNotBlank()) put("fingerprint", p.xrayFingerprint)
                if (security == "reality") {
                    put("publicKey", p.xrayPublicKey)
                    if (p.xrayShortId.isNotBlank()) put("shortId", p.xrayShortId)
                    if (p.xraySpiderX.isNotBlank()) put("spiderX", p.xraySpiderX)
                }
            }
            if (security == "tls") stream.put("tlsSettings", tlsLike)
            if (security == "reality") stream.put("realitySettings", tlsLike)
        }

        outbound.put("streamSettings", stream)
        return outbound
    }
}
