<p align="center">
  <img src="assets/logo/readme_logo_light.png#gh-light-mode-only" width="320" alt="Tunnel">
  <img src="assets/logo/readme_logo_dark.png#gh-dark-mode-only" width="320" alt="Tunnel">
</p>

# Tunnel

Windows and Android client for self-hosted [TrustTunnel](https://github.com/TrustTunnel/TrustTunnel) VPN servers.

## Download

- [Tunnel-Setup.exe](https://github.com/Carn1son/Tunnel/releases/latest/download/Tunnel-Setup.exe) — Windows 10/11, x64
- [Tunnel.apk](https://github.com/Carn1son/Tunnel/releases/latest/download/Tunnel.apk) — Android 8 and newer, arm64
- The app checks for updates itself

## Features

- Servers: add manually, import a server config or a `tt://` link, copy a server link
- One-click connect from the window or the tray
- Routing profiles: VPN mode or bypass mode with domain and IP rules
- Split tunneling by application and by IP address, subnet or IP with port
- Excluded routes for local networks
- Connection log: app, destination and route of every connection
- HTTP/2 and QUIC transports, TLS fingerprint, custom SNI, Client Random, self-signed certificates
- Windows: tray icon, launch with Windows, `tt://` links
- Android: quick settings tile, home screen widget, Always-on VPN, `tt://` links
- Auto-connect, light and dark theme, Russian and English

## How it works

- `desktop/` — Tunnel.exe: WebView2 window, tray icon, bridge to the VPN service
- `android/` — the Android app: WebView, VpnService with per-app split, tile, widget
- `ui/index.html` — the interface, shared by both apps
- `core/` — builds AdGuard's TrustTunnel client engine with the split tunneling hook
- On Windows the VPN runs in the `TunnelVPN` service installed by the setup

## Credits

- TrustTunnel protocol and engine: [TrustTunnel](https://github.com/TrustTunnel) by AdGuard, Apache 2.0
- [Wintun](https://www.wintun.net) virtual network adapter by WireGuard
