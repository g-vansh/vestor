# Lucent

A glass home screen for Fire TV that doubles as the living room's information screen, with
Westgate's bikes, the shuttles and the 85, the weather, planes overhead, your shared calendar,
notes from each other and your own photos on it. You run it from the remote, or from your phones
through a control center the TV serves itself. Built for a Fire TV Stick 4K (2nd generation) on
Fire OS 8.1.8.5, driving a Samsung TV and a VIZIO 5.1 SE soundbar over eARC.

## What 2.0 adds

- **Phone control center.** Scan a code on the TV once and your phone can draw notes that write
  themselves onto the TV, send hearts, upload family photos, keep a shared list and countdowns,
  start bike finder, play and pause music, show guests the Wi-Fi and change any setting.
- **Day and night.** Frosted light glass from an hour after sunrise, warm dark glass from sunset,
  crossfading over half a minute. Change it by hand from the remote or a phone.
- **A screen that stays on all day and dims itself.** No screensaver during the hours you choose,
  a soft dim after ten idle minutes, and an optional bedtime when the TV goes to sleep.
- **Bike alarm.** When bike finder sees an e-bike it rings until you press any button, over
  whatever is playing. Alexa in the bedroom can start it: "Alexa, find e-bike".
- **Your photos as the wallpaper**, uploaded from a phone, stored on the TV at up to 4K, rotating
  with the nature photographs or on their own, with a photo from this day in an earlier year
  first each morning.
- **Shared calendar.** Her Google Calendar and your iCloud calendar together, with the next event
  under the clock.
- **Mirroring, music and headphones.** A Mirror button for iPhone, iPad, Mac, Android and
  Windows, a Now Playing card the remote's Play key controls, and a card when AirPods connect.
- **Never rate limited, refreshed as fast as each source publishes.** Every feed goes through a
  governor that stays under each service's published limit, asks "has this changed?" instead of
  downloading again, and fails over to a second plane source.
- **The 85 says where it goes:** 85A to Assembly Row, 85B to BU and Ruggles.
- **A new look**, editorial rather than app-like: a serif clock, mono labels and quiet glass.

No ads, no sponsored rows, no autoplaying trailers, no accounts.

## Using it with the remote

| Remote | Does |
|---|---|
| Left / Right | Move along the dock, the glance bar or the top row |
| Up | From the dock to the glance bar, then to the top row |
| Down | Back towards the dock |
| OK on an app | Open it |
| OK on a glance card | Open its full panel. Left and Right move between panels |
| OK on the bikes card | Start bike finder and open the Bluebikes panel |
| Hold OK on the bikes card | Start or stop bike finder without opening anything |
| Hold OK on an app, or ☰ | Move, Hide or App Info. ☰ elsewhere opens Settings |
| Play/Pause | Play or pause whatever is playing on the TV |
| Rewind / Fast Forward | The previous or next wallpaper. On the music card, the previous or next track |
| Back | Close what is open, or jump to the first app |
| Any button while the alarm rings | Stops it |

The top row, left to right: **Fire TV Home** (the exit: Amazon's home until you open an app),
**Mirror a Screen**, **Phone Control** (pair a phone), **Sound** and **Settings**.

Fire TV keeps four buttons for itself in every app, so no home screen can use them: HOME, the
microphone (Alexa), volume and the app shortcut buttons. Alexa voice commands work as before.

Focus an item in the glance bar and it grows into a card with the detail. OK grows the card
into a full panel: the weather's next six hours with the chance of rain, the three nearest docks,
every shuttle and bus with the following ones, the week's plans and the shared list, what is
playing, and the other planes in range.

## Phone control center

The TV serves a small web page on your home Wi-Fi. Nothing is installed on the phone, and
nothing goes through the internet.

**Pairing, once per phone or laptop:**

1. On the TV, press Up twice and choose the phone icon. A code appears for two minutes.
2. Point the phone's camera at the code and open the link. (Or open the address shown under the
   code and type the six digits.)
3. The TV asks "Allow this device?" Press OK on the remote.

The phone stays paired. On an iPhone, Share › Add to Home Screen turns the page into an app icon.

**What it does:**

- **Home:** everything the TV shows, live: bikes, buses, shuttles, weather and alerts, planes.
  Start or stop bike finder and silence the alarm. Play, pause and skip what is playing. Bring
  Lucent to the front, switch to Fire TV's home, or put the TV to sleep.
