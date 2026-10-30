// Tunnel: split tunnelling by application and by IP address, on top of the TrustTunnel routing profile.
// Rules come from the [split] table of the client TOML:
//   [split]
//   enabled = true
//   mode = "exclude"            # "exclude": the listed apps/addresses bypass the VPN; "include": only they use it
//   apps = ["C:\\Program Files\\App\\app.exe", "telegram.exe"]
//   targets = ["192.168.1.0/24", "8.8.8.8", "1.2.3.4:443", "2001:db8::/32", "[2001:db8::1]:443"]
#pragma once

#include <optional>
#include <string>

#include <toml++/toml.h>

#include "vpn/vpn.h"

namespace ag::tunnel_split {

/** Read the [split] table. Called for every new configuration; a missing table turns the feature off. */
void configure(const toml::table &config);

/**
 * Decide what to do with a new connection.
 * @param src     source address of the connection, as seen on the virtual interface
 * @param dst     destination of the connection
 * @param proto   IPPROTO_TCP or IPPROTO_UDP
 * @param app_out receives the executable path of the owning process when it was looked up
 * @return a forced action, or nullopt to route by the profile (vpn_mode + exclusions)
 */
std::optional<VpnConnectAction> decide(const sockaddr *src, const VpnAddress *dst, int proto, std::string &app_out);

} // namespace ag::tunnel_split
