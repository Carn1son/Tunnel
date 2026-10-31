"""Put the Tunnel split-tunnelling hook into a TrustTunnelClient checkout.

python core/apply_split.py <TrustTunnelClient dir>

Every edit checks that its anchor is still there, so a changed upstream fails loudly
instead of building without the hook.
"""
import pathlib
import shutil
import sys

root = pathlib.Path(sys.argv[1])
here = pathlib.Path(__file__).resolve().parent
src = root / "trusttunnel" / "src"


def edit(path, anchor, new, count=1):
    text = path.read_text(encoding="utf-8")
    if new in text:
        return  # already applied
    if text.count(anchor) != count:
        sys.exit(f"apply_split: anchor not found {text.count(anchor)}x in {path}:\n{anchor}")
    path.write_text(text.replace(anchor, new), encoding="utf-8")


shutil.copy(here / "tunnel_split.h", src / "tunnel_split.h")
shutil.copy(here / "tunnel_split.cpp", src / "tunnel_split.cpp")

# the new source file and the Windows IP Helper library (process lookup by socket)
cmake = root / "trusttunnel" / "CMakeLists.txt"
edit(cmake, "        src/persistent_ring_buffer.cpp\n", "        src/persistent_ring_buffer.cpp\n        src/tunnel_split.cpp\n")
edit(cmake, "target_link_libraries(vpnlibs_trusttunnel nlohmann_json::nlohmann_json)\n",
     "target_link_libraries(vpnlibs_trusttunnel nlohmann_json::nlohmann_json)\n"
     "if (WIN32)\n    target_link_libraries(vpnlibs_trusttunnel iphlpapi)\nendif ()\n")

# the rules are read together with the rest of the configuration
config = src / "config.cpp"
edit(config, '#include "vpn/trusttunnel/config.h"\n', '#include "vpn/trusttunnel/config.h"\n#include "tunnel_split.h"\n')
edit(config, "    TrustTunnelConfig result;\n", "    TrustTunnelConfig result;\n    tunnel_split::configure(config);\n")

# every new connection asks the rules first
client = src / "client.cpp"
edit(client, '#include "vpn/vpn.h"\n', '#include "vpn/vpn.h"\n#include "tunnel_split.h"\n')
edit(client,
     "        info->action = VPN_CA_DEFAULT;\n",
     "        info->action = VPN_CA_DEFAULT;\n"
     "        {\n"
     "            std::string split_app;\n"
     "            if (auto forced = tunnel_split::decide(event->src, event->dst, event->proto, split_app)) {\n"
     "                info->action = *forced;\n"
     "            }\n"
     "        }\n")

print("apply_split: hook in place")

# Connection details for the log (debug level): handshake time, bytes sent and received, duration.
# The engine already counts bytes per connection; when a reported connection closes, it is reported once more
# with these numbers (`closed` in the JSON), and the app adds them to the row of the same `id`.
core = root / "core"
vpnh = core / "include" / "vpn" / "vpn.h"
edit(vpnh, "    VpnFinalConnectionAction action; // final action\n} VpnConnectionInfoEvent;\n",
     "    VpnFinalConnectionAction action; // final action\n"
     "    uint64_t tn_id = 0;              // Tunnel: connection id\n"
     "    int tn_closed = 0;               // Tunnel: 1 when the connection has closed\n"
     "    uint64_t tn_up = 0;              // Tunnel: bytes sent by the app\n"
     "    uint64_t tn_down = 0;            // Tunnel: bytes received by the app\n"
     "    int64_t tn_ms = -1;              // Tunnel: how long the connection lived, ms\n"
     "    int64_t tn_hs = -1;              // Tunnel: time until the remote side was connected, ms\n"
     "} VpnConnectionInfoEvent;\n")
connh = core / "include" / "vpn" / "internal" / "vpn_connection.h"
edit(connh, "    std::chrono::high_resolution_clock::time_point requested_at{};\n",
     "    std::chrono::high_resolution_clock::time_point requested_at{};\n"
     "    std::chrono::steady_clock::time_point tn_start{}; // Tunnel: when the app opened the connection\n"
     "    int64_t tn_handshake_ms = -1;                     // Tunnel: until the remote side was connected\n")
tun = core / "src" / "tunnel.cpp"
edit(tun, "        conn->flags.set(CONNF_FIRST_PACKET);\n",
     "        conn->flags.set(CONNF_FIRST_PACKET);\n        conn->tn_start = std::chrono::steady_clock::now();\n")
