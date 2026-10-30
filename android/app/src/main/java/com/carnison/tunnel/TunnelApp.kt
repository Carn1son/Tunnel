package com.carnison.tunnel

import android.app.Application

class TunnelApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Vpn.app = this
        // the engine's file logging (export/clear from Settings), as in AdGuard's own app
        runCatching { com.adguard.trusttunnel.VpnService.initialize(this) }
    }
}
