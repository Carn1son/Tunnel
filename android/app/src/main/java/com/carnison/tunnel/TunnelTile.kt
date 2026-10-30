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

    private val listener: (Int) -> Unit = { draw() }

    override fun onStartListening() { Vpn.stateListeners += listener; draw() }
    override fun onStopListening() { Vpn.stateListeners -= listener }

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
        t.state = when (Vpn.state) { Vpn.CONNECTED -> Tile.STATE_ACTIVE; Vpn.DISCONNECTED -> Tile.STATE_INACTIVE; else -> Tile.STATE_ACTIVE }
        t.label = "Tunnel"
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (Vpn.state == Vpn.DISCONNECTED) Vpn.statusText(this) else Vpn.lastServerName(this)
        t.updateTile()
    }
}
