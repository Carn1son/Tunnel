// SSH for the "Server" tab: runs short commands on the user's own server (password, key or keyboard-interactive login).
// Sessions are kept for a few minutes and reused, so the server sees one login instead of one per command.
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading;
using Renci.SshNet;
using Renci.SshNet.Common;

namespace Tunnel
{
    static class Ssh
    {
        class Entry { public SshClient Client; public DateTime Used; }
        static readonly Dictionary<string, Entry> pool = new Dictionary<string, Entry>();
        static readonly Timer sweeper = new Timer(_ => Sweep(), null, 60000, 60000);

        public class SshError : Exception
        {
            public readonly string Code, Fp;
            public SshError(string code, string msg, string fp = null) : base(msg) { Code = code; Fp = fp; }
        }

        static string S(Dictionary<string, object> m, string k) { object v; return m != null && m.TryGetValue(k, out v) && v != null ? v.ToString() : ""; }

        /// conn: host, port, user, auth ("password" | "key"), password, key (private key text), passphrase, fp (pinned host key)
        public static Dictionary<string, object> Exec(Dictionary<string, object> conn, string command, string stdin, int timeoutSec)
        {
            string fp;
            var client = Get(conn, out fp);
            try
            {
                using (var cmd = client.CreateCommand(command))
                {
                    cmd.CommandTimeout = TimeSpan.FromSeconds(timeoutSec > 0 ? timeoutSec : 120);
                    var ar = cmd.BeginExecute();
                    if (!string.IsNullOrEmpty(stdin))
                        using (var input = cmd.CreateInputStream())
                        {
                            var b = Encoding.UTF8.GetBytes(stdin);
                            input.Write(b, 0, b.Length);
                        }
                    cmd.EndExecute(ar);
                    object es = cmd.ExitStatus;
                    return new Dictionary<string, object> {
                        { "code", es == null ? -1 : Convert.ToInt32(es) }, { "out", cmd.Result ?? "" }, { "err", cmd.Error ?? "" }, { "fp", fp } };
                }
            }
            catch (SshOperationTimeoutException) { throw new SshError("timeout", "the command took too long", fp); }
            catch (SshConnectionException e) { Drop(conn); throw new SshError("connect", e.Message, fp); }
        }

        static string Key(Dictionary<string, object> c) { return S(c, "user") + "@" + S(c, "host") + ":" + S(c, "port") + "/" + S(c, "auth") + "/" + (S(c, "password") + S(c, "key")).GetHashCode(); }

        public static void Drop(Dictionary<string, object> conn)
        {
            lock (pool)
            {
                Entry e; var k = Key(conn);
                if (pool.TryGetValue(k, out e)) { pool.Remove(k); try { e.Client.Dispose(); } catch { } }
            }
        }

        static void Sweep()
        {
            lock (pool)
                foreach (var k in pool.Where(p => DateTime.UtcNow - p.Value.Used > TimeSpan.FromMinutes(4)).Select(p => p.Key).ToList())
                {
                    try { pool[k].Client.Dispose(); } catch { }
                    pool.Remove(k);
                }
        }

        static SshClient Get(Dictionary<string, object> conn, out string fp)
        {
            string k = Key(conn);
            lock (pool)
            {
                Entry e;
                if (pool.TryGetValue(k, out e) && e.Client.IsConnected) { e.Used = DateTime.UtcNow; fp = S(conn, "fp"); return e.Client; }
                if (e != null) { try { e.Client.Dispose(); } catch { } pool.Remove(k); }
            }
            var client = Connect(conn, out fp);
            lock (pool) pool[k] = new Entry { Client = client, Used = DateTime.UtcNow };
            return client;
        }

        static SshClient Connect(Dictionary<string, object> conn, out string fp)
        {
            string host = S(conn, "host").Trim(), user = S(conn, "user").Trim(), pinned = S(conn, "fp");
            int port; if (!int.TryParse(S(conn, "port"), out port) || port <= 0) port = 22;
            if (host == "" || user == "") throw new SshError("input", "host and user are required");

            var methods = new List<AuthenticationMethod>();
            if (S(conn, "auth") == "key")
            {
                PrivateKeyFile pk;
                try
                {
                    using (var ms = new MemoryStream(Encoding.UTF8.GetBytes(S(conn, "key").Replace("\r\n", "\n").Trim() + "\n")))
                        pk = S(conn, "passphrase") == "" ? new PrivateKeyFile(ms) : new PrivateKeyFile(ms, S(conn, "passphrase"));
                }
                catch (Exception e) { throw new SshError("key", e.Message); }
                methods.Add(new PrivateKeyAuthenticationMethod(user, pk));
            }
            else
            {
                string pw = S(conn, "password");
                methods.Add(new PasswordAuthenticationMethod(user, pw));
                var ki = new KeyboardInteractiveAuthenticationMethod(user); // servers that only ask "Password:" interactively
                ki.AuthenticationPrompt += (s, e) => { foreach (var p in e.Prompts) p.Response = pw; };
                methods.Add(ki);
            }

            var info = new ConnectionInfo(host, port, user, methods.ToArray()) { Timeout = TimeSpan.FromSeconds(15) };
            var client = new SshClient(info) { KeepAliveInterval = TimeSpan.FromSeconds(30) };
            string seen = null;
            client.HostKeyReceived += (s, e) =>
            {
                seen = "SHA256:" + e.FingerPrintSHA256;
                e.CanTrust = pinned == "" || pinned == seen; // the first key is remembered; a different one later is refused
            };
            try { client.Connect(); }
            catch (SshConnectionException e) when (seen != null && pinned != "" && pinned != seen) { client.Dispose(); throw new SshError("hostkey", e.Message, seen); }
            catch (SshAuthenticationException e) { client.Dispose(); throw new SshError("auth", e.Message, seen); }
            catch (SshOperationTimeoutException e) { client.Dispose(); throw new SshError("connect", e.Message, seen); }
            catch (System.Net.Sockets.SocketException e) { client.Dispose(); throw new SshError("connect", e.Message, seen); }
            catch (SshException e) { client.Dispose(); throw new SshError(seen != null && pinned != "" && pinned != seen ? "hostkey" : "connect", e.Message, seen); }
            fp = seen ?? pinned;
            return client;
        }
    }
}
