package com.svpn.app.xray

import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.svpn.app.data.ServerProfile

/**
 * Owns the TUN interface for Xray-family connections (VLESS/VMess/Trojan/
 * Shadowsocks) and drives the Xray core loop directly against that
 * interface's raw fd — no separate tun2socks process needed, the patched
 * Xray-core (via libv2ray.aar) handles that internally once you hand it
 * the fd through CoreController.startLoop().
 *
 * Kept separate from AmneziaWG's SvpnTunnelService (which is owned
 * entirely by the AmneziaWG backend library) since these are two
 * unrelated engines with unrelated lifecycles.
 */
class XrayVpnService : VpnService() {

    private val coreManager by lazy { XrayCoreManager(this) }
    private var tunInterface: ParcelFileDescriptor? = null

    inner class LocalBinder : Binder() {
        fun getService(): XrayVpnService = this@XrayVpnService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    /** Brings the tunnel + Xray core up for the given profile. Blocking; call off the main thread. */
    @Synchronized
    fun connect(profile: ServerProfile) {
        disconnect()

        val mtu = 1500
        val builder = Builder()
            .setSession("PeladuVPN")
            .setMtu(mtu)
            .addAddress("10.10.14.1", 30)
            .addDnsServer("1.1.1.1")
            .addDnsServer("1.0.0.1")
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)

        val pfd = builder.establish()
            ?: throw IllegalStateException("VpnService.Builder.establish() вернул null — не выдано разрешение на VPN?")
        tunInterface = pfd

        try {
            val configJson = XrayConfigBuilder.build(profile, mtu)
            coreManager.start(configJson, pfd.fd)
        } catch (e: Exception) {
            // Don't leave a dangling TUN interface if the core failed to start.
            pfd.close()
            tunInterface = null
            throw e
        }
    }

    @Synchronized
    fun disconnect() {
        coreManager.stop()
        tunInterface?.let {
            try { it.close() } catch (_: Exception) { /* already gone */ }
        }
        tunInterface = null
    }

    override fun onRevoke() {
        disconnect()
        super.onRevoke()
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }
}
