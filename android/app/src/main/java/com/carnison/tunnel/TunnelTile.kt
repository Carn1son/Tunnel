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
    private var listening = false
    private var level = 0f // how full the "aquarium" is while connecting, 0..1
    private var frame: android.graphics.Bitmap? = null

    private fun connecting() = Vpn.state != Vpn.CONNECTED && Vpn.state != Vpn.DISCONNECTED

    // while connecting the icon fills up from the bottom like an aquarium, again and again, until the state settles
    private val fill = object : Runnable {
        override fun run() {
            if (!listening || !connecting()) return
            level += 0.07f; if (level > 1.15f) level = 0f
            draw(); main.postDelayed(this, 70)
        }
    }
    private val listener: (Int) -> Unit = { main.post { level = 0f; draw(); startFill() } }

    private fun startFill() {
        main.removeCallbacks(fill)
        if (listening && connecting()) main.postDelayed(fill, 70)
    }

    override fun onStartListening() { listening = true; Vpn.stateListeners += listener; level = 0f; draw(); startFill() }
    override fun onStopListening() { listening = false; main.removeCallbacks(fill); Vpn.stateListeners -= listener }

    /** The tile icon filled to [level]: the empty part faint, the filled part solid. */
    private fun aquarium(): android.graphics.drawable.Icon {
        val size = 96
        val bmp = frame ?: android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888).also { frame = it }
        bmp.eraseColor(0)
        val c = android.graphics.Canvas(bmp)
        val d = getDrawable(R.drawable.ic_tile)!!.mutate()
        d.setBounds(0, 0, size, size)
        d.setTint(android.graphics.Color.WHITE)
        d.alpha = 80; d.draw(c)
        val top = size - (size * level.coerceIn(0f, 1f))
        c.save(); c.clipRect(0f, top, size.toFloat(), size.toFloat())
        d.alpha = 255; d.draw(c); c.restore()
        return android.graphics.drawable.Icon.createWithBitmap(bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false))
    }

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
        // off: dim, on: lit, connecting: dim with the icon filling up; still tappable, so a tap cancels the connection
        t.state = when { Vpn.state == Vpn.CONNECTED -> Tile.STATE_ACTIVE; Vpn.state == Vpn.DISCONNECTED -> Tile.STATE_INACTIVE; else -> Tile.STATE_INACTIVE }
        t.icon = if (connecting()) aquarium() else android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_tile)
        t.label = "Tunnel"
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (Vpn.state == Vpn.CONNECTED) Vpn.lastServerName(this) else Vpn.statusText(this)
        t.updateTile()
    }
}
