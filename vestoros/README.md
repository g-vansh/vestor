# VestorOS

A glass home screen for Fire TV that doubles as the living room's information screen, with
Westgate's bikes, the shuttles and the 85, the weather, planes overhead, your shared calendar,
notes from each other and your own photos on it. You run it from the remote, or from your phones
through a control center the TV serves itself. Built for a Fire TV Stick 4K (2nd generation) on
Fire OS 8.1.8.5, driving a Samsung TV and a VIZIO 5.1 SE soundbar over eARC.

## What 2.0 adds

- **A dashboard that rests.** The home screen is the clock, today and an info bar along the
  bottom. Down brings up the apps, and they go back down by themselves, so nothing covers the
  photo when you are not watching anything.
- **Phone control center.** Scan a code on the TV once and your phone can draw notes that write
  themselves onto the TV, send hearts, upload family photos, keep a shared list and countdowns,
  start bike finder, play and pause music, show guests the Wi-Fi and change any setting.
- **Day and night.** Frosted light glass from an hour after sunrise, warm dark glass from sunset,
  crossfading over half a minute. Change it by hand from the remote or a phone.
- **A screen that stays on all day and dims itself.** No screensaver during the hours you choose,
  a soft dim after ten idle minutes, and an optional bedtime when the TV goes to sleep.
- **Bike alarm.** When bike finder sees an e-bike it rings at full volume until you press any
  button, over whatever is playing, and your phones buzz with a link to reserve it in Bluebikes.
  Any Echo can start it: "Alexa, turn on bike finder".
- **Two docks and a forecast.** Westgate's dock on Vassar Street and the Erie Street dock outside
  Flour, as one pool: bike finder rings for the first e-bike at either. The card says when the
  e-bikes will likely be gone, when the next one should turn up, and in the evening the chance
  of one at 7:30, 8:30 and 9:30 the next morning, from 31 months of Bluebikes trips, MIT's
  calendar and the weather.
- **The Samsung's remote on your phone.** A Remote tab with the TV's own keys, menus and
  settings, over your Wi-Fi, so the Samsung remote can stay in the drawer.