edit(tun, '            log_tun(this, warn, "Got server connect result for inexistent or already closed connection: {}", id);\n            upstream->close_connection(id, false, true);\n            assert(0);\n            break;\n        }\n',
     '            log_tun(this, warn, "Got server connect result for inexistent or already closed connection: {}", id);\n            upstream->close_connection(id, false, true);\n            assert(0);\n            break;\n        }\n'
     '        if (conn->tn_handshake_ms < 0 && conn->tn_start.time_since_epoch().count() != 0) {\n'
     '            conn->tn_handshake_ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - conn->tn_start).count();\n'
     '        }\n')
edit(tun, '        log_conn(tunnel, conn, dbg, "Destroyed (download={}, upload={})", conn->incoming_bytes, conn->outgoing_bytes);\n',
     '        log_conn(tunnel, conn, dbg, "Destroyed (download={}, upload={})", conn->incoming_bytes, conn->outgoing_bytes);\n'
     '        if (conn->flags.test(CONNF_CONN_INFO_SENT) && conn->tn_start.time_since_epoch().count() != 0) {\n'
     '            VpnConnectionInfoEvent tn_info;\n'
     '            tn_info.src = conn->addr.src.c_storage();\n'
     '            const SocketAddress *tn_dst = std::get_if<SocketAddress>(&conn->addr.dst);\n'
     '            tn_info.dst = tn_dst != nullptr ? tn_dst->c_storage() : nullptr;\n'
     '            tn_info.domain = tn_dst != nullptr ? nullptr : std::get<NamePort>(conn->addr.dst).name.c_str();\n'
     '            tn_info.proto = conn->proto;\n'
     '            tn_info.action = VPN_FCA_TUNNEL;\n'
     '            tn_info.tn_id = conn->client_id;\n'
     '            tn_info.tn_closed = 1;\n'
     '            tn_info.tn_up = conn->outgoing_bytes;\n'
     '            tn_info.tn_down = conn->incoming_bytes;\n'
     '            tn_info.tn_ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - conn->tn_start).count();\n'
     '            tn_info.tn_hs = conn->tn_handshake_ms;\n'
     '            const vpn_client::Handler &tn_handler = tunnel->vpn->parameters.handler;\n'
     '            tn_handler.func(tn_handler.arg, vpn_client::EVENT_CONNECTION_INFO, &tn_info);\n'
     '        }\n')
edit(tun, "    info.action = get_final_action(self->vpn->domain_filter.get_mode(), conn->action.value_or(VPN_CA_DEFAULT));\n",
     "    info.action = get_final_action(self->vpn->domain_filter.get_mode(), conn->action.value_or(VPN_CA_DEFAULT));\n    info.tn_id = conn->client_id;\n")
ci = src / "connection_info.cpp"
edit(ci, '    if (include_current_time) {\n',
     '    json["id"] = info->tn_id;\n'
     '    if (info->tn_closed) {\n'
     '        json["closed"] = true;\n'
     '        json["up"] = info->tn_up;\n'
     '        json["down"] = info->tn_down;\n'
     '        json["ms"] = info->tn_ms;\n'
     '        if (info->tn_hs >= 0) {\n'
     '            json["hs"] = info->tn_hs;\n'
     '        }\n'
     '    }\n\n'
     '    if (include_current_time) {\n')
print("apply_split: connection details in place")

# Windows: bootstrap_conan_deps.py starts export_conan.sh directly, which only works where .sh files run.
# Run it through Git's bash instead (not WSL's bash.exe from System32).
if sys.platform == "win32":
    boot = root / "scripts" / "bootstrap_conan_deps.py"
    text = boot.read_text(encoding="utf-8")
    if "TUNNEL_BASH" not in text:
        text = text.replace("import sys\n", "import sys\nTUNNEL_BASH = r'C:\\Program Files\\Git\\bin\\bash.exe'\n", 1)
        text = text.replace('subprocess.run([os.path.join(repo_dir, "scripts", "export_conan.sh")],',
                            'subprocess.run([TUNNEL_BASH, os.path.join(repo_dir, "scripts", "export_conan.sh").replace(os.sep, "/")],')
        text = text.replace('subprocess.run([os.path.join("scripts", "export_conan.sh")], check=True)',
                            'subprocess.run([TUNNEL_BASH, "scripts/export_conan.sh"], check=True)')
        if text.count("TUNNEL_BASH") != 3:
            sys.exit("apply_split: bootstrap_conan_deps.py changed, cannot patch the export_conan.sh calls")
        boot.write_text(text, encoding="utf-8")
        print("apply_split: export_conan.sh runs through Git bash")
