// The tray icon: the same plain chip as the Android status bar and quick settings tile, drawn in one colour
// that suits the taskbar (white on a dark one, black on a light one).
// Off: faint. Connecting: fills up from the bottom like an aquarium, again and again. On: solid.
using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Runtime.InteropServices;
using System.Windows.Forms;
using Microsoft.Win32;

namespace Tunnel
{
    class TrayArt : IDisposable
    {
        [DllImport("user32.dll")] static extern bool DestroyIcon(IntPtr h);

        readonly NotifyIcon tray;
        readonly Timer timer = new Timer { Interval = 70 };
        int state = -1;      // Core state: 0 off, 2 on, anything else connecting
        float level;         // aquarium level while connecting, 0..1.15
        IntPtr shown = IntPtr.Zero;

        public TrayArt(NotifyIcon tray)
        {
            this.tray = tray;
            timer.Tick += (s, e) => { level += 0.06f; if (level > 1.15f) level = 0f; Draw(); };
            SystemEvents.UserPreferenceChanged += OnPrefs;
        }

        void OnPrefs(object s, UserPreferenceChangedEventArgs e) { if (e.Category == UserPreferenceCategory.General || e.Category == UserPreferenceCategory.Color) Draw(); }

        public void Set(int st)
        {
            if (st == state) return;
            state = st;
            bool connecting = st != Core.Connected && st != Core.Disconnected;
            if (connecting) { level = 0f; timer.Start(); } else timer.Stop();
            Draw();
        }

        static bool LightTaskbar()
        {
            try
            {
                using (var k = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize"))
                    return k != null && Convert.ToInt32(k.GetValue("SystemUsesLightTheme", 0)) == 1;
            }
            catch { return false; }
        }

        void Draw()
        {
            var size = SystemInformation.SmallIconSize.Width;
            if (size < 16) size = 16;
            var ink = LightTaskbar() ? Color.FromArgb(20, 20, 20) : Color.White;
            using (var bmp = new Bitmap(size, size, System.Drawing.Imaging.PixelFormat.Format32bppArgb))
            {
                using (var g = Graphics.FromImage(bmp))
                {
                    g.SmoothingMode = SmoothingMode.AntiAlias;
                    g.PixelOffsetMode = PixelOffsetMode.HighQuality;
                    g.Clear(Color.Transparent);
                    g.ScaleTransform(size / 100f, size / 100f);
                    if (state == Core.Connected) Chip(g, ink, 255);
                    else if (state == Core.Disconnected || state < 0) Chip(g, ink, 110);
                    else
                    {
                        Chip(g, ink, 80);
                        float top = 100f - 100f * Math.Min(1f, level);
                        g.SetClip(new RectangleF(0, top, 100, 100 - top));
                        Chip(g, ink, 255);
                        g.ResetClip();
                    }
                }
                var h = bmp.GetHicon();
                tray.Icon = Icon.FromHandle(h);
                if (shown != IntPtr.Zero) DestroyIcon(shown);
                shown = h;
            }
        }

        /// The chip of ic_tile.xml (100×100): pins, a body with the tunnel cut out, and the door inside it.
        static void Chip(Graphics g, Color ink, int alpha)
        {
            var c = Color.FromArgb(alpha, ink);
            using (var pen = new Pen(c, 7f) { StartCap = LineCap.Round, EndCap = LineCap.Round })
            {
                foreach (var x in new[] { 32f, 50f, 68f }) g.DrawLine(pen, x, 6, x, 16);
                foreach (var y in new[] { 32f, 50f, 68f }) { g.DrawLine(pen, 6, y, 16, y); g.DrawLine(pen, 84, y, 94, y); }
                g.DrawLine(pen, 26, 84, 26, 94); g.DrawLine(pen, 74, 84, 74, 94);
            }
            using (var body = Rounded(18, 18, 64, 64, 10))
            using (var cut = Arch(34, 82, 16))
            using (var door = Arch(42, 82, 8, 57))
            using (var b = new SolidBrush(c))
            {
                var r = new Region(body); r.Exclude(cut);
                g.FillRegion(b, r); r.Dispose();
                g.FillPath(b, door);
            }
        }

        static GraphicsPath Rounded(float x, float y, float w, float h, float r)
        {
            var p = new GraphicsPath(); float d = r * 2;
            p.AddArc(x, y, d, d, 180, 90); p.AddArc(x + w - d, y, d, d, 270, 90);
            p.AddArc(x + w - d, y + h - d, d, d, 0, 90); p.AddArc(x, y + h - d, d, d, 90, 90);
            p.CloseFigure(); return p;
        }

        /// An arch from x (left) to x+2r, standing on bottom, with its round top centred at cy (default: bottom − 30).
        static GraphicsPath Arch(float x, float bottom, float r, float cy = -1)
        {
            if (cy < 0) cy = bottom - 30;
            var p = new GraphicsPath();
            p.AddLine(x, bottom, x, cy);
            p.AddArc(x, cy - r, r * 2, r * 2, 180, 180);
            p.AddLine(x + r * 2, cy, x + r * 2, bottom);
            p.CloseFigure(); return p;
        }

        public void Dispose()
        {
            timer.Stop(); timer.Dispose();
            SystemEvents.UserPreferenceChanged -= OnPrefs;
            if (shown != IntPtr.Zero) { DestroyIcon(shown); shown = IntPtr.Zero; }
        }
    }
}