- **Draw:** a note in your handwriting or typed, on one of four papers. It pops up on the TV and
  writes itself stroke by stroke, then stays pinned on the home screen for the day (or for good,
  if you pin it). Hearts, sparkles or a wave float up the screen at a tap, even over a film.
- **Photos:** upload from the phone's library. The TV keeps up to 300 photos at up to 4K, and
  you can caption, delete, or show one on the TV right away.
- **Plans:** the shared calendar, a shared list (groceries, errands) and countdowns.
- **Settings:** every setting on the TV's sheet, plus awake hours, bedtime, calendars, the MBTA
  key, guest Wi-Fi, Alexa, the paired devices (forget any of them) and a health page showing how
  each data source is doing against its limits.

**From outside the house:** install Tailscale on the Fire TV and your phones, and the control
center answers on the TV's Tailscale address too, end-to-end encrypted, with no port opened on
your router. Tailscale's Fire TV app is sideloaded from pkgs.tailscale.com. In Settings on the TV,
"Phone Control Works On" picks Home Wi-Fi and Tailscale, Tailscale only, or Off.

## Notes, hearts and photos on the TV

A new note appears in the middle of the home screen and draws itself, with the sender's name
and the time. OK or Back closes it, and it stays pinned at the right. If a film is playing, it
appears in the top corner over the film for twenty seconds without taking the remote. If the TV
is off, it waits and shows the next time you come home, within a day. In Settings, "Notes Wake
the TV" can also turn the TV on for a note (never, in the daytime, or always).

From the remote: Settings › Write a Note opens a text box. The keyboard's microphone turns
speech into the text on Fire TV builds that offer it.

Photos are decoded in a sealed-off sandbox process with no permissions, then re-encoded at up
to 3840 pixels, so location data and anything hidden in the file are gone before the TV keeps
it. Portrait photos are shown whole over a soft, enlarged copy of themselves instead of being
cropped to a strip. Settings › Photos picks Ours and Nature (taking turns), Nature, or Ours
Only. Settings › Change Wallpaper sets the pace, from every minute to every day.

"Picture: Still and Sharpest" stops the slow drift so the photo sits pixel for pixel. Fire OS
draws every app's screen at 1920 by 1080 and the stick upscales it to the TV's 4K, so that is
the sharpest any home screen can show a still picture on this stick.

## Day and night

Night starts at sunset and day an hour after sunrise, worked out on the TV for Cambridge, with
no network call. Settings › Look (or the phone) picks Day or Night by hand. A choice made by
hand holds until the next sunrise or sunset change, then the automatic rule takes over again.
At night the photograph dims and warms and the moon's phase joins the line under the clock.

## Staying on all day, dimming, bedtime

Between the awake hours (7:00 to 0:30 unless you change them on the phone) the home screen
keeps the TV on, so Fire TV's screensaver and sleep never take over while you use it as a lamp.
After ten minutes without a button press the dock hides and the screen dims to a quiet glow
(Settings › Dim When Idle). Any button brings it back. Outside the awake hours Fire TV's own
timers apply again.

A **bedtime** set on the phone puts the TV to sleep at that time if nobody has pressed a button
for ten minutes. Once a night: wake it again and it stays awake.

Two TV settings matter for an all-day screen:

- **Samsung:** Settings › General (General & Privacy on newer models) › Power and Energy Saving
  (Eco Solution on older ones) › **Auto Power Off: Off**, or the TV switches itself off after
  four hours without its own remote. Keep **Anynet+ (HDMI-CEC)** on so the Fire TV can turn the
  TV on and off.
- **Fire TV:** Fire OS 8 can ask "Are you still watching?" after about four hours without a
  button press. Any button answers it.

## Bike finder and the alarm

1. Press Up once from the dock, move to the bikes card and press OK. Or tap Find on the phone,
   or ask Alexa.
2. Lucent reads the Westgate dock's live status every 5 seconds. The card shows a radar and how
   long it has been looking. Go and watch something.
3. When an e-bike can be rented, the alarm rings and a card says "E-bike ready at Westgate",
   over whatever is playing. It rings until you press any button on the remote, or tap Silence
   on the phone, or for at most ten minutes (Settings › Alarm Rings For). "When an E-Bike Is
   Found: Chime Once" gives the old single chime instead.
4. It gives up after 30 minutes (Settings › Bike Finder Gives Up, up to 2 hours).

