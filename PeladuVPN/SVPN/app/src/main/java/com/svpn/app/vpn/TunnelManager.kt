package com.svpn.app.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.svpn.app.data.Protocol
import com.svpn.app.data.ServerProfile
import com.svpn.app.data.WgConfigParser
import com.svpn.app.xray.XrayVpnService
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.backend.TunnelActionHandler
import org.amnezia.awg.config.Config
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

enum class TunnelState { DOWN, CONNECTING, UP }

/**
 * Dispatches to one of two completely separate engines depending on the
 * server's protocol:
 *
 *  - AmneziaWG: the real AmneziaWG GoBackend (org.amnezia.awg.*, from
 *    com.zaneschepke:amneziawg-android). Same API shape as plain
 *    WireGuard's backend, but this one actually understands the
 *    Jc/Jmin/Jmax/S1/S2/H1-H4 obfuscation fields.
 *
 *  - VLESS / VMess / Trojan / Shadowsocks: XrayVpnService, which owns its
 *    own TUN interface and drives an Xray-core loop against it directly
 *    (see xray/XrayCoreManager.kt, xray/XrayVpnService.kt). Requires
 *    app/libs/libv2ray.aar to be present — see
 *    .github/workflows/build-xray-aar.yml for how to build a trustworthy
 *    copy from the official 2dust/AndroidLibXrayLite source.
 *
 * Reliability notes:
 *  - connect() always tears down any previous tunnel first (on whichever
 *    engine was last used), so switching servers/protocols can't leave
 *    two tunnels fighting over the TUN interface.
 *  - internal `state` is reset to DOWN on any failure, so a failed connect
 *    attempt never leaves the manager stuck thinking it's mid-connection.
 *  - all calls here are synchronous/blocking by design (matching the
 *    AmneziaWG backend's own contract), so callers should invoke
 *    connect()/disconnect() from a background dispatcher, never the main
 *    thread.
 */
class TunnelManager(context: Context) {

    class UnsupportedProtocolException(message: String) : Exception(message)

    private val appContext = context.applicationContext
    private val backend = GoBackend(appContext, NoOpTunnelActionHandler())
    private var currentTunnel: SimpleTunnel? = null

    private var xrayService: XrayVpnService? = null
    private var xrayConnection: ServiceConnection? = null

    @Volatile
    var state: TunnelState = TunnelState.DOWN
        private set

    @Synchronized
    fun connect(profile: ServerProfile) {
        // Always tear down whatever tunnel might already be up — on
        // *either* engine — before bringing a new one up.
        teardownQuietly()

        state = TunnelState.CONNECTING
        try {
            if (profile.protocol == Protocol.AMNEZIAWG) {
                connectAmneziaWg(profile)
            } else {
                connectXray(profile)
            }
            state = TunnelState.UP
        } catch (e: Exception) {
            currentTunnel = null
            unbindXrayQuietly()
            state = TunnelState.DOWN
            throw e
        }
    }

    private fun connectAmneziaWg(profile: ServerProfile) {
        val configText = WgConfigParser.serialize(profile)
        val config = Config.parse(ByteArrayInputStream(configText.toByteArray(StandardCharsets.UTF_8)))
        // Tunnel names are capped at 15 chars by the library (Tunnel.NAME_MAX_LENGTH)
        // and must match [a-zA-Z0-9_=+.-]{1,15} — plain hex from the UUID (no dashes)
        // fits that charset directly, no prefix needed.
        val tunnelName = profile.id.replace("-", "").take(15)
        val tunnel = SimpleTunnel(tunnelName)

        backend.setState(tunnel, Tunnel.State.UP, config)
        currentTunnel = tunnel
    }

    private fun connectXray(profile: ServerProfile) {
        val latch = CountDownLatch(1)
        var bindFailure: Exception? = null

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                try {
                    val service = (binder as XrayVpnService.LocalBinder).getService()
                    service.connect(profile)
                    xrayService = service
                } catch (e: Exception) {
                    bindFailure = e
                } finally {
                    latch.countDown()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                xrayService = null
            }
        }

        xrayConnection = connection
        val intent = Intent(appContext, XrayVpnService::class.java)
        appContext.startService(intent)
        val bound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!bound) throw UnsupportedProtocolException("Не удалось запустить службу VPN для ${profile.protocol.displayName}")

        if (!latch.await(15, TimeUnit.SECONDS)) {
            throw UnsupportedProtocolException("Служба VPN для ${profile.protocol.displayName} не ответила вовремя")
        }
        bindFailure?.let { throw it }
        if (xrayService == null) throw UnsupportedProtocolException("Не удалось подключиться к службе VPN")
    }

    @Synchronized
    fun disconnect() {
        teardownQuietly()
        state = TunnelState.DOWN
    }

    private fun teardownQuietly() {
        currentTunnel?.let { tunnel ->
            try {
                backend.setState(tunnel, Tunnel.State.DOWN, null)
            } catch (_: Exception) {
                // Best-effort teardown — if the backend already tore itself down
                // (e.g. system killed the VPN), there's nothing more to clean up.
            }
        }
        currentTunnel = null

        xrayService?.let {
            try { it.disconnect() } catch (_: Exception) { /* already gone */ }
        }
        unbindXrayQuietly()
    }

    private fun unbindXrayQuietly() {
        xrayConnection?.let {
            try { appContext.unbindService(it) } catch (_: Exception) { /* not bound */ }
        }
        xrayConnection = null
        xrayService = null
    }

    fun isUp(): Boolean = state == TunnelState.UP

    private class SimpleTunnel(private val tunnelName: String) : Tunnel {
        override fun getName(): String = tunnelName
        override fun onStateChange(newState: Tunnel.State) { /* no-op, UI polls state */ }
        override fun isIpv4ResolutionPreferred(): Boolean = true
        override fun isMetered(): Boolean = false
    }

    /**
     * PeladuVPN doesn't support custom PreUp/PostUp/PreDown/PostDown shell
     * scripts in imported configs (WgConfigParser doesn't even read those
     * fields), so there's never anything to run here — but GoBackend's
     * constructor requires a handler regardless.
     */
    private class NoOpTunnelActionHandler : TunnelActionHandler {
        override fun runPreUp(scripts: MutableCollection<String>) { /* no-op */ }
        override fun runPostUp(scripts: MutableCollection<String>) { /* no-op */ }
        override fun runPreDown(scripts: MutableCollection<String>) { /* no-op */ }
        override fun runPostDown(scripts: MutableCollection<String>) { /* no-op */ }
    }
}
