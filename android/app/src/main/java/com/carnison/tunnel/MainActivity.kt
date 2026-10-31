package com.carnison.tunnel

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Tunnel: the same page as on Windows in a WebView, talking to the app through window.TunnelAndroid. */
class MainActivity : Activity() {
    companion object {
        private const val REQ_VPN = 1
        private const val REQ_KEY = 3
        private const val REPO = "Carn1son/Tunnel"
        private const val APK = "Tunnel.apk"
    }

    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private val io = Executors.newCachedThreadPool()
    private var pageReady = false
    private var pendingLink: String? = null
    private var pendingConnect: Pair<JSONObject, Any?>? = null
    private var downloadedApk: File? = null
    private var pendingKeyReq: Any? = null

    private val stateListener: (Int) -> Unit = { s -> event(JSONObject().put("t", "vpn").put("state", s)) }
    private val connListener: (JSONObject) -> Unit = { info -> event(JSONObject().put("t", "conn").put("info", info)) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingLink = linkOf(intent)
        web = WebView(this)
        web.setBackgroundColor(0xFF0A0A0A.toInt())
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            textZoom = 100
            mediaPlaybackRequiresUserGesture = true
        }
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.addJavascriptInterface(Bridge(), "TunnelAndroid")
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url.toString()
                if (u.startsWith("file:///android_asset/")) return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, req.url)) }
                return true
            }
        }
        // the page is laid out between the status bar and the navigation bar (Android 15 draws apps under them by default)
        root = FrameLayout(this)
        root.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        applyTheme(Vpn.prefs(this).getString("theme", "dark") != "light")
        // Android 13+ (predictive back): the same handling through the new callback
        if (Build.VERSION.SDK_INT >= 33)
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBack() }
        web.loadUrl("file:///android_asset/index.html")
        Vpn.stateListeners += stateListener
        Vpn.connListeners += connListener
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
    }

    override fun onDestroy() {
        Vpn.stateListeners -= stateListener
        Vpn.connListeners -= connListener
        web.destroy()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val link = linkOf(intent) ?: return
        if (pageReady) event(JSONObject().put("t", "link").put("uri", link)) else pendingLink = link
    }

    private fun linkOf(i: Intent?): String? = i?.data?.toString()?.takeIf { it.startsWith("tt://", true) || it.startsWith("trusttunnel://", true) }

    /** Back closes what is open in the page and returns to the servers screen; from there Tunnel goes to the background. */
    private fun goBack() {
        web.evaluateJavascript("(window.tnBack && window.tnBack()) ? 1 : 0") { v -> if (v != "1") moveTaskToBack(true) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = goBack()

    // back after a while in the background: the app opens on the servers screen again
    private var stoppedAt = 0L
    override fun onStop() { super.onStop(); stoppedAt = System.currentTimeMillis() }
    override fun onRestart() {
        super.onRestart()
        if (pageReady && stoppedAt > 0 && System.currentTimeMillis() - stoppedAt > 30_000) event(JSONObject().put("t", "home"))
    }

    // ---------- page <-> app
    private fun event(m: JSONObject) {
        val js = "window.__tnRecv && window.__tnRecv(" + m.toString() + ")"
        runOnUiThread { runCatching { web.evaluateJavascript(js, null) } }
    }
    private fun reply(req: Any?, data: Any?, error: String? = null) {
        val m = JSONObject().put("t", "res").put("req", req).put("data", data ?: JSONObject.NULL)
        if (error != null) m.put("error", error)
        event(m)
    }

    inner class Bridge {
        @JavascriptInterface
        fun host(): String = JSONObject()
            .put("platform", "android")
            .put("version", BuildConfigLite.version(this@MainActivity))
            .put("label", BuildConfigLite.label(this@MainActivity))
            .put("day", BuildConfigLite.day(this@MainActivity))
            .toString()

        @JavascriptInterface
        fun post(json: String) {
            val m = runCatching { JSONObject(json) }.getOrNull() ?: return
            runOnUiThread { handle(m) }
        }
    }

    private fun handle(m: JSONObject) {
        val req = if (m.has("req")) m.get("req") else null
        when (m.optString("t")) {
            "ready" -> {
                pageReady = true
                reply(req, JSONObject()
                    .put("state", Vpn.prefs(this).getString("state", null) ?: JSONObject.NULL)
                    .put("vpn", Vpn.state)
                    .put("link", pendingLink ?: JSONObject.NULL))
                pendingLink = null
                Vpn.recentConnections().forEach { connListener(it) }
            }
            "save" -> {
                val st = m.optString("state")
                val o = runCatching { JSONObject(st) }.getOrNull()
                val lang = o?.optString("lang", "ru") ?: "ru"
                val theme = o?.optString("theme", "dark") ?: "dark"
                Vpn.prefs(this).edit().putString("state", st).putString("lang", lang).putString("theme", theme).apply()
                applyTheme(theme != "light")
            }
            "ssh" -> io.execute {
                val conn = m.optJSONObject("conn") ?: JSONObject()
                try { reply(req, Ssh.exec(conn, m.optString("cmd"), m.optString("stdin"), m.optInt("timeout", 120))) }
                catch (e: Ssh.SshError) { reply(req, JSONObject().put("fp", e.fp ?: ""), e.code + ": " + e.message) }
                catch (e: Exception) { reply(req, null, "connect: " + (e.message ?: e.javaClass.simpleName)) }
            }
            "ssh-drop" -> m.optJSONObject("conn")?.let { c -> io.execute { Ssh.drop(c) } }
            "pick-key" -> { pendingKeyReq = req; runCatching { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_KEY) }.onFailure { reply(req, null) } }
            "theme" -> {
                val dark = m.optBoolean("dark", true)
                Vpn.prefs(this).edit().putString("theme", if (dark) "dark" else "light").apply()
                applyTheme(dark)
            }
            "connect" -> {
                val prep = VpnService.prepare(this)
                if (prep != null) { pendingConnect = m to req; startActivityForResult(prep, REQ_VPN) }
                else connect(m, req)
            }
            "disconnect" -> { Vpn.stop(this); reply(req, true) }
            "link-decode" -> io.execute {
                val r = runCatching { DeepLinkLite.decode(m.optString("uri").trim()) }.getOrNull()
                if (r.isNullOrEmpty()) reply(req, null, "bad-link") else reply(req, r)
            }
            "link-encode" -> io.execute {
                val r = runCatching { DeepLinkLite.encode(m.optString("toml")) }.getOrNull()
                if (r.isNullOrEmpty()) reply(req, null, "bad-config")
                else { runOnUiThread { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Tunnel", r)) }; reply(req, r) }
            }
            "running" -> io.execute { reply(req, installedApps()) }
            "logs-export" -> io.execute { reply(req, exportLogs()) }
            "logs-clear" -> { runCatching { com.adguard.trusttunnel.VpnService.clearLogs() }; Vpn.clearRecent(); reply(req, true) }
            "vpn-settings" -> runCatching { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
            "open-url" -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(m.optString("url")))) }
            "traffic" -> reply(req, Vpn.traffic())
            "upd-check" -> io.execute { reply(req, checkUpdate()) }
            "upd-download" -> io.execute { reply(req, downloadUpdate()) }
            "upd-install" -> installUpdate()
            "quit" -> finish()
        }
    }

    /** System bars follow the page theme: dark bars with light icons, or the other way round. */
    private fun applyTheme(dark: Boolean) {
        val bar = if (dark) 0xFF121212.toInt() else 0xFFFFFFFF.toInt()
        root.setBackgroundColor(bar)
        web.setBackgroundColor(if (dark) 0xFF0A0A0A.toInt() else 0xFFEBEBEB.toInt())
        @Suppress("DEPRECATION") run { window.statusBarColor = bar; window.navigationBarColor = bar }
        WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    private fun connect(m: JSONObject, req: Any?) {
        val extra = m.optJSONObject("android") ?: JSONObject()
        Vpn.start(this, m.optString("toml"), extra.toString())
        reply(req, true)
        askBattery()
    }

    /**
     * Battery savers (HyperOS, One UI and others) cut the network of apps that are not on screen, so a VPN started
     * from the widget or the tile keeps reconnecting. Once, Tunnel asks to be left out of battery optimisation.
     */
    @SuppressLint("BatteryLife")
    private fun askBattery() {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName) || Vpn.prefs(this).getBoolean("askedBattery", false)) return
        Vpn.prefs(this).edit().putBoolean("askedBattery", true).apply()
        runCatching { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_KEY) {
            val r = pendingKeyReq; pendingKeyReq = null
            val text = data?.data?.takeIf { resultCode == RESULT_OK }?.let { u -> runCatching { contentResolver.openInputStream(u)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull() }
            reply(r, text)
            return
        }
        if (requestCode != REQ_VPN) return
        val p = pendingConnect ?: return
        pendingConnect = null
        if (resultCode == RESULT_OK) connect(p.first, p.second) else reply(p.second, null, "access")
    }

    // ---------- apps for split tunnelling
    private fun installedApps(): JSONArray {
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val seen = HashSet<String>()
        val list = pm.queryIntentActivities(main, 0).mapNotNull { ri ->
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName || !seen.add(pkg)) null
            else Triple(ri.loadLabel(pm).toString(), pkg, ri)
        }.sortedBy { it.first.lowercase() }
        val arr = JSONArray()
        for ((label, pkg, ri) in list) {
            val o = JSONObject().put("name", label).put("path", pkg)
            runCatching { o.put("icon", iconData(ri.loadIcon(pm))) }
            arr.put(o)
        }
        return arr
    }

    private fun iconData(d: android.graphics.drawable.Drawable): String {
        val size = 72
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); d.setBounds(0, 0, size, size); d.draw(c)
        val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    // ---------- logs: zipped and handed to the share sheet
    private fun exportLogs(): String? {
        val files = runCatching { com.adguard.trusttunnel.VpnService.exportLogs(this) }.getOrDefault(emptyList())
        val zip = File(cacheDir, "Tunnel-logs.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("system.txt"))
            z.write(("Tunnel " + BuildConfigLite.label(this) + " (" + BuildConfigLite.version(this) + ")\nAndroid " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), " + Build.MANUFACTURER + " " + Build.MODEL + "\n").toByteArray())
            z.closeEntry()
            for (f in files) {
                val file = File(f); if (!file.isFile) continue
                z.putNextEntry(ZipEntry(file.name)); file.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", zip)
        val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runOnUiThread { startActivity(Intent.createChooser(send, "Tunnel logs")) }
        return zip.name
    }

    // ---------- updates from the newest GitHub release
    private fun http(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000; readTimeout = 30000; instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Tunnel/" + BuildConfigLite.version(this@MainActivity))
    }

    private fun checkUpdate(): JSONObject {
        val cur = BuildConfigLite.version(this)
        return runCatching {
            val json = JSONObject(http("https://api.github.com/repos/$REPO/releases/latest").inputStream.bufferedReader().readText())
            val tag = json.optString("tag_name").removePrefix("v")
            val label = json.optString("name").removePrefix("Tunnel ")
            JSONObject().put("version", cur).put("latest", tag).put("label", label).put("newer", newer(tag, cur))
        }.getOrElse { JSONObject().put("version", cur).put("error", it.message ?: "error") }
    }

    private fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }; val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) { val p = x.getOrElse(i) { 0 }; val q = y.getOrElse(i) { 0 }; if (p != q) return p > q }
        return false
    }

    private fun downloadUpdate(): Boolean = runCatching {
        var c = http("https://github.com/$REPO/releases/latest/download/$APK")
        // GitHub answers with a redirect to another host
        var hops = 0
        while (c.responseCode in 300..399 && hops++ < 5) c = http(c.getHeaderField("Location"))
        val total = c.contentLengthLong
        val to = File(cacheDir, APK)
        var got = 0L; var last = -1
        c.inputStream.use { src -> to.outputStream().use { dst ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = src.read(buf); if (n < 0) break
                dst.write(buf, 0, n); got += n
                val pct = if (total > 0) (got * 100 / total).toInt() else 0
                if (pct != last) { last = pct; event(JSONObject().put("t", "upd").put("type", "progress").put("pct", pct)) }
            }
        } }
        downloadedApk = to
        true
    }.getOrElse { event(JSONObject().put("t", "upd").put("type", "error").put("msg", it.message)); false }

    private fun installUpdate() {
        val apk = downloadedApk ?: return
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", apk)
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** Build number, label and date, filled in by CI. */
object BuildConfigLite {
    fun version(a: Activity): String = a.packageManager.getPackageInfo(a.packageName, 0).versionName ?: "0.0.0"
    fun label(@Suppress("UNUSED_PARAMETER") a: Activity): String = BuildConfig.LABEL
    fun day(@Suppress("UNUSED_PARAMETER") a: Activity): String = BuildConfig.DAY
}

/** tt:// links through the engine's own codec (the native library is loaded with the engine). */
object DeepLinkLite {
    init { System.loadLibrary("trusttunnel_android") }
    fun decode(uri: String): String = com.adguard.trusttunnel.DeepLink.decode(uri)
    fun encode(toml: String): String = com.adguard.trusttunnel.DeepLink.encode(toml)
}
