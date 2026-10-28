#!/usr/bin/env bash
# AirPlay from this Mac to the Samsung on a network that blocks AirPlay's discovery (MIT's Wi-Fi).
#
#   scripts/airplay-samsung.sh [samsung-ip [port]]      default 10.29.147.80, port found by itself
#
# AirPlay finds a TV by Bonjour, which MIT's Wi-Fi blocks between devices, but the connection
# itself is ordinary traffic that MIT routes between its subnets. So this asks the Samsung for the
# exact Bonjour record it would have announced (GET /info, asking for txtAirPlay), then publishes
# that record on this Mac only, pointing at the Samsung's address. The Mac's Screen Mirroring menu
# then lists "Samsung TV (direct)" and connects straight to it. Nothing is announced to the network,
# and nothing stays once this stops: Ctrl-C, or close the terminal. An iPhone cannot use this,
# because iOS finds AirPlay devices only by Bonjour on its own network.
#
# Needs: AirPlay on in the Samsung's Settings, General, Apple AirPlay Settings. If the Samsung's
# address changes, read it again in its Settings, General, Network, Network Status, IP Settings.
# This Samsung's AirPlay listens on a port it picks itself (46977 on setup night) and announces it
# only over Bonjour, so this tries 7000, then looks for the port that answers as AirTunes (with
# nmap: brew install nmap), unless you give the port.
#
# macOS hides an AirPlay device whose record has no pairing ID (psi) or key (pk), and plain
# GET /info leaves both out. Asking with the txtAirPlay qualifier returns the full record.
set -euo pipefail
ip=${1:-10.29.147.80}
port=${2:-}
[[ $ip =~ ^(10|192\.168|172\.(1[6-9]|2[0-9]|3[01]))(\.[0-9]{1,3}){2,3}$ ]] || { echo "error: give the Samsung's private address, like 10.29.147.80" >&2; exit 2; }
work=$(mktemp -d "${TMPDIR:-/tmp}/samsung-airplay.XXXXXX")
pids=()
# Stops only the registrations this started, and with them the publishing.
trap 'rm -rf "$work"; [[ ${#pids[@]} -eq 0 ]] || kill "${pids[@]}" 2>/dev/null || true' EXIT

# The question AirPlay senders ask: send the full Bonjour records, not just the summary.
python3 -I -c 'import plistlib, sys
sys.stdout.buffer.write(plistlib.dumps({"qualifier": ["txtAirPlay", "txtRAOP"]}, fmt=plistlib.FMT_BINARY))' >"$work/ask"

# Whether $1 is the TV's AirPlay port: its /info answers as AirTunes. Keeps the answer in $work/info.
airtunes() {
  curl -s -m 4 -X GET --data-binary @"$work/ask" -H 'Content-Type: application/x-apple-binary-plist' \
    -H 'User-Agent: AirPlay/690.7.1' -D - -o "$work/info" "http://$ip:$1/info" 2>/dev/null |
    tr -d '\r' | grep -qi '^server: *AirTunes'
}
if [[ -n $port ]]; then
  if ! [[ $port =~ ^[0-9]{2,5}$ ]] || ! airtunes "$port"; then
    echo "error: nothing answers as AirPlay on $ip port $port" >&2
    exit 1
  fi
elif airtunes 7000; then
  port=7000
elif command -v nmap >/dev/null; then
  echo "Looking for the Samsung's AirPlay port (a few minutes)..."
  for p in $(nmap -Pn -p 1024-65535 -T4 --min-rate 1500 --open "$ip" 2>/dev/null | sed -n 's|^\([0-9]*\)/tcp.*|\1|p'); do
    if airtunes "$p"; then
      port=$p
      break
    fi
  done
fi
if [[ -z $port ]]; then
  echo "error: the Samsung at $ip does not answer AirPlay." >&2
  echo "  Turn AirPlay on: Settings, General, Apple AirPlay Settings. Then run this again." >&2
  command -v nmap >/dev/null || echo "  If it is on, brew install nmap so this can find the port the TV picked." >&2
  exit 1
fi
echo "The Samsung answers AirPlay on port $port."

# The TV's own Bonjour records, one entry per line: air for _airplay._tcp, raop for _raop._tcp
# when the TV has one. Prints the device id.
mac=$(python3 -I - "$work" <<'EOF'
import os, plistlib, sys
work = sys.argv[1]
d = plistlib.load(open(os.path.join(work, 'info'), 'rb'))
def entries(raw):
    out, i = [], 0
    while i < len(raw):
        n = raw[i]
        out.append(raw[i + 1:i + 1 + n].decode('utf-8', 'replace'))
        i += 1 + n
    return [e for e in out if e and '\n' not in e]
air = entries(d.get('txtAirPlay') or b'')
if not any(e.startswith('psi=') or e.startswith('pk=') for e in air):
    sys.exit("error: the Samsung did not give its pairing ID, so macOS would hide it")
for name, key in (('air', 'txtAirPlay'), ('raop', 'txtRAOP')):
    with open(os.path.join(work, name), 'w') as f:
        f.write(''.join(e + '\n' for e in entries(d.get(key) or b'')))
print(str(d.get('deviceID') or ''))
EOF
)
[[ -n $mac ]] || { echo "error: the Samsung's AirPlay answer had no device id" >&2; exit 1; }
airtxt=()
while IFS= read -r e; do airtxt+=("$e"); done <"$work/air"
raoptxt=()
while IFS= read -r e; do raoptxt+=("$e"); done <"$work/raop"

name="Samsung TV (direct)"
host="samsung-tv-direct.local"
echo "Publishing $name on this Mac only, pointing at $ip:$port. Leave this running and open Control"
echo "Center, Screen Mirroring. The first time, the TV shows a code to type on the Mac. Ctrl-C stops it."
dns-sd -lo -P "$name" _airplay._tcp local "$port" "$host" "$ip" "${airtxt[@]}" &
pids+=($!)
if [[ ${#raoptxt[@]} -gt 0 ]]; then
  dns-sd -lo -P "${mac//:/}@$name" _raop._tcp local "$port" "$host" "$ip" "${raoptxt[@]}" &
  pids+=($!)
fi
wait
