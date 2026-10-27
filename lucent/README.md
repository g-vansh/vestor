# Lucent

A glass home screen for Fire TV, with Westgate's bikes, shuttles, weather and sky on it. Built for a
Fire TV Stick 4K (2nd generation) on Fire OS 8.1.8.5, driving a Samsung TV and a VIZIO 5.1 SE
soundbar over eARC.

- Full-screen landscape photography that drifts slowly, with the place named in the corner.
- A glance bar above the dock, readable from the couch:
  - **Weather** in °F with °C beside it (or the other way round in Settings), from Open-Meteo.
  - **Bluebikes at Westgate**: e-bikes, classic bikes and open docks, live from the GBFS feed.
  - **Shuttles and buses**: MIT Tech Shuttle and Tech +NW at Grad Junction West, SafeRide at W98
    @ Vassar St, and the MBTA 85 both ways: to Assembly Row from Vassar St @ Audrey St (beside the
    Bluebikes dock) and to Ruggles from Amesbury St @ Vassar St, as live countdowns.
  - **Planes overhead**: the nearest flight with its route, altitude and speed.
- **Bike finder**: press OK on the bikes card and Lucent checks Westgate every 5 seconds. The
  moment an e-bike can be rented it chimes and shows an alert, even over Netflix.
- A floating glass dock of your apps, each tile showing the real artwork and logo from the app
  installed on the stick.
- A **Sound** card showing what your TV and soundbar accept over HDMI, and a settings sheet.
- Ambient mode: after a few idle minutes the dock fades, and the photo, clock and glance bar stay.

No ads, no sponsored rows, no autoplaying trailers.

## Using it

| Remote | Does |
|---|---|
| Left / Right | Move along the dock or the glance bar |
| Up | From the dock to the glance bar, then to Sound and Settings |
| Down | Back towards the dock |
| OK on an app | Open it |
| OK on a glance card | Open its full panel. Left and Right move between panels |
| OK on the bikes card | Start bike finder and open the Bluebikes panel |
| Hold OK on the bikes card | Start or stop bike finder without opening anything |
| Hold OK on an app, or ☰ | Move, Hide or App Info |
| Back | Close a panel or card, or jump to the first app |
| Hold HOME | Amazon's quick menu, unchanged |
| Alexa button | Unchanged: "Alexa, open Netflix" still works |

Focus an item in the glance bar and it grows into a card with the detail: the weather's high, low,
feels-like, humidity and wind, every e-bike, bike and dock as a row of dots, the next buses on
every line, the flight's route and cities. OK grows the card into a full panel: the weather's next
six hours with the chance of rain, the three nearest docks, every shuttle and bus with its stop and
the following ones, and the other planes in range. When the 85 has no live prediction its row says
when the timetable's next bus is due.

## Bike finder

1. Press Up once from the dock, move to the bikes card and press OK.
2. Lucent reads the Westgate dock's live status every 5 seconds. The card shows a radar and how
   long it has been looking. You can go and watch something.
3. When an e-bike can be rented you hear a two-note chime and see "E-bike docked" in the top right
   corner, over whatever is playing. It stays 30 seconds and never pauses the film. If one is
   there already when you start, it tells you straight away.
4. It then stops by itself. It also stops after 30 minutes (Settings, Bike Finder Gives Up, up to
   2 hours), or when you press Stop Finding.

Only bikes the station will actually rent count: a dock that is not renting shows its bikes
dimmed and is never reported as found. Bluebikes refreshes its feed on its own schedule, and
checking every 5 seconds means Lucent sees each update within seconds of it being published.

Westgate's dock is the one MIT opened in front of the Westgate low-rise buildings on Vassar Street.
Lucent finds it in the live feed by name or position, so a rename does not break it. To follow a
different dock pick it in Settings, Bike Station, which lists the nearest ones.

## App tiles and logos

Every tile shows artwork from the app itself, read from the app installed on your stick, the way
Android TV and Google TV fill their app rows. Lucent never draws a logo of its own or downloads
one. When an app updates its art, the tile updates with it. In order, a tile uses:

