package com.carnison.tunnel

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick settings tile: one tap connects or disconnects; without a server or VPN permission it opens Tunnel. */
class TunnelTile : TileService() {
    companion object {
        fun refresh(ctx: Context) {
            runCatching { TileService.requestListeningState(ctx, ComponentName(ctx, TunnelTile::class.java)) }
        }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var blink = false
    private var listening = false
    // while connecting the tile pulses: bright and dim in turn, until the state settles
    private val pulse = object : Runnable {
        override fun run() {
            if (!listening || Vpn.state == Vpn.CONNECTED || Vpn.state == Vpn.DISCONNECTED) return
            blink = !blink; draw(); main.postDelayed(this, 450)
        }
    }
    private val listener: (Int) -> Unit = { main.post { draw(); startPulse() } }

    private fun startPulse() {
        main.removeCallbacks(pulse)
        if (listening && Vpn.state != Vpn.CONNECTED && Vpn.state != Vpn.DISCONNECTED) main.postDelayed(pulse, 450)
    }

    override fun onStartListening() { listening = true; Vpn.stateListeners += listener; draw(); startPulse() }
    override fun onStopListening() { listening = false; main.removeCallbacks(pulse); Vpn.stateListeners -= listener }

    override fun onClick() {
        if (!Vpn.toggle(this)) {
            val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34)
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
            else @Suppress("DEPRECATION") startActivityAndCollapse(i)
        }
    }

    private fun draw() {
        val t = qsTile ?: return
        // off: the dim system look; on: lit; connecting: pulses between the two
        t.state = when (Vpn.state) { Vpn.CONNECTED -> Tile.STATE_ACTIVE; Vpn.DISCONNECTED -> Tile.STATE_INACTIVE; else -> if (blink) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE }
        t.label = "Tunnel"
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (Vpn.state == Vpn.CONNECTED) Vpn.lastServerName(this) else Vpn.statusText(this)
        t.updateTile()
    }
}
