package com.svpn.app.data

import java.util.UUID

/**
 * A single imported VPN server / config.
 * Holds the raw WireGuard fields needed to bring up a tunnel, plus any
 * other [Interface] fields the config had (AmneziaWG obfuscation params
 * like Jc/Jmin/Jmax/S1-S4/H1-H4/I1-I5 and whatever gets added later) —
 * kept generically so we never silently drop a field the server actually
 * needs for its handshake, even if it's one we don't specifically know
 * about yet.
 */
data class ServerProfile(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var protocol: Protocol = Protocol.AMNEZIAWG,

    // [Interface] — core fields (AmneziaWG / WireGuard only)
    var privateKey: String = "",
    var address: String = "",
    var dns: String = "",
    var mtu: String = "1420",

    // [Peer] (AmneziaWG / WireGuard only)
    var publicKey: String = "",
    var presharedKey: String = "",
    var endpoint: String = "",
    var allowedIps: String = "0.0.0.0/0, ::/0",
    var persistentKeepalive: String = "25",

    // Any other [Interface] key = value pairs, in original casing and
    // order (Jc, Jmin, Jmax, S1-S4, H1-H4, I1-I5, and anything future
    // AmneziaWG versions might add).
    var extraInterfaceFields: Map<String, String> = emptyMap(),

    // True if the config had a [Peladu] section — a PeladuVPN-specific
    // block (not part of the WireGuard/AmneziaWG protocol at all) used
    // purely to carry app-side metadata like the server's display name.
    // Never sent to the tunnel backend.
    var isPeladuOfficial: Boolean = false,

    // --- Xray family: VLESS / VMess / Trojan / Shadowsocks ---
    var xrayId: String = "",          // uuid (vless/vmess) or password (trojan/shadowsocks)
    var xrayHost: String = "",        // server address
    var xrayPort: String = "443",
    var xrayNetwork: String = "tcp",  // tcp / ws / grpc / http (transport)
    var xraySecurity: String = "none",// none / tls / reality
    var xrayFlow: String = "",        // vless only, e.g. xtls-rprx-vision
    var xraySni: String = "",         // TLS/REALITY serverName
    var xrayPublicKey: String = "",   // REALITY publicKey
    var xrayShortId: String = "",     // REALITY shortId
    var xrayFingerprint: String = "chrome", // REALITY/TLS utls fingerprint
    var xraySpiderX: String = "",     // REALITY spiderX path
    var xrayAlterId: String = "0",    // VMess legacy alterId (usually "0")
    var xrayMethod: String = "",      // Shadowsocks cipher, e.g. aes-256-gcm
    var xrayWsPath: String = "",      // websocket transport path
    var xrayWsHost: String = ""       // websocket Host header
)
