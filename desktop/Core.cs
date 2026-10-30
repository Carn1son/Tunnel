// The TrustTunnel engine: trusttunnel.dll (AdGuard's Windows adapter, built from source with the Tunnel split hook).
// The VPN itself runs in the "TunnelVPN" Windows service (installed by the installer); this process only talks to it.
using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;

namespace Tunnel
{
    static class Core
    {
        public const string ServiceName = "TunnelVPN";
        const string Dll = "trusttunnel.dll";

        [UnmanagedFunctionPointer(CallingConvention.Cdecl)] public delegate void StateCb(IntPtr arg, int state);
        [UnmanagedFunctionPointer(CallingConvention.Cdecl)] public delegate void JsonCb(IntPtr arg, IntPtr json);
        [UnmanagedFunctionPointer(CallingConvention.Cdecl)] delegate void PathCb(IntPtr arg, IntPtr path);

        [DllImport(Dll, CallingConvention = CallingConvention.Cdecl, CharSet = CharSet.Unicode)]
        static extern int trusttunnel_service_attach(string service, string pipe, StateCb state, IntPtr stateArg, JsonCb info, IntPtr infoArg);
        [DllImport(Dll, CallingConvention = CallingConvention.Cdecl)]
        static extern void trusttunnel_service_detach();
        [DllImport(Dll, CallingConvention = CallingConvention.Cdecl)]
        static extern int trusttunnel_service_start(byte[] tomlUtf8, [MarshalAs(UnmanagedType.I1)] bool fallIntoRecovery);
        [DllImport(Dll, CallingConvention = CallingConvention.Cdecl)]
        static extern int trusttunnel_service_stop();
        [DllImport(Dll, CallingConvention = CallingConvention.Cdecl, CharSet = CharSet.Unicode)]
        static extern void trusttunnel_service_read_all_connection_info(string ringBuffer, JsonCb cb, IntPtr arg);
        [DllImport(Dll, CallingConvention = CallingConvention.Cdecl, CharSet = CharSet.Unicode)]
        static extern void trusttunnel_log_init(string logsDir);
        [DllImport(Dll, CallingConvention = CallingConvention.Cdecl, CharSet = CharSet.Unicode)]
        static extern void trusttunnel_log_export(string destDir, PathCb cb, IntPtr arg);
        [DllImport(Dll, CallingConvention = CallingConvention.Cdecl)]
        static extern void trusttunnel_log_clear();
        [DllImport(Dll, CallingConvention = CallingConvention.Cdecl)]
        static extern IntPtr trusttunnel_decode_deeplink(byte[] uriUtf8);
        [DllImport(Dll, CallingConvention = CallingConvention.Cdecl)]
        static extern IntPtr trusttunnel_encode_deeplink(byte[] tomlUtf8);
        [DllImport(Dll, CallingConvention = CallingConvention.Cdecl)]
        static extern void trusttunnel_free_deeplink_string(IntPtr s);

        // ag::VpnSessionState
        public const int Disconnected = 0, Connecting = 1, Connected = 2;

        static StateCb stateCb; static JsonCb infoCb; // kept alive for the native side
        public static event Action<int> StateChanged;
        public static event Action<string> ConnectionInfo;
        public static bool Attached;

        public static string DataDir { get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "Tunnel"); } }
        public static string LogsDir { get { return Path.Combine(DataDir, "logs"); } }
        public static string RingBuffer { get { return Path.Combine(DataDir, "connections.bin"); } }

        static byte[] Z(string s) { var b = Encoding.UTF8.GetBytes(s ?? ""); Array.Resize(ref b, b.Length + 1); return b; }
        static string Str(IntPtr p)
        {
            if (p == IntPtr.Zero) return null;
            int n = 0; while (Marshal.ReadByte(p, n) != 0) n++;
            var b = new byte[n]; Marshal.Copy(p, b, 0, n); return Encoding.UTF8.GetString(b);
        }

        public static void Init()
        {
            // the app and the service share %ProgramData%\Tunnel, as AdGuard's own client does with %ProgramData%\TrustTunnel:
            // the service writes its log family there, so export and clear cover both
            try { Directory.CreateDirectory(LogsDir); trusttunnel_log_init(LogsDir); } catch { }
            stateCb = (a, s) => { var h = StateChanged; if (h != null) h(s); };
            infoCb = (a, j) => { var h = ConnectionInfo; if (h != null) h(Str(j)); };
            Attach();
        }

        /// Binds the callbacks; returns 0 when the service is running (its current state then arrives through StateChanged).
        public static int Attach()
        {
            int r = trusttunnel_service_attach(ServiceName, null, stateCb, IntPtr.Zero, infoCb, IntPtr.Zero);
            Attached = true;
            return r;
        }

        public static int Start(string toml) { return trusttunnel_service_start(Z(toml), true); }
        public static int Stop() { return trusttunnel_service_stop(); }
        public static void Detach() { try { if (Attached) trusttunnel_service_detach(); } catch { } Attached = false; }

        public static void ReadHistory(Action<string> each)
        {
            JsonCb cb = (a, j) => each(Str(j));
            try { trusttunnel_service_read_all_connection_info(RingBuffer, cb, IntPtr.Zero); } catch { }
            GC.KeepAlive(cb);
        }

        public static string ExportLogs(string dir)
        {
            Directory.CreateDirectory(dir);
            PathCb cb = (a, p) => { };
            trusttunnel_log_export(dir, cb, IntPtr.Zero);
            GC.KeepAlive(cb);
            return dir;
        }
        public static void ClearLogs() { trusttunnel_log_clear(); }

        public static string DecodeLink(string uri)
        {
            IntPtr p = trusttunnel_decode_deeplink(Z(uri.Trim()));
            try { return Str(p); } finally { if (p != IntPtr.Zero) trusttunnel_free_deeplink_string(p); }
        }
        public static string EncodeLink(string endpointToml)
        {
            IntPtr p = trusttunnel_encode_deeplink(Z(endpointToml));
            try { return Str(p); } finally { if (p != IntPtr.Zero) trusttunnel_free_deeplink_string(p); }
        }

        public static string ErrorText(int code)
        {
            switch (code)
            {
                case 0: return null;
                case 1: return "access";      // TRUSTTUNNEL_SVC_ERR_ACCESS
                case 2: return "exists";
                case 3: return "no-service";  // the service is not installed: reinstall Tunnel
                case 4: return "timeout";
                default: return "other";
            }
        }
    }
}