1. **The app's TV banner**, the 16:9 picture with its logo that Android TV apps ship for home
   screens, at the highest resolution the app has. Netflix, Disney+, Max, Peacock, Paramount+,
   Spotify and Plex all make one for Android TV.
2. **The app's wide logo**, if it has one, centred on a tile tinted from its own colours.
3. **A wide launcher icon.** Some Fire TV apps put their TV artwork in the icon, because Amazon's
   home shows icons and ignores banners.
4. **The app's square icon** at full resolution, on a backdrop made from the icon's own colours.
   An app built only for Fire TV that ships no banner, as Amazon's own Prime Video may, looks
   like this.

Amazon's home screen is the exception: for Appstore apps it downloads separate 1280 x 720 art
from Amazon's servers, which no other app on the stick can read. If you prefer that art, or any
picture, for an app, save it as a PNG or JPEG named after the app's package in the banners
folder, and it wins over everything above:

```
adb shell mkdir -p /sdcard/Android/media/tv.lucent.home/banners
adb push netflix-art.png /sdcard/Android/media/tv.lucent.home/banners/com.netflix.ninja.png
```

It is cropped to 16:9 and shows the next time you press HOME. Find a package name with
`adb shell pm list packages`. The screenshots in this folder use placeholder tiles, because
they were made on a computer without your apps.

## Install (about 10 minutes, once)

You need a computer on the same Wi-Fi as the TV.

1. **Turn on ADB on the Fire TV.** Settings › My Fire TV › About, then press OK on
   **Fire TV Stick 4K** seven times until it says you are a developer. Back out to
   My Fire TV › Developer Options and turn **ADB Debugging** on.
2. **Find the TV's IP address.** Settings › My Fire TV › About › Network.
3. **Install adb on the computer.**
   - Mac: `brew install android-platform-tools`
   - Windows: download Android SDK Platform-Tools from developer.android.com, unzip it, and run
     the commands below in Git Bash.
4. **Connect and install.** In this folder:

   ```
   adb connect 192.168.1.23:5555      # your TV's address. Press Allow on the TV, tick Always allow
   ./install.sh
   ```

   It installs the app, gives it the HOME button and opens it. Press HOME on the remote to check.

5. **Turn ADB off.** At the end the installer asks to do it for you, once you have pressed
   HOME and seen Lucent. Later, `./install.sh --adb-off` does the same. Lucent keeps working.
   ADB is the one way into the TV from your network, so leave it off and turn it on in
   Developer Options only to update or undo. Updating is the same `./install.sh`, and your
   settings are kept.

**If you installed the first build:** that one was signed with a key that travelled inside its
source zip, so this build uses a new key that does not. `./install.sh` notices the old copy and
asks once to replace it, which resets your dock order and hidden apps. Delete the first
download afterwards.

## Getting the best sound from the VIZIO

The home screen never touches audio. Each streaming app negotiates its own format, and on this
stick Amazon transcodes streaming audio to **Dolby MAT**, which is why the VIZIO app shows
"Dolby MAT". That format only travels over eARC, so seeing it means your chain is working.
For the best result:

- **Fire TV:** Settings › Display & Sounds › Audio › Surround Sound: **Best Available**.
  Turn Volume Leveler and Dialogue Enhancer **off** (they process the audio).
- **Samsung TV:** Settings › Sound › Expert Settings › **HDMI eARC Mode: Auto**, and
  **Digital Output Audio Format: Pass-Through** (try Auto if anything drops out).
  Keep Anynet+ (HDMI-CEC) on so the Fire TV remote controls the soundbar volume.
- **Soundbar:** **Direct** mode for the cleanest sound, **Movie** for its virtual widening.
  The 5.1 SE has no up-firing drivers, so its Atmos height effect is processed virtually.
- **DTS:** Samsung TVs do not pass DTS over ARC or eARC. Streaming services use Dolby, so this
  only matters for Plex or Kodi files.

The Sound card on the home screen lists what the TV reports, live. The bike finder's chime is
mixed in by Fire TV like any system sound, so it plays through the soundbar too.

