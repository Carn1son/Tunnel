<p align="center"><picture><source media="(prefers-color-scheme: dark)" srcset="docs/logo-dark.png"><img src="docs/logo-light.png" width="560" alt="Tunnel — for our own moles only"></picture></p>

<p align="center">TrustTunnel VPN client for Windows and Android. Your own server, your own rules.</p>

<p align="center">
  <a href="https://github.com/Carn1son/Tunnel/releases/latest/download/Tunnel.apk"><b>Android APK</b></a> ·
  <a href="https://github.com/Carn1son/Tunnel/releases/latest/download/Tunnel-Setup.exe"><b>Windows Installer</b></a>
</p>

## Features

### Servers
- Self-hosted TrustTunnel servers only
- Manual setup or import of a server config and `tt://` links
- Server link copy for sharing
- HTTP/2 and QUIC transports
- TLS fingerprint, custom SNI, Client Random, self-signed certificates

### Routing
- Profiles in VPN or bypass mode with domain and IP rules
- Split tunneling by application
- Split tunneling by IP address, subnet or IP with port
- Excluded routes for local networks

### Server Management
- Connects to your own server over SSH: password or key, root or sudo, any port
- Installs TrustTunnel with the questions of the official wizard and their defaults
- Works without a domain: self-signed certificate by default, Let's Encrypt or your own certificate optional
- Users: add, delete, new passwords, connection links
- Traffic and last activity of every user
- Restart, update, logs; statistics every 5 minutes at the lowest priority

### Connection Log
- App, destination and route of every connection
- Filter, pause and clear

### Windows
- Tray icon with status and server switch
- Launch with Windows and auto-connect
- VPN runs in a background service

### Android
- Quick settings tile
- Home screen widget
- Always-on VPN

### Application
- Dark and light themes
- Russian and English
- Built-in updates

## Credits

- TrustTunnel protocol and engine by [AdGuard](https://github.com/TrustTunnel), Apache 2.0
- [Wintun](https://www.wintun.net) virtual network adapter by WireGuard
