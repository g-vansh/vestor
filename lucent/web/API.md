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
- The key cookie is replaced about once a minute: any answer may carry
  `Set-Cookie: lucent=...; Path=/; HttpOnly; SameSite=Strict; Max-Age=34560000`. The browser
  handles it; the page does nothing. The previous key keeps working for 30 s, so up to three
  requests at once are fine. `401 {"error": "reused"}` means the key was also used from somewhere
  else (copied): this device was signed out (the answer clears the cookie) and the TV says so.
  Show pairing again, with a line like "Signed out for safety. Pair again from the TV."
- Limits a page must stay within: at most 6 connections at once per device (a browser's
  default), the head of each request sent within 5 s, a whole request within 20 s (photo
  uploads: 130 s; `/api/pair/wait`: it answers within 25 s). Before pairing, a device's requests
  are served two at a time; the rest wait their turn, so do not treat a slow file as an error.

## Pairing (no key yet)

1. `GET /api/hello` → `{"paired": bool, "name": "Lucent", "pairing": bool, "nonce": string}`.
   `nonce` (22 URL-safe base64 characters) is there only while a pairing window is open, and is
   new for each window.
2. If not paired, show the pairing screen. Two ways in, both only work while someone opened
   "Pair a phone" on the TV (a 2 minute window):
   - The TV shows a QR code for `http://<ip>:8686/#k=<22-char secret>`. When the page loads with
     `#k=` in `location.hash`, read it, immediately `history.replaceState(null, '', '/')` to drop
     it, and keep it in memory only (never in storage, never in a URL, never sent). Ask for a
     device name (prefilled from the user agent: "iPhone", "iPad", "Mac", "Windows PC",
     "Android phone"; at most 100 characters, the TV keeps 40). Then prove the secret without
     sending it:
     - `GET /api/hello` for the current `nonce`.
     - `c` = 16 random bytes from `crypto.getRandomValues` as URL-safe base64 without padding
       (22 characters). A new `c` for every try: the TV refuses one it has seen in this window.
     - `proof` = lowercase hex of HMAC-SHA256 with key = the UTF-8 bytes of the secret `k` and
       message = the UTF-8 bytes of `"lucent-pair:" + nonce + ":" + c + ":" + name`, where
       `name` is exactly the string sent below.
     - `POST /api/pair {"proof": proof, "c": c, "name": name}`.

     The page is plain http, not a secure context, so `crypto.subtle` is not there: include a
     small SHA-256 and HMAC in app.js (HMAC per RFC 2104, 64-byte block) and check it against
     the test vector below. `crypto.getRandomValues` does work on plain http.
     The old form `{"k": secret}` is refused with 400: the secret never crosses the network.
   - Or the user types the 6-digit code shown on the TV: `POST /api/pair {"pin": "123456", "name": name}`.
     Five wrong codes from one device, or twenty from everyone, turn the code off for the rest
     of the window (scanning still works); after three such windows in an hour the TV shows no
     code for 15 minutes.
3. Answers: `202 {"wait": id, "match": "042"}`, or `403 {"error": "wrong"|"closed"|"locked", "lockedFor": ms}`,
   or `400 {"error": "pair"}` for a malformed request.
   - After 202 the TV asks "Allow <name>?" next to the same three digits: show `match` large
     ("Check the TV shows 042"), so the owner can tell this phone's request from anyone else's.
   - `wrong`: the code or proof did not match (a wrong proof costs the window nothing; get a new
     `c` and nonce and try again). `closed`: no window is open, or it just paired someone.
     `locked`: the six-digit code is off for this device (or for now); `lockedFor` is how long, in
     ms. Suggest scanning the QR code instead.
4. Then loop `POST /api/pair/wait {"id": id}`. It holds up to 25 s. `202 {"wait": id}` means keep
   waiting (show "Press OK on the remote to allow this phone"), `200 {"ok": true}` means paired
   (the server set an HttpOnly cookie; reload the app state), `403` means refused or expired.
   Only the address that sent the proof or code can collect the key.

### Test vector for the proof

| | |
|---|---|
| `k` | `AAECAwQFBgcICQoLDA0ODw` (bytes 0x00..0x0f, base64url) |
| `nonce` | `EBESExQVFhcYGRobHB0eHw` (bytes 0x10..0x1f) |
| `c` | `ICEiIyQlJicoKSorLC0uLw` (bytes 0x20..0x2f) |
| `name` | `Alex’s iPhone` (with U+2019 RIGHT SINGLE QUOTATION MARK, as iOS types it: 15 UTF-8 bytes) |
| message | `lucent-pair:EBESExQVFhcYGRobHB0eHw:ICEiIyQlJicoKSorLC0uLw:Alex’s iPhone` |
| `proof` | `39fc66d8e364aba818c0c8c07bc8ae54b309468c50cd2ef41df71f057502e48c` |

