// Tunnel for Windows: the Tunnel page in a WebView2 window plus a tray icon. The VPN runs in the TunnelVPN service.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Pipes;
using System.Linq;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;
using Microsoft.Win32;

namespace Tunnel
{
    static class Build
    {
        public static readonly Dictionary<string, string> V = Read();
        static Dictionary<string, string> Read()
        {
            var d = new Dictionary<string, string>();
            using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("build.txt"))
            using (var r = new StreamReader(s, Encoding.UTF8))
                foreach (var line in r.ReadToEnd().Split('\n'))
                {
                    int i = line.IndexOf('='); if (i > 0) d[line.Substring(0, i).Trim()] = line.Substring(i + 1).Trim();
                }
            return d;
        }
        public static string Get(string k, string def) { string v; return V.TryGetValue(k, out v) && v != "" ? v : def; }
        public static string Version { get { return Get("version", "0.0.0"); } }
        public static string Label { get { return Get("label", "V1.0"); } }
        public static string Day { get { return Get("day", ""); } }
    }

    /// The page's state (servers with passwords, profiles, settings), encrypted for this Windows user.
    static class Store
    {
        static string File_ { get { return Path.Combine(Program.Home, "state.dat"); } }
        public static string Load()
        {
            try { return Encoding.UTF8.GetString(ProtectedData.Unprotect(File.ReadAllBytes(File_), null, DataProtectionScope.CurrentUser)); }
            catch { return null; }
        }
        public static void Save(string json)
        {
            var tmp = File_ + ".tmp";
            File.WriteAllBytes(tmp, ProtectedData.Protect(Encoding.UTF8.GetBytes(json), null, DataProtectionScope.CurrentUser));
            if (File.Exists(File_)) File.Replace(tmp, File_, null); else File.Move(tmp, File_);
        }
    }

    static class Program
    {
        public static string Home;   // %LOCALAPPDATA%\Tunnel
        public static string AppDir; // unpacked page + WebView2Loader.dll for this version
        static Mutex single;
        const string Pipe = "Tunnel-open-";

        [STAThread]
        static void Main(string[] args)
        {
            AppDomain.CurrentDomain.AssemblyResolve += (s, e) => // the WebView2 .NET libraries live inside this exe
            {
                string name = new AssemblyName(e.Name).Name + ".dll";
                using (var st = Assembly.GetExecutingAssembly().GetManifestResourceStream(name))
                {
                    if (st == null) return null;
                    var b = new byte[st.Length]; st.Read(b, 0, b.Length); return Assembly.Load(b);
                }
            };
            Run(args);
        }

        static void Run(string[] args)
        {
            string pipe = Pipe + Environment.UserName;
            bool first;
            single = new Mutex(true, "Local\\" + pipe, out first);
            if (!first) { PassToRunning(pipe, args); return; } // a second launch shows the window (and hands over a tt:// link)

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            System.Net.ServicePointManager.SecurityProtocol = System.Net.SecurityProtocolType.Tls12;

            Home = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Tunnel");
            Directory.CreateDirectory(Home);
            try { AppDir = Unpack(); }
            catch (Exception e) { MessageBox.Show("Tunnel: " + e.Message, "Tunnel", MessageBoxButtons.OK, MessageBoxIcon.Error); return; }

            string wv = null;
            try { CoreWebView2Environment.SetLoaderDllFolderPath(AppDir); wv = CoreWebView2Environment.GetAvailableBrowserVersionString(); } catch { }
            if (string.IsNullOrEmpty(wv))
            {
                if (MessageBox.Show("Для Tunnel нужен компонент Microsoft Edge WebView2 (обычно он уже есть в Windows 10 и 11).\n\nОткрыть страницу загрузки?",
                    "Tunnel", MessageBoxButtons.YesNo, MessageBoxIcon.Information) == DialogResult.Yes)
                    try { Process.Start("https://go.microsoft.com/fwlink/p/?LinkId=2124703"); } catch { }
                return;
            }

            try { Core.Init(); }
            catch (DllNotFoundException) { MessageBox.Show("Не найден trusttunnel.dll. Переустановите Tunnel.", "Tunnel", MessageBoxButtons.OK, MessageBoxIcon.Error); return; }

            var form = new MainForm(args.Contains("--tray"), args.FirstOrDefault(IsLink));
            new Thread(() => Listen(pipe, form)) { IsBackground = true }.Start();
            Application.Run(form);
            Core.Detach();
            GC.KeepAlive(single);
        }

        public static bool IsLink(string a) { return a != null && (a.StartsWith("tt://", StringComparison.OrdinalIgnoreCase) || a.StartsWith("trusttunnel://", StringComparison.OrdinalIgnoreCase)); }

        static string Unpack()
        {
            // the folder name also carries a hash of the page, so a rebuild with the same version still brings the new interface
            string hash;
            using (var st = Assembly.GetExecutingAssembly().GetManifestResourceStream("index.html"))
            using (var sha = System.Security.Cryptography.SHA1.Create())
                hash = BitConverter.ToString(sha.ComputeHash(st)).Replace("-", "").Substring(0, 10).ToLowerInvariant();
            string dir = Path.Combine(Home, "app-" + Build.Version + "-" + hash);
            string mark = Path.Combine(dir, ".ok");
            if (File.Exists(mark)) return dir;
            if (Directory.Exists(dir)) Directory.Delete(dir, true);
            Directory.CreateDirectory(Path.Combine(dir, "www"));
            var asm = Assembly.GetExecutingAssembly();
            foreach (var res in new[] { "index.html", "WebView2Loader.dll" })
                using (var st = asm.GetManifestResourceStream(res))
                using (var f = File.Create(Path.Combine(dir, res == "index.html" ? "www" : "", res))) st.CopyTo(f);
            File.WriteAllText(mark, Build.Version);
            foreach (var old in Directory.GetDirectories(Home, "app-*")) if (!string.Equals(old, dir, StringComparison.OrdinalIgnoreCase)) try { Directory.Delete(old, true); } catch { }
            return dir;
        }

        static void PassToRunning(string pipe, string[] args)
        {
            try
            {
                using (var c = new NamedPipeClientStream(".", pipe, PipeDirection.Out))
                {
                    c.Connect(3000);
                    var b = Encoding.UTF8.GetBytes((args.FirstOrDefault(IsLink) ?? "") + "\n\u0004");
                    c.Write(b, 0, b.Length); c.Flush();
                }
            }
            catch { }
        }

        static void Listen(string pipe, MainForm form)
        {
            while (true)
            {
                try
                {
                    using (var s = new NamedPipeServerStream(pipe, PipeDirection.In, 1))
                    {
                        s.WaitForConnection();
                        var link = new StreamReader(s, Encoding.UTF8).ReadToEnd().Replace("\u0004", "").Trim();
                        form.BeginInvoke((Action)(() => form.FromOutside(link)));
                    }
                }
                catch { Thread.Sleep(500); }
            }
        }
    }

    class MainForm : Form
    {
        [System.Runtime.InteropServices.DllImport("dwmapi.dll")]
        static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);
        protected override void OnHandleCreated(EventArgs e) // title bar in the page's theme
        {
            base.OnHandleCreated(e);
            TitleBar(LightTheme());
            if (!inited) { inited = true; BeginInvoke(new Action(async () => await Init())); } // also when started hidden in the tray
        }
        bool inited;

        bool LightTheme() { try { return state != null && (string)state["theme"] == "light"; } catch { return false; } }
        void TitleBar(bool light)
        {
            try
            {
                int dark = light ? 0 : 1; if (DwmSetWindowAttribute(Handle, 20, ref dark, 4) != 0) DwmSetWindowAttribute(Handle, 19, ref dark, 4);
                int cap = light ? 0x00ffffff : 0x00121212, text = light ? 0x00141414 : 0x00ececec; // 0x00BBGGRR, Windows 11
                DwmSetWindowAttribute(Handle, 35, ref cap, 4); DwmSetWindowAttribute(Handle, 36, ref text, 4);
            }
            catch { }
        }

        readonly WebView2 web = new WebView2();
        readonly NotifyIcon tray = new NotifyIcon();
        readonly Icon icoOn, icoOff;
        public readonly JavaScriptSerializer Json = new JavaScriptSerializer { MaxJsonLength = int.MaxValue };
        readonly Updater updater;
        bool startHidden, allowShow, quitting, pageReady;
        string pendingLink;
        int vpnState = Core.Disconnected;
        Dictionary<string, object> state; // the saved page state, for the tray menu and window behaviour

        public MainForm(bool fromAutostart, string link)
        {
            pendingLink = link;
            state = Parse(Store.Load());
            startHidden = fromAutostart && !Setting("openWin", false);
            allowShow = !startHidden;
            Text = "Tunnel";
            var asm = Assembly.GetExecutingAssembly();
            Icon = Icon.ExtractAssociatedIcon(asm.Location);
            icoOn = LoadIcon("tray_on.ico"); icoOff = LoadIcon("tray_off.ico");
            BackColor = ColorTranslator.FromHtml("#0a0a0a");
            MinimumSize = new Size(760, 560);
            Size = new Size(1100, 740);
            StartPosition = FormStartPosition.CenterScreen;
            web.Dock = DockStyle.Fill;
            web.DefaultBackgroundColor = BackColor;
            Controls.Add(web);
            updater = new Updater(this);

            tray.Icon = icoOff; tray.Text = "Tunnel"; tray.Visible = true;
            tray.MouseClick += (s, e) => { if (e.Button == MouseButtons.Left) ShowWindow(); };
            tray.ContextMenuStrip = new ContextMenuStrip();
            tray.ContextMenuStrip.Opening += (s, e) => BuildTrayMenu();

            Core.StateChanged += st => BeginInvoke((Action)(() => OnState(st)));
            Core.ConnectionInfo += j => OnConnection(j);

            FormClosing += OnClosing;
        }

        static Icon LoadIcon(string res)
        {
            using (var st = Assembly.GetExecutingAssembly().GetManifestResourceStream(res)) return new Icon(st, SystemInformation.SmallIconSize);
        }

        protected override void SetVisibleCore(bool value)
        {
            if (!allowShow) { value = false; if (!IsHandleCreated) CreateHandle(); }
            base.SetVisibleCore(value);
        }

        Dictionary<string, object> Parse(string json)
        {
            try { return string.IsNullOrEmpty(json) ? null : Json.Deserialize<Dictionary<string, object>>(json); } catch { return null; }
        }
        bool Setting(string key, bool def)
        {
            try { var set = (Dictionary<string, object>)state["set"]; return set.ContainsKey(key) ? Convert.ToBoolean(set[key]) : def; } catch { return def; }
        }
        string Lang { get { try { return (string)state["lang"] ?? "ru"; } catch { return "ru"; } } }
        string T(string ru, string en) { return Lang == "en" ? en : ru; }

        async Task Init()
        {
            try
            {
                var env = await CoreWebView2Environment.CreateAsync(null, Path.Combine(Program.Home, "WebView2"), new CoreWebView2EnvironmentOptions("--disable-features=msSmartScreenProtection"));
                var unused = web.Handle; // the page also loads while the window is hidden (start in the tray)
                await web.EnsureCoreWebView2Async(env);
                var cw = web.CoreWebView2;
                cw.Settings.AreDevToolsEnabled = false;
                cw.Settings.IsStatusBarEnabled = false;
                cw.Settings.IsZoomControlEnabled = false;
                cw.Settings.AreBrowserAcceleratorKeysEnabled = false;
                cw.Settings.IsPasswordAutosaveEnabled = false;
                cw.Settings.IsGeneralAutofillEnabled = false;
                cw.SetVirtualHostNameToFolderMapping("app.tunnel", Path.Combine(Program.AppDir, "www"), CoreWebView2HostResourceAccessKind.Allow);
                var host = new Dictionary<string, object> { { "version", Build.Version }, { "label", Build.Label }, { "day", Build.Day } };
                await cw.AddScriptToExecuteOnDocumentCreatedAsync("window.TUNNEL_HOST = " + Json.Serialize(host) + ";");
                cw.WebMessageReceived += OnMessage;
                cw.NewWindowRequested += (s, e) => { e.Handled = true; External(e.Uri); };
                cw.NavigationStarting += (s, e) => { if (!e.Uri.StartsWith("https://app.tunnel/", StringComparison.OrdinalIgnoreCase)) { e.Cancel = true; External(e.Uri); } };
                cw.Navigate("https://app.tunnel/index.html");
            }
            catch (Exception ex)
            {
                MessageBox.Show("WebView2: " + ex.Message, "Tunnel", MessageBoxButtons.OK, MessageBoxIcon.Error);
                quitting = true; Close();
            }
        }

        static void External(string url)
        {
            if (string.IsNullOrEmpty(url) || !(url.StartsWith("http://") || url.StartsWith("https://"))) return;
            try { Process.Start(new ProcessStartInfo(url) { UseShellExecute = true }); } catch { }
        }

        // ---------- page <-> host
        public void Post(Dictionary<string, object> m)
        {
            if (InvokeRequired) { BeginInvoke((Action)(() => Post(m))); return; }
            try { if (web.CoreWebView2 != null) web.CoreWebView2.PostWebMessageAsJson(Json.Serialize(m)); } catch { }
        }
        public void Event(string t, Dictionary<string, object> data) { data["t"] = t; Post(data); }
        void Reply(object req, object data, string error = null)
        {
            var m = new Dictionary<string, object> { { "t", "res" }, { "req", req }, { "data", data } };
            if (error != null) m["error"] = error;
            Post(m);
        }
        static string S(Dictionary<string, object> m, string k) { object v; return m.TryGetValue(k, out v) ? v as string : null; }

        async void OnMessage(object sender, CoreWebView2WebMessageReceivedEventArgs e)
        {
            Dictionary<string, object> m;
            try { m = Json.Deserialize<Dictionary<string, object>>(e.WebMessageAsJson); } catch { return; }
            string t = S(m, "t");
            object req = m.ContainsKey("req") ? m["req"] : null;
            try
            {
                switch (t)
                {
                    case "ready":
                        pageReady = true;
                        Reply(req, new Dictionary<string, object> { { "state", Store.Load() }, { "vpn", vpnState }, { "autostarted", startHidden }, { "link", pendingLink } });
                        pendingLink = null;
                        break;
                    case "save":
                        Store.Save(S(m, "state")); state = Parse(S(m, "state"));
                        ApplyAutostart(Setting("autostart", false));
                        break;
                    case "connect":
                        {
                            string toml = S(m, "toml");
                            int r = await Task.Run(() => Core.Start(toml)); // starts the service if it is not running
                            Reply(req, r == 0, Core.ErrorText(r));
                            break;
                        }
                    case "disconnect": await Task.Run(() => Core.Stop()); Reply(req, true); break;
                    case "link-decode":
                        {
                            var toml = await Task.Run(() => Core.DecodeLink(S(m, "uri")));
                            Reply(req, toml, toml == null ? "bad-link" : null); break;
                        }
                    case "link-encode":
                        {
                            var link = await Task.Run(() => Core.EncodeLink(S(m, "toml")));
                            if (link != null) Clipboard.SetText(link);
                            Reply(req, link, link == null ? "bad-config" : null); break;
                        }
                    case "pick-exe":
                        using (var d = new OpenFileDialog { Filter = T("Программы", "Programs") + " (*.exe)|*.exe", Title = T("Выберите программу", "Choose a program") })
                            Reply(req, d.ShowDialog(this) == DialogResult.OK ? Apps.Describe(d.FileName) : null);
                        break;
                    case "running": Reply(req, await Task.Run(() => Apps.Running())); break;
                    case "icon": Reply(req, await Task.Run(() => Apps.IconOf(S(m, "path")))); break;
                    case "logs-export":
                        {
                            string dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), "Tunnel-logs-" + DateTime.Now.ToString("yyyy-MM-dd_HH-mm"));
                            await Task.Run(() => Core.ExportLogs(dir));
                            File.WriteAllText(Path.Combine(dir, "system.txt"), "Tunnel " + Build.Label + " (" + Build.Version + ")\r\nWindows " + Environment.OSVersion.VersionString + (Environment.Is64BitOperatingSystem ? " x64" : "") + "\r\n");
                            try { Process.Start("explorer.exe", "\"" + dir + "\""); } catch { }
                            Reply(req, dir); break;
                        }
                    case "logs-clear": Core.ClearLogs(); Reply(req, true); break;
                    case "upd-check": Reply(req, await updater.Check()); break;
                    case "upd-download": Reply(req, await updater.Download()); break;
                    case "upd-install": if (updater.Install()) Quit(false); break;
                    case "open-url": External(S(m, "url")); break;
                    case "theme": TitleBar(!(m.ContainsKey("dark") && Convert.ToBoolean(m["dark"]))); break;
                    case "quit": Quit(true); break;
                }
            }
            catch (Exception ex) { if (req != null) Reply(req, null, ex.Message); }
        }

        // ---------- VPN state, connection log
        void OnState(int st)
        {
            vpnState = st;
            tray.Icon = st == Core.Connected ? icoOn : icoOff;
            tray.Text = "Tunnel — " + (st == Core.Connected ? T("подключено", "connected") : st == Core.Disconnected ? T("отключено", "disconnected") : T("подключение…", "connecting…"));
            Event("vpn", new Dictionary<string, object> { { "state", st } });
        }

        void OnConnection(string json)
        {
            if (string.IsNullOrEmpty(json)) return;
            Dictionary<string, object> info;
            try { info = Json.Deserialize<Dictionary<string, object>>(json); } catch { return; }
            // the engine reports the virtual-interface side; the owning program is looked up while the socket is still open
            string src = S(info, "src"), proto = S(info, "proto");
            if (src != null)
            {
                string path = Apps.OwnerOf(src, proto == "TCP");
                if (path != null) { info["app"] = Path.GetFileName(path); info["path"] = path; }
            }
            Event("conn", new Dictionary<string, object> { { "info", info } });
        }

        // ---------- tray
        void BuildTrayMenu()
        {
            var menu = tray.ContextMenuStrip; menu.Items.Clear();
            string status = vpnState == Core.Connected ? T("Подключено", "Connected") : vpnState == Core.Disconnected ? T("Отключено", "Disconnected") : T("Подключение…", "Connecting…");
            menu.Items.Add(new ToolStripMenuItem(T("Статус: ", "Status: ") + status) { Enabled = false });
            menu.Items.Add(new ToolStripSeparator());
            var servers = new List<KeyValuePair<string, string>>(); string sel = null;
            try
            {
                sel = state["sel"] as string;
                foreach (var o in (System.Collections.ArrayList)state["servers"]) { var s = (Dictionary<string, object>)o; servers.Add(new KeyValuePair<string, string>((string)s["id"], (string)s["name"])); }
            }
            catch { }
            if (vpnState == Core.Disconnected)
            {
                var c = new ToolStripMenuItem(T("Подключить", "Connect")) { Enabled = servers.Count > 0 };
                c.Click += (s, e) => Event("tray", new Dictionary<string, object> { { "action", "connect" } });
                menu.Items.Add(c);
            }
            else
            {
                var d = new ToolStripMenuItem(T("Отключить", "Disconnect"));
                d.Click += (s, e) => Event("tray", new Dictionary<string, object> { { "action", "disconnect" } });
                menu.Items.Add(d);
            }
            if (servers.Count > 0)
            {
                var sub = new ToolStripMenuItem(T("Сервер", "Server"));
                foreach (var kv in servers)
                {
                    var id = kv.Key;
                    var it = new ToolStripMenuItem(kv.Value) { Checked = id == sel };
                    it.Click += (s, e) => Event("tray", new Dictionary<string, object> { { "action", "select" }, { "id", id } });
                    sub.DropDownItems.Add(it);
                }
                menu.Items.Add(sub);
            }
            menu.Items.Add(new ToolStripSeparator());
            var open = new ToolStripMenuItem(T("Открыть Tunnel", "Open Tunnel")); open.Click += (s, e) => ShowWindow(); menu.Items.Add(open);
            var quit = new ToolStripMenuItem(T("Выйти из Tunnel", "Quit Tunnel")); quit.Click += (s, e) => Quit(true); menu.Items.Add(quit);
        }

        public void ShowWindow()
        {
            allowShow = true;
            Show();
            if (WindowState == FormWindowState.Minimized) WindowState = FormWindowState.Normal;
            Activate();
        }

        public void FromOutside(string link)
        {
            ShowWindow();
            if (!string.IsNullOrEmpty(link)) { if (pageReady) Event("link", new Dictionary<string, object> { { "uri", link } }); else pendingLink = link; }
        }

        void OnClosing(object sender, FormClosingEventArgs e)
        {
            if (quitting || e.CloseReason == CloseReason.WindowsShutDown) { tray.Visible = false; return; }
            if (Setting("minTray", true)) { e.Cancel = true; Hide(); return; }
            e.Cancel = true; Quit(true);
        }

        void Quit(bool ask)
        {
            if (ask && vpnState != Core.Disconnected)
            {
                ShowWindow();
                if (MessageBox.Show(this, T("Активное подключение будет разорвано.", "The active connection will be closed."), T("Выйти из Tunnel?", "Quit Tunnel?"),
                    MessageBoxButtons.OKCancel, MessageBoxIcon.Question) != DialogResult.OK) return;
            }
            if (vpnState != Core.Disconnected) try { Core.Stop(); } catch { }
            quitting = true; tray.Visible = false;
            Close();
        }

        // ---------- autostart with Windows (current user)
        static void ApplyAutostart(bool on)
        {
            try
            {
                using (var k = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run", true))
                {
                    if (on) k.SetValue("Tunnel", "\"" + Assembly.GetExecutingAssembly().Location + "\" --tray");
                    else if (k.GetValue("Tunnel") != null) k.DeleteValue("Tunnel");
                }
            }
            catch { }
        }
    }
}
