package com.svpn.app.data

enum class ButtonDesign {
    OUROBOROS, SWORD, CHAINS, SUN, DOUBLE_SUN;

    companion object {
        fun fromStorageValue(value: String?): ButtonDesign =
            entries.find { it.name == value } ?: CHAINS
    }
}
