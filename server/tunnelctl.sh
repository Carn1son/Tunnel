#!/bin/sh
# Tunnel server helper: installs and manages a TrustTunnel endpoint for the Tunnel app.
# The app uploads this file over SSH and runs:  sh tunnelctl.sh <command> [args]
# Commands print one JSON document (or progress lines for install/update) and exit non-zero on failure.
# Stats: a systemd timer runs "collect" every 5 minutes at the lowest CPU and IO priority.

DIR="${TT_DIR:-/opt/trusttunnel}"
STATS="$DIR/tunnel-stats.tsv"
METRICS="127.0.0.1:1987"
INSTALL_URL="https://raw.githubusercontent.com/TrustTunnel/TrustTunnel/refs/heads/master/scripts/install.sh"
PATH="$PATH:/usr/sbin:/sbin:/usr/local/bin"
USER="${USER:-$(id -un 2>/dev/null || echo root)}"; export USER PATH   # the official installer needs $USER

say() { printf '%s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
# JSON string
js() { printf '"%s"' "$(printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g' | tr -d '\n\r\t')"; }
need_root() { [ "$(id -u)" = 0 ] || die "root rights are needed (run as root or allow sudo)"; }
valid_name() { printf '%s' "$1" | grep -Eq '^[A-Za-z0-9_.@-]{1,64}$' || die "bad user name: use letters, digits, . _ - @"; }
valid_pass() { printf '%s' "$1" | grep -Eq '^[A-Za-z0-9_.@%+=!#-]{4,128}$' || die "bad password: 4-128 characters, no spaces or quotes"; }

fetch() { # url -> stdout
  if command -v curl >/dev/null 2>&1; then curl -fsSL --max-time "${2:-60}" "$1"
  elif command -v wget >/dev/null 2>&1; then wget -qO- -T "${2:-60}" "$1"
  else return 127; fi
}

ensure_tools() { # curl and tar for the official installer
  for t in curl tar; do
    command -v "$t" >/dev/null 2>&1 && continue
    say "Installing $t…"
    if command -v apt-get >/dev/null 2>&1; then DEBIAN_FRONTEND=noninteractive apt-get update -qq >/dev/null 2>&1; DEBIAN_FRONTEND=noninteractive apt-get install -y -qq "$t" >/dev/null 2>&1
    elif command -v dnf >/dev/null 2>&1; then dnf install -y -q "$t" >/dev/null 2>&1
    elif command -v yum >/dev/null 2>&1; then yum install -y -q "$t" >/dev/null 2>&1
    elif command -v apk >/dev/null 2>&1; then apk add -q "$t" >/dev/null 2>&1
    fi
    command -v "$t" >/dev/null 2>&1 || die "cannot install $t"
  done
}

# ---------- credentials.toml as "name<TAB>password" lines
creds_file() {
  f=$(sed -n 's/^[[:space:]]*credentials_file[[:space:]]*=[[:space:]]*"\(.*\)".*/\1/p' "$DIR/vpn.toml" 2>/dev/null | head -1)
  [ -n "$f" ] || f="credentials.toml"
  case "$f" in /*) printf '%s' "$f" ;; *) printf '%s/%s' "$DIR" "$f" ;; esac
}
creds_read() {
  awk '
    /^[[:space:]]*\[\[client\]\]/ { if (u != "") print u "\t" p; u=""; p=""; next }
    /^[[:space:]]*username[[:space:]]*=/ { s=$0; sub(/^[^=]*=[[:space:]]*/, "", s); gsub(/^"|"[[:space:]]*$/, "", s); u=s; next }
    /^[[:space:]]*password[[:space:]]*=/ { s=$0; sub(/^[^=]*=[[:space:]]*/, "", s); gsub(/^"|"[[:space:]]*$/, "", s); p=s; next }
    END { if (u != "") print u "\t" p }' "$(creds_file)" 2>/dev/null
}
creds_write() { # stdin: name<TAB>password lines
  f=$(creds_file); tmp="$f.tmp.$$"
  awk -F '\t' 'NF >= 2 && $1 != "" { printf "[[client]]\nusername = \"%s\"\npassword = \"%s\"\n\n", $1, $2 }' > "$tmp" || die "cannot write $f"
  chmod 600 "$tmp" 2>/dev/null; mv "$tmp" "$f"
}