With `name` = `Alex's iPhone` (a plain apostrophe) the proof is
`937c172cd604f7cb7d135d1253a304a03a85313e35362038b0f88c32526d4f29`: a page that encodes the name
as anything but UTF-8 gets the first one wrong. The same vector is checked by
`preview/tests/SecurityTest.java` against `Keys.proof`.

## State

`GET /api/state` (poll every 4 s while the page is visible, every 30 s hidden; stop when
`document.hidden` for more than 5 min). Fields may be missing or null when unknown: render
gracefully.

```json
{
  "tv": {"name": "Lucent", "version": "2.0", "front": true, "mode": "night", "modeSetting": "auto",
         "nextChange": 1793170000000, "sunrise": 1793100000000, "sunset": 1793140000000,
         "moon": "Waxing crescent", "moonLit": 0.31, "urls": ["http://192.168.1.23:8686"],
         "access": "lan", "now": 1793124300000, "dim": false, "ringing": false},
  "me": {"name": "Alex's iPhone", "id": "Ab3d"},
  "weather": {"ok": true, "city": "Cambridge", "tempF": 53, "tempC": 11.6, "feelsF": 49, "hiF": 56, "loF": 41,
              "condition": "Partly cloudy", "code": 2, "day": true, "humidity": 58, "windMph": 9, "windDir": "W",
              "updated": "1:56 PM",
              "hours": [{"label": "Now", "f": 53, "c": 12, "code": 2, "day": true, "rain": 10}],
              "rain": {"summary": "Rain from 3:45 PM", "at": 1793140700000, "until": 1793147900000},
              "model": "NOAA blend (NBM)"},
  "air": {"aqi": 44, "aqiWord": "Good", "uv": 0.8, "uvWord": "Low"},
  "display": {"width": 3840, "height": 2160, "hz": 60, "hdr": ["HDR10", "HDR10+", "HLG"], "uiWidth": 1920,
              "uiHeight": 1080, "advice": ""},
  "samsung": {"connected": true, "device": "Samsung 43\" TU7000", "modes": ["Dynamic", "Standard", "Natural", "Movie"],
              "mode": "Movie", "glare": false, "follow": "on", "dayMode": "Standard", "nightMode": "Movie",
              "glareMode": "Dynamic", "error": "", "setup": "none"},
  "push": {"on": true, "topic": "kJ3x9Qm2LwT7aZp0vB5nRc", "url": "https://ntfy.sh/kJ3x9Qm2LwT7aZp0vB5nRc"},
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
               "wifiSsid": "Home", "wifiSet": true, "theme": "auto", "alarmFor": "5", "quality": "motion"},
  "stations": [{"id": "auto", "name": "Westgate"}, {"id": "2261583246847893606", "name": "Hastings Square"}],
  "devices": [{"id": "Ab3d", "name": "Alex's iPhone", "seen": 1793124300000, "ip": "192.168.1.40", "me": true}],
  "health": [{"host": "gbfs.lyft.com", "state": "OK", "sent": 120, "ok": 30, "unchanged": 88, "limited": 0,
              "failed": 2, "perMinute": 40}],
  "alexa": {"on": true, "url": "<find e-bike link>", "stopUrl": "<stop and silence link>",
            "commands": [{"name": "Bike finder", "say": "Alexa, turn on bike finder", "on": "<url>", "off": "<url>"}]}
}
```

Notes on the newer fields (all optional, the page renders without them):

- `tv.ringing` mirrors `finder.alarm`. Either one being true shows the Silence alarm banner.
- `weather.rain.summary` is shown under the conditions ("Rain from 3:45 PM" or "Dry for the next 2 hours"),
  `at` and `until` are epoch ms or 0. `weather.model` is credited next to Open-Meteo ("Open-Meteo, NOAA blend").
- `display` is what the TV is being sent. The page shows "4K, 60 Hz, HDR10, HDR10+, HLG. Apps draw at 1080p, films
  play in 4K." and shows `advice` in amber when it is not empty (a one-line fix when the TV is not getting 4K).
- `samsung` is present when the TV can drive a Samsung over SmartThings. `mode` may be "". `error` is the last
  error text, empty when fine. `setup` is "begun" between `samsung begin` and `samsung finish`. When `samsung` is
  missing the page hides the Samsung controls. Secrets never appear in state: phones only see `connected`.
- `push` is present when phone alerts are available. `topic` is 22 characters of base64url.
- `alexa.commands` is present only when `alexa.on`. `off` may be "". `url` and `stopUrl` remain for older pages.

## Actions: `POST /api/<action>` with a JSON body. Reply is `{"ok": true}` plus fields noted.

