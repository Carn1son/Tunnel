package com.carnison.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import com.adguard.trusttunnel.CertificateVerificator
import com.adguard.trusttunnel.VpnClient
import com.adguard.trusttunnel.VpnClientListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * The VPN: a TUN interface from Android (with the per-app split) and AdGuard's TrustTunnel engine on top of it.
 * The engine gets the same TOML as on Windows; IP split rules are applied inside the engine by the Tunnel hook.
 */
class TunnelVpnService : android.net.VpnService(), VpnClientListener {
    companion object {
        const val ACTION_START = "com.carnison.tunnel.START"
        const val ACTION_STOP = "com.carnison.tunnel.STOP"
        const val ACTION_TOGGLE = "com.carnison.tunnel.TOGGLE"
        private const val CHANNEL = "vpn"
        private const val NOTIFICATION_ID = 1
        private val ADGUARD_DNS = listOf("46.243.231.30", "46.243.231.31", "2a10:50c0::2:ff", "2a10:50c0::1:ff")
        private const val FAKE_DNS = "198.18.53.53" // the engine answers it with the server's own DNS upstreams
        private val NON_ROUTABLE = listOf("0.0.0.0/8", "224.0.0.0/3")
    }

    private val worker = Executors.newSingleThreadExecutor()
    private var client: VpnClient? = null
    private var tun: ParcelFileDescriptor? = null
    private var verifier: CertificateVerificator? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        val action = intent?.action
        worker.execute {
            when (action) {
                ACTION_STOP -> close()
                ACTION_TOGGLE -> {
                    val toml = Vpn.lastToml(this)
                    if (running || toml == null || prepare(this) != null) close() else open(toml, JSONObject(Vpn.lastExtra(this)))
                }
                // ACTION_START, or Android starting the service for Always-on VPN: the last configuration
                else -> {
                    val toml = Vpn.lastToml(this)
                    if (toml == null) close() else open(toml, JSONObject(Vpn.lastExtra(this)))
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun open(toml: String, extra: JSONObject) {
        if (running) closeEngine()
        Vpn.serverName = extra.optString("name")
        Vpn.setState(1)
        try {
            verifier = CertificateVerificator()
            val fd = buildTun(extra) ?: throw IllegalStateException("TUN interface was not created")
            tun = fd
            val c = VpnClient(toml, this)
            client = c
            running = true
            watchNetwork()
            if (!c.start(fd, true)) throw IllegalStateException("engine did not start")
            updateNotification()
        } catch (e: Exception) {
            android.util.Log.e("Tunnel", "start failed", e)
            close()
        }
    }

    private fun buildTun(extra: JSONObject): ParcelFileDescriptor? {
        val b = Builder().setSession("Tunnel").setMtu(1350)
            .addAddress("172.20.2.13", 32)
            .addAddress("fdfd:29::2", 64)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) b.setMetered(false)

        // split tunnelling by application is done by Android itself
        val apps = extra.optJSONArray("apps") ?: JSONArray()
        val include = extra.optString("mode") == "include" && apps.length() > 0
        if (include) {
            for (i in 0 until apps.length()) {
                val pkg = apps.optString(i)
                if (pkg != packageName) runCatching { b.addAllowedApplication(pkg) }
            }
        } else {
            b.addDisallowedApplication(packageName)
            if (extra.optString("mode") == "exclude") for (i in 0 until apps.length()) runCatching { b.addDisallowedApplication(apps.optString(i)) }
        }

        (if (extra.optBoolean("ownDns")) listOf(FAKE_DNS) else ADGUARD_DNS).forEach { b.addDnsServer(it) }

        val excluded = mutableListOf<String>()
        extra.optJSONArray("excluded")?.let { for (i in 0 until it.length()) excluded += it.optString(i) }
        val routes = VpnClient.excludeCidr(listOf("0.0.0.0/0", "2000::/3"), excluded + NON_ROUTABLE)
            ?: throw IllegalStateException("routes")
        for (r in routes) {
            val slash = r.indexOf('/')
            b.addRoute(InetAddress.getByName(r.substring(0, slash)), r.substring(slash + 1).toInt())
        }
        return b.establish()
    }

    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { worker.execute { client?.notifyNetworkChange(true) } }
            override fun onLost(network: Network) { worker.execute { client?.notifyNetworkChange(false) } }
        }
        runCatching { cm.registerNetworkCallback(req, cb); netCallback = cb }
    }

    private fun closeEngine() {
        netCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) } }
        netCallback = null
        client?.let { runCatching { it.stop() }; runCatching { it.close() } }
        client = null
        runCatching { tun?.close() }
        tun = null
        running = false
    }

    private fun close() {
        closeEngine()
        Vpn.setState(Vpn.DISCONNECTED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() { worker.execute { close() } }

    override fun onDestroy() {
        worker.execute { closeEngine() }
        super.onDestroy()
    }

    // ---- engine callbacks
    override fun protectSocket(socket: Int): Boolean = protect(socket)

    override fun verifyCertificate(certificate: ByteArray?, rawChain: List<ByteArray?>?): Boolean =
        verifier?.verifyCertificate(certificate, rawChain) ?: false

    override fun onStateChanged(state: Int) {
        Vpn.setState(state)
        updateNotification()
        if (state == Vpn.DISCONNECTED && running) worker.execute { if (running) close() }
    }

    override fun onConnectionInfo(info: String) {
        val j = runCatching { JSONObject(info) }.getOrNull() ?: return
        ownerOf(j)?.let { (label, pkg) -> j.put("app", label); j.put("path", pkg) }
        Vpn.connection(j)
    }

    /** The app that owns a connection, asked while it is still open (Android tells this only to the active VPN app). */
    private fun ownerOf(j: JSONObject): Pair<String, String>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val proto = if (j.optString("proto") == "TCP") OsConstants.IPPROTO_TCP else OsConstants.IPPROTO_UDP
            val src = addr(j.optString("src")) ?: return null
            val dst = addr(j.optString("dst")) ?: return null
            val uid = getSystemService(ConnectivityManager::class.java).getConnectionOwnerUid(proto, src, dst)
            if (uid < 0) return null
            val pkg = packageManager.getPackagesForUid(uid)?.firstOrNull() ?: return null
            val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            label to pkg
        }.getOrNull()
    }

    private fun addr(s: String): InetSocketAddress? {
        if (s.isEmpty()) return null
        val host: String; val port: Int
        if (s.startsWith("[")) { val c = s.indexOf(']'); host = s.substring(1, c); port = s.substring(c + 2).toInt() }
        else { val c = s.lastIndexOf(':'); host = s.substring(0, c); port = s.substring(c + 1).toInt() }
        return InetSocketAddress(InetAddress.getByName(host), port)
    }

    // ---- notification
    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "VPN", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, TunnelVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val en = Vpn.prefs(this).getString("lang", "ru") == "en"
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(Vpn.statusText(this))
            .setContentText(Vpn.lastServerName(this))
            .setContentIntent(open)
            .setOngoing(true)
            .setColor(0xFF3B82F6.toInt())
            .addAction(Notification.Action.Builder(null, if (en) "Disconnect" else "Отключить", stop).build())
            .build()
    }

    private fun startInForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        else startForeground(NOTIFICATION_ID, notification())
    }

    private fun updateNotification() {
        if (running) runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification()) }
    }
}
