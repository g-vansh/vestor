# Lucent control center: web app contract

The TV runs a small HTTP server (Console.java) at `http://<tv-ip>:8686`. It serves these
files from `lucent/assets/web/` and the JSON API below. The page is the only client.

## Files served (exact paths, nothing else)

| URL | Asset | Type |
|---|---|---|
| `/` | `web/index.html` | text/html |
| `/app.js` | `web/app.js` | text/javascript |
| `/app.css` | `web/app.css` | text/css |
| `/icon.svg` | `web/icon.svg` | image/svg+xml |
| `/manifest.webmanifest` | `web/manifest.webmanifest` | application/manifest+json |
| `/fonts/inter-medium.ttf`, `/fonts/inter-semibold.ttf` | Inter | font/ttf |
| `/fonts/newsreader-display.ttf`, `/fonts/newsreader-italic.ttf` | Newsreader Display (opsz 72, 400, and italic) | font/ttf |
| `/fonts/geist-mono.ttf` | Geist Mono Medium | font/ttf |

Content-Security-Policy on every response:
`default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' blob: data:; font-src 'self'; connect-src 'self'; manifest-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'`

So: no inline `<script>` or `<style>`, no `style=""` attributes, no `on*=` attributes, no
external URLs of any kind, no eval. Set styles from JS only through `element.style.setProperty`
(CSSOM is allowed under CSP) or class toggles. Render all text with `textContent`, never
`innerHTML` with data. The page is served over plain http on a LAN IP, so it is NOT a secure
context: no `crypto.subtle`, no service workers, no clipboard write without a gesture fallback.

## Every API request

- Send header `X-Lucent: 1` on every `/api/*` request (GET and POST).
- POST bodies are JSON with `Content-Type: application/json`, except photo uploads.
- Use `fetch(url, {credentials: 'same-origin', cache: 'no-store'})`.
- Errors come back as JSON `{"error": ...}` with status 400/401/403/404/413/415/429.
  401 means not paired (show pairing). 429 means slow down (back off 2 s).

## Pairing (no key yet)

1. `GET /api/hello` → `{"paired": bool, "name": "Lucent", "pairing": bool}`.
2. If not paired, show the pairing screen. Two ways in, both only work while someone opened
   "Pair a phone" on the TV (a 2 minute window):
   - The TV shows a QR code for `http://<ip>:8686/#k=<22-char secret>`. When the page loads with
     `#k=` in `location.hash`, read it, immediately `history.replaceState(null, '', '/')` to drop
     it, ask for a device name (prefilled from the user agent: "iPhone", "iPad", "Mac",
     "Windows PC", "Android phone"), then `POST /api/pair {"k": secret, "name": name}`.
   - Or the user types the 6-digit code shown on the TV: `POST /api/pair {"pin": "123456", "name": name}`.
3. Answers: `202 {"wait": id}` (now the TV asks "Allow <name>?" and someone presses OK), or
   `403 {"error": "wrong"|"closed"|"locked", "lockedFor": ms}`.
4. Then loop `POST /api/pair/wait {"id": id}`. It holds up to 25 s. `202 {"wait": id}` means keep
   waiting (show "Press OK on the remote to allow this phone"), `200 {"ok": true}` means paired
   (the server set an HttpOnly cookie; reload the app state), `403` means refused or expired.

## State

`GET /api/state` (poll every 4 s while the page is visible, every 30 s hidden; stop when
`document.hidden` for more than 5 min). Fields may be missing or null when unknown: render
gracefully.

