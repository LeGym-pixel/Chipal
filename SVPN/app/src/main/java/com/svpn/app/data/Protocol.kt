package com.svpn.app.data

enum class Protocol(val displayName: String) {
    AMNEZIAWG("AmneziaWG"),
    VLESS("VLESS"),
    VMESS("VMess"),
    TROJAN("Trojan"),
    SHADOWSOCKS("Shadowsocks");

    companion object {
        fun fromStorageValue(value: String?): Protocol =
            entries.find { it.name == value } ?: AMNEZIAWG
    }
}