| Action | Body | Effect |
|---|---|---|
| `finder` | `{"do": "start"\|"stop"\|"silence"}` | Start or stop bike finder, or silence a ringing alarm. A start reply may carry `"note"` (for example "Waking Bluebikes, starting in a moment"), which the page shows |
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
| `tv` | `{"do": "lucent"\|"firetv"\|"sleep"}` | Bring Lucent to the front, open Fire TV's own home, or put the TV to sleep. `sleep` answers 409 when the HOME service is off |
| `list` | `{"op": "add", "text"}`, `{"op": "toggle", "id"}`, `{"op": "remove", "id"}`, `{"op": "clear"}` | Shared list |
| `wifi` | `{"do": "show"\|"hide"}` | Show the guest Wi-Fi QR code on the TV for 2 minutes |
| `revoke` | `{"id"}` | Forget a paired device (its key stops working) |
| `logout` | `{}` | Forget this device |
| `refresh` | `{}` | Ask the TV to fetch live data now and keep it fresh for 2 minutes |
| `samsung` | `{"do": "begin", "clientId", "clientSecret"}` | Start SmartThings sign-in. Reply `{"ok": true, "authorize": "https://api.smartthings.com/v1/oauth/authorize?…"}`. The page opens it only if it is an https smartthings.com address |
| `samsung` | `{"do": "finish", "code"}` | `code` is the bare code, the whole httpbin address, or the JSON the user pasted (the TV extracts it). Reply `{"ok": true, "device": "…"}`, or 400/409 `{"error": "<words for the user>"}`, which the page shows in amber |
| `samsung` | `{"do": "disconnect"}` | Forget the SmartThings sign-in |
| `samsung` | `{"do": "glare", "on": bool}` | Glare picture on (switches to `glareMode`) or back to the day or night picture |
| `samsung` | `{"do": "mode", "mode": "Dynamic"}` | Set the picture mode now (one of `samsung.modes`) |
| `push` | `{"do": "test"}` | Send a test notification to subscribed phones. 409 `{"error": "…"}` when phone alerts are off |

Setting keys for `set` (anything else is refused): `units` f/c/off, `clock` auto/12/24,
`rotate` 1m/5m/10m/hour/day/off, `album` nature/ours/both, `motion` full/reduced, `ambient`
2/5/15/off, `dim` 5/10/30/off, `awakeFrom` and `awakeTo` "HH:MM" or "always", `bedtime` "HH:MM"
or off, `findFor` 15/30/60/120, `sky` on/off, `station` (an id from `stations`), `alarm` ring/chime,
`wakeForNotes` always/day/never, `theme` auto/day/night (same as mode), `mbtaKey` (a 32-hex key or
empty, never echoed back, state shows "set" or ""), `calendars` (array of up to 6 objects
`{"name": "Us", "url": "https://calendar.google.com/... or webcal://pNN-caldav.icloud.com/..."}`,
URLs never echoed back in full), `countdowns` (array of up to 6 `{"title", "date": "YYYY-MM-DD"}`),
`wifiSsid` and `wifiPass` (pass never echoed), `alexa` on/off/new (new makes fresh private links and
invalidates the old ones; when on, state.alexa has the links and `commands`), `alarmFor` 2/5/10/30 (minutes the
bike alarm rings before it gives up by itself), `quality` motion/still (motion is the slow drifting wallpaper,
still shows the photo pixel for pixel), `pictureFollow` on/off (the Samsung picture follows day and night),
`dayMode`, `nightMode` and `glareMode` (each one of `samsung.modes`), `push` on/off/new (new makes a fresh topic,
and phones must subscribe again).

Calendars round trip: state.calendar.feeds[].url is masked like "https://calendar.google.com/…". When saving the
list with `set calendars`, send unchanged feeds back with that exact masked url and the TV keeps the saved
address. New feeds send the full URL (https:// or webcal://).

Errors from actions: a 400 or 409 may carry `{"error": "<a sentence for the user>"}`. The page shows that sentence
when it is a sentence, and its own wording for numeric errors.

## Photo upload

`POST /api/photo`, body = the raw image bytes, `Content-Type: image/jpeg` (or image/png),
`X-Caption: <encodeURIComponent(caption)>` (≤60 chars), `X-Taken: <epoch ms>` (from EXIF
DateTimeOriginal when present, else file.lastModified). Max 14 MB. Before uploading, the page
must decode the picked file into a canvas (this applies the EXIF rotation and strips every bit
of EXIF, GPS included), scale so the long side is at most 3840 px, and export with
`canvas.toBlob(cb, 'image/jpeg', 0.9)`. Use `<input type="file" accept="image/jpeg,image/png" multiple>`
so iOS hands over JPEG instead of HEIC. Upload one at a time and show progress (XMLHttpRequest
upload progress events are fine). Reply `{"ok": true, "id": "..."}` or an error
(`413` too big, `507`-like `{"error": "full"}` when storage is full, `415` not a photo, `409`
`{"error": "album full"}` or `{"error": "tv storage low"}`).
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
