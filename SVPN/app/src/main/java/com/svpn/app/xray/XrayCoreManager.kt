package com.svpn.app.xray

import android.content.Context
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

/**
 * Thin wrapper around the libv2ray.aar native bindings (package `libv2ray`,
 * classes `Libv2ray` / `CoreController` / `CoreCallbackHandler`). Modeled
 * directly on 2dust/v2rayNG's own CoreNativeManager.kt / CoreServiceManager.kt
 * — same engine, same calling convention — since that's the reference
 * implementation for this exact .aar.
 *
 * NOTE: this file only compiles once `app/libs/libv2ray.aar` actually
 * exists (see .github/workflows/build-xray-aar.yml to obtain it from the
 * official 2dust/AndroidLibXrayLite source). Until then, this whole
 * package won't build — that's expected, not a bug.
 */
class XrayCoreManager(context: Context) {

    private val appContext = context.applicationContext
    private var controller: CoreController? = null
    private var envInitialized = false

    private fun ensureEnvInitialized() {
        if (envInitialized) return
        // Second argument is a "device id" string used for some anti-abuse
        // telemetry in upstream v2rayNG; an empty string is fine for us.
        Libv2ray.initCoreEnv(appContext.filesDir.absolutePath, "")
        envInitialized = true
    }

    /** Starts the Xray core loop, binding it directly to the given TUN fd. */
    @Synchronized
    fun start(configJson: String, tunFd: Int) {
        stop()
        ensureEnvInitialized()

        val handler = object : CoreCallbackHandler {
            override fun startup(): Long = 0
            override fun shutdown(): Long = 0
            override fun onEmitStatus(l: Long, s: String?): Long = 0
        }

        val c = Libv2ray.newCoreController(handler)
        c.startLoop(configJson, tunFd)
        controller = c
    }

    @Synchronized
    fun stop() {
        val c = controller ?: return
        try {
            c.stopLoop()
        } catch (_: Exception) {
            // Best-effort — if the core already tore itself down there's
            // nothing more to do.
        } finally {
            controller = null
        }
    }

    fun isRunning(): Boolean = controller?.isRunning == true
}