```json
{
  "tv": {"name": "Lucent", "version": "2.0", "front": true, "mode": "night", "modeSetting": "auto",
         "nextChange": 1793170000000, "sunrise": 1793100000000, "sunset": 1793140000000,
         "moon": "Waxing crescent", "moonLit": 0.31, "urls": ["http://192.168.1.23:8686"],
         "access": "lan", "now": 1793124300000, "dim": false},
  "me": {"name": "Alex's iPhone", "id": "Ab3d"},
  "weather": {"ok": true, "city": "Cambridge", "tempF": 53, "tempC": 11.6, "feelsF": 49, "hiF": 56, "loF": 41,
              "condition": "Partly cloudy", "code": 2, "day": true, "humidity": 58, "windMph": 9, "windDir": "W",
              "updated": "1:56 PM",
              "hours": [{"label": "Now", "f": 53, "c": 12, "code": 2, "day": true, "rain": 10}]},
  "air": {"aqi": 44, "aqiWord": "Good", "uv": 0.8, "uvWord": "Low"},
  "alerts": [{"event": "Wind Advisory", "headline": "...", "severity": "Moderate", "expires": 1793150000000}],
  "bikes": {"ok": true, "label": "Westgate", "fetched": 1793124290000, "published": 1793124260000,
            "stations": [{"id": "f835...", "name": "MIT Vassar St", "short": "Westgate", "ebikes": 1, "classic": 6,
                          "docks": 18, "capacity": 25, "renting": true, "miles": 0.0}]},
  "finder": {"state": "idle", "started": 0, "deadline": 0, "ended": 0, "ebikes": 0, "checks": 0,
             "station": "Westgate", "alarm": false, "offline": false},
  "transit": {"fetched": 1793124295000, "live": false,
              "lines": [{"id": "85a", "chip": "85A", "label": "85A to Assembly Row", "stop": "Vassar St @ Audrey St",
                         "color": "#FFC72C", "minutes": [3, 15], "idle": ""}]},
  "sky": {"fetched": 1793124298000, "source": "adsb.fi", "inRange": 12,
          "flights": [{"name": "JetBlue 1387", "from": "BOS", "to": "LAX", "fromCity": "Boston", "toCity": "Los Angeles",
                       "alt": 12400, "knots": 289, "miles": 1.8, "type": "A321", "track": 105, "trend": "Climbing"}]},
  "calendar": {"events": [{"title": "Dinner", "start": 1793140000000, "end": 1793145000000, "allDay": false,
                           "cal": 0, "calName": "Us", "location": "Oleana"}],
               "feeds": [{"name": "Us", "url": "https://calendar.google.com/…(masked)", "ok": true, "count": 14}],
               "countdowns": [{"title": "Wedding", "date": "2027-06-12", "days": 228}]},
  "notes": [{"id": "n1", "from": "Alex", "at": 1793124000000, "text": "Dinner at 8?", "paper": 0, "pinned": true,
             "strokes": [{"c": 1, "w": 8, "p": [1200, 3400, 1300, 3420]}]}],
  "photos": {"count": 23, "bytes": 51234567, "freeBytes": 812000000, "limit": 300, "album": "both",
             "items": [{"id": "0123456789abcdef0123456789abcdef", "caption": "Paris", "taken": 1717000000000,
                        "added": 1793000000000, "from": "Alex", "w": 3840, "h": 2560}]},
  "list": [{"id": "l1", "text": "Oat milk", "done": false, "by": "Alex", "at": 1793120000000}],
  "media": {"active": true, "app": "Spotify", "title": "Song", "artist": "Artist", "album": "Album",
            "playing": true, "position": 63000, "duration": 210000, "canNext": true, "canPrev": true},
  "audio": {"route": "HDMI", "device": "", "headline": "Dolby Atmos", "formats": ["Dolby Atmos", "Dolby MAT"]},
  "settings": {"units": "f", "clock": "auto", "rotate": "hour", "album": "both", "motion": "full", "ambient": "5",
               "dim": "10", "awakeFrom": "07:00", "awakeTo": "00:30", "bedtime": "off", "findFor": "30",
               "sky": "on", "station": "auto", "alarm": "ring", "wakeForNotes": "day", "mbtaKey": "set"|"",
               "wifiSsid": "Home", "wifiSet": true, "theme": "auto"},
  "stations": [{"id": "auto", "name": "Westgate"}, {"id": "2261583246847893606", "name": "Hastings Square"}],
  "devices": [{"id": "Ab3d", "name": "Alex's iPhone", "seen": 1793124300000, "ip": "192.168.1.40", "me": true}],
  "health": [{"host": "gbfs.lyft.com", "state": "OK", "sent": 120, "ok": 30, "unchanged": 88, "limited": 0,
              "failed": 2, "perMinute": 40}],
  "alexa": {"on": false, "url": ""}
}
```

## Actions: `POST /api/<action>` with a JSON body. Reply is `{"ok": true}` plus fields noted.

