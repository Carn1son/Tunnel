package com.carnison.tunnel

import android.util.Base64
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * SSH for the "Server" tab: short commands on the user's own server (password, key or keyboard-interactive login).
 * Sessions are reused for a few minutes, so the server sees one login instead of one per command.
 */
object Ssh {
    class SshError(val code: String, msg: String, val fp: String? = null) : Exception(msg)

    private class Entry(val session: Session, @Volatile var used: Long)
    private val pool = ConcurrentHashMap<String, Entry>()

    init {
        Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay({
            val now = System.currentTimeMillis()
            pool.entries.removeIf { (_, e) -> (now - e.used > 4 * 60_000).also { old -> if (old) runCatching { e.session.disconnect() } } }
        }, 1, 1, TimeUnit.MINUTES)
    }

    private fun key(c: JSONObject) = c.optString("user") + "@" + c.optString("host") + ":" + c.optString("port") + "/" + c.optString("auth") + "/" + (c.optString("password") + c.optString("key")).hashCode()

    fun drop(c: JSONObject) { pool.remove(key(c))?.let { runCatching { it.session.disconnect() } } }

    fun exec(c: JSONObject, command: String, stdin: String?, timeoutSec: Int): JSONObject {
        val (session, fp) = get(c)
        try {
            val ch = session.openChannel("exec") as ChannelExec
            ch.setCommand(command)
            val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
            ch.setErrStream(err, true)
            val input = ch.inputStream
            val toServer = ch.outputStream
            ch.connect(15000)
            if (!stdin.isNullOrEmpty()) { toServer.write(stdin.toByteArray()); toServer.flush() }
            toServer.close()
            val deadline = System.currentTimeMillis() + (if (timeoutSec > 0) timeoutSec else 120) * 1000L
            val buf = ByteArray(8192)
            while (true) {
                while (input.available() > 0) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                if (ch.isClosed) { if (input.available() > 0) continue; break }
                if (System.currentTimeMillis() > deadline) { ch.disconnect(); throw SshError("timeout", "the command took too long", fp) }
                Thread.sleep(30)
            }
            val code = ch.exitStatus
            ch.disconnect()
            return JSONObject().put("code", code).put("out", out.toString("UTF-8")).put("err", err.toString("UTF-8")).put("fp", fp)
        } catch (e: JSchException) {
            drop(c); throw SshError("connect", e.message ?: "connection lost", fp)
        }
    }

    private fun get(c: JSONObject): Pair<Session, String> {
        val k = key(c)
        pool[k]?.let { e ->
            if (e.session.isConnected) { e.used = System.currentTimeMillis(); return e.session to c.optString("fp") }
            pool.remove(k)
        }
        val (s, fp) = connect(c)
        pool[k] = Entry(s, System.currentTimeMillis())
        return s to fp
    }

    private fun connect(c: JSONObject): Pair<Session, String> {
        val host = c.optString("host").trim(); val user = c.optString("user").trim()
        val port = c.optString("port").toIntOrNull()?.takeIf { it > 0 } ?: 22
        val pinned = c.optString("fp")
        if (host.isEmpty() || user.isEmpty()) throw SshError("input", "host and user are required")
        val jsch = JSch()
        val password = c.optString("password")
        if (c.optString("auth") == "key") {
            try {
                val pass = c.optString("passphrase").takeIf { it.isNotEmpty() }?.toByteArray()
                jsch.addIdentity("key", (c.optString("key").replace("\r\n", "\n").trim() + "\n").toByteArray(), null, pass)
            } catch (e: JSchException) { throw SshError("key", e.message ?: "bad key") }
        }
        val s = jsch.getSession(user, host, port)
        if (c.optString("auth") != "key") s.setPassword(password)
        s.userInfo = object : UserInfo, UIKeyboardInteractive {
            override fun getPassphrase() = c.optString("passphrase")
            override fun getPassword() = password
            override fun promptPassword(message: String?) = true
            override fun promptPassphrase(message: String?) = true
            override fun promptYesNo(message: String?) = true
            override fun showMessage(message: String?) {}
            override fun promptKeyboardInteractive(destination: String?, name: String?, instruction: String?, prompt: Array<out String>?, echo: BooleanArray?): Array<String> =
                Array(prompt?.size ?: 0) { password }
        }
        s.setConfig("StrictHostKeyChecking", "no") // checked below against the remembered fingerprint
        s.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
        s.setServerAliveInterval(30_000)
        try { s.connect(15000) } catch (e: JSchException) {
            val m = e.message ?: ""
            throw SshError(if (m.contains("Auth", true)) "auth" else "connect", m)
        }
        val fp = "SHA256:" + Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(Base64.decode(s.hostKey.key, Base64.DEFAULT)), Base64.NO_WRAP or Base64.NO_PADDING)
        if (pinned.isNotEmpty() && pinned != fp) { s.disconnect(); throw SshError("hostkey", "the server key has changed", fp) }
        return s to fp
    }
}