Only bikes the station will actually rent count. Westgate's dock is the one MIT opened in front
of the Westgate low-rise buildings on Vassar Street ("MIT Vassar St" in the feed). Lucent finds
it by name or position, so a rename does not break it. Settings › Bike Station follows another.

## "Alexa, find e-bike" from the bedroom

Alexa cannot open a sideloaded app by voice, so Lucent listens for a private signal instead:

1. On the phone page, Settings › Alexa › On. It shows a private link (and a second one to stop).
2. At virtualsmarthome.xyz, sign in with your Amazon account and create a URL switch whose "on"
   address is that link. Enable the Virtual Smart Home skill in the Alexa app and let it
   discover the new device.
3. In the Alexa app, add a routine: when you say "find e-bike", turn on that device.

Then "Alexa, find e-bike" on any Echo wakes the TV, brings Lucent to the front and starts bike
finder. The signal travels through ntfy.sh, a public relay. The link holds a random 22-character
name that works as its password, so treat it like one. Settings › Alexa › New Link replaces it
if it ever leaks.

## Shared calendar

Lucent reads calendars from their private iCal links, every ten minutes, and shows the week in
the Plans panel and the next event under the clock. Paste each link on the phone page, Plans ›
Calendars. Up to six.

- **Google Calendar:** on a computer, calendar.google.com › Settings › pick the calendar under
  "Settings for my calendars" › Integrate calendar › **Secret address in iCal format**.
- **iCloud:** on the iPhone, Calendar › Calendars › the ⓘ beside the calendar › **Public
  Calendar** on › Share Link. "Public" here means anyone with the link can read it. It is not
  listed or searchable anywhere.
- **A calendar for the two of you:** create one called "Us" in Google Calendar, share it with
  each other with edit rights, and add its secret address once. Both of you add to it from your
  phones, and the TV follows.

The links are kept on the TV and never shown again in full, even to the phone page. Recurring
events, exceptions, all-day events and time zones follow the iCalendar standard.

## Mirroring a phone or laptop

The Mirror button on the top row offers two ways:

- **iPhone, iPad or Mac:** the best route is your **Samsung TV's own AirPlay** (built into
  Samsung TVs from 2018 on): Control Center › Screen Mirroring › the Samsung TV. It switches the
  TV's input by itself, and the Fire TV remote's HOME brings you back. To mirror through the Fire
  TV instead, install **AirReceiver** ($2.99) or **AirScreen** (free with limits) from the
  Appstore, and Lucent's Mirror button opens it.
- **Android phone or Windows PC:** Fire TV's own Miracast screen opens. On Windows press
  Win and K and pick the Fire TV. On Android use Smart View or Cast (Samsung phones support
  Miracast. Pixels only cast to Chromecast, so use the Samsung TV's own Screen Mirroring or
  AirScreen there). Any remote button ends a Miracast session.

## FaceTime and video calls

The Fire TV Stick has no FaceTime, WhatsApp or Zoom app and does not take a webcam, so the TV
cannot place a call itself. What works today: start the FaceTime or WhatsApp video call on the
iPhone and AirPlay its screen to the Samsung TV. The call fills the TV and the sound comes through
the soundbar, while the phone, propped up, is the camera and microphone. For FaceTime on the TV
properly, an Apple TV 4K uses your iPhone as its camera (Continuity Camera) and runs FaceTime
on the big screen.

## Music, surround sound and headphones

- **Spotify** streams in stereo on every device, so there is no surround music to unlock there.
  Spotify Connect from your phone to the Fire TV's Spotify app works well.
- **YouTube Music** is stereo too. Ordinary YouTube videos play in 5.1 on Fire TV when the video
  has it.
- **Dolby Atmos music** on this stick comes from **Amazon Music Unlimited** (turn on HD and
  Spatial Audio in its settings). Apple Music on Fire TV plays stereo.
- **To make stereo music fill the room**, put the VIZIO in **Music** mode with **Virtual:X on**
  and the surround level up. If music still sounds front-heavy, compare Fire TV's Surround Sound
  set to Best Available with Dolby Digital Plus: in some setups the Dolby MAT stream Best
  Available produces keeps the soundbar from spreading stereo out.

The Now Playing card shows what is playing in any app with a media session (Spotify, YouTube,
Amazon Music, Plex), with the time left. Play/Pause on the remote and the phone control it.

