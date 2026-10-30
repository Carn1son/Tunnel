package com.carnison.tunnel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.VpnService
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/** Tunnel's VPN state shared by the window, the service, the quick settings tile and the widget. */
object Vpn {
    // ag::VpnSessionState
    const val DISCONNECTED = 0
    const val CONNECTED = 2

    @Volatile var state = DISCONNECTED
        private set
    @Volatile var serverName: String = ""

    val stateListeners = CopyOnWriteArrayList<(Int) -> Unit>()
    val connListeners = CopyOnWriteArrayList<(JSONObject) -> Unit>()
    private val recent = ArrayDeque<JSONObject>()

    lateinit var app: Application

    fun prefs(ctx: Context) = ctx.getSharedPreferences("tunnel", Context.MODE_PRIVATE)

    fun setState(s: Int) {
        state = s
        stateListeners.forEach { runCatching { it(s) } }
        TunnelWidget.update(app)
        TunnelTile.refresh(app)
    }

    fun connection(info: JSONObject) {
        synchronized(recent) { recent.addLast(info); while (recent.size > 300) recent.removeFirst() }
        connListeners.forEach { runCatching { it(info) } }
    }

    fun recentConnections(): List<JSONObject> = synchronized(recent) { recent.toList() }
    fun clearRecent() = synchronized(recent) { recent.clear() }

    /** The last configuration that was connected: used by Always-on VPN, the tile and the widget. */
    fun saveLast(ctx: Context, toml: String, extra: String) {
        prefs(ctx).edit().putString("lastToml", toml).putString("lastExtra", extra).apply()
    }
    fun lastToml(ctx: Context): String? = prefs(ctx).getString("lastToml", null)
    fun lastExtra(ctx: Context): String = prefs(ctx).getString("lastExtra", null) ?: "{}"

    fun start(ctx: Context, toml: String, extra: String) {
        saveLast(ctx, toml, extra)
        val i = Intent(ctx, TunnelVpnService::class.java).setAction(TunnelVpnService.ACTION_START)
        ctx.startForegroundService(i)
    }

    fun stop(ctx: Context) {
        val i = Intent(ctx, TunnelVpnService::class.java).setAction(TunnelVpnService.ACTION_STOP)
        ctx.startForegroundService(i)
    }

    /** From the tile or the widget: returns false when the app has to be opened (no server yet or no VPN permission). */
    fun toggle(ctx: Context): Boolean {
        if (state != DISCONNECTED) { stop(ctx); return true }
        val toml = lastToml(ctx) ?: return false
        if (VpnService.prepare(ctx) != null) return false
        start(ctx, toml, lastExtra(ctx))
        return true
    }

    fun statusText(ctx: Context): String {
        val en = (prefs(ctx).getString("lang", "ru") == "en")
        return when (state) {
            CONNECTED -> if (en) "Connected" else "Подключено"
            DISCONNECTED -> if (en) "Disconnected" else "Отключено"
            else -> if (en) "Connecting…" else "Подключение…"
        }
    }

    fun lastServerName(ctx: Context): String =
        serverName.ifEmpty { runCatching { JSONObject(lastExtra(ctx)).optString("name") }.getOrDefault("") }.ifEmpty { "Tunnel" }
}