## Your own wallpapers, weather place and bike dock

Wallpapers: copy JPEG or PNG files into the app's media folder and pick them in Settings:

```
adb push my-photo.jpg /sdcard/Android/media/tv.lucent.home/wallpapers/
```

Weather place (defaults to Cambridge, MA) and, if you like, a fixed bike dock by its GBFS
station id, in a `lucent.conf`:

```
printf 'city=Cambridge\nlat=42.3736\nlon=-71.1097\n' > lucent.conf
adb push lucent.conf /sdcard/Android/media/tv.lucent.home/lucent.conf
```

Add a line `bike_station=<station id>` to pin a dock, or `bike_station=auto` to go back to
Westgate. Lucent reads the file the next time you press HOME, keeps the values in its private
settings and deletes the file, since other apps can read that folder. Push a new one to change
anything.

## Undo

```
./install.sh --undo      # HOME goes back to Amazon. Lucent stays installed
./install.sh --remove    # HOME goes back to Amazon and Lucent is uninstalled
```

If another launcher's HOME service (dumbtv or Projectivy) was on before, install paused it and
`--undo` turns it back on.

Worst case, with no computer: a factory reset clears everything Lucent did, because it never
writes to the system partition. Hold **Back** and the **right side of the ring** together for
10 seconds, or Settings › My Fire TV › Reset to Factory Defaults. That also signs out the
Amazon account and removes downloaded apps, so it is the last resort.

## What it changes, exactly

- Installs one app, `tv.lucent.home`, with one permission: internet.
- Turns on one accessibility service, "Lucent home button". It looks only at which app and screen
  a new window belongs to, to spot Amazon's home, ignores the rest of each event, and draws the
  bike finder's alert. It is declared unable to read screen content, press keys, read the remote,
  make gestures or take screenshots. Other services, such as VoiceView, stay on throughout.
- Sets `min_futurity=0` in `alarm_manager_constants`, so the HOME switch is instant. A previous
  value is saved and restored by `--undo`.
- Exempts the app from Doze so the switch works after the stick has idled.

It does not root anything, disable Amazon apps, block updates or change audio settings.

Network requests, all to public feeds with no account and no key:

| Host | For | When |
|---|---|---|
| api.open-meteo.com | Weather, for a position rounded to about 2 km | Every 15 minutes while home is on screen |
| gbfs.lyft.com | Bluebikes stations and live counts | Every 30 s on the home screen, every 5 s while bike finder runs |
| passio3.com | MIT shuttles (GTFS-realtime) | Every 20 s on the home screen |
| api-v3.mbta.com | MBTA 85 predictions, and its timetable once a day | Every 20 s on the home screen |
| api.adsb.lol, hexdb.io | Planes overhead (position rounded to about 5 km) and their routes | Every 15 s on the home screen, if Planes Overhead is on |

Nothing is fetched while you are in another app, except by bike finder while it runs. These six
hosts are fixed in the app: it refuses to contact any other.

## Security

Lucent has no account, no password and no server of its own, and it opens no network port, so
there is nothing on it for anyone on the internet or your Wi-Fi to log in to.

- **One permission, internet.** It cannot see your files, your Amazon account, the microphone
  or other apps' data. No third-party code is inside: no SDKs, ads, analytics or web views.
- **Outgoing only, to the six hosts above, over HTTPS.** Plain HTTP is refused, and so are
  certificates a user or another app installed, so nobody on your network can read or alter
  the traffic. Redirects are followed only within the same host. Every answer has a size cap
  and a time limit.
- **What leaves the TV.** No identifiers and no cookies, and the user agent is just "Lucent"
  instead of Android's, which names the device model. Weather and planes get rounded positions. Bluebikes and MIT feeds are downloaded whole, so they never learn which
  dock or stop you watch. The MBTA request names the two Vassar St stops. Like any website,
  each service sees your home IP address.
- **Broken or hostile data cannot take it down.** The parsers are tested against about 28,000
  broken, truncated and malicious inputs (`preview/tests/Hostile.java`): deep nesting,
  impossible numbers, overflowing lengths, zip bombs. A bad answer skips one refresh and the
  last good data stays on screen.