**Headphones.** Settings › Bluetooth Headphones opens Fire TV's pairing screen. When AirPods
connect, a card says so and the Sound button shows their name. Fire TV sends sound to one
Bluetooth device at a time, so two pairs of AirPods cannot both listen to the stick. Two ways
round it: an iPhone's Share Audio (for something playing on the iPhone), or a dual-link Bluetooth
transmitter on the TV's optical output, which feeds two pairs at once while the soundbar rests.

## Getting the best sound from the VIZIO

The home screen never touches audio. Each streaming app negotiates its own format, and on this
stick Amazon transcodes streaming audio to **Dolby MAT**, which is why the VIZIO app shows
"Dolby MAT". That format only travels over eARC, so seeing it means your chain works.

- **Fire TV:** Settings › Display & Sounds › Audio › Surround Sound: **Best Available**.
  Volume Leveler and Dialogue Enhancer **off**.
- **Samsung TV:** Settings › Sound › Expert Settings › **HDMI eARC Mode: Auto**, and **Digital
  Output Audio Format: Pass-Through** (try Auto if anything drops out). Keep Anynet+ on so the
  Fire TV remote controls the soundbar's volume.
- **Soundbar:** **Movie** for films, **Music** with Virtual:X for music, **Direct** for the
  cleanest untouched sound. The 5.1 SE has no up-firing drivers, so its Atmos height is virtual.
- **DTS:** Samsung TVs do not pass DTS over ARC or eARC. Streaming services use Dolby, so this
  only matters for Plex or Kodi files.

## Never rate limited, always fresh

Each service publishes how often it may be asked. Lucent keeps a budget per service, below that
limit, and every request goes through it: a minimum gap, a per-minute allowance, a daily cap
where the service has one, and an immediate back-off when a service says "slow down" (it then
waits exactly as long as the service asks). Requests are conditional, so when nothing changed the
answer is empty and costs almost nothing. Connections are kept open between requests.

| Data | Source | How fresh |
|---|---|---|
| Bluebikes | gbfs.lyft.com | Asked every 10 s, every 5 s while bike finder runs. Bluebikes publishes once a minute, so a change shows within seconds |
| MIT shuttles | passio3.com | Every 10 s. MIT's feed updates every 12 to 16 s |
| MBTA 85 | api-v3.mbta.com | Every 10 s without a key. With a free MBTA key (phone › Settings), a live stream that pushes each change the moment the MBTA has it |
| Planes | opendata.adsb.fi, then api.adsb.lol | Every 5 s. If adsb.fi fails, adsb.lol takes over within its 500-a-day allowance |
| Flight routes | api.adsbdb.com, then hexdb.io | Once per flight, kept for 12 hours |
| Weather | api.open-meteo.com | Every 15 minutes, which is how often it updates |
| Air quality, UV | air-quality-api.open-meteo.com | Hourly |
| Weather alerts | api.weather.gov | Every 2 minutes |
| Calendars | Google, iCloud, Outlook | Every 10 minutes |
| Alexa signal | ntfy.sh | One open connection, only while Alexa is on |

Everything runs while the home screen is up or a phone has the control center open. Behind a
film nothing is fetched except bike finder and the Alexa signal. The phone page's health list
shows, per service, how many requests went out, how many came back unchanged, and whether any
was ever told to slow down. Vestor's LED wall uses the same approach for its flight tracker.

## Will it survive Fire OS updates?

Lucent is an ordinary installed app, so updates and restarts keep it and all its settings, notes
and photos. What an update or a restart can do is switch off its HOME service. Lucent now puts
the service back by itself at every boot (that is what the one-time permission from install.sh
is for). Every time the TV turns on it shows Lucent, within half a second of Amazon's home.

If a future Fire OS blocks the method entirely, HOME simply lands on Amazon's home, nothing breaks,
and Lucent still opens from Your Apps. Running `./install.sh` again is the first thing to try.

**Leaving Lucent:** the first button on the top row opens Amazon's home until you open an app.
Settings › Lucent Opens on HOME › No hands HOME back to Amazon for good, until you open Lucent
from Your Apps and switch it back on.

## Install or update (about 10 minutes, once)

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

   It installs the app, gives it the HOME button and the two one-time permissions below, and
   opens it. Press HOME on the remote to check. It prints the control center's address.

5. **Turn ADB off.** The installer offers to at the end. Later, `./install.sh --adb-off` does
   the same. ADB is the one way into the TV from your network, so leave it off and turn it on in
   Developer Options only to update or undo. Updating is the same `./install.sh`, and everything
   you set up is kept.