restart_endpoint() {
  if command -v systemctl >/dev/null 2>&1 && systemctl list-unit-files trusttunnel.service >/dev/null 2>&1; then
    systemctl restart trusttunnel >/dev/null 2>&1 || die "trusttunnel did not restart: $(journalctl -u trusttunnel -n 5 --no-pager 2>/dev/null | tail -3)"
  fi
}

# ---------- statistics: lifetime traffic and last activity per user, kept across endpoint restarts
collect() {
  [ -f "$DIR/vpn.toml" ] || return 0
  raw=$(fetch "http://$METRICS/clients" 3 2>/dev/null) || return 0
  now=$(date +%s)
  touch "$STATS"
  printf '%s' "$raw" | tr -d '[]\n' | sed 's/},[[:space:]]*{/}\n{/g' | awk -v now="$now" -v stats="$STATS" '
    function field(s, k,   r) {
      if (match(s, "\"" k "\":[^,}]*")) { r = substr(s, RSTART + length(k) + 3, RLENGTH - length(k) - 3); gsub(/"/, "", r); return r }
      return ""
    }
    BEGIN { FS = "\t"; while ((getline l < stats) > 0) { split(l, a, "\t"); n = a[1]; tin[n] = a[2]; tout[n] = a[3]; rin[n] = a[4]; rout[n] = a[5]; seen[n] = a[6]; ip[n] = a[7]; known[n] = 1; had[n] = 1 } }
    /username/ {
      n = field($0, "username"); if (n == "") next
      i = field($0, "inbound") + 0; o = field($0, "outbound") + 0; s = field($0, "sessions") + 0; addr = field($0, "ip")
      di = (i >= rin[n] + 0) ? i - rin[n] : i; do_ = (o >= rout[n] + 0) ? o - rout[n] : o
      tin[n] += di; tout[n] += do_; rin[n] = i; rout[n] = o
      if (s > 0 || ((di > 0 || do_ > 0) && (n in had))) seen[n] = now   # traffic from before the first count is not "just now"
      if (addr != "" && addr != "null") ip[n] = addr
      sess[n] = s; known[n] = 1
    }
    END { for (n in known) printf "%s\t%.0f\t%.0f\t%.0f\t%.0f\t%s\t%s\t%s\n", n, tin[n], tout[n], rin[n], rout[n], seen[n], ip[n], sess[n] + 0 }
  ' > "$STATS.tmp" && mv "$STATS.tmp" "$STATS"
}

# ---------- commands
cmd_status() {
  installed=false; running=false; version=""; listen=""; host=""; users=0; metrics=false; timer=false
  if [ -x "$DIR/trusttunnel_endpoint" ]; then
    installed=true
    version=$("$DIR/trusttunnel_endpoint" --version 2>/dev/null | head -1 | sed 's/[^0-9.]*\([0-9][0-9.]*\).*/\1/')
    listen=$(sed -n 's/^[[:space:]]*listen_address[[:space:]]*=[[:space:]]*"\(.*\)".*/\1/p' "$DIR/vpn.toml" 2>/dev/null | head -1)
    host=$(sed -n 's/^[[:space:]]*hostname[[:space:]]*=[[:space:]]*"\(.*\)".*/\1/p' "$DIR/hosts.toml" 2>/dev/null | head -1)
    users=$(creds_read | grep -c . 2>/dev/null)
    grep -q '^[[:space:]]*per_client_metrics[[:space:]]*=[[:space:]]*true' "$DIR/vpn.toml" 2>/dev/null && metrics=true
    systemctl is-active --quiet trusttunnel 2>/dev/null && running=true
    systemctl is-active --quiet tunnel-stats.timer 2>/dev/null && timer=true
  fi
  configured=false; [ -f "$DIR/vpn.toml" ] && configured=true
  load=$(cut -d' ' -f1 /proc/loadavg 2>/dev/null)
  cpus=$(nproc 2>/dev/null || echo 1)
  memt=$(awk '/MemTotal/ {print $2}' /proc/meminfo 2>/dev/null); mema=$(awk '/MemAvailable/ {print $2}' /proc/meminfo 2>/dev/null)
  up=$(cut -d' ' -f1 /proc/uptime 2>/dev/null | cut -d. -f1)
  os=$(. /etc/os-release 2>/dev/null && printf '%s' "$PRETTY_NAME"); arch=$(uname -m)
  p443=false; if command -v ss >/dev/null 2>&1 && ss -ltnH 2>/dev/null | awk '{print $4}' | grep -Eq '[:.]443$'; then p443=true; fi
  root=false; [ "$(id -u)" = 0 ] && root=true
  printf '{"installed":%s,"configured":%s,"running":%s,"version":%s,"listen":%s,"hostname":%s,"users":%s,"metrics":%s,"timer":%s,"load":%s,"cpus":%s,"memTotal":%s,"memAvail":%s,"uptime":%s,"os":%s,"arch":%s,"port443busy":%s,"root":%s,"dir":%s}\n' \
    "$installed" "$configured" "$running" "$(js "$version")" "$(js "$listen")" "$(js "$host")" "${users:-0}" "$metrics" "$timer" \
    "${load:-0}" "${cpus:-1}" "${memt:-0}" "${mema:-0}" "${up:-0}" "$(js "$os")" "$(js "$arch")" "$p443" "$root" "$(js "$DIR")"
}

cmd_users() { # [{"name","password","in","out","seen","ip","sessions"}], seen = unix time of the last activity (0 = never)
  need_root
  collect
  creds_read | awk -F '\t' -v stats="$STATS" '
    function j(s) { gsub(/\\/, "\\\\", s); gsub(/"/, "\\\"", s); return "\"" s "\"" }
    BEGIN { while ((getline l < stats) > 0) { split(l, a, "\t"); tin[a[1]] = a[2]; tout[a[1]] = a[3]; seen[a[1]] = a[6]; ip[a[1]] = a[7]; ses[a[1]] = a[8] } printf "[" }
    $1 != "" { printf "%s{\"name\":%s,\"password\":%s,\"in\":%d,\"out\":%d,\"seen\":%d,\"ip\":%s,\"sessions\":%d}", (n++ ? "," : ""), j($1), j($2), tin[$1], tout[$1], seen[$1], j(ip[$1]), ses[$1] }
    END { print "]" }'
}

cmd_adduser() {
  need_root; valid_name "$1"; valid_pass "$2"
  creds_read | awk -F '\t' -v n="$1" '$1 == n { f = 1 } END { exit f ? 0 : 1 }' && die "user $1 already exists"
  { creds_read; printf '%s\t%s\n' "$1" "$2"; } | creds_write
  restart_endpoint
  say '{"ok":true}'
}

cmd_deluser() {
  need_root; valid_name "$1"
  left=$(creds_read | awk -F '\t' -v n="$1" '$1 != n' | grep -c .)
  [ "$left" -gt 0 ] || die "the last user cannot be removed"
  creds_read | awk -F '\t' -v n="$1" '$1 != n' | creds_write
  [ -f "$STATS" ] && awk -F '\t' -v n="$1" '$1 != n' "$STATS" > "$STATS.tmp" && mv "$STATS.tmp" "$STATS"
  restart_endpoint
  say '{"ok":true}'
}

cmd_passwd() {
  need_root; valid_name "$1"; valid_pass "$2"
  creds_read | awk -F '\t' -v n="$1" '$1 == n { f = 1 } END { exit f ? 0 : 1 }' || die "no user $1"
  creds_read | awk -F '\t' -v OFS='\t' -v n="$1" -v p="$2" '$1 == n { $2 = p } { print }' | creds_write
  restart_endpoint
  say '{"ok":true}'
}

cmd_link() { # name address
  need_root; valid_name "$1"; [ -n "$2" ] || die "address is required"
  cd "$DIR" || die "TrustTunnel is not installed"
  out=$(./trusttunnel_endpoint vpn.toml hosts.toml -c "$1" -a "$2" 2>&1) || die "$out"
  link=$(printf '%s\n' "$out" | grep -o 'tt://[^[:space:]]*' | head -1)
  [ -n "$link" ] || die "no link in the endpoint output: $out"
  printf '{"link":%s}\n' "$(js "$link")"
}

cmd_service() { # start|stop|restart
  need_root
  systemctl "$1" trusttunnel >/dev/null 2>&1 || die "systemctl $1 failed: $(journalctl -u trusttunnel -n 5 --no-pager 2>/dev/null | tail -3)"
  say '{"ok":true}'
}

cmd_logs() {
  need_root
  journalctl -u trusttunnel -n "${1:-80}" --no-pager -o short-iso 2>&1 | tail -n "${1:-80}"
}

enable_metrics() {
  grep -q '^[[:space:]]*\[metrics\]' "$DIR/vpn.toml" && { sed -i 's/^\([[:space:]]*per_client_metrics[[:space:]]*=[[:space:]]*\)false/\1true/' "$DIR/vpn.toml"; return; }
  printf '\n[metrics]\naddress = "%s"\nrequest_timeout_secs = 3\nper_client_metrics = true\n' "$METRICS" >> "$DIR/vpn.toml"
}

install_timer() {
  cp "$0" "$DIR/tunnelctl.sh" 2>/dev/null; chmod 700 "$DIR/tunnelctl.sh"
  cat > /etc/systemd/system/tunnel-stats.service <<EOF
[Unit]
Description=Tunnel: TrustTunnel user statistics
After=trusttunnel.service

[Service]
Type=oneshot
Nice=19
IOSchedulingClass=idle
ExecStart=/bin/sh $DIR/tunnelctl.sh collect
EOF
  cat > /etc/systemd/system/tunnel-stats.timer <<EOF
[Unit]
Description=Tunnel: TrustTunnel user statistics every 5 minutes

[Timer]
OnBootSec=2min
OnUnitActiveSec=5min
AccuracySec=1min

[Install]
WantedBy=timers.target
EOF
  systemctl daemon-reload && systemctl enable --now tunnel-stats.timer >/dev/null 2>&1
}

open_port() { # port
  if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q 'Status: active'; then
    ufw allow "$1"/tcp >/dev/null 2>&1; ufw allow "$1"/udp >/dev/null 2>&1; say "Firewall (ufw): port $1 opened"
  elif command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then
    firewall-cmd -q --permanent --add-port="$1"/tcp; firewall-cmd -q --permanent --add-port="$1"/udp; firewall-cmd -q --reload; say "Firewall (firewalld): port $1 opened"
  fi
}

# install KEY=VALUE...: listen user pass cert(self-signed|letsencrypt|provided) hostname email chain key stats(1|0) firewall(1|0) force(1|0)
cmd_install() {
  need_root
  listen="0.0.0.0:443"; user=""; pass=""; cert="self-signed"; hostname="vpn.endpoint"; email=""; chain=""; key=""; stats=1; firewall=1; force=0
  for kv in "$@"; do
    k=${kv%%=*}; v=${kv#*=}
    case "$k" in listen) listen=$v ;; user) user=$v ;; pass) pass=$v ;; cert) cert=$v ;; hostname) hostname=$v ;; email) email=$v ;; chain) chain=$v ;; key) key=$v ;; stats) stats=$v ;; firewall) firewall=$v ;; force) force=$v ;; esac
  done
  valid_name "$user"; valid_pass "$pass"
  [ "$(uname -s)" = Linux ] || die "only Linux servers are supported"
  command -v systemctl >/dev/null 2>&1 || die "systemd is required"
  if [ -f "$DIR/vpn.toml" ]; then
    [ "$force" = 1 ] || die "TrustTunnel is already configured in $DIR"
    b="$DIR/backup-$(date +%Y%m%d-%H%M%S)"; mkdir -p "$b"; mv "$DIR"/*.toml "$b"/ 2>/dev/null; say "Old settings moved to $b"
  fi

  say "STEP 1/5 Downloading TrustTunnel"
  ensure_tools
  fetch "$INSTALL_URL" 60 | sh -s - -a y -o "$DIR" || die "the official installer failed"
  [ -x "$DIR/setup_wizard" ] || die "setup_wizard was not installed"

  say "STEP 2/5 Generating settings and certificate"
  cd "$DIR" || die "no $DIR"
  set -- -m non-interactive -a "$listen" -c "$user:$pass" -n "$hostname" --lib-settings vpn.toml --hosts-settings hosts.toml --cert-type "$cert"
  case "$cert" in
    letsencrypt) [ -n "$email" ] || die "email is required for Let's Encrypt"; set -- "$@" --acme-email "$email" ;;
    provided) set -- "$@" --cert-chain-path "$chain" --cert-key-path "$key" ;;
  esac
  ./setup_wizard "$@" </dev/null 2>&1 || die "setup_wizard failed"
  [ -f vpn.toml ] && [ -f hosts.toml ] || die "the settings were not created"
  [ "$stats" = 1 ] && enable_metrics

  say "STEP 3/5 Installing the service"
  sed "s#/opt/trusttunnel#$DIR#g" trusttunnel.service.template > /etc/systemd/system/trusttunnel.service || die "no service template"
  systemctl daemon-reload && systemctl enable --now trusttunnel >/dev/null 2>&1 || die "trusttunnel did not start: $(journalctl -u trusttunnel -n 8 --no-pager | tail -5)"

  say "STEP 4/5 Statistics and firewall"
  [ "$stats" = 1 ] && install_timer
  [ "$firewall" = 1 ] && open_port "${listen##*:}"

  say "STEP 5/5 Checking"
  sleep 2
  systemctl is-active --quiet trusttunnel || die "trusttunnel is not running: $(journalctl -u trusttunnel -n 8 --no-pager | tail -5)"
  say "DONE"
}

cmd_update() {
  need_root
  ensure_tools
  say "STEP 1/2 Downloading the latest TrustTunnel"
  systemctl stop trusttunnel >/dev/null 2>&1
  fetch "$INSTALL_URL" 60 | sh -s - -a y -o "$DIR" || { systemctl start trusttunnel; die "the official installer failed"; }
  say "STEP 2/2 Starting"
  systemctl start trusttunnel || die "trusttunnel did not start"
  [ -f /etc/systemd/system/tunnel-stats.timer ] && cp "$0" "$DIR/tunnelctl.sh" 2>/dev/null
  say "DONE"
}

cmd_uninstall() {
  need_root
  systemctl disable --now trusttunnel tunnel-stats.timer >/dev/null 2>&1
  rm -f /etc/systemd/system/trusttunnel.service /etc/systemd/system/tunnel-stats.service /etc/systemd/system/tunnel-stats.timer
  systemctl daemon-reload
  rm -rf "$DIR"
  say '{"ok":true}'
}

c="$1"; [ $# -gt 0 ] && shift
case "$c" in
  status) cmd_status ;;
  users) cmd_users ;;
  adduser) cmd_adduser "$@" ;;
  deluser) cmd_deluser "$@" ;;
  passwd) cmd_passwd "$@" ;;
  link) cmd_link "$@" ;;
  start|stop|restart) cmd_service "$c" ;;
  logs) cmd_logs "$@" ;;
  install) cmd_install "$@" ;;
  update) cmd_update ;;
  uninstall) cmd_uninstall ;;
  collect) need_root; collect ;;
  stats) need_root; enable_metrics; install_timer; restart_endpoint; say '{"ok":true}' ;;
  *) die "unknown command: $c" ;;
esac
