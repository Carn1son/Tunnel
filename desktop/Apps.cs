// Applications on this PC: the owner of a connection (for the connection log), running apps and .exe files
// to pick for split tunnelling, with their names and icons.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Net;
using System.Runtime.InteropServices;
using System.Text;

namespace Tunnel
{
    static class Apps
    {
        [DllImport("kernel32.dll", SetLastError = true)] static extern IntPtr OpenProcess(uint access, bool inherit, int pid);
        [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr h);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern bool QueryFullProcessImageNameW(IntPtr h, int flags, StringBuilder name, ref int size);
        [DllImport("iphlpapi.dll")] static extern uint GetExtendedTcpTable(IntPtr table, ref int size, bool order, int af, int cls, uint reserved);
        [DllImport("iphlpapi.dll")] static extern uint GetExtendedUdpTable(IntPtr table, ref int size, bool order, int af, int cls, uint reserved);
        const uint QueryLimited = 0x1000;
        const int TcpOwnerPidAll = 5, UdpOwnerPid = 1;

        public static string PathOf(int pid)
        {
            if (pid <= 4) return null;
            IntPtr h = OpenProcess(QueryLimited, false, pid);
            if (h == IntPtr.Zero) return null;
            try { var sb = new StringBuilder(1024); int n = sb.Capacity; return QueryFullProcessImageNameW(h, 0, sb, ref n) ? sb.ToString(0, n) : null; }
            finally { CloseHandle(h); }
        }

        // ---- who owns a connection: "10.x.x.x:51234" as reported by the engine for the virtual interface side
        public static string OwnerOf(string src, bool tcp)
        {
            try
            {
                string host; int port;
                if (!Split(src, out host, out port)) return null;
                IPAddress ip; if (!IPAddress.TryParse(host, out ip)) return null;
                int pid = Find(ip, port, tcp);
                return pid > 0 ? PathOf(pid) : null;
            }
            catch { return null; }
        }

        static bool Split(string s, out string host, out int port)
        {
            host = null; port = 0;
            if (string.IsNullOrEmpty(s)) return false;
            if (s.StartsWith("[")) { int c = s.IndexOf(']'); if (c < 0) return false; host = s.Substring(1, c - 1); return c + 2 <= s.Length && int.TryParse(s.Substring(c + 2), out port); }
            int i = s.LastIndexOf(':'); if (i < 0) return false;
            host = s.Substring(0, i); return int.TryParse(s.Substring(i + 1), out port);
        }

        static int Find(IPAddress ip, int port, bool tcp)
        {
            bool v6 = ip.AddressFamily == System.Net.Sockets.AddressFamily.InterNetworkV6;
            int af = v6 ? 23 : 2, size = 0;
            uint rc = tcp ? GetExtendedTcpTable(IntPtr.Zero, ref size, false, af, TcpOwnerPidAll, 0) : GetExtendedUdpTable(IntPtr.Zero, ref size, false, af, UdpOwnerPid, 0);
            size += 4096;
            IntPtr buf = Marshal.AllocHGlobal(size);
            try
            {
                rc = tcp ? GetExtendedTcpTable(buf, ref size, false, af, TcpOwnerPidAll, 0) : GetExtendedUdpTable(buf, ref size, false, af, UdpOwnerPid, 0);
                if (rc != 0) return 0;
                int n = Marshal.ReadInt32(buf);
                byte[] want = ip.GetAddressBytes();
                int wildcard = 0;
                // row layouts: MIB_TCPROW_OWNER_PID 24 bytes, MIB_UDPROW_OWNER_PID 12, MIB_TCP6ROW_OWNER_PID 56, MIB_UDP6ROW_OWNER_PID 28
                int rowSize = v6 ? (tcp ? 56 : 28) : (tcp ? 24 : 12);
                for (int i = 0; i < n; i++)
                {
                    IntPtr row = buf + 4 + i * rowSize;
                    byte[] local; int lport, pid;
                    if (!v6)
                    {
                        int o = tcp ? 4 : 0; // TCP rows start with dwState
                        local = BitConverter.GetBytes(Marshal.ReadInt32(row, o));
                        lport = Port(Marshal.ReadInt32(row, o + 4));
                        pid = Marshal.ReadInt32(row, tcp ? 20 : 8);
                    }
                    else
                    {
                        local = new byte[16]; Marshal.Copy(row, local, 0, 16);
                        lport = Port(Marshal.ReadInt32(row, 20)); // after ucLocalAddr[16] + dwLocalScopeId
                        pid = Marshal.ReadInt32(row, tcp ? 52 : 24);
                    }
                    if (lport != port) continue;
                    if (Same(local, want)) return pid;
                    if (Zero(local) && wildcard == 0) wildcard = pid;
                }
                return wildcard;
            }
            finally { Marshal.FreeHGlobal(buf); }
        }
        static int Port(int dw) { return ((dw & 0xFF) << 8) | ((dw >> 8) & 0xFF); }
        static bool Same(byte[] a, byte[] b) { if (a.Length != b.Length) return false; for (int i = 0; i < a.Length; i++) if (a[i] != b[i]) return false; return true; }
        static bool Zero(byte[] a) { foreach (var x in a) if (x != 0) return false; return true; }

        // ---- names and icons
        public static string NameOf(string path)
        {
            try { var d = FileVersionInfo.GetVersionInfo(path).FileDescription; if (!string.IsNullOrWhiteSpace(d)) return d.Trim(); } catch { }
            return Path.GetFileNameWithoutExtension(path);
        }

        public static string IconOf(string path)
        {
            try
            {
                using (var ico = Icon.ExtractAssociatedIcon(path))
                using (var bmp = ico.ToBitmap())
                using (var ms = new MemoryStream())
                {
                    bmp.Save(ms, ImageFormat.Png);
                    return "data:image/png;base64," + Convert.ToBase64String(ms.ToArray());
                }
            }
            catch { return null; }
        }

        public static Dictionary<string, object> Describe(string path)
        {
            return new Dictionary<string, object> { { "name", NameOf(path) }, { "path", path }, { "icon", IconOf(path) } };
        }

        /// Programs with a window, one entry per executable.
        public static List<object> Running()
        {
            var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            var list = new List<object>();
            string self = Process.GetCurrentProcess().MainModule.FileName;
            foreach (var p in Process.GetProcesses())
            {
                try
                {
                    if (p.MainWindowHandle == IntPtr.Zero) continue;
                    string path = PathOf(p.Id);
                    if (string.IsNullOrEmpty(path) || !seen.Add(path) || string.Equals(path, self, StringComparison.OrdinalIgnoreCase)) continue;
                    if (path.StartsWith(Environment.GetFolderPath(Environment.SpecialFolder.Windows), StringComparison.OrdinalIgnoreCase) && !path.EndsWith("explorer.exe", StringComparison.OrdinalIgnoreCase)) continue;
                    list.Add(Describe(path));
                }
                catch { }
                finally { p.Dispose(); }
            }
            list.Sort((a, b) => string.Compare((string)((Dictionary<string, object>)a)["name"], (string)((Dictionary<string, object>)b)["name"], StringComparison.CurrentCultureIgnoreCase));
            return list;
        }
    }
}
