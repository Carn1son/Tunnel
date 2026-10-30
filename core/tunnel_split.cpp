// Tunnel: split tunnelling by application and by IP address. See tunnel_split.h.
#include "tunnel_split.h"

#include <algorithm>
#include <charconv>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <shared_mutex>
#include <string_view>
#include <vector>

#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <iphlpapi.h>
#else
#include <arpa/inet.h>
#include <netinet/in.h>
#endif

#include "common/logger.h"

namespace ag::tunnel_split {
namespace {

Logger g_logger{"TUNNEL_SPLIT"};

struct Net {
    int family = 0;     // AF_INET / AF_INET6
    uint8_t addr[16]{}; // network order
    int prefix = 0;     // bits
    uint16_t port = 0;  // 0 = any port
};

struct Rules {
    bool enabled = false;
    bool include = false;          // true: only the listed go through the VPN
    std::vector<std::string> apps; // lowercase, UTF-8: full paths or bare file names
    std::vector<Net> nets;
};

std::shared_mutex g_mutex;
Rules g_rules;

std::string lower(std::string_view s) {
    std::string r(s);
    std::transform(r.begin(), r.end(), r.begin(), [](unsigned char c) {
        return (char) ((c >= 'A' && c <= 'Z') ? c + 32 : c);
    });
    return r;
}

// exceptions are off in this build (/EHs-c-): numbers are parsed without throwing
bool to_num(std::string_view s, unsigned long max, unsigned long &out) {
    if (s.empty()) {
        return false;
    }
    auto [p, ec] = std::from_chars(s.data(), s.data() + s.size(), out);
    return ec == std::errc{} && p == s.data() + s.size() && out <= max;
}

std::string_view file_name(std::string_view path) {
    size_t p = path.find_last_of("\\/");
    return p == std::string_view::npos ? path : path.substr(p + 1);
}

bool parse_net(std::string_view s, Net &n) {
    std::string host(s);
    int prefix = -1;
    n.port = 0;
    if (!host.empty() && host.front() == '[') { // [v6]:port
        size_t close = host.find(']');
        if (close == std::string::npos) {
            return false;
        }
        std::string rest = host.substr(close + 1);
        host = host.substr(1, close - 1);
        if (!rest.empty()) {
            if (rest.front() != ':') {
                return false;
            }
            unsigned long v = 0;
            if (!to_num(std::string_view(rest).substr(1), 65535, v)) {
                return false;
            }
            n.port = (uint16_t) v;
        }
    } else {
        size_t slash = host.find('/');
        if (slash != std::string::npos) {
            unsigned long v = 0;
            if (!to_num(std::string_view(host).substr(slash + 1), 128, v)) {
                return false;
            }
            prefix = (int) v;
            host = host.substr(0, slash);
        }
        size_t colon = host.find(':');
        if (colon != std::string::npos && host.find(':', colon + 1) == std::string::npos) { // v4:port
            unsigned long v = 0;
            if (!to_num(std::string_view(host).substr(colon + 1), 65535, v)) {
                return false;
            }
            n.port = (uint16_t) v;
            host = host.substr(0, colon);
        }
    }
    if (inet_pton(AF_INET, host.c_str(), n.addr) == 1) {
        n.family = AF_INET;
        n.prefix = prefix < 0 ? 32 : std::min(prefix, 32);
        return true;
    }
    if (inet_pton(AF_INET6, host.c_str(), n.addr) == 1) {
        n.family = AF_INET6;
        n.prefix = prefix < 0 ? 128 : std::min(prefix, 128);
        return true;
    }
    return false;
}

bool net_match(const Net &n, const sockaddr *sa) {
    const uint8_t *a = nullptr;
    uint16_t port = 0;
    if (sa->sa_family == AF_INET && n.family == AF_INET) {
        const auto *in = (const sockaddr_in *) sa;
        a = (const uint8_t *) &in->sin_addr;
        port = ntohs(in->sin_port);
    } else if (sa->sa_family == AF_INET6 && n.family == AF_INET6) {
        const auto *in6 = (const sockaddr_in6 *) sa;
        a = (const uint8_t *) &in6->sin6_addr;
        port = ntohs(in6->sin6_port);
    } else {
        return false;
    }
    if (n.port != 0 && n.port != port) {
        return false;
    }
    int full = n.prefix / 8, rest = n.prefix % 8;
    if (std::memcmp(a, n.addr, full) != 0) {
        return false;
    }
    if (rest == 0) {
        return true;
    }
    uint8_t mask = (uint8_t) (0xFF << (8 - rest));
    return (a[full] & mask) == (n.addr[full] & mask);
}

#ifdef _WIN32
std::string to_utf8(const wchar_t *w, int len) {
    int n = WideCharToMultiByte(CP_UTF8, 0, w, len, nullptr, 0, nullptr, nullptr);
    std::string r(n > 0 ? n : 0, '\0');
    if (n > 0) {
        WideCharToMultiByte(CP_UTF8, 0, w, len, r.data(), n, nullptr, nullptr);
    }
    return r;
}

std::string process_path(DWORD pid) {
    if (pid == 0 || pid == 4) {
        return {};
    }
    HANDLE h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
    if (h == nullptr) {
        return {};
    }
    wchar_t buf[MAX_PATH * 2];
    DWORD len = (DWORD) (sizeof(buf) / sizeof(buf[0]));
    std::string r;
    if (QueryFullProcessImageNameW(h, 0, buf, &len)) {
        r = to_utf8(buf, (int) len);
    }
    CloseHandle(h);
    return r;
}

template <typename Fn>
bool with_table(bool tcp, ULONG family, Fn &&fn) {
    DWORD size = 0;
    std::vector<uint8_t> buf;
    for (int attempt = 0; attempt < 4; ++attempt) {
        DWORD rc = tcp ? GetExtendedTcpTable(buf.empty() ? nullptr : buf.data(), &size, FALSE, family,
                                 TCP_TABLE_OWNER_PID_ALL, 0)
                       : GetExtendedUdpTable(buf.empty() ? nullptr : buf.data(), &size, FALSE, family,
                                 UDP_TABLE_OWNER_PID, 0);
        if (rc == NO_ERROR && !buf.empty()) {
            fn(buf.data());
            return true;
        }
        if (rc != ERROR_INSUFFICIENT_BUFFER && !(rc == NO_ERROR && buf.empty())) {
            return false;
        }
        buf.resize(size + 4096);
        size = (DWORD) buf.size();
    }
    return false;
}

/** The process that owns the local end `src` of a connection that entered the virtual interface. */
DWORD owner_pid(const sockaddr *src, int proto) {
    bool tcp = proto == IPPROTO_TCP;
    DWORD pid = 0, wildcard = 0;
    if (src->sa_family == AF_INET) {
        const auto *in = (const sockaddr_in *) src;
        DWORD port = in->sin_port; // network order, as in the tables
        DWORD addr = in->sin_addr.s_addr;
        with_table(tcp, AF_INET, [&](uint8_t *p) {
            if (tcp) {
                auto *t = (MIB_TCPTABLE_OWNER_PID *) p;
                for (DWORD i = 0; i < t->dwNumEntries && pid == 0; ++i) {
                    const auto &r = t->table[i];
                    if ((r.dwLocalPort & 0xFFFF) == port && (r.dwLocalAddr == addr || r.dwLocalAddr == 0)) {
                        pid = r.dwOwningPid;
                    }
                }
            } else {
                auto *t = (MIB_UDPTABLE_OWNER_PID *) p;
                for (DWORD i = 0; i < t->dwNumEntries && pid == 0; ++i) {
                    const auto &r = t->table[i];
                    if ((r.dwLocalPort & 0xFFFF) == port) {
                        if (r.dwLocalAddr == addr) {
                            pid = r.dwOwningPid;
                        } else if (r.dwLocalAddr == 0 && wildcard == 0) {
                            wildcard = r.dwOwningPid;
                        }
                    }
                }
            }
        });
    } else if (src->sa_family == AF_INET6) {
        const auto *in6 = (const sockaddr_in6 *) src;
        DWORD port = in6->sin6_port;
        static const uint8_t any[16]{};
        with_table(tcp, AF_INET6, [&](uint8_t *p) {
            if (tcp) {
                auto *t = (MIB_TCP6TABLE_OWNER_PID *) p;
                for (DWORD i = 0; i < t->dwNumEntries && pid == 0; ++i) {
                    const auto &r = t->table[i];
                    if ((r.dwLocalPort & 0xFFFF) == port
                            && (std::memcmp(r.ucLocalAddr, &in6->sin6_addr, 16) == 0
                                    || std::memcmp(r.ucLocalAddr, any, 16) == 0)) {
                        pid = r.dwOwningPid;
                    }
                }
            } else {
                auto *t = (MIB_UDP6TABLE_OWNER_PID *) p;
                for (DWORD i = 0; i < t->dwNumEntries && pid == 0; ++i) {
                    const auto &r = t->table[i];
                    if ((r.dwLocalPort & 0xFFFF) == port) {
                        if (std::memcmp(r.ucLocalAddr, &in6->sin6_addr, 16) == 0) {
                            pid = r.dwOwningPid;
                        } else if (std::memcmp(r.ucLocalAddr, any, 16) == 0 && wildcard == 0) {
                            wildcard = r.dwOwningPid;
                        }
                    }
                }
            }
        });
    }
    return pid != 0 ? pid : wildcard;
}
#endif // _WIN32

std::string owner_path(const sockaddr *src, int proto) {
#ifdef _WIN32
    if (src == nullptr || (proto != IPPROTO_TCP && proto != IPPROTO_UDP)) {
        return {};
    }
    return process_path(owner_pid(src, proto));
#else
    (void) src;
    (void) proto;
    return {};
#endif
}

} // namespace

void configure(const toml::table &config) {
    Rules r;
    if (const toml::table *t = config["split"].as_table(); t != nullptr) {
        r.enabled = (*t)["enabled"].value_or(false);
        r.include = (*t)["mode"].value_or<std::string_view>("exclude") == "include";
        if (const auto *apps = (*t)["apps"].as_array()) {
            for (const auto &a : *apps) {
                if (auto s = a.value<std::string_view>(); s && !s->empty()) {
                    r.apps.push_back(lower(*s));
                }
            }
        }
        if (const auto *targets = (*t)["targets"].as_array()) {
            for (const auto &a : *targets) {
                if (auto s = a.value<std::string_view>(); s && !s->empty()) {
                    Net n;
                    if (parse_net(*s, n)) {
                        r.nets.push_back(n);
                    } else {
                        warnlog(g_logger, "Skipping split target: {}", *s);
                    }
                }
            }
        }
    }
    infolog(g_logger, "Split tunnelling: {}, mode {}, {} apps, {} targets", r.enabled ? "on" : "off",
            r.include ? "include" : "exclude", r.apps.size(), r.nets.size());
    std::unique_lock lock(g_mutex);
    g_rules = std::move(r);
}

std::optional<VpnConnectAction> decide(const sockaddr *src, const VpnAddress *dst, int proto, std::string &app_out) {
    std::shared_lock lock(g_mutex);
    const Rules &r = g_rules;
    if (!r.enabled || (r.apps.empty() && r.nets.empty() && !r.include)) {
        return std::nullopt;
    }
    bool hit = false;
    if (!r.apps.empty()) {
        app_out = owner_path(src, proto);
        if (!app_out.empty()) {
            std::string path = lower(app_out);
            std::string_view name = file_name(path);
            for (const auto &a : r.apps) {
                if (a == path || (a.find_first_of("\\/") == std::string::npos && a == name)) {
                    hit = true;
                    break;
                }
            }
        }
    }
    if (!hit && dst != nullptr && dst->type == VPN_AT_ADDR) {
        const auto *sa = (const sockaddr *) &dst->addr;
        for (const auto &n : r.nets) {
            if (net_match(n, sa)) {
                hit = true;
                break;
            }
        }
    }
    if (hit) {
        return r.include ? VPN_CA_FORCE_REDIRECT : VPN_CA_FORCE_BYPASS;
    }
    if (r.include) {
        return VPN_CA_FORCE_BYPASS;
    }
    return std::nullopt;
}

} // namespace ag::tunnel_split