Updating from 1.1 keeps your dock order, hidden apps and settings. From the very first build
(signed with the retired key) the installer asks once to replace it, which resets those.

## Undo

```
./install.sh --undo      # HOME goes back to Amazon, the two permissions are taken back. Lucent stays installed
./install.sh --remove    # the same, and Lucent is uninstalled with its notes and photos
```

If another launcher's HOME service (dumbtv or Projectivy) was on before, install paused it and
`--undo` turns it back on.

Worst case, with no computer: a factory reset clears everything Lucent did, because it never
writes to the system partition. It also signs out the Amazon account and removes downloaded
apps, so it is the last resort.

## What it changes, exactly

- Installs one app, `tv.lucent.home`. Its permissions: internet, keeping the screen awake (for a
  note or Alexa), and starting at boot.
- Turns on one accessibility service, "Lucent home button". It looks only at which app and screen
  a new window belongs to, to spot Amazon's home, and it draws the bike alarm, notes and hearts
  over other apps. It is declared unable to read screen content, press keys, read the remote,
  make gestures or take screenshots. Other services, such as VoiceView, stay on throughout.
- Grants two permissions Fire OS has no screen for:
  - **Write secure settings**, used for one thing only: putting Lucent's own HOME service back
    in the enabled list after an update or restart switched it off. Every other service in the
    list is kept as it was.
  - **Media session access** (as a notification listener), used only to see which app is
    playing and to pause it or skip a track. Lucent reads no notifications.
- Sets `min_futurity=0` in `alarm_manager_constants`, so the HOME switch is instant. A previous
  value is saved and restored by `--undo`.
- Exempts the app from Doze, so the switch works after the stick has idled.
- Opens the control center on port 8686, on your home network's address and, if Tailscale runs
  on the TV, its Tailscale address. Never on any other network.

It does not root anything, disable Amazon apps, block updates or change audio settings.

## Security

The goal: nothing on the internet can reach the TV, nothing on your Wi-Fi can use it without a
device you approved on the TV with the remote, and nothing anyone sends it can take it over.

**The control center**

- **Not on the internet.** It answers only devices on the same home network subnet as the TV (or
  your own Tailscale devices). Nothing is opened on your router, and there is no cloud service.
- **Only devices you allowed.** Pairing works only for two minutes after someone opens it on the
  TV, and every new device needs OK pressed on the remote. The QR code holds a one-time secret.
  The six-digit code allows five tries per window, and repeated wrong windows lock pairing for
  fifteen minutes. A paired device holds a long random key, stored on the TV only as a hash.
- **Locked to its own page.** Requests from any other website are refused, even from a browser
  on your Wi-Fi: the server checks the address it was asked for, where the request came from,
  and a custom header other sites cannot send. The page runs no outside code and loads nothing
  from the internet.
- **Limited powers.** A paired phone can do what the page offers and nothing else. It cannot pair
  other devices, change who may connect, reach the accessibility service, or touch other apps.
  Everything it sends is checked against fixed lists and sizes.
- **Plain HTTP on the home network.** The page has no certificate because the TV has no internet
  name. Someone who knows your Wi-Fi password and records the traffic could copy a phone's key and
  do what the page can do: send a note, change a setting. Give guests the guest network (the
  phone page can put its code on the TV), and use Tailscale away from home, which encrypts
  everything. Settings › Paired Phones on the TV forgets every phone at once.

**Everything else**

- **Outgoing only to the hosts above, over HTTPS.** Plain HTTP is refused, and so are certificates
  a user or another app installed. Redirects stay on the same host. Every answer has a size cap
  and a time limit. No identifiers or cookies leave the TV, and the user agent is just "Lucent" (the
  National Weather Service, which asks callers for a contact, also gets this project's GitHub
  address). Weather and planes get positions rounded to a few kilometres.
- **Broken or hostile data cannot take it down.** The parsers are tested against hundreds of
  thousands of broken, truncated and malicious inputs (`preview/tests`): deep nesting, impossible
  numbers, overflowing lengths, zip bombs, calendars with endless repeats. A bad answer skips one
  refresh and the last good data stays on screen.
- **Photos are opened in a sandbox.** Uploaded and pushed pictures are decoded in an isolated
  process with no permissions, network or files, and only Lucent's own re-encoded copy is kept.