| Action | Body | Effect |
|---|---|---|
| `finder` | `{"do": "start"\|"stop"\|"silence"}` | Start or stop bike finder, or silence a ringing alarm |
| `note` | `{"text": "≤280 chars", "strokes": [...], "paper": 0..3, "pin": bool}` | Note pops up on the TV. Reply `{"ok": true, "id": "..."}` |
| `notedel` | `{"id"}` | Remove a note |
| `notepin` | `{"id", "pin": bool}` | Keep a note on the home screen, or not |
| `heart` | `{"kind": "hearts"\|"sparkles"\|"wave"}` | A burst of hearts (or sparkles, or a wave) floats up the TV. At most one per 3 s |
| `photodel` | `{"id"}` | Delete a photo |
| `photocap` | `{"id", "caption": "≤60"}` | Rename a photo |
| `photoshow` | `{"id"}` | Show this photo as the wallpaper now |
| `mode` | `{"mode": "auto"\|"day"\|"night"}` | Day and night look |
| `set` | `{"key": "...", "value": "..." or array}` | Change one setting (keys below) |
| `media` | `{"do": "toggle"\|"next"\|"prev"}` | Control what is playing on the TV |
| `tv` | `{"do": "lucent"\|"firetv"\|"sleep"}` | Bring Lucent to the front, open Fire TV's own home, or put the TV to sleep |
| `list` | `{"op": "add", "text"}`, `{"op": "toggle", "id"}`, `{"op": "remove", "id"}`, `{"op": "clear"}` | Shared list |
| `wifi` | `{"do": "show"\|"hide"}` | Show the guest Wi-Fi QR code on the TV for 2 minutes |
| `revoke` | `{"id"}` | Forget a paired device (its key stops working) |
| `logout` | `{}` | Forget this device |
| `refresh` | `{}` | Ask the TV to fetch live data now and keep it fresh for 2 minutes |

Setting keys for `set` (anything else is refused): `units` f/c/off, `clock` auto/12/24,
`rotate` 1m/5m/10m/hour/day/off, `album` nature/ours/both, `motion` full/reduced, `ambient`
2/5/15/off, `dim` 5/10/30/off, `awakeFrom` and `awakeTo` "HH:MM" or "always", `bedtime` "HH:MM"
or off, `findFor` 15/30/60/120, `sky` on/off, `station` (an id from `stations`), `alarm` ring/chime,
`wakeForNotes` always/day/never, `theme` auto/day/night (same as mode), `mbtaKey` (a 32-hex key or
empty, never echoed back, state shows "set" or ""), `calendars` (array of up to 6 objects
`{"name": "Us", "url": "https://calendar.google.com/... or webcal://pNN-caldav.icloud.com/..."}`,
URLs never echoed back in full), `countdowns` (array of up to 6 `{"title", "date": "YYYY-MM-DD"}`),
`wifiSsid` and `wifiPass` (pass never echoed), `alexa` on/off (when on, state.alexa.url is the
link to paste into the Alexa routine).

## Photo upload

`POST /api/photo`, body = the raw image bytes, `Content-Type: image/jpeg` (or image/png),
`X-Caption: <encodeURIComponent(caption)>` (≤60 chars), `X-Taken: <epoch ms>` (from EXIF
DateTimeOriginal when present, else file.lastModified). Max 14 MB. Before uploading, the page
must decode the picked file into a canvas (this applies the EXIF rotation and strips every bit
of EXIF, GPS included), scale so the long side is at most 3840 px, and export with
`canvas.toBlob(cb, 'image/jpeg', 0.9)`. Use `<input type="file" accept="image/jpeg,image/png" multiple>`
so iOS hands over JPEG instead of HEIC. Upload one at a time and show progress (XMLHttpRequest
upload progress events are fine). Reply `{"ok": true, "id": "..."}` or an error
(`413` too big, `507`-like `{"error": "full"}` when storage is full, `415` not a photo).
Photos are fetched back as `GET /api/photo/<id>.thumb.jpg` (about 480 px) and `/api/photo/<id>.jpg`
(full) with the same X-Lucent header: fetch them as blobs and show through `URL.createObjectURL`
(an `<img src>` cannot send the header).

## Drawn notes

Drawing area aspect 4:3. Points are integers 0..10000 on both axes of that 4:3 box (x right, y
down). A stroke is `{"c": colour 0..7, "w": width in thousandths of the box width (4..60), "p":
[x0, y0, x1, y1, ...]}`. Limits: ≤ 200 strokes, ≤ 20,000 points in total, text ≤ 280 chars,
body ≤ 384 KB. Drop points closer than about 0.4% of the width to the previous one, and smooth
with a short moving average before sending. Colours (shared with the TV, keep exactly):

| c | name | hex |
|---|---|---|
| 0 | ink (white on dark paper, near-black on cream) | #F5F1EA / #161514 |
| 1 | rose | #FF6B8B |
| 2 | ember | #EC944C |
| 3 | gold | #F4D06F |
| 4 | mint | #7FD49B |
| 5 | sky | #6EC1FF |
| 6 | violet | #B79CFF |
| 7 | red | #FF5E5E |

Papers: 0 night glass (dark), 1 cream `#F6F3EE`, 2 blush `#F9E3E3`, 3 midnight blue `#151A2D`.
The TV replays the strokes in order, as if being written, over about 2.5 s.
