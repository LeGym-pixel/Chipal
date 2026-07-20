package com.svpn.app.vpn

import org.amnezia.awg.backend.AbstractBackend

/**
 * Required so Android can bind the VpnService used internally by GoBackend.
 * GoBackend itself creates/manages the actual tun interface; this class
 * just needs to exist and be declared in the manifest with the VpnService
 * intent filter.
 */
class SvpnTunnelService : AbstractBackend.VpnService()