- **The media folder is treated as untrusted**, because other apps with storage access can write
  to it. Wallpapers and tile pictures must really be JPEG or PNG, checked by their first bytes
  and by the decoder before use, within size limits. `lucent.conf` is checked line by line,
  copied to private storage and deleted. App names and feed text are cleaned of control
  characters and capped before they are drawn. Photos you add as wallpapers stay in that
  folder, where apps with storage access can see them.
- **It cannot trap the TV.** Amazon's home stays the system's HOME underneath. If Lucent ever
  crashes three times in three minutes, HOME goes back to Amazon's home by itself for a while,
  and Lucent still opens from Your Apps.
- **Only you can update it.** Android installs an update over Lucent only if it carries the same
  signature, which stops anyone else swapping in an app that would inherit its HOME service.
  The installer also checks the signature of what it installed and refuses to hand the HOME
  button to anything signed by another key. The
  key comes as its own file, `signing-key-keep-private.zip`, never inside `lucent.zip`, so this
  folder can be shared safely. Keep the key somewhere private, such as a password manager or an
  encrypted drive, and never in a repository or shared folder. The source's `.gitignore` keeps
  it out of git, and `package.sh` refuses to build a source zip with key material in it. The
  source and this guide name Westgate and its stops, so anyone you share them with knows which
  dock you follow.
- **ADB is the real door.** While ADB Debugging is on, any computer on your Wi-Fi can ask to
  connect. The TV asks you first, and "Always allow" trusts that computer from then on. So
  turn it off with `./install.sh --adb-off` once HOME works, and keep `~/.android/adbkey` on
  your computer private.
- **On the TV:** leave Settings › My Fire TV › Developer Options › Install unknown apps off for
  every app, and keep Fire OS updates on. They carry Android's security fixes, and Lucent never
  blocks them.

## Known limits

- HOME shows about 0.3 s of Lucent's blurred wallpaper before the home screen settles in.
  Amazon's home is covered before it draws.
- Waking from sleep can show Amazon's home briefly before Lucent takes over.
- A Fire OS update can switch the HOME service off. If HOME lands on Amazon's home again,
  run `./install.sh` once more. Amazon has blocked earlier launcher methods in updates, so it
  could stop working one day. Then your Fire TV simply behaves as stock.
- The ☰ and Alexa buttons keep Amazon's behaviour inside other apps.
- Shuttle times are tracked buses only. A line with no tracked bus shows its hours instead,
  from MIT's published schedules (or, for the 85, the MBTA timetable), which change in summer
  and on holidays.
- Bike finder's alert can't take the remote while a film plays, so Back goes to the film. The
  alert leaves on its own after 30 seconds.

## Rebuilding

`./build.sh` builds `build/lucent.apk` with the bare Android tools (aapt2, javac, dx or d8,
zipalign, apksigner). See the top of the script. An update must be signed with the same key,
so unzip `signing-key-keep-private.zip` (sent separately) somewhere private outside this folder
and point the build at it: `LUCENT_KEYSTORE=/path/to/release.keystore ./build.sh`. It reads the
password from `release.keystore.pass` beside the key. A build with a new key prints the value
to give the installer as `LUCENT_SIG`. The `preview/` folder renders the
real scene code on a desktop JVM from recorded-shape feed fixtures, which is how the screenshots
were made. `preview/tests` checks every feed parser, feeds them hostile input and fuzzes the
remote.

## Credits

See NOTICE. The HOME guard is adapted from dumbtv (MIT). Type is Inter (SIL OFL 1.1).
Photographs are CC0 from Wikimedia Commons, as collected by Glass Home (MIT). Stops, routes and
counting rules follow Vestor. Weather by Open-Meteo (CC BY 4.0), bike data from Bluebikes, shuttle
data from MIT (Passio), bus data from the MBTA, aircraft from adsb.lol (ODbL) and routes from
hexdb.io. BU's Hyatt shuttle, which Vestor shows, ended with BU's Hyatt housing in fall 2026.