- **Ask Vestor.** Claude on the TV: ask a question, say "play Better Call Saul", or "every
  weekday at 7:15 start the bike finder", from the sparkle on the TV or the phone's Ask box
  (the phone keyboard's microphone turns speech into the question).
- **The grocery shuttle.** On its days, MIT's Trader Joe's and Whole Foods run joins the
  shuttles, live at Tang/Westgate.
- **A Glare button.** One press switches the Samsung to its brightest picture for a sunny
  afternoon, from the remote, a phone or Alexa. The evening picture comes back at sunset.
- **Rain in the next two hours** for Westgate, from NOAA's 15-minute HRRR forecast: "Rain from
  3:45 PM" or "Dry for the next 2 hours".
- **A 4K check.** The Sound card says what the stick is sending the TV (4K, 60 Hz, HDR10) and
  names the one setting to change if 4K or HDR is not getting through.
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

The home screen rests as a dashboard: the clock, today's plans and any pinned note above, and
the info bar along the bottom with the bikes, the weather, the shuttles and planes overhead. The
apps stay out of sight until you ask for them, so nothing sits on top of the photo.

| Remote | Does |
|---|---|
| Down | Bring up the apps |
| Left / Right | Wake the info bar, then move along it. On the apps or the top row, move along those |
| Up | From the apps to the info bar, then to the top row |
| OK on an app | Open it |
| OK on an info card | Open its full panel. Left and Right move between panels |
| OK on the bikes card | Start bike finder and open the Bluebikes panel |
| Hold OK on the bikes card | Start or stop bike finder without opening anything |
| Hold OK on an app, or ☰ | Move, Hide or App Info. ☰ elsewhere opens Settings |
| Play/Pause | Play or pause whatever is playing on the TV |
| Rewind / Fast Forward | The previous or next wallpaper. On the music card, the previous or next track |
| Back | Close what is open. On the apps, jump to the first app, then put the apps away |
| Any button while the alarm rings | Stops it |

The apps go back down by themselves after two idle minutes (Settings › Apps Go Back Down) and
the info bar lets go of its focus after half a minute, so the screen settles back into a quiet
picture that works as the screensaver. For the first few seconds a small "Apps" mark beside the
bar is the reminder that Down brings them up.

The top row, left to right: **Fire TV Home** (the exit: Amazon's home until you open an app),
**Mirror a Screen**, **Phone Control** (pair a phone), **Sound** and **Settings**.

**HOME always comes back here.** Press HOME in Netflix or any app and Fire TV starts to open
Amazon's home, then Vestor's home button service replaces it, behind a frosted cover so you see
the glass settle rather than Amazon's rows. "Alexa, go home" lands here too. Holding HOME still
opens Fire TV's quick menu (sleep, settings, mirroring), and the Fire TV Home tile on the top row
is the way into Amazon's home on purpose. The microphone, volume and the app shortcut buttons stay
Fire TV's own in every app.

Focus an item in the info bar and it grows into a card with the detail. OK grows the card
into a full panel: the weather's next six hours with the chance of rain, the three nearest docks,
every shuttle and bus with the following ones, the week's plans and the shared list, what is
playing, and the other planes in range.

## Phone control center

The TV serves a small web page on your home Wi-Fi. The server runs on the TV itself, inside
Vestor, and Vestor's HOME service keeps it running behind films too, so it answers whenever the
Fire TV is on, with no computer or cloud in between. Nothing is installed on the phone.

**Pairing, once per phone or laptop:**

1. On the TV, press Up twice and choose the phone icon. A code appears for two minutes.
2. Point the phone's camera at the code and open the link. (Or open the address shown under the
   code and type the six digits.)
3. The TV asks "Allow this device?" and shows three digits. If the phone shows the same three,
   press OK on the remote.

The phone stays paired. On an iPhone, Share › Add to Home Screen turns the page into an app icon.
Do that first and pair from the icon: Safari and the icon keep separate keys, and a key that turns
up in two places looks copied, so the TV signs it out. If that happens, pair again from the icon.

**What it does:**

- **Home:** everything the TV shows, live: bikes, buses, shuttles, weather and alerts, planes.
  Start or stop bike finder and silence the alarm. Play, pause and skip what is playing. Bring
  Vestor to the front, switch to Fire TV's home, or put the TV to sleep.
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

"Wallpaper Motion: Still and Sharpest" stops the slow drift so the photo sits pixel for pixel. Fire OS
draws every app's screen at 1920 by 1080 and the stick upscales it to the TV's 4K, so that is
the sharpest any home screen can show a still picture on this stick.

## Day and night

Night starts at sunset and day an hour after sunrise, worked out on the TV for Cambridge, with
no network call. Settings › Look (or the phone) picks Day or Night by hand. A choice made by
hand holds until the next sunrise or sunset change, then the automatic rule takes over again.
At night the photograph dims and warms and the moon's phase joins the line under the clock.

## Glare: a bright picture for sunny afternoons

**First, the Samsung's own settings.** They cost nothing and do the most. From RTINGS' settings
for this TV (the TU7000):

- Settings › General › **Eco Solution**: turn its options off. Power Saving and Motion Lighting
  dim the screen to save power. Auto Power Off lives here too (see below).
- Settings › Picture › Expert Settings › **Brightness** up. On 2020 Samsungs this is the backlight,
  and raising it costs no picture quality.
- **HDR films that look too dark** (Netflix in 4K, for example): while the film plays, raise
  **Brightness** and **Gamma** and set **Contrast Enhancer** to High. RTINGS found Brightness at 5
  with Contrast Enhancer on High noticeably brighter in HDR.

**The Glare button.** One press puts the Samsung in **Dynamic**, its brightest picture mode, and
the next press brings back the picture you had. It is in three places: Settings › Glare on the
TV, the sun button on the phone's Home page, and "Alexa, turn on glare mode" (or turn off). Glare
ends by itself at sunset. Turn on **TV Picture Follows the Sun** and the TV also changes its
picture at sunrise and sunset: Standard by day and Movie at night, or any of the TV's four modes
you pick on the phone. If the TV is off at that moment, Vestor makes the change when it is back.

**Setup, once, about ten minutes with a computer.** The Glare button goes through SmartThings,
Samsung's own cloud, which is the one way an app may change a Samsung's picture mode.

1. On the Mac: `brew install smartthingscommunity/smartthings/smartthings`
2. Run `smartthings apps:create`, sign in with the Samsung account the TV uses, and choose
   **OAuth-In App**. Name it Vestor, pick the scopes `r:devices:*` and `x:devices:*`, and add the
   redirect URI `https://httpbin.org/get`. Copy the Client ID and Client Secret it prints.
3. On the phone page, Settings › Samsung Picture: paste both, tap Continue, then **Open Samsung
   Sign-in** and allow it.
4. The browser lands on a page of plain text from httpbin.org. Copy its address (or only the
   code in it), paste it into the phone page and tap Connect.

What it can reach: Samsung grants those two scopes for every device on the account, so Vestor
looks only for the TV and touches nothing else. The secret and the sign-in stay on the TV, phones
only ever learn "Connected", and Disconnect forgets all of it. The one-time code passes through
httpbin.org once and is useless without the secret. Samsung drops a sign-in nobody used for about
a month, so Vestor renews it every 20 days. To withdraw it from Samsung's side, run
`smartthings apps:delete`.

## The Samsung TV from your phone

The Remote tab on the phone page is the Samsung's own remote: arrows and OK, Back, Home, Menu,
Source, volume, mute, power, numbers and play controls. Menu opens the Samsung's settings, so
every picture, sound and general setting is reachable from the phone. Picture mode, sound mode,
input and apps also have buttons of their own.

It reaches the TV two ways, and works with either or both:

- **Over your Wi-Fi (the keys).** The same channel Samsung's SmartThings app uses for its remote:
  instant, with no limit on presses. Set up once on the phone: Settings › Samsung Remote, type
  the TV's address (on the TV: Settings › General › Network › Network Status › IP Settings) and
  tap Pair. The TV asks once whether to allow Vestor. Choose Allow with the Samsung remote, or,
  if SmartThings is connected, with the Left, Right and OK buttons the phone shows, which go
  through Samsung's cloud. To turn the TV on from the phone too, switch on **Power On with
  Mobile** (Settings › General › Network › Expert Settings). The Fire TV already turns it on
  through HDMI whenever you use the stick.
- **Through SmartThings (the modes).** The Glare setup above. It adds picture mode, sound mode,
  input and app launches, and reports whether the TV is on. Samsung allows 12 commands a minute,
  so the keys go over Wi-Fi.

**Safe by design.** Vestor dials only the TV's address, which must be a private home address,
and only the TV's two remote ports. The TV's certificate is remembered the first time and any
other is refused before the TV's key is sent, so nothing on the network can pose as the TV. The
key stays on the TV, never on a phone. If a Samsung update changes the certificate, the phone
says so and Trust the TV Again pairs afresh. If you ever choose Deny by mistake, allow Vestor on
the TV: Settings › General › External Device Manager › Device Connection Manager › Device List.

**What I need from you: nothing in chat.** Please do not send passwords or keys here. SmartThings
is set up on the phone page with your own app ID, and the sign-in goes straight from your phone
to Samsung and back to the TV. The Wi-Fi remote needs no account at all.

## Staying on all day, dimming, bedtime

Between the awake hours (7:00 to 0:30 unless you change them on the phone) the home screen
keeps the TV on, so Fire TV's screensaver and sleep never take over while you use it as a lamp.
After ten minutes without a button press the dock hides and the screen dims to a quiet glow
(Settings › Dim When Idle). Any button brings it back. Outside the awake hours Fire TV's own
timers apply again.

A **bedtime** set on the phone puts the TV to sleep at that time if nobody has pressed a button
for ten minutes. Once a night: wake it again and it stays awake.

**Will the TV go to sleep by itself in chill mode?** The Fire TV will not: during the awake
hours Vestor holds the screen on, which stops Fire TV's screensaver and its sleep timer. The
Samsung can, so change one setting on it:

- **Samsung:** Settings › General › Eco Solution › **Auto Power Off: Off**. It is on from the
  factory, and it turns the TV off when the Samsung's own remote has not been touched for four
  hours (the shortest choice on this model), after a five-minute warning. The Fire TV remote does
  not count as touching it. Keep **Anynet+ (HDMI-CEC)** on so the Fire TV can still turn the TV
  on and off.
- **Fire TV:** Fire OS 8 can ask "Are you still watching?" after about four hours of a film
  without a button press. Any button answers it.

## Bike finder and the alarm

1. Press Up once from the dock, move to the bikes card and press OK. Or tap Find on the phone,
   or ask Alexa.
2. Vestor reads the live status of Westgate's dock and the Erie Street dock outside Flour every
   5 seconds. The card shows a radar and how long it has been looking. Go and watch something.
3. When an e-bike can be rented at either dock, the alarm rings and a card names the dock ("E-bike
   ready at Westgate" or "at Erie St"), over whatever is playing. If both have one, Westgate wins.
   It rings until you press any button on the remote, or tap Silence on the phone, or for at
   most ten minutes (Settings › Alarm Rings For). "When an E-Bike Is Found: Chime Once" gives
   the old single chime instead.
4. It gives up after 30 minutes (Settings › Bike Finder Gives Up, up to 2 hours).

Only bikes the station will actually rent count. Westgate's dock is the one MIT opened in front
of the Westgate low-rise buildings on Vassar Street ("MIT Vassar St" in the feed). The second is
the Erie Street dock by Flour in Cambridgeport. Vestor finds both by name or position, so a
rename does not break them. Settings › Bike Station follows another first dock, and Settings ›
Also Watch (on the TV or the phone) picks the second, or none.

### The forecast

With e-bikes at either dock, the card says when they will likely all be gone ("Likely all gone by
8:46 AM (8:14 to 9:53)") and when Westgate alone will be. With none, when the next one should
turn up at either. In the evening it gives the chance of one at 7:30, 8:30 and 9:30 the next
morning. The
middle time is the likeliest, the range covers four times in five.

It comes from every Bluebikes trip at these docks since March 2024, the MIT Registrar's calendar
(class days, finals, IAP, breaks, holidays), the hour's chance of rain and the temperature, and
Lyft's restock patterns, and runs on the TV in a fraction of a second. Tested on 34 days of real
mornings it had never seen, the range caught the real time 81% to 93% of the time, except
weekend run-outs (71%), which come sooner than it expects. `analysis/bikes/README.md` has the
data, the method, MIT's measured effects (33% more e-bike trips on class days, 17% fewer in a wet
hour) and every test.


The alarm rings at one steady level from the first second, a little under full scale so the
soundbar never clips, and the soundbar's volume decides how loud that is.

## Phone alerts, and reserving the bike

When bike finder finds an e-bike, every phone you set up buzzes with "E-bike ready at Westgate".
Tapping it opens the Bluebikes app, where **Reserve** holds the bike for up to 10 minutes.

1. On the phone page, Settings › Phone Alerts › On.
2. Install **ntfy** from the App Store (free), tap +, and subscribe to the topic the page shows.
   Do it on both phones. **Send a Test** checks it.

ntfy.sh carries the alert to Apple's notification service. The topic is a long random name that
works as its password. Anyone who learned it could read the alerts or send you fake ones, which
is all a topic allows, and New Topic replaces it.

**Could Vestor reserve the bike by itself?** Every route I could find, and why the alert is the
one that ships:

- **Bluebikes and Lyft publish no reservation API.** The public feed (GBFS) is read-only: station
  counts and a link into the app. Nothing in it acts on an account.
- **The app's private interface** could be copied, but the TV would have to hold your Lyft
  sign-in, it breaks Lyft's terms (which can close the account), and every reserved minute is
  billed, so a bug would cost money. Not built.
- **Phone automation:** I found no Siri or Shortcuts action for Bluebikes, so a shortcut can
  open the app but cannot press Reserve.
- **What works:** the alert, one tap into Bluebikes, and Reserve. The reserved bike's screen says
  "reserved" and nobody else can undock it. A reserved minute costs the same as a minute ridden
  ($0.07 on the $5 Boston Bikes Pass), per the City of Boston's Bluebikes FAQ.

## Ask Vestor: Claude on the TV

Press Up to the top row and choose the sparkle (far left), or use the Ask box at the top of the
phone page. Type, or speak: on the iPhone, tap the microphone on the keyboard. On the TV, the
Fire TV keyboard's microphone works on some models. The remote's own microphone button always
belongs to Alexa, so it cannot reach Vestor. The answer shows on a glass card on the TV (when
Vestor is in front, never over a film) and on the phone.

**What it can do**

- **Answer questions**, searching the web when it needs to ("What's the famous brass town in
  India?", "Who's ahead in the World Series?"), with the sites it used at the bottom.
- **Answer about the room**: it sees what the home screen shows, so "When will the e-bikes run
  out?", "Is it going to rain before 6?", "When's the next Tech Shuttle?" and "What's on our
  calendar tonight?" work.
- **Play things**: "Play Better Call Saul" finds the title and opens it in Netflix. YouTube videos
  and Spotify playlists, albums and songs by name work the same way, and Prime Video, Disney+,
  Max, Hulu, Peacock, Paramount+ and Apple TV open at the title's page where those apps allow it.
- **Run the house**: start or stop the bike finder, glare on or off, the day or night look, the
  guest Wi-Fi code, hearts, play and pause, volume, the Samsung's picture or sound mode and input,
  and a note on the home screen.
- **Automations**: "Every weekday at 7:15 start the bike finder", "At sunset switch the TV to
  Movie", "Weekends at 9 put up a note: farmers market". They run on the TV each day on their
  own, with no AI involved, and the phone lists them with a switch and a delete for each.

**What it won't do.** Buy, rent, sign in, message anyone or change an account. After it reads a
web page in a question it only plays titles and opens apps: a change to the TV or a new automation
needs you to ask for it on its own, so nothing a web page says can steer the TV.

**Cost.** Every question starts on Claude Haiku 5.5, the cheapest model. Haiku asks Opus 5.5,
Anthropic's most capable model, for advice only when a question needs real reasoning, and Vestor
retries on Sonnet 5.5 if Haiku stumbles. Measured on this build:

| Kind of question | Cost | Time |
|---|---|---|
| Plain question or command ("15% of 84", "start the bike finder") | 0.01 to 0.08 cents | about 2 s |
| Long plan, comparison or explanation | about 0.1 cents | 8 to 15 s |
| Needs the web, including finding a title to play | about 1.2 cents (1 cent is the search fee) | 5 to 10 s |
| Hard question where Haiku consults Opus (it rarely needs to) | 4 to 8 cents | 10 to 20 s |

So a dollar covers more than a thousand everyday questions.

**Prompt caching** is on in every request, with nothing to set up. About 6,000 tokens of each
request never change: Vestor's instructions and the descriptions of its tools and of web search.
Anthropic keeps them ready for five minutes after each use and charges a tenth of the normal price
to read them back, so a question within five minutes of the last costs about 0.01 cents. Inside one
question, the round after a search or an action reads the first round back the same way: for "Play
Better Call Saul" that round fell from 0.08 cents to 0.02, and on Opus from about 3.5 cents to 0.3.
The cache holds no text, only working data in memory that Anthropic deletes once it expires. If you would rather pay more for the
strongest model every time, choose "Always the best model" on the phone under Settings › Claude:
each answer then runs on Opus 5.5, about 1 to 4 cents, or 5 to 10 with a search. Vestor prices
every answer as it arrives and stops for the month at your cap (40 dollars unless you change it on
the phone, up to 100), with at most 150 questions a day and 6 a minute. The phone shows the
month's total and each answer's model and cost.

**Setup, once.** Either run the installer with the key in a file: `./install.sh --claude-key
claude-key.txt` (or `VESTOR_CLAUDE_KEY=claude-key.txt ./install.sh` while updating), or paste it
on the phone page under Settings › Claude. The key then lives only in Vestor's private storage on
the TV and goes only to Anthropic. Phones see its last four characters, never the key.

**Privacy.** When you ask, the TV sends Anthropic your question, the last few questions and
answers, and what the TV knows right now: the time, both docks' bike counts and forecast, the
finder's state, the weather, shuttle times, your next few calendar entries by title and time,
what's playing, your installed app names, the Samsung's inputs and picture and sound modes, your
automations and the wallpaper look. Questions asked from any phone or the TV share that same short
memory. Web searches use your rough location (Cambridge, MA). Nothing from your phones themselves
goes out. Anthropic does not train on API traffic and deletes it within 30 days
([Anthropic privacy center](https://privacy.claude.com/en/articles/7996866-how-long-do-you-store-my-organization-s-data)).

**Spoken questions without a phone in hand.** If your stick has Alexa+ (free on supported Fire TVs
in the US since August), the remote's microphone already answers general questions. Ask Vestor is
for what Alexa can't see: your bikes, shuttles, plans and this TV's own controls.

## Alexa and the Echos

**What works with nothing set up.** In the Alexa app, Devices › your Echo › Settings, link the
living room Echo to the Fire TV (same Amazon account). Then from the Echo: "Alexa, open Netflix",
"Alexa, pause", "Alexa, play", "Alexa, go home" (which lands on Vestor), "Alexa, volume up", and
"Alexa, turn off the TV". The microphone button on the Fire TV remote takes the same requests.

**Vestor's own commands, from any Echo in the house.** Alexa cannot open a sideloaded app's
features by voice, so each command is a small virtual switch that sends a private signal to the
TV:

| Say | Does |
|---|---|
| "Alexa, turn on bike finder" (or off) | Wakes the TV, brings Vestor up and starts bike finder. Off stops it and the alarm |
| "Alexa, turn on glare mode" (or off) | The Samsung's bright picture, or back |
| "Alexa, turn on night look" (or off) | The evening look now. Off goes back to following the sun |
| "Alexa, turn on Vestor" (or off) | Brings Vestor to the front. Off opens Fire TV's own home |
| "Alexa, turn on guest Wi-Fi" (or off) | Shows the guest Wi-Fi code on the TV, or puts it away |
| "Alexa, turn on hearts" | Hearts float up the screen |

Setup, once:

1. On the phone page, Settings › Alexa › On. It lists each command with its two addresses.
2. At virtualsmarthome.xyz, sign in with your Amazon account, open **URL Switch**, and add one
   switch per command with the name in the table (Bike Finder, Glare Mode and so on). Paste the
   "on" address and, where there is one, the "off" address. The Copy buttons on the phone page
   save typing.
3. In the Alexa app, enable the **URL Switch** skill and let it discover devices.

"Alexa, turn on bike finder" works straight away. For your own phrase ("Alexa, find e-bike"),
make a routine in the Alexa app: when you say it, turn on Bike Finder.

The signal travels through ntfy.sh, a public relay, and the TV listens on one private topic. The
addresses hold a random name that works as their password, so treat them like one. Only the
words above do anything, each message counts once and only within two minutes, and Settings ›
Alexa › New Link replaces the name if it ever leaks.

## Shared calendar

Vestor reads calendars from their private iCal links, every ten minutes, and shows the week in
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
  Appstore, and Vestor's Mirror button opens it.
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

**Spotify and YouTube Music have no Dolby Atmos or surround music**, on any device, so neither
can send the VIZIO Atmos. What each does at its best here:

- **Spotify:** Premium includes Lossless (FLAC up to 24-bit/44.1 kHz) since September 2025, on
  phones, computers and some Connect speakers. Spotify does not list TV apps, and I found no
  confirmation that the Fire TV app plays lossless, so expect its Very High setting there. Play
  from the phone with Spotify Connect to the Fire TV, which keeps the sound on HDMI into the
  soundbar. Skip Bluetooth from the phone straight to the soundbar: it compresses the sound again.
- **YouTube Music:** Premium tops out at 256 kbps. Turn on Always High in its audio quality
  setting. Ordinary YouTube videos play in 5.1 on Fire TV when the video has it, which some
  concerts and live sets do.
- **Honestly, the soundbar sets the ceiling.** VIZIO rates the 5.1 SE from 50 Hz to 16 kHz, so
  the step from Spotify's 320 kbps to lossless falls mostly above what it plays. Room placement
  and the settings below change the sound far more.
- **For real Atmos music**, the one service whose Fire TV app plays Dolby Atmos on this stick is
  **Amazon Music Unlimited**: in its settings turn on Ultra HD and Spatial Audio. The soundbar
  decodes it and creates the height virtually, since it has no up-firing speakers.
- **To make stereo fill the room**, put the VIZIO in **Music** mode with **Virtual:X on** and the
  surround level up. If music still sounds front-heavy, compare Fire TV's Surround Sound set to
  Best Available with Dolby Digital Plus: in some setups the Dolby MAT stream Best Available
  produces keeps the soundbar from spreading stereo out.

The Now Playing card shows what is playing in any app with a media session (Spotify, YouTube,
Amazon Music, Plex), with the time left. Play/Pause on the remote and the phone control it.

**Headphones.** Settings › Bluetooth Headphones opens Fire TV's pairing screen. When AirPods
connect, a card says so and the Sound button shows their name. Fire TV sends sound to one
Bluetooth device at a time, so two pairs of AirPods cannot both listen to the stick. Two ways
round it: an iPhone's Share Audio (for something playing on the iPhone), or a dual-link Bluetooth
transmitter on the TV's optical output, which feeds two pairs at once while the soundbar rests.

## Getting the best sound from the VIZIO

**What Vestor can change on the soundbar: volume and mute.** Up, down and mute from the remote
or the phone go from the Fire TV to the TV and on to the VIZIO over HDMI. The 5.1 SE has no
Wi-Fi and came without a remote of its own. Its modes, bass, treble, center, surround, height and
Virtual:X are set only in VIZIO's phone app, over Bluetooth, and VIZIO publishes nothing about
that connection. It also takes one controlling device at a time, so a TV app holding it would
lock your phone out. No account or key from you would change that, so Vestor leaves those to the
VIZIO app. Set them once, below, and they stay.


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

## The best picture: 4K and HDR

Fire TV draws every app's screen, Vestor's included, at 1920 by 1080, and films go to the TV
at the full 4K. So menus look the same whatever you set, and films are where 4K shows. Three
settings let the whole picture through:

1. **Samsung, on the Fire TV's input:** Settings › General › External Device Manager › **Input
   Signal Plus** › on for **HDMI 1**. Without it that port takes 4K only in 8-bit colour, so HDR
   never reaches the screen.
2. **Fire TV:** Settings › Display & Sounds › Display › **Video Resolution: Auto** and **Dynamic
   Range Settings: Adaptive**, which sends HDR only when the film has it. **Match Original Frame
   Rate: On** plays films at their own 24 frames a second, so slow pans move evenly.
3. **The cable:** the one from the Fire TV box, or any Premium High Speed HDMI cable, straight
   into HDMI 1. The VIZIO stays on HDMI 2 (eARC).

To check, open **Sound** on the top row. Under Picture over HDMI it shows what the stick is
sending, for example "4K, 60 Hz, HDR10, HDR10+ and HLG", and when something is off it names the
setting to change. The phone page shows the same line. The TU7000 takes HDR10, HDR10+ and HLG.
No Samsung TV takes Dolby Vision, so Netflix and Prime Video send HDR10 or HDR10+.

## Weather for Westgate

The forecast is for Westgate itself (sent rounded to about 2 km), from Open-Meteo, which is free
and needs no key. For Boston it already blends the best free models there are:

- **NOAA's HRRR** for the next day and a half: 3 km detail, a new run every hour, and real
  15-minute steps. That is where "Rain from 3:45 PM" on the weather card comes from.
- **NOAA's National Blend of Models** for each hour's chance of rain. The National Weather
  Service's own forecasts start from the same blend.
- **GFS** beyond that, and **National Weather Service alerts** for Cambridge every two minutes.

Why this over the others: the Weather Service's own forecast is built from the same blend and is
free too, but it is redone a few times a day and has no 15-minute steps. Apple's WeatherKit needs
a paid developer account, and Tomorrow.io and OpenWeather want a key, with a tight daily cap or a
card on file. The one thing more exact would be a weather station on your own balcony.

## Never rate limited, always fresh

Each service publishes how often it may be asked. Vestor keeps a budget per service, below that
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
| Weather | api.open-meteo.com | Every 15 minutes. NOAA's HRRR runs hourly, with 15-minute rain steps |
| Air quality, UV | air-quality-api.open-meteo.com | Hourly |
| Weather alerts | api.weather.gov | Every 2 minutes |
| Calendars | Google, iCloud, Outlook | Every 10 minutes |
| Alexa signal | ntfy.sh | One open connection, only while Alexa is on |
| Phone alerts | ntfy.sh | Only when bike finder finds a bike |
| Samsung picture | api.smartthings.com | Only when Glare or the day and night picture changes, and a sign-in renewal every 20 days |

Everything runs while the home screen is up or a phone has the control center open. Behind a
film nothing is fetched except bike finder and the Alexa signal. The phone page's health list
shows, per service, how many requests went out, how many came back unchanged, and whether any
was ever told to slow down. Vestor's LED wall uses the same approach for its flight tracker.

## Will it survive Fire OS updates?

Vestor is an ordinary installed app, so updates and restarts keep it and all its settings, notes
and photos. What an update or a restart can do is switch off its HOME service. Vestor now puts
the service back by itself at every boot (that is what the one-time permission from install.sh
is for). Every time the TV turns on it shows Vestor, within half a second of Amazon's home.

If a future Fire OS blocks the method entirely, HOME simply lands on Amazon's home, nothing breaks,
and Vestor still opens from Your Apps. Running `./install.sh` again is the first thing to try.

**Leaving Vestor:** the first button on the top row opens Amazon's home until you open an app.
Settings › Vestor Opens on HOME › No hands HOME back to Amazon for good, until you open Vestor
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

**Coming from Lucent.** VestorOS is Lucent under its new name, so Android sees a new app and it
starts fresh. The installer copies any wallpapers and app banners you pushed for Lucent, then
switches Lucent off once VestorOS has HOME (it is not removed, so nothing is lost). Afterwards,
once: pair your phones again, allow VestorOS when the Samsung asks, put the Claude key on with
`./install.sh --claude-key claude-key.txt`, and add back calendar links, the SmartThings token,
phone alerts and the Alexa links. Lucent's notes and photos stay inside it. `adb uninstall
tv.lucent.home` removes it for good, and `adb shell pm enable tv.lucent.home` turns it back on.

## Undo

```
./install.sh --undo      # HOME goes back to Amazon, the two permissions are taken back. Vestor stays installed
./install.sh --remove    # the same, and Vestor is uninstalled with its notes and photos
```

If another launcher's HOME service (dumbtv or Projectivy) was on before, install paused it and
`--undo` turns it back on.

Worst case, with no computer: a factory reset clears everything Vestor did, because it never
writes to the system partition. It also signs out the Amazon account and removes downloaded
apps, so it is the last resort.

## What it changes, exactly

- Installs one app, `tv.vestor.home`. Its permissions: internet, keeping the screen awake (for a
  note or Alexa), and starting at boot.
- Turns on one accessibility service, "Vestor home button". It looks only at which app and screen
  a new window belongs to, to spot Amazon's home, and it draws the bike alarm, notes and hearts
  over other apps. It is declared unable to read screen content, press keys, read the remote,
  make gestures or take screenshots. Other services, such as VoiceView, stay on throughout.
- Grants two permissions Fire OS has no screen for:
  - **Write secure settings**, used for one thing only: putting Vestor's own HOME service back
    in the enabled list after an update or restart switched it off. Every other service in the
    list is kept as it was.
  - **Media session access** (as a notification listener), used only to see which app is
    playing and to pause it or skip a track. Vestor reads no notifications.
- Sets `min_futurity=0` in `alarm_manager_constants`, so the HOME switch is instant. A previous
  value is saved and restored by `--undo`.
- Exempts the app from Doze, so the switch works after the stick has idled.
- If Lucent (this app's earlier name) is on the TV: copies the wallpapers and banners you pushed
  for it, switches its HOME service off, and once VestorOS has HOME, switches Lucent itself off
  with `pm disable-user`. Its data stays in it until you uninstall it.
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
  TV, and every new device needs OK pressed on the remote. The TV and the phone both show the same
  three digits, so you can tell which request you are allowing. The QR code holds a one-time
  secret that never crosses the network: the phone proves it has it. The six-digit code turns off
  after five wrong tries from one device (or twenty in all) and stays off for fifteen minutes
  after three bad windows in an hour, while scanning keeps working. OK counts only after the card
  has been up for a second, so a press meant for something else cannot allow a device.
- **Keys that change every minute.** A paired device holds a long random key, stored on the TV
  only as a hash. Each answer hands the phone a fresh one, and the old one keeps working for 30
  seconds. If an old key ever comes back, it was copied: the TV signs that device out at once and
  says so on screen.
- **Hard to tie up.** A request has five seconds to arrive in full, and each device gets only a
  few connections at a time, so a slow or flooding device cannot keep the page from your phone.
- **Locked to its own page.** Requests from any other website are refused, even from a browser
  on your Wi-Fi: the server checks the address it was asked for, where the request came from,
  and a custom header other sites cannot send. The page runs no outside code and loads nothing
  from the internet.
- **Limited powers.** A paired phone can do what the page offers and nothing else. It cannot pair
  other devices, change who may connect, reach the accessibility service, or touch other apps.
  Everything it sends is checked against fixed lists and sizes.
- **Plain HTTP on the home network.** The page has no certificate because the TV has no internet
  name. Someone on your Wi-Fi who records the traffic could copy a phone's key and do what the
  page can do (send a note, change a setting) until the key changes, which is within about a
  minute and a half, and the copy then signs that phone out. Give guests the guest network (the
  phone page can put its code on the TV). **If the TV shares a network you do not run, such as
  MIT's or the building's Wi-Fi, set Phone Control Works On to Tailscale Only**, which encrypts
  everything and answers only your own devices. Settings › Paired Phones forgets every phone at
  once.

**Everything else**

- **Outgoing only to the hosts above, over HTTPS.** Plain HTTP is refused, and so are certificates
  a user or another app installed. Redirects stay on the same host. Every answer has a size cap
  and a time limit. No identifiers or cookies leave the TV, and the user agent is just "Vestor"
  (the National Weather Service, which asks callers to name themselves, sees "Vestor/2.0, home
  screen for Fire TV"). Weather and planes get positions rounded to a few kilometres.
- **The Samsung sign-in stays on the TV.** The SmartThings secret and tokens go only to Samsung,
  phones never see them, and Disconnect forgets them.
- **The Claude key stays on the TV.** It goes only to api.anthropic.com, phones see its last four
  characters, and install.sh hands it over ADB to a receiver that only ADB's shell may call.
  Pasting it on the phone page sends it once over your Wi-Fi like any setting, so prefer the
  installer if the TV shares a network you do not run. Claude acts only through fixed, checked
  tools, and a monthly cap bounds what it can spend.
- **Broken or hostile data cannot take it down.** The parsers are tested against hundreds of
  thousands of broken, truncated and malicious inputs (`preview/tests`): deep nesting, impossible
  numbers, overflowing lengths, zip bombs, calendars with endless repeats. A bad answer skips one
  refresh and the last good data stays on screen.
- **Pictures are opened in a sandbox.** Every picture that did not ship inside the app (uploads,
  pushed photos, album wallpapers and your own tile art) is decoded in an isolated process with no
  permissions, network or files. Only Vestor's own re-encoded copy or plain pixels come back.
- **It cannot trap the TV.** Amazon's home stays the system's HOME underneath. If Vestor crashes
  three times in three minutes (including crashes in native code), HOME goes back to Amazon's home
  by itself for a while, and Vestor still opens from Your Apps.
- **Only you can update it.** Android installs an update over Vestor only if it carries the same
  signature. The installer also checks the APK's certificate before installing and refuses to
  hand the HOME button to anything signed by another key. The key comes as its own file,
  `signing-key-keep-private.zip`, never inside `vestoros.zip`. Keep it in a password manager or on
  an encrypted drive, never in a repository or shared folder.
- **ADB is the real door.** While ADB Debugging is on, any computer on your Wi-Fi can ask to
  connect. Turn it off with `./install.sh --adb-off` once HOME works.
- **On the TV:** leave Install unknown apps off for every app, and keep Fire OS updates on. They
  carry Android's security fixes, and Vestor never blocks them. Vestor draws other apps' own
  tile art, so an app you install could in theory carry a booby-trapped picture aimed at an
  unpatched image decoder, which is one more reason to keep updates on.

## Known limits

- HOME shows about 0.3 s of Vestor's blurred wallpaper before the home screen settles in.
- Waking from sleep can show Amazon's home briefly before Vestor takes over.
- Speaking to type depends on the Fire TV keyboard. Fire OS gives apps no speech recognition.
- While a film plays, notes and hearts never take the remote. The bike alarm does, on purpose,
  so that any button stops it. Volume still works.
- Shuttle times are tracked buses only. A line with no tracked bus shows its hours instead,
  from MIT's published schedules (or, for the 85, the MBTA timetable).
- Plain HTTP on the home network, as explained under Security.
- The e-bike forecast is least sure about weekend run-outs, which tend to come sooner than it
  says. Lyft can change its restock rounds at any time, and the next-e-bike forecast learns that
  only when the model is refitted (`analysis/bikes`).
- The soundbar's modes and EQ stay in VIZIO's app (see Getting the best sound from the VIZIO).
- Ask Vestor starts playback reliably on Netflix and YouTube. Other apps open at the title's page,
  or at their home if they take no links. Spotify plays what it can find on the web (public
  playlists, albums, songs). Your own private playlists would need Spotify's account link, which
  is not built.
- The grocery shuttle's live minutes depend on MIT's feed labelling its trips. Without them the
  line shows the next departure from Kendall from MIT's schedule.

## Rebuilding

`./build.sh` builds `build/vestoros.apk` with the bare Android tools (aapt2, javac, dx or d8,
zipalign, apksigner). The signing key lives outside this folder: unzip
`signing-key-keep-private.zip` into `~/.vestor/` (or set `VESTOR_KEYSTORE`). The password comes
from `VESTOR_KEYPASS`, the macOS Keychain (`security add-generic-password -s vestor-keystore -a
vestor -w`), or `release.keystore.pass` beside the key. The build refuses to invent a new key
unless `VESTOR_NEW_KEY=1`, because the TV would refuse updates signed by it.

`preview/` renders the real scene code on a desktop JVM from recorded feed fixtures, which is how
the screenshots were made. `preview/tests` checks every feed parser, the calendar reader and the
QR encoder, feeds them hostile input and fuzzes the remote. `assets/web` is the control center's
page, and `web/API.md` describes what it may ask the TV.

## App tiles and logos

Every tile shows artwork from the app itself, read from the app installed on your stick. Vestor
never draws a logo of its own or downloads one. In order, a tile uses the app's TV banner, its
wide logo, a wide launcher icon, or its square icon on a backdrop made from its own colours. To
use a picture of your own for an app, save it as a PNG or JPEG named after the app's package:

```
adb shell mkdir -p /sdcard/Android/media/tv.vestor.home/banners
adb push netflix-art.png /sdcard/Android/media/tv.vestor.home/banners/com.netflix.ninja.png
```

The screenshots in this folder use placeholder tiles, because they were made on a computer
without your apps.

## Weather place and bike dock

The weather place defaults to Cambridge, MA. To change it, or to pin a bike dock by its GBFS
station id:

```
printf 'city=Cambridge\nlat=42.3556\nlon=-71.1040\nbike_station=auto\n' > vestor.conf
adb push vestor.conf /sdcard/Android/media/tv.vestor.home/vestor.conf
```

Vestor reads the file the next time you press HOME, keeps the values privately and deletes it.
Pictures pushed to `/sdcard/Android/media/tv.vestor.home/wallpapers/` join the album through
the same sandbox as uploads.

## Credits

See NOTICE. The HOME guard is adapted from dumbtv (MIT). Type is Inter, Newsreader and Geist
Mono (SIL OFL 1.1). Photographs are CC0 from Wikimedia Commons, as collected by Glass Home (MIT).
Weather and air quality by Open-Meteo (CC BY 4.0), alerts from the National Weather Service,
bike data from Bluebikes, shuttle data from MIT (Passio), bus data from the MBTA, aircraft from
adsb.fi and adsb.lol (ODbL), routes from adsbdb and hexdb.io. Stops, routes and counting rules
follow Vestor.
