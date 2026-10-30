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