- **It cannot trap the TV.** Amazon's home stays the system's HOME underneath. If Lucent crashes
  three times in three minutes (including crashes in native code), HOME goes back to Amazon's home
  by itself for a while, and Lucent still opens from Your Apps.
- **Only you can update it.** Android installs an update over Lucent only if it carries the same
  signature. The installer also checks the APK's certificate before installing and refuses to
  hand the HOME button to anything signed by another key. The key comes as its own file,
  `signing-key-keep-private.zip`, never inside `lucent.zip`. Keep it in a password manager or on
  an encrypted drive, never in a repository or shared folder.
- **ADB is the real door.** While ADB Debugging is on, any computer on your Wi-Fi can ask to
  connect. Turn it off with `./install.sh --adb-off` once HOME works.
- **On the TV:** leave Install unknown apps off for every app, and keep Fire OS updates on. They
  carry Android's security fixes, and Lucent never blocks them. Lucent draws other apps' own
  tile art, so an app you install could in theory carry a booby-trapped picture aimed at an
  unpatched image decoder, which is one more reason to keep updates on.

## Known limits

- HOME shows about 0.3 s of Lucent's blurred wallpaper before the home screen settles in.
- Waking from sleep can show Amazon's home briefly before Lucent takes over.
- Speaking to type depends on the Fire TV keyboard. Fire OS gives apps no speech recognition.
- While a film plays, notes and hearts never take the remote. The bike alarm does, on purpose,
  so that any button stops it. Volume still works.
- Shuttle times are tracked buses only. A line with no tracked bus shows its hours instead,
  from MIT's published schedules (or, for the 85, the MBTA timetable).
- Plain HTTP on the home network, as explained under Security.

## Rebuilding

`./build.sh` builds `build/lucent.apk` with the bare Android tools (aapt2, javac, dx or d8,
zipalign, apksigner). The signing key lives outside this folder: unzip
`signing-key-keep-private.zip` into `~/.lucent/` (or set `LUCENT_KEYSTORE`). The password comes
from `LUCENT_KEYPASS`, the macOS Keychain (`security add-generic-password -s lucent-keystore -a
lucent -w`), or `release.keystore.pass` beside the key. The build refuses to invent a new key
unless `LUCENT_NEW_KEY=1`, because the TV would refuse updates signed by it.

`preview/` renders the real scene code on a desktop JVM from recorded feed fixtures, which is how
the screenshots were made. `preview/tests` checks every feed parser, the calendar reader and the
QR encoder, feeds them hostile input and fuzzes the remote. `assets/web` is the control center's
page, and `web/API.md` describes what it may ask the TV.

## App tiles and logos

Every tile shows artwork from the app itself, read from the app installed on your stick. Lucent
never draws a logo of its own or downloads one. In order, a tile uses the app's TV banner, its
wide logo, a wide launcher icon, or its square icon on a backdrop made from its own colours. To
use a picture of your own for an app, save it as a PNG or JPEG named after the app's package:

```
adb shell mkdir -p /sdcard/Android/media/tv.lucent.home/banners
adb push netflix-art.png /sdcard/Android/media/tv.lucent.home/banners/com.netflix.ninja.png
```

The screenshots in this folder use placeholder tiles, because they were made on a computer
without your apps.

## Weather place and bike dock

The weather place defaults to Cambridge, MA. To change it, or to pin a bike dock by its GBFS
station id:

```
printf 'city=Cambridge\nlat=42.3736\nlon=-71.1097\nbike_station=auto\n' > lucent.conf
adb push lucent.conf /sdcard/Android/media/tv.lucent.home/lucent.conf
```

Lucent reads the file the next time you press HOME, keeps the values privately and deletes it.
Pictures pushed to `/sdcard/Android/media/tv.lucent.home/wallpapers/` join the album through
the same sandbox as uploads.

## Credits

See NOTICE. The HOME guard is adapted from dumbtv (MIT). Type is Inter, Newsreader and Geist
Mono (SIL OFL 1.1). Photographs are CC0 from Wikimedia Commons, as collected by Glass Home (MIT).
Weather and air quality by Open-Meteo (CC BY 4.0), alerts from the National Weather Service,
bike data from Bluebikes, shuttle data from MIT (Passio), bus data from the MBTA, aircraft from
adsb.fi and adsb.lol (ODbL), routes from adsbdb and hexdb.io. Stops, routes and counting rules
follow Vestor.
