package tv.vestor.home;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The whole home screen: layout, focus, motion and drawing. It knows nothing about Android, so
 * the desktop preview renders exactly what the TV renders.
 *
 * Design rules, after Apple's Liquid Glass guidance: glass is only the functional layer (dock,
 * glance bar, top controls, sheets) floating over the photograph, never glass on glass; one
 * material everywhere, adapting to the light underneath; corners concentric (inner radius =
 * outer radius minus padding); colour comes from the wallpaper, the apps and the data, not from
 * tinting the chrome; focus is shown by lift, light and a fill rather than an outline.
 *
 * The type is editorial, after the sites the owner chose (analoguegroup.org, worksinprogress.co,
 * landonorris.com, therevivalfund.com, apple.com): a serif (Newsreader Display) for the clock and
 * the big numbers, a grotesk (Inter) for everything you read, small mono capitals (Geist Mono)
 * for labels, tight leading on display type and generous space around it.
 *
 * Two looks, day and night ({@link Tone}): frosted light glass from an hour after sunrise, warm
 * dark glass and a dimmed photograph from sunset, crossfading over half a minute.
 *
 * Live information follows visionOS's two levels of detail: the glance bar above the dock shows
 * one line per subject that reads from the couch; focus grows a segment into a card, and OK
 * grows the card into a full panel. Each step is the same pane of glass changing shape.
 */
final class Scene {
    interface Host {
        void launch(Model.App app);

        void appInfo(Model.App app);

        void fireSettings();

        void amazonHome();

        /** A setting changed; the host stores it and reacts (new wallpaper, units, ...). */
        void option(String key, String value);

        /** The dock order after a move, by app id. */
        void order(List<String> ids);

        void hide(String id);

        void unhideAll();

        /** Navigation feedback; the host plays the system's sound if the user has it on. */
        void sound(boolean click);

        /** Start bike finder on the followed station. */
        void findBike();

        void stopFinding();

        /** Clear a finished search from the bikes card. */
        void dismissFinder();

        /** Stop the bike alarm ringing. */
        void silenceAlarm();

        /** {@link #MEDIA_TOGGLE}, {@link #MEDIA_NEXT} or {@link #MEDIA_PREV} for what is playing. */
        void media(int what);

        /** The next (1) or previous (-1) wallpaper. */
        void wallpaper(int dir);

        /** Fire TV's own mirroring, for Android phones and Windows PCs. Apple devices use the Samsung's AirPlay. */
        void mirror();

        /** Opens or closes the window for pairing a phone. */
        void pairing(boolean open);

        /** OK (allow) or Back (refuse) on "Allow this phone?". */
        void decide(String id, boolean allow);

        /** A settings sheet action row other than the built-in ones. */
        void action(String key);
    }

    static final int LEFT = 1, RIGHT = 2, UP = 3, DOWN = 4, OK = 5, LONG_OK = 6, BACK = 7, MENU = 8,
            PLAY = 9, REWIND = 10, FORWARD = 11, LONG_MENU = 12;
    static final int MEDIA_TOGGLE = 0, MEDIA_NEXT = 1, MEDIA_PREV = 2;

    private static final int NONE = 0, SETTINGS = 1, TILE_MENU = 2, AUDIO = 4, MOVE = 5, PANEL = 6, PAIR = 7, ASK = 8,
            NOTE = 9, MIRROR = 10, WIFI = 11, ALARM = 12, ANSWER = 13, MAP = 14;
    private static final int DOCK = 0, TOP = 1, GLANCE = 2;
    /** Top controls, left to right. */
    /** The settings sheet's width in dp. */
    private static final float SHEET_W = 510;
    private static final int T_ASK = 0, T_CALL = 1, T_EXIT = 2, T_MIRROR = 3, T_PHONE = 4, T_MAP = 5, T_AUDIO = 6, T_GEAR = 7,
            T_COUNT = 8;
    private static final String[] TOP_NAMES = {"Ask Vestor", "Video Calls", "Fire TV Home", "Mirror a Screen", "Phone Control", "Bike Map",
        "Sound", "Settings"};
    /** The bike map closes itself after this long with no key pressed, since it draws every frame. */
    static final long MAP_IDLE_MS = 10 * 60000L;
    private static final String[] MENU_ROWS = {"Move", "Hide from Home", "App Info"};

    /** Light text drawn straight on the photograph, whatever the look (the scrim keeps it legible). */
    private static final int ON_PHOTO = 0xFFF5F1EA;

    // Gradient constants: colours at full strength, opacity passed separately (see Gfx).
    private static final int[] TOP_LIGHT = {0x24FFFFFF, 0x0DFFFFFF, 0x08FFFFFF};
    private static final float[] TOP_LIGHT_STOPS = {0f, 0.45f, 1f};
    private static final int[] RIM = {0xA6FFFFFF, 0x24FFFFFF, 0x0AFFFFFF, 0x1AFFFFFF, 0x66FFFFFF};
    private static final float[] RIM_STOPS = {0f, 0.26f, 0.5f, 0.74f, 1f};
    private static final int[] GLARE = {0x00FFFFFF, 0x30FFFFFF, 0x00FFFFFF};
    private static final float[] GLARE_STOPS = {0f, 0.5f, 1f};
    private static final int[] TILE_RIM = {0xB3FFFFFF, 0x26FFFFFF, 0x66FFFFFF};
    private static final int[] TILE_RIM_IDLE = {0x40FFFFFF, 0x10FFFFFF, 0x24FFFFFF};
    private static final float[] THREE_STOPS = {0f, 0.5f, 1f};
    private static final int[] FALLBACK_SKY = {0xFF0E1A2E, 0xFF1C2350, 0xFF07080D};
    private static final int[] EDGE_GLINT = {0x00FFFFFF, 0xD9FFFFFF, 0x00FFFFFF};
    private static final int[] RULE = {0x00F5F1EA, 0xCCF5F1EA, 0x00F5F1EA};
    private static final int[] SCRIM = {0x99000000, 0x52000000, 0x00000000};
    private static final float[] SCRIM_STOPS = {0f, 0.5f, 1f};

    final Host host;

    // Layout, in pixels.
    private float d = 2f;
    private int width = 1920, height = 1080;
    private float side, top, dockL, dockT, dockR, dockB, dockRad, innerL, innerR;
    private float tileW, tileH, tileGap, tileRad, tileTop, capH;
    private float barL, barB, barH;

    // Content.
    private final List<Model.App> apps = new ArrayList<>();
    private Model.Wall wall, oldWall;
    private Model.Weather weather;
    private Model.Audio audio;
    private Model.Picture picture;
    private Model.Air air;
    private List<Model.Alert> alerts = new ArrayList<>();
    final Cards cards = new Cards();
    private boolean skyOn = true;
    private String time = "", ampm = "", date = "", city = "Cambridge";
    private int hour = 12;
    private long sunrise, sunset;
    private double moon;
    private final List<Model.Option> options = new ArrayList<>();
    // Each settings row's title and value as drawn, cut to sit side by side, and what they were cut from.
    private String[] rowTitle = new String[0], rowValue = new String[0], rowTitleFrom = new String[0], rowValueFrom = new String[0];
    private float[] rowValueSize = new float[0];
    private boolean reduced, still, weatherOn = true;
    private long ambientAfter = 5 * 60000L, dimAfter = 10 * 60000L;
    private String hint = "";

    // Moments.
    private Notes.Note pinned, popNote;
    private Ink pinnedInk, popInk;
    private long noteAt;
    private String heartKind = "", heartFrom = "";
    private long heartsAt = -100000, heartSeed;
    private boolean ringing;
    private Model.Finder alarmFinder;
    private Model.Station alarmStation;
    private long alarmAt;
    private String[] pairWindow;
    private Object pairQr, wifiQr;
    private long pairUntil, wifiUntil;
    private String askId = "", askName = "", askMatch = "";
    /** When the "Allow this device?" card came up, and the time of the latest frame or key. */
    private long askAt, lastNow;
    /** OK on the card counts only after this long, so a press meant for the screen before it cannot allow a device. */
    static final long ASK_GUARD_MS = 1000;
    private String wifiSsid = "";
    private String toastTitle = "", toastDetail = "";
    private int toastIcon;
    private long toastAt = -100000;
    private int mirrorRow;
    /** The mirroring sheet shows the AirPlay steps rather than the two ways. */
    private boolean mirrorSteps;
    /** The Fire TV's Bluetooth is pointing Apple devices nearby at the Samsung (see AirPlay). */
    private boolean airplayBeacon;
    /** The clock's words, rebuilt only when what they say changes (never once a frame). */
    private String eyebrowText = "", skyText = "", nextText = "", greetText = "", todayCount = "";
    /** The standfirst as drawn: whole sentences dropped from the end until it clears the pinned note. */
    private String skyFit = "", skyFitFor = "";
    private float skyFitW = -1;
    private final float[] pinBox = new float[5];
    private String[] todayLabel = new String[0], todayWhen = new String[0], todayWhat = new String[0];
    private long textKey = Long.MIN_VALUE;
    private int textVersion;

    // Focus and overlays.
    private int zone = DOCK, focus, topFocus = T_GEAR, mode = NONE, row, menuRow;
    private int glanceFocus = Cards.BIKES, panelKind = Cards.BIKES, panelPrev = Cards.BIKES;
    private final List<Spring> tileFocus = new ArrayList<>();
    private final Spring[] topSpring = new Spring[T_COUNT];
    /** The bike map, made the first time it opens, and what Back returns to. */
    private BikeMap map;
    private final Spring mapSpring = new Spring(0, 0.4f, 0);
    private int mapBack = NONE;
    /** The preview and tests draw the map's city in the calling thread, so every frame is the same. */
    boolean mapSync;
    /** How far each glance segment has grown into its card. */
    private final Spring[] grow = new Spring[Cards.COUNT];
    /** Compact widths as last measured, for focus moves between draws. */
    private final float[] segW = new float[Cards.COUNT];
    private final Spring panel = new Spring(0, 0.5f, 0.12f), panelSwap = new Spring(1, 0.3f, 0);
    private final Spring panelW = new Spring(0, 0.45f, 0.08f), panelH = new Spring(0, 0.45f, 0.08f);
    private final Spring button = new Spring(0, 0.25f, 0.05f);
    private final float[] panelFrom = new float[4];
    private final Spring notice = new Spring(0, 0.45f, 0.08f);
    private Notice.Spec noticeSpec;
    private long noticeAt, noticeUntil;
    private int finderState = Model.Finder.IDLE;
    private final Spring scroll = new Spring(0, 0.5f, 0);
    private final Spring tiltX = new Spring(0, 0.6f, 0.25f), tiltY = new Spring(0, 0.6f, 0.25f);
    private final Spring ambient = new Spring(1, 1.2f, 0), ui = new Spring(1, 0.55f, 0), dim = new Spring(0, 3.5f, 0);
    /** 1 while the dashboard rests: no card grown, the top row put away. */
    private final Spring rest = new Spring(1, 0.5f, 0);
    /**
     * The dashboard at rest: the photo, the clock and the day, the pinned note and the glance
     * bar at the bottom. Down brings the apps up; any other move wakes the bar.
     */
    private boolean resting = true;
    private long hintUntil;
    private float barRight, clockBottom;
    private static final long REST_MS = 30000;
    private final Spring sheet = new Spring(0, 0.45f, 0.06f), menu = new Spring(0, 0.34f, 0.12f), pop = new Spring(0, 0.34f, 0.1f);
    private final Spring glanceLight = new Spring(0, 0.5f, 0);
    private final Spring sheetScroll = new Spring(0, 0.4f, 0), sheetPill = new Spring(0, 0.28f, 0.08f), menuPill = new Spring(0, 0.25f, 0.08f);
    private final Spring light = new Spring(0, 1.4f, 0.2f);
    private final Spring fade = new Spring(1, 1.5f, 0);
    /** The look: 0 night, 1 day. A slow crossfade, as the light outside changes. */
    private final Spring look = new Spring(0, 6f, 0);
    private final Spring noteSpring = new Spring(0, 0.55f, 0.14f), modal = new Spring(0, 0.42f, 0.1f);
    private final Spring alarmSpring = new Spring(0, 0.45f, 0.12f), toastSpring = new Spring(0, 0.4f, 0.1f);
    private final Spring pinSpring = new Spring(0, 0.6f, 0.1f), mirrorPill = new Spring(0, 0.28f, 0.08f),
            mirrorPage = new Spring(0, 0.34f, 0);
    private long lastInput, start = -1, wallSince, nowMs, enteredAt;
    private float[] wallRect = new float[4];

    private Gfx cacheOwner;
    /**
     * Pre-rendered nine-slice shadows by corner radius and blur, least recently used dropped.
     */
    private final Map<LongKey, Object> shadows = new LinkedHashMap<LongKey, Object>(16, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<LongKey, Object> e) {
            return size() > 64;
        }
    };

    /** A long as a map key without boxing: lookups reuse {@link #probe}, so a frame allocates nothing. */
    private static final class LongKey {
        long v;

        LongKey(long v) {
            this.v = v;
        }

        LongKey set(long x) {
            v = x;
            return this;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof LongKey && ((LongKey) o).v == v;
        }

        @Override
        public int hashCode() {
            return (int) (v ^ (v >>> 32));
        }
    }

    private final LongKey probe = new LongKey(0);

    Scene(Host host) {
        this.host = host;
        for (int i = 0; i < Cards.COUNT; i++) grow[i] = new Spring(0, 0.38f, 0.1f);
        for (int i = 0; i < T_COUNT; i++) topSpring[i] = new Spring(0, 0.3f, 0.1f);
    }

    // ---- Inputs from the platform ----------------------------------------------------------

    void setSize(int w, int h, float density) {
        width = w;
        height = h;
        d = density;
        cards.density(density);
        // Settings rows were cut to fit at the old size.
        java.util.Arrays.fill(rowTitleFrom, null);
        // The safe frame: 48 dp at the sides, 30 at top and bottom (Fire TV's 5% overscan rule).
        side = dp(48);
        top = dp(34);
        dockL = dp(40);
        dockR = w - dp(40);
        dockB = h - dp(30);
        dockT = dockB - dp(148);
        dockRad = dp(34);
        innerL = dockL + dp(20);
        innerR = dockR - dp(20);
        tileW = dp(160);
        tileH = dp(90);
        tileGap = dp(20);
        tileRad = dp(14);
        tileTop = dockT + dp(18);
        capH = dp(46);
        barL = dockL;
        barH = dp(52);
        barB = dockT - dp(16);
    }

    void setApps(List<Model.App> list) {
        String keep = focus < apps.size() ? apps.get(focus).id : null;
        apps.clear();
        apps.addAll(list);
        while (tileFocus.size() < apps.size()) tileFocus.add(new Spring(0, 0.34f, 0.14f));
        while (tileFocus.size() > apps.size()) tileFocus.remove(tileFocus.size() - 1);
        focus = 0;
        for (int i = 0; keep != null && i < apps.size(); i++) {
            if (apps.get(i).id.equals(keep)) focus = i;
        }
        boolean kept = false;
        for (int i = 0; keep != null && i < apps.size(); i++) kept |= apps.get(i).id.equals(keep);
        if (mode == MOVE || mode == TILE_MENU && !kept) {
            // An install or update reshuffled the dock: drop the move or the menu of a vanished app.
            mode = NONE;
            hint = "";
            menu.to(0);
        }
    }

    void setWall(Model.Wall next) {
        if (next == wall) return;
        oldWall = wall;
        wall = next;
        fade.snap(oldWall == null ? 1 : 0);
        fade.to(1);
        wallSince = -1;
    }

    Model.Wall wall() {
        return wall;
    }

    void setWeather(Model.Weather w) {
        textVersion++;
        weather = w;
        cards.weather = w;
    }

    void setAudio(Model.Audio a) {
        audio = a;
    }

    void setPicture(Model.Picture p) {
        picture = p;
    }

    void setAir(Model.Air a) {
        air = a;
        cards.air = a;
    }

    void setAlerts(List<Model.Alert> list) {
        alerts = list == null ? new ArrayList<Model.Alert>() : list;
        cards.alert = alerts.isEmpty() ? null : alerts.get(0);
    }

    void setBikes(Model.Bikes b) {
        cards.bikes = b;
    }

    void setForecast(Forecast.Result r, long now) {
        cards.setForecast(r, now);
    }

    void setTransit(Model.Transit t) {
        cards.transit = t;
    }

    void setSky(Model.Sky s) {
        cards.sky = s;
    }

    void setPlans(List<Ical.Event> events, List<String> feeds, List<Plans.Countdown> countdowns, List<Plans.Item> items) {
        textVersion++;
        cards.events = events;
        cards.feeds = feeds;
        cards.countdowns = countdowns;
        cards.items = items;
    }

    void setMedia(Model.Media m) {
        cards.media = m == null ? new Model.Media() : m;
    }

    /** The device's elapsed-realtime clock, which music positions are measured on. */
    void setUptime(long ms) {
        cards.uptime = ms;
    }

    void setSkyOn(boolean on) {
        skyOn = on;
        if (!on && glanceFocus == Cards.SKY) glanceFocus = Cards.TRANSIT;
    }

    /** Sunrise and sunset today, and the moon's phase (0 new, 0.5 full). */
    void setSun(long rise, long set, double moonPhase) {
        textVersion++;
        sunrise = rise;
        sunset = set;
        moon = moonPhase;
    }

    /** Night or day. {@code instant} for the first frame, else a 30 s crossfade. */
    void setNight(boolean night, boolean instant) {
        textVersion++;
        if (instant || reduced) look.snap(night ? 0 : 1);
        else look.to(night ? 0 : 1);
    }

    boolean night() {
        return look.target < 0.5f;
    }

    /**
     * Bike finder's state. A search that just ended while the home screen is in front gets the
     * same alert the guard shows over other apps, on real glass.
     */
    void setFinder(Model.Finder f, Model.Station station, long now) {
        if (f == null) return;
        int was = finderState;
        finderState = f.state;
        cards.finder = f;
        if (was == f.state) return;
        // Timed from when it happened, so coming home much later does not replay an old alert.
        long foundLeft = f.ended + 30000 - now, timeoutLeft = f.ended + 8000 - now;
        if (f.state == Model.Finder.FOUND && was == Model.Finder.SEARCHING && foundLeft > 0) {
            showNotice(Notice.found(f, station), now, foundLeft);
        } else if (f.state == Model.Finder.TIMED_OUT && was == Model.Finder.SEARCHING && timeoutLeft > 0) {
            showNotice(Notice.timedOut(f, station), now, timeoutLeft);
        } else if (f.state == Model.Finder.STOPPED || f.state == Model.Finder.SEARCHING) {
            notice.to(0);
        }
    }

    private void showNotice(Notice.Spec spec, long now, long forMs) {
        noticeSpec = spec;
        noticeAt = now;
        noticeUntil = now + forMs;
        wake(now);
        notice.to(1);
    }

    /** The bike alarm started or stopped ringing. While it rings, any button stops it. */
    void setAlarm(boolean on, Model.Finder f, Model.Station s, long now) {
        ringing = on;
        if (on) {
            alarmFinder = f;
            alarmStation = s;
            alarmAt = now;
            notice.to(0);
            wake(now);
            mode = ALARM;
            alarmSpring.to(1);
        } else {
            alarmSpring.to(0);
            if (mode == ALARM) mode = NONE;
        }
    }

    /** A note arrived: it fills the middle of the screen and writes itself, then steps back. */
    void showNote(Notes.Note n, long now) {
        if (n == null) return;
        popNote = n;
        float k = noteScale(Pop.noteSize(n, NOTE_DRAW));
        popInk = new Ink(n, Math.round(dp(NOTE_DRAW) * k), Math.round(dp(NOTE_DRAW) * 0.75f * k));
        noteAt = now;
        wake(now);
        if (mode != ALARM && mode != ASK) {
            closeOverlays();
            mode = NOTE;
        }
        noteSpring.snap(reduced ? 1 : 0);
        noteSpring.to(1);
    }

    /** The note kept on the home screen, or null. */
    void setPinned(Notes.Note n) {
        if (n == pinned || n != null && pinned != null && n.id.equals(pinned.id)) {
            pinned = n;
            return;
        }
        pinned = n;
        pinnedInk = n == null ? null : new Ink(n, Math.round(dp(PIN_DRAW * PIN_SCALE)), Math.round(dp(PIN_DRAW * PIN_SCALE) * 0.75f));
        pinSpring.snap(0);
        pinSpring.to(n == null ? 0 : 1);
    }

    /** Hearts (or sparkles, or a wave) float up the screen. */
    void hearts(String kind, String from, long now) {
        heartKind = kind;
        heartFrom = from == null ? "" : from;
        heartsAt = now;
        heartSeed = now;
        wake(now);
    }

    /** Typing the code stopped working (too many wrong tries): the card keeps only the QR. */
    void pairCodeOff() {
        if (pairWindow != null) pairWindow = new String[] {pairWindow[0], "", pairWindow[2]};
    }

    /** The pairing window: {QR link, code, address} and the QR's modules, or null when closed. */
    void setPairing(String[] window, Gfx g, boolean[][] qr, long until) {
        pairWindow = window;
        pairUntil = until;
        pairQr = window == null || qr == null || g == null ? null : qrImage(g, qr);
        if (window != null) {
            closeOverlays();
            mode = PAIR;
            modal.to(1);
        } else if (mode == PAIR) {
            mode = NONE;
            modal.to(0);
        }
    }

    /**
     * "Allow this phone?" for a device that scanned or typed the code, or null to clear. The
     * three match digits are the ones the phone shows, so two requests at once cannot be mixed up.
     */
    void ask(String id, String name, String match) {
        if (id == null) {
            if (mode == ASK) {
                mode = NONE;
                modal.to(0);
            }
            askId = "";
            return;
        }
        askId = id;
        askName = name;
        askMatch = match == null || !match.matches("[0-9]{3}") ? "" : match.charAt(0) + " " + match.charAt(1) + " " + match.charAt(2);
        askAt = lastNow;
        if (mode != ALARM) {
            mode = ASK;
            modal.to(1);
        }
    }

    // ---- The assistant's answer ------------------------------------------------------------------

    private Claude.Answer answer;
    private long answerShown;

    /**
     * A question on its way or its answer. {@code show} brings the card up (Vestor is in front);
     * otherwise only an open card is updated.
     */
    void setAnswer(Claude.Answer a, boolean show) {
        if (a == null) return;
        boolean same = answer != null && answer.question.equals(a.question) && Math.abs(answer.at - a.at) < 120000L;
        answer = a;
        textVersion++;
        if (mode == ANSWER) return;
        // A new question opens the card, and so does its answer (even if the card was closed while
        // Vestor was thinking); a repeat of "thinking" does not.
        if (show && (!same || !a.busy) && mode != ALARM && mode != ASK && mode != PAIR) {
            closeOverlays();
            mode = ANSWER;
            answerShown = lastNow;
            modal.to(1);
        }
    }

    private void drawAnswer(Gfx g, float t, long now) {
        float p = mode == ANSWER ? modal.value : 0;
        Claude.Answer a = answer;
        if (p <= 0.003f || a == null) return;
        float w = Math.min(dp(900), width - dp(80)), l = (width - w) / 2;
        float x = l + dp(48), right = l + w - dp(48), mw = right - x;
        // The answer sets the card's height. A short one is set large in the serif, like the clock,
        // and a long one in the text face, so a one-line reply never sits in an empty card.
        String body = a.busy ? "" : !a.error.isEmpty() && a.text.isEmpty() ? a.error : a.text;
        boolean lead = !a.busy && body.length() <= 120;
        int face = lead ? Gfx.SERIF : Gfx.TEXT_MEDIUM, maxLines = lead ? 3 : 9;
        float size = lead ? dp(38) : dp(23), leading = lead ? dp(48) : dp(33);
        int qn = Pop.count(g, a.question, Gfx.TEXT_SEMIBOLD, dp(19), mw, 2);
        int n = a.busy ? 2 : Math.max(1, Pop.count(g, body, face, size, mw, maxLines));
        float top = dp(100) + (qn - 1) * dp(26) + dp(30);
        float block = a.busy ? dp(96) : dp(14) + size * 0.8f + (n - 1) * leading;
        float h = top + block + (a.did.isEmpty() ? 0 : dp(46)) + dp(104);
        h = Math.max(dp(330), Math.min(height - dp(80), h));
        float tp = (height - h) / 2;
        sheetFrame(g, l, tp, w, h, p, t);
        // While Vestor thinks, the sparkle breathes.
        float breathe = a.busy ? 1 + 0.14f * (float) Math.sin(now / 260.0) : 1;
        Icons.sparkle(g, x + dp(12), tp + dp(52), dp(24) * breathe, Pixels.alpha(Tone.ACCENT, p));
        String who = a.from.isEmpty() || "the TV".equals(a.from) ? "YOU ASKED" : "ASKED ON " + a.from.toUpperCase(Locale.US);
        g.text(who, Gfx.MONO, dp(12.5f), 0.1f, x + dp(36), tp + dp(57), Tone.text(Tone.SECONDARY, p), Gfx.LEFT, 0);
        Pop.lines(g, a.question, Gfx.TEXT_SEMIBOLD, dp(19), x, tp + dp(100), mw, dp(26), 2, Tone.text(Tone.SECONDARY, p));
        float y = tp + top;
        g.line(x, y, right, y, dp(1), Tone.text(0.16f, p));
        if (a.busy) {
            int dots = (int) ((now / 400) % 4);
            String thinking = "Thinking" + "...".substring(0, dots);
            g.text(thinking, Gfx.SERIF, dp(38), -0.01f, x, y + dp(50), Tone.text(Tone.PRIMARY, p), Gfx.LEFT, 0);
            g.text("A search on the web can take up to a minute.", Gfx.TEXT_MEDIUM, dp(15), 0, x, y + dp(86), Tone.text(Tone.TERTIARY, p),
                    Gfx.LEFT, 0);
        } else {
            Pop.lines(g, body, face, size, x, y + dp(14) + size * 0.8f, mw, leading, maxLines, Tone.text(Tone.PRIMARY, p));
        }
        float by = y + block + dp(36);
        if (!a.did.isEmpty()) {
            StringBuilder did = new StringBuilder();
            for (String d : a.did) did.append(did.length() > 0 ? "  \u00b7  " : "").append(d);
            Icons.check(g, x + dp(9), by - dp(6), dp(18), Pixels.alpha(Tone.ACCENT, p));
            g.text(Text.fit(g, did.toString(), Gfx.TEXT_SEMIBOLD, dp(16), mw - dp(30)), Gfx.TEXT_SEMIBOLD, dp(16), 0, x + dp(28), by,
                    Pixels.alpha(Tone.ACCENT, p), Gfx.LEFT, 0);
        }
        float fy = tp + h - dp(42);
        // Sources as small outlined tags, as many as fit beside the keys.
        float sx = x, room = mw - dp(250);
        for (String[] sr : a.sources) {
            String host = sr[1].replaceFirst("^https://(www\\.)?", "");
            int slash = host.indexOf('/');
            if (slash > 0) host = host.substring(0, slash);
            float tw = g.measure(host, Gfx.TEXT_MEDIUM, dp(13), 0) + dp(24);
            if (sx + tw > x + room) break;
            g.strokeRoundRect(sx, fy - dp(19), sx + tw, fy + dp(9), dp(14), dp(1), Tone.text(Tone.TERTIARY, p));
            g.text(host, Gfx.TEXT_MEDIUM, dp(13), 0, sx + dp(12), fy, Tone.text(Tone.SECONDARY, p), Gfx.LEFT, 0);
            sx += tw + dp(8);
        }
        // Keys, right to left: Back closes, OK asks the next question once this one is answered.
        int ink = Tone.text(Tone.SECONDARY, p);
        float cw = g.measure("Close", Gfx.TEXT_MEDIUM, dp(14), 0);
        float kx = right - cw;
        g.text("Close", Gfx.TEXT_MEDIUM, dp(14), 0, kx, fy, ink, Gfx.LEFT, 0);
        kx -= dp(8) + Icons.keycapWidth(g, dp(24), "Back");
        Icons.keycap(g, kx, fy - dp(6), dp(24), "Back", ink);
        if (!a.busy) {
            float aw = g.measure("Ask again", Gfx.TEXT_MEDIUM, dp(14), 0);
            kx -= dp(22) + aw;
            g.text("Ask again", Gfx.TEXT_MEDIUM, dp(14), 0, kx, fy, ink, Gfx.LEFT, 0);
            kx -= dp(8) + Icons.keycapWidth(g, dp(24), "OK");
            Icons.keycap(g, kx, fy - dp(6), dp(24), "OK", ink);
        }
    }

    /** The guest Wi-Fi card, shown for two minutes, or null to hide. */
    void setWifi(String ssid, Gfx g, boolean[][] qr, long until) {
        if (ssid == null || qr == null) {
            if (mode == WIFI) {
                mode = NONE;
                modal.to(0);
            }
            wifiQr = null;
            return;
        }
        wifiSsid = ssid;
        wifiUntil = until;
        wifiQr = qrImage(g, qr);
        if (mode != ALARM && mode != ASK) {
            closeOverlays();
            mode = WIFI;
            modal.to(1);
        }
    }

    /** Whether the AirPlay beacon is on air, which the Mirror sheet's AirPlay page mentions. */
    void setAirPlayBeacon(boolean on) {
        airplayBeacon = on;
    }

    /** A short message at the top: headphones connected, a photo arrived. */
    void toast(String title, String detail, int icon, long now) {
        toastTitle = title;
        toastDetail = detail == null ? "" : detail;
        toastIcon = icon;
        toastAt = now;
        toastSpring.to(1);
    }

    static final int TOAST_EARBUDS = 1, TOAST_SOUNDBAR = 2, TOAST_PHOTO = 3, TOAST_PHONE = 4, TOAST_INFO = 5;

    void setClock(String time, String ampm, String date) {
        textVersion++;
        this.time = time;
        this.ampm = ampm == null ? "" : ampm;
        this.date = date;
    }

    /** The hour now (0-23), for the greeting, and the place, for the clock's label. */
    void setHour(int h, String place) {
        textVersion++;
        hour = h;
        if (place != null && !place.isEmpty()) city = place;
    }

    void setOptions(List<Model.Option> list) {
        options.clear();
        options.addAll(list);
        row = Math.max(0, Math.min(row, options.size() - 1));
        int n = options.size();
        if (rowTitle.length != n) {
            rowTitle = new String[n];
            rowValue = new String[n];
            rowTitleFrom = new String[n];
            rowValueFrom = new String[n];
            rowValueSize = new float[n];
        }
    }

    void setReduced(boolean on) {
        reduced = on;
        cards.still = on;
    }

    /** The photograph pixel for pixel, without the slow drift: the sharpest picture, and fewer frames. */
    void setStill(boolean on) {
        still = on;
    }

    void setWeatherOn(boolean on) {
        textVersion++;
        weatherOn = on;
        if (!on && glanceFocus == Cards.WEATHER) glanceFocus = Cards.BIKES;
    }

    void setClock24(boolean on) {
        textVersion++;
        cards.h24 = on;
    }

    void setAmbientAfter(long ms) {
        ambientAfter = ms;
    }

    /** How long without a button press before the screen dims, 0 for never. */
    void setDimAfter(long ms) {
        dimAfter = ms;
    }

    /** Glass materialises: called when the home screen comes back to the front. */
    void enter(long now) {
        lastInput = now;
        enteredAt = now;
        // Coming home always lands on the dashboard; the apps are one press of Down away.
        ambient.snap(1);
        dim.snap(0);
        resting = true;
        zone = GLANCE;
        rest.snap(1);
        hintUntil = now + 9000;
        if (!reduced) ui.snap(0);
        ui.to(1);
        if (mode != ALARM && mode != ASK && mode != PAIR) closeOverlays();
    }

    /** HOME pressed while already home: back to the first app, overlays closed. */
    void reset(long now) {
        wake(now);
        if (mode == ALARM) {
            host.silenceAlarm();
            return;
        }
        closeOverlays();
        notice.to(0);
        zone = GLANCE;
        focus = 0;
        resting = true;
        ambient.to(1);
        hintUntil = now + 9000;
    }

    /** Anything worth seeing happened: the screen brightens and the dock comes back. */
    private void wake(long now) {
        lastInput = now;
        dim.to(0);
    }

    /** Whether the screen is dimmed for idling (the host may let the TV sleep at bedtime then). */
    boolean dimmed() {
        return dim.target > 0.5f;
    }

    long idleFor(long now) {
        return now - lastInput;
    }

    /** The label of whatever has focus, for the system's screen reader. */
    String focusedLabel() {
        if (mode == SETTINGS && row < options.size()) return options.get(row).title + ", " + options.get(row).name();
        if (mode == PANEL) return Cards.name(panelKind);
        if (mode == MAP && map != null) return "Bike map, " + BikeMap.STOP_NAMES[map.stop()] + ". OK for the next place, Back to close.";
        if (mode == ALARM) return "E-bike ready. Press OK to stop the alarm.";
        if (mode == ASK) return "Allow " + askName + " to use the control center? OK to allow, Back to refuse.";
        if (resting && mode == NONE) return "Home. Press Down for your apps.";
        if (zone == DOCK && focus < apps.size()) return apps.get(focus).label;
        if (zone == GLANCE) return Cards.name(glanceFocus);
        return zone == TOP ? TOP_NAMES[topFocus] : "";
    }

    // ---- Keys --------------------------------------------------------------------------------

    boolean key(int k, long now) {
        lastNow = now;
        boolean wasDim = dim.target > 0.5f;
        lastInput = now;
        // The alarm takes every button: any press stops it.
        if (mode == ALARM) {
            host.sound(true);
            host.silenceAlarm();
            return true;
        }
        if (resting && k != DOWN) hintUntil = Math.max(hintUntil, now + 6000);
        // Holding Menu asks Vestor from anywhere on the home screen, dimmed or not. A phone's request
        // to pair waits for its own answer, so it stays up.
        if (k == LONG_MENU) {
            dim.to(0);
            if (mode != ASK) {
                closeOverlays();
                host.action("ask");
            }
            return true;
        }
        if (wasDim) {
            dim.to(0);
            if (mode != ASK && mode != NOTE) return true;
        }
        // Media keys work wherever focus is.
        if (k == PLAY) {
            host.media(MEDIA_TOGGLE);
            button.kick(-6f);
            return true;
        }
        if (k == REWIND || k == FORWARD) {
            if (mode == PANEL && panelKind == Cards.MUSIC || cards.media.active && mode == NONE && zone == GLANCE && glanceFocus == Cards.MUSIC) {
                host.media(k == FORWARD ? MEDIA_NEXT : MEDIA_PREV);
            } else if (mode == NONE) {
                host.wallpaper(k == FORWARD ? 1 : -1);
            }
            return true;
        }
        if (notice.target > 0.5f && k == BACK && mode == NONE) {
            notice.to(0);
            if (finderState == Model.Finder.FOUND || finderState == Model.Finder.TIMED_OUT) host.dismissFinder();
            return true;
        }
        switch (mode) {
            case SETTINGS:
                settingsKey(k);
                return true;
            case TILE_MENU:
                menuKey(k);
                return true;
            case AUDIO:
                popKey(k);
                return true;
            case PANEL:
                panelKey(k);
                return true;
            case MOVE:
                moveKey(k);
                return true;
            case PAIR:
                if (k == BACK || k == OK || k == MENU) {
                    host.pairing(false);
                    mode = NONE;
                    modal.to(0);
                    host.sound(false);
                }
                return true;
            case ASK:
                if (k == OK && now - askAt < ASK_GUARD_MS) return true;
                if (k == OK || k == BACK) {
                    host.sound(k == OK);
                    host.decide(askId, k == OK);
                    mode = NONE;
                    modal.to(0);
                }
                return true;
            case NOTE:
                if (k == OK || k == BACK || k == MENU) {
                    host.sound(false);
                    mode = NONE;
                    noteSpring.to(0);
                }
                return true;
            case WIFI:
                if (k == OK || k == BACK || k == MENU) {
                    mode = NONE;
                    modal.to(0);
                    host.action("wifi-hide");
                }
                return true;
            case ANSWER:
                if (k == OK || k == BACK || k == MENU) {
                    host.sound(false);
                    mode = NONE;
                    modal.to(0);
                    // OK on an answer that is in asks the next question.
                    if (k == OK && answer != null && !answer.busy) host.action("ask");
                }
                return true;
            case MIRROR:
                mirrorKey(k);
                return true;
            case MAP:
                mapKey(k, now);
                return true;
            default:
                return homeKey(k);
        }
    }

    private boolean homeKey(int k) {
        if (zone == DOCK) {
            int n = apps.size();
            switch (k) {
                case LEFT:
                    if (focus > 0) moveFocus(focus - 1, -1);
                    else bump(-1);
                    return true;
                case RIGHT:
                    if (focus < n - 1) moveFocus(focus + 1, 1);
                    else bump(1);
                    return true;
                case UP:
                    zone = GLANCE;
                    resting = false;
                    glanceFocus = nearestGlance(tileX(focus) + tileW / 2);
                    light.kick(-2f);
                    host.sound(false);
                    return true;
                case DOWN:
                    tiltY.kick(-4f);
                    return true;
                case OK:
                    if (n > 0) {
                        host.sound(true);
                        host.launch(apps.get(focus));
                    }
                    return true;
                case LONG_OK:
                case MENU:
                    if (n > 0) openMenu();
                    else openSheet();
                    return true;
                case BACK:
                    if (focus != 0) moveFocus(0, -1);
                    else closeDock();
                    return true;
                default:
                    return false;
            }
        }
        if (zone == GLANCE) return glanceKey(k);
        switch (k) {
            case LEFT:
            case RIGHT: {
                int next = topFocus + (k == LEFT ? -1 : 1);
                if (next >= 0 && next < T_COUNT) {
                    topFocus = next;
                    light.kick(k == LEFT ? -1.5f : 1.5f);
                    host.sound(false);
                }
                return true;
            }
            case DOWN: {
                float[] r = topRect(topFocus, null);
                zone = GLANCE;
                resting = false;
                glanceFocus = nearestGlance((r[0] + r[2]) / 2);
                light.kick(2f);
                host.sound(false);
                return true;
            }
            case BACK:
                if (dockOpen()) {
                    zone = DOCK;
                } else {
                    zone = GLANCE;
                    resting = true;
                }
                light.kick(2f);
                host.sound(false);
                return true;
            case OK:
                host.sound(true);
                switch (topFocus) {
                    case T_GEAR:
                        openSheet();
                        break;
                    case T_AUDIO:
                        openPop();
                        break;
                    case T_PHONE:
                        host.pairing(true);
                        break;
                    case T_MAP:
                        showMap(BikeMap.OVERVIEW, lastNow);
                        break;
                    case T_ASK:
                        host.action("ask");
                        break;
                    case T_CALL:
                        host.action("calls");
                        break;
                    case T_MIRROR:
                        mode = MIRROR;
                        mirrorRow = 0;
                        mirrorSteps = false;
                        mirrorPill.snap(0);
                        mirrorPage.snap(0);
                        modal.to(1);
                        break;
                    default:
                        host.amazonHome();
                }
                return true;
            case MENU:
                openSheet();
                return true;
            default:
                return true;
        }
    }

    private boolean glanceKey(int k) {
        int[] kinds = glanceKinds();
        int at = indexOf(kinds, glanceFocus);
        if (resting) {
            // At rest the first press only wakes the bar where it is; Down brings up the apps.
            switch (k) {
                case LEFT:
                case RIGHT:
                    resting = false;
                    light.kick(k == LEFT ? -1.2f : 1.2f);
                    host.sound(false);
                    return true;
                case DOWN:
                    openDock();
                    return true;
                case BACK:
                    return true;
                default:
                    resting = false;
            }
        }
        switch (k) {
            case LEFT:
            case RIGHT: {
                int next = at + (k == LEFT ? -1 : 1);
                if (next >= 0 && next < kinds.length) {
                    glanceFocus = kinds[next];
                    light.kick(k == LEFT ? -1.2f : 1.2f);
                    host.sound(false);
                } else {
                    glanceLight.kick(k == LEFT ? -3f : 3f);
                }
                return true;
            }
            case UP:
                zone = TOP;
                topFocus = nearestTop(glanceCentre(glanceFocus));
                light.kick(-2f);
                host.sound(false);
                return true;
            case DOWN:
                openDock();
                return true;
            case BACK:
                if (dockOpen()) {
                    zone = DOCK;
                    light.kick(2f);
                } else {
                    resting = true;
                }
                host.sound(false);
                return true;
            case OK:
                host.sound(true);
                // Selecting the bikes card is how bike finder starts, as asked: one press.
                if (glanceFocus == Cards.BIKES && !cards.finding() && !cards.foundRecently()) host.findBike();
                openPanel(glanceFocus);
                return true;
            case LONG_OK:
                if (glanceFocus == Cards.BIKES) {
                    host.sound(true);
                    if (cards.finding()) host.stopFinding();
                    else host.findBike();
                } else if (glanceFocus == Cards.MUSIC) {
                    host.media(MEDIA_TOGGLE);
                }
                return true;
            case MENU:
                openSheet();
                return true;
            default:
                return true;
        }
    }

    private void panelKey(int k) {
        switch (k) {
            case LEFT:
            case RIGHT: {
                int[] kinds = glanceKinds();
                int next = indexOf(kinds, panelKind) + (k == LEFT ? -1 : 1);
                if (next >= 0 && next < kinds.length) {
                    panelPrev = panelKind;
                    panelKind = kinds[next];
                    glanceFocus = panelKind;
                    panelSwap.snap(0);
                    panelSwap.to(1);
                    float[] size = cards.panelSize(panelKind);
                    panelW.to(size[0]);
                    panelH.to(size[1]);
                    button.to(panelKind == Cards.BIKES || panelKind == Cards.MUSIC ? 1 : 0);
                    host.sound(false);
                }
                return;
            }
            case OK:
                host.sound(true);
                if (panelKind == Cards.BIKES) {
                    if (cards.finding()) host.stopFinding();
                    else host.findBike();
                    button.kick(-6f);
                } else if (panelKind == Cards.MUSIC) {
                    host.media(MEDIA_TOGGLE);
                    button.kick(-6f);
                } else {
                    closePanel();
                }
                return;
            case UP:
                // Up from the Bluebikes panel opens the bike map; Back there comes back here.
                if (panelKind == Cards.BIKES) showMap(BikeMap.OVERVIEW, lastNow);
                return;
            case BACK:
            case MENU:
            case DOWN:
                closePanel();
                return;
            default:
        }
    }

    // ---- Bike map ----------------------------------------------------------------------------

    /** Opens the bike map over everything (from the top row, the Bluebikes panel or a phone) at stop {@code which}. */
    void showMap(int which, long now) {
        if (mode == ALARM) return;
        if (mode != MAP) {
            mapBack = mode == PANEL ? PANEL : NONE;
            if (mode != PANEL) closeOverlays();
            if (map == null) {
                map = new BikeMap();
                map.sync = mapSync;
            }
            if (!map.isOpen() || mapSpring.value < 0.05f) map.open(now);
            mode = MAP;
            mapSpring.to(1);
            dim.to(0);
            lastInput = now;
        }
        if (which != map.stop()) map.show(which, now);
    }

    /** Back from the bike map: to the Bluebikes panel if it was opened there, else home. */
    void closeMap() {
        if (mode != MAP) return;
        mode = mapBack;
        mapSpring.to(0);
    }

    boolean mapOpen() {
        return mode == MAP;
    }

    private void mapKey(int k, long now) {
        if (k == BACK || k == MENU) {
            closeMap();
            host.sound(false);
        } else if (map.key(k, now)) {
            host.sound(false);
        }
    }

    private void mirrorKey(int k) {
        if (mirrorSteps) {
            // The AirPlay steps: Back returns to the two ways, OK closes.
            if (k == BACK) {
                mirrorSteps = false;
                host.sound(false);
            } else if (k == OK || k == MENU) {
                mode = NONE;
                modal.to(0);
                host.sound(k == OK);
            }
            return;
        }
        switch (k) {
            case UP:
            case LEFT:
                if (mirrorRow > 0) {
                    mirrorRow--;
                    host.sound(false);
                }
                break;
            case DOWN:
            case RIGHT:
                if (mirrorRow < 1) {
                    mirrorRow++;
                    host.sound(false);
                }
                break;
            case OK:
                host.sound(true);
                if (mirrorRow == 0) {
                    // Apple devices mirror to the Samsung's own AirPlay, started on the device, so this shows how.
                    mirrorSteps = true;
                    break;
                }
                mode = NONE;
                modal.to(0);
                host.mirror();
                break;
            case BACK:
            case MENU:
                mode = NONE;
                modal.to(0);
                host.sound(false);
                break;
            default:
                break;
        }
    }

    private static int indexOf(int[] a, int v) {
        for (int i = 0; i < a.length; i++) if (a[i] == v) return i;
        return 0;
    }

    private void moveFocus(int to, int dir) {
        focus = to;
        tiltX.value = dir * 0.9f;
        tiltX.velocity = 0;
        light.kick(dir * 1.2f);
        host.sound(false);
    }

    /** At the end of the row the tile leans and springs back, like a rubber band. */
    private void bump(int dir) {
        tiltX.kick(dir * 7f);
        scroll.kick(dir * dp(220));
    }

    private void settingsKey(int k) {
        if (options.isEmpty()) {
            closeOverlays();
            return;
        }
        Model.Option o = options.get(row);
        switch (k) {
            case UP:
                if (row > 0) {
                    row--;
                    host.sound(false);
                }
                break;
            case DOWN:
                if (row < options.size() - 1) {
                    row++;
                    host.sound(false);
                }
                break;
            case LEFT:
            case RIGHT:
                if (!o.action) cycle(o, k == LEFT ? -1 : 1);
                break;
            case OK:
            case LONG_OK:
                if (o.action) {
                    host.sound(true);
                    act(o.key);
                } else {
                    cycle(o, 1);
                }
                break;
            case BACK:
            case MENU:
                closeOverlays();
                break;
            default:
                break;
        }
    }

    private void cycle(Model.Option o, int dir) {
        int n = o.values.length;
        o.index = ((o.index + dir) % n + n) % n;
        host.sound(false);
        host.option(o.key, o.value());
    }

    private void act(String key) {
        if ("unhide".equals(key)) {
            host.unhideAll();
        } else if ("fire".equals(key)) {
            closeOverlays();
            host.fireSettings();
        } else if ("amazon".equals(key)) {
            closeOverlays();
            host.amazonHome();
        } else if ("pair".equals(key)) {
            closeOverlays();
            host.pairing(true);
        } else {
            closeOverlays();
            host.action(key);
        }
    }

    private void menuKey(int k) {
        switch (k) {
            case UP:
                if (menuRow > 0) {
                    menuRow--;
                    host.sound(false);
                }
                break;
            case DOWN:
                if (menuRow < MENU_ROWS.length - 1) {
                    menuRow++;
                    host.sound(false);
                }
                break;
            case OK:
                host.sound(true);
                if (focus >= apps.size()) {
                    closeOverlays();
                } else if (menuRow == 0) {
                    mode = MOVE;
                    menu.to(0);
                    hint = "Moving " + apps.get(focus).label + ". Left and right to place it, then OK.";
                } else if (menuRow == 1) {
                    Model.App a = apps.get(focus);
                    closeOverlays();
                    host.hide(a.id);
                } else {
                    Model.App a = apps.get(focus);
                    closeOverlays();
                    host.appInfo(a);
                }
                break;
            case BACK:
            case MENU:
            case LONG_OK:
                closeOverlays();
                break;
            default:
                break;
        }
    }

    private void popKey(int k) {
        if (k == OK) {
            closeOverlays();
            host.sound(true);
            host.action("bluetooth");
            return;
        }
        if (k == BACK || k == MENU || k == DOWN || k == LONG_OK) closeOverlays();
    }

    private void moveKey(int k) {
        if (k == LEFT || k == RIGHT) {
            int to = focus + (k == LEFT ? -1 : 1);
            if (to < 0 || to >= apps.size()) {
                bump(k == LEFT ? -1 : 1);
                return;
            }
            Model.App a = apps.remove(focus);
            apps.add(to, a);
            Spring s = tileFocus.remove(focus);
            tileFocus.add(to, s);
            moveFocus(to, k == LEFT ? -1 : 1);
            return;
        }
        List<String> ids = new ArrayList<>();
        for (Model.App a : apps) ids.add(a.id);
        mode = NONE;
        hint = "";
        host.sound(true);
        host.order(ids);
    }

    private boolean dockOpen() {
        return ambient.target < 0.5f;
    }

    /** Down from the dashboard: the apps rise into view and take focus. */
    private void openDock() {
        ambient.to(0);
        zone = DOCK;
        resting = false;
        light.kick(2f);
        host.sound(false);
    }

    /** Back from the apps: the dashboard again, at rest. */
    private void closeDock() {
        ambient.to(1);
        zone = GLANCE;
        resting = true;
        light.kick(-2f);
        host.sound(false);
    }

    private void openSheet() {
        mode = SETTINGS;
        sheet.to(1);
        menu.to(0);
        pop.to(0);
    }

    private void openMenu() {
        mode = TILE_MENU;
        menuRow = 0;
        menuPill.snap(0);
        menu.to(1);
    }

    private void openPop() {
        mode = AUDIO;
        pop.to(1);
    }

    /** OK on a glance card: the card's glass grows into the panel. */
    private void openPanel(int kind) {
        float[] r = cardRect(kind, 1f);
        System.arraycopy(r, 0, panelFrom, 0, 4);
        mode = PANEL;
        panelKind = kind;
        panelPrev = kind;
        panelSwap.snap(1);
        float[] size = cards.panelSize(kind);
        if (panel.value < 0.01f) {
            panelW.snap(size[0]);
            panelH.snap(size[1]);
        } else {
            panelW.to(size[0]);
            panelH.to(size[1]);
        }
        button.snap(0);
        button.to(kind == Cards.BIKES || kind == Cards.MUSIC ? 1 : 0);
        panel.to(1);
    }

    private void closePanel() {
        mode = NONE;
        zone = GLANCE;
        glanceFocus = panelKind;
        // After paging, the glass shrinks back into the card of the subject now shown.
        System.arraycopy(cardRect(panelKind, 1f), 0, panelFrom, 0, 4);
        panel.to(0);
        host.sound(false);
    }

    private void closeOverlays() {
        if (mode == MOVE && !apps.isEmpty()) {
            List<String> ids = new ArrayList<>();
            for (Model.App a : apps) ids.add(a.id);
            host.order(ids);
        }
        if (mode == PAIR) host.pairing(false);
        mode = NONE;
        hint = "";
        sheet.to(0);
        menu.to(0);
        pop.to(0);
        panel.to(0);
        modal.to(0);
        noteSpring.to(0);
    }

    private final int[] kindsBuf = new int[Cards.COUNT];
    private int[] kindsCache = new int[0];

    /** The subjects in the glance bar, left to right. */
    private int[] glanceKinds() {
        boolean w = weatherOn && weather != null && weather.ok;
        boolean s = skyOn && cards.sky != null && cards.sky.ok;
        boolean p = cards.hasPlans();
        boolean m = cards.media.active;
        if (s && !(mode == PANEL && panelKind == Cards.SKY)) {
            // Planes are the extra: they step aside when the bar would run past the dock.
            float total = segWidth(Cards.BIKES) + segWidth(Cards.TRANSIT) + segWidth(Cards.SKY) + (w ? segWidth(Cards.WEATHER) : 0)
                    + (p ? segWidth(Cards.PLANS) : 0) + (m ? segWidth(Cards.MUSIC) : 0);
            if (total > dockR - barL) s = false;
        }
        if (p && !(mode == PANEL && panelKind == Cards.PLANS)) {
            float total = segWidth(Cards.BIKES) + segWidth(Cards.TRANSIT) + segWidth(Cards.PLANS) + (w ? segWidth(Cards.WEATHER) : 0)
                    + (m ? segWidth(Cards.MUSIC) : 0);
            if (total > dockR - barL) p = false;
        }
        // Weather is the last to step aside: the line under the clock still says it.
        if (w && !(mode == PANEL && panelKind == Cards.WEATHER) && coreWidth(true) > dockR - barL) w = false;
        int n = 0;
        if (m) kindsBuf[n++] = Cards.MUSIC;
        if (w) kindsBuf[n++] = Cards.WEATHER;
        kindsBuf[n++] = Cards.BIKES;
        kindsBuf[n++] = Cards.TRANSIT;
        if (p) kindsBuf[n++] = Cards.PLANS;
        if (s) kindsBuf[n++] = Cards.SKY;
        if (kindsCache.length != n) kindsCache = new int[n];
        System.arraycopy(kindsBuf, 0, kindsCache, 0, n);
        return kindsCache;
    }

    /** The info bar's right edge as last drawn, and the edge it must stay inside (for the desk tests). */
    float[] barEdges() {
        return new float[] {barRight, dockR};
    }

    /** The segments that always show (bikes, shuttles, music when playing), with the weather if asked. */
    private float coreWidth(boolean withWeather) {
        return segWidth(Cards.BIKES) + segWidth(Cards.TRANSIT) + (withWeather ? segWidth(Cards.WEATHER) : 0)
                + (cards.media.active ? segWidth(Cards.MUSIC) : 0);
    }

    private float segLeft(int kind) {
        float x = barL;
        for (int k : glanceKinds()) {
            if (k == kind) return x;
            x += segWidth(k);
        }
        return x;
    }

    private float segWidth(int kind) {
        return segW[kind] > 0 ? segW[kind] : dp(150);
    }

    private float glanceCentre(int kind) {
        return segLeft(kind) + segWidth(kind) / 2;
    }

    private int nearestGlance(float x) {
        int best = Cards.BIKES;
        float bestGap = Float.MAX_VALUE;
        for (int k : glanceKinds()) {
            float gap = Math.abs(glanceCentre(k) - x);
            if (gap < bestGap) {
                bestGap = gap;
                best = k;
            }
        }
        return best;
    }

    /** The top control nearest to x, so Up goes where the eye already is. */
    private int nearestTop(float x) {
        int best = T_GEAR;
        float bestGap = Float.MAX_VALUE;
        for (int id = 0; id < T_COUNT; id++) {
            float[] r = topRect(id, null);
            float gap = Math.abs((r[0] + r[2]) / 2 - x);
            if (gap < bestGap) {
                bestGap = gap;
                best = id;
            }
        }
        return best;
    }

    /**
     * The rectangle of glance segment {@code kind} grown by {@code e} (0 compact, 1 card): it grows
     * up from the bar's bottom edge and right from its own left edge, staying on screen.
     */
    private float[] cardRect(int kind, float e) {
        float sw = segWidth(kind);
        float w = lerp(sw, cards.expandedWidth(kind), e);
        float h = lerp(barH, dp(cards.cardHeight(kind)), e);
        float bottom = barBottom();
        float l = Math.min(segLeft(kind), dockR - w);
        l = Math.max(dockL, l);
        return new float[] {l, bottom - h, l + w, bottom};
    }

    /** The bar's bottom edge: above the dock, or where the dock was once ambient mode hides it. */
    private float barBottom() {
        return lerp(barB, dockB, ambient.value);
    }

    // ---- Motion ------------------------------------------------------------------------------

    /** Advances the animation; returns whether anything still needs fast frames. */
    boolean step(float dt, long now) {
        lastNow = now;
        nowMs = now;
        cards.now = now;
        if (start < 0) start = now;
        cards.time = (now - start) / 1000f;
        if (wallSince < 0) wallSince = now;
        boolean idleModes = mode == NONE && notice.target < 0.5f && !ringing;
        // Left alone, the apps go back down and then the bar settles; later the screen dims.
        if (idleModes && ambientAfter > 0 && now - lastInput > ambientAfter && dockOpen()) {
            ambient.to(1);
            zone = GLANCE;
            resting = true;
        }
        if (idleModes && !resting && !dockOpen() && now - lastInput > REST_MS) {
            resting = true;
            zone = GLANCE;
        }
        if (idleModes && dimAfter > 0 && now - lastInput > dimAfter) dim.to(1);
        if (notice.target > 0.5f && now > noticeUntil) notice.to(0);
        if (mode == NOTE && popNote != null && now - noteAt > 30000) {
            mode = NONE;
            noteSpring.to(0);
        }
        if (mode == PAIR && now > pairUntil) {
            host.pairing(false);
            mode = NONE;
            modal.to(0);
        }
        if (mode == WIFI && now > wifiUntil) {
            mode = NONE;
            modal.to(0);
        }
        if (mode == MAP && now - lastInput > MAP_IDLE_MS) closeMap();
        if (toastSpring.target > 0.5f && now - toastAt > 6000) toastSpring.to(0);
        for (int i = 0; i < tileFocus.size(); i++) {
            boolean on = zone == DOCK && i == focus && mode != SETTINGS && ambient.target < 0.5f;
            tileFocus.get(i).to(on ? 1 : 0);
        }
        for (int id = 0; id < T_COUNT; id++) {
            topSpring[id].to(zone == TOP && topFocus == id && mode == NONE ? 1 : 0);
        }
        int[] kinds = glanceKinds();
        boolean shown = false;
        for (int k : kinds) shown |= k == glanceFocus;
        // A subject can leave the bar (weather switched off, planes out of room): focus moves to
        // its neighbour instead of resting on something invisible.
        if (!shown && kinds.length > 0) glanceFocus = kinds[Math.min(kinds.length - 1, indexOf(kinds, Cards.BIKES))];
        for (int k = 0; k < Cards.COUNT; k++) {
            boolean on = zone == GLANCE && glanceFocus == k && (mode == NONE || mode == PANEL) && !resting;
            grow[k].to(on ? 1 : 0);
        }
        int n = apps.size();
        float step = tileW + tileGap;
        float max = Math.max(0, n * step - tileGap - (innerR - innerL));
        scroll.to(Math.max(0, Math.min(max, (focus - 1) * step)));
        tiltX.to(0);
        tiltY.to(0);
        light.to(0);
        glanceLight.to(0);
        sheetPill.to(row);
        menuPill.to(menuRow);
        mirrorPill.to(mirrorRow);
        mirrorPage.to(mirrorSteps ? 1 : 0);
        if (mode == SETTINGS) {
            float rowH = dp(58);
            float view = height - dp(34) * 2 - dp(128);
            float want = sheetScroll.target;
            float y = row * rowH;
            if (y - want > view - rowH) want = y - view + rowH;
            if (y < want) want = y;
            sheetScroll.to(Math.max(0, want));
        }
        boolean moving = false;
        boolean instant = reduced;
        for (int i = 0; i < tileFocus.size(); i++) moving |= tileFocus.get(i).step(dt, false);
        for (Spring s : topSpring) moving |= s.step(dt, false);
        for (Spring s : grow) moving |= s.step(dt, false);
        moving |= scroll.step(dt, false);
        moving |= tiltX.step(dt, instant);
        moving |= tiltY.step(dt, instant);
        moving |= light.step(dt, instant);
        moving |= glanceLight.step(dt, instant);
        moving |= ambient.step(dt, false);
        rest.to(resting && zone != TOP && mode == NONE ? 1 : 0);
        moving |= rest.step(dt, false);
        moving |= dim.step(dt, false);
        moving |= ui.step(dt, instant);
        moving |= sheet.step(dt, instant);
        moving |= menu.step(dt, instant);
        moving |= pop.step(dt, instant);
        moving |= panel.step(dt, instant);
        moving |= panelSwap.step(dt, instant);
        moving |= panelW.step(dt, instant);
        moving |= panelH.step(dt, instant);
        moving |= button.step(dt, instant);
        moving |= notice.step(dt, instant);
        moving |= sheetScroll.step(dt, false);
        moving |= sheetPill.step(dt, false);
        moving |= menuPill.step(dt, false);
        moving |= mirrorPill.step(dt, false);
        moving |= mirrorPage.step(dt, false);
        moving |= fade.step(dt, false);
        moving |= look.step(dt, false);
        moving |= noteSpring.step(dt, instant);
        moving |= modal.step(dt, instant);
        moving |= alarmSpring.step(dt, instant);
        moving |= toastSpring.step(dt, instant);
        moving |= pinSpring.step(dt, instant);
        moving |= mapSpring.step(dt, instant);
        if (map != null) {
            // The map moves every frame while it shows; once faded out it lets its worker go.
            if (mode == MAP || mapSpring.value > 0.003f) moving |= map.step(now);
            else if (map.isOpen()) map.close();
        }
        if (!fade.moving() && oldWall != null) oldWall = null;
        // The found alert's rings and bounce play out at full frame rate.
        if (!reduced && notice.value > 0.01f && (now - noticeAt) / 1000f < Notice.PULSE_SECONDS) moving = true;
        // A note writing itself, hearts rising, the alarm pulsing and the greeting arriving.
        if (noteSpring.value > 0.01f && popNote != null && !reduced && (now - noteAt) / 1000f < Ink.seconds(popNote) + 1.5f) moving = true;
        if (heartsActive(now)) moving = true;
        if (alarmSpring.value > 0.01f) moving = true;
        if (!reduced && now - enteredAt < 2200) moving = true;
        if (pinSpring.value > 0.01f && pinnedInk != null && !pinnedInk.done()) moving = true;
        return moving;
    }

    private boolean heartsActive(long now) {
        float t = (now - heartsAt) / 1000f;
        return t >= 0 && t < Pop.HEARTS_SECONDS;
    }

    /** Whether something shows seconds (bike finder's timer, music), which needs a few frames a second. */
    boolean ticking() {
        return cards.finding() || notice.value > 0.01f || cards.media.playing || mode == PAIR || mode == WIFI
                || mode == ANSWER && answer != null && answer.busy;
    }

    /** Whether the wallpaper drifts, which needs slow continuous frames. */
    boolean drifting() {
        return !reduced && !still && wall != null && dim.value < 0.99f;
    }

    // ---- Drawing -----------------------------------------------------------------------------

    void draw(Gfx g, long now) {
        if (g != cacheOwner) {
            cacheOwner = g;
            shadows.clear();
        }
        Tone.use(look.value);
        if (start < 0) start = now;
        float t = (now - start) / 1000f;
        if (map != null && mode == MAP && mapSpring.value > 0.999f) {
            // The map covers the whole screen: nothing beneath it is drawn.
            map.draw(g, width, height, d, now, cards.bikes, 1);
            return;
        }
        computeWallRect(t);
        drawWallpaper(g);

        float amb = ambient.value;
        float uiA = ui.value * (1 - amb);
        float topA = ui.value * (1 - rest.value);
        drawClock(g, amb, now);
        drawToday(g, ui.value * amb, now);
        drawCaption(g, uiA, rest.value);
        drawTop(g, topA, t);
        drawPinned(g, ui.value * amb, t);
        drawDock(g, uiA, t);
        drawGlance(g, ui.value, t);
        drawHint(g, amb, now);

        float modalDim = Math.max(sheet.value, Math.max(menu.value * 0.75f, Math.max(pop.value * 0.55f, panel.value * 0.6f)));
        modalDim = Math.max(modalDim, Math.max(modal.value * 0.7f, Math.max(noteSpring.value * 0.6f, alarmSpring.value * 0.45f)));
        if (modalDim > 0.002f) {
            g.fillRect(0, 0, width, height, Pixels.alpha(0xFF000000, 0.34f * modalDim));
            if (menu.value > 0.002f && focus < apps.size()) {
                // The app the menu belongs to stays lit above the dimming.
                drawTile(g, focus, tileX(focus), tileTop, 1f, 1f, false);
            }
        }
        drawPanel(g, t);
        drawSheet(g, t);
        drawMenu(g, t);
        drawPop(g, t);
        drawMirror(g, t);
        drawPair(g, t, now);
        drawAsk(g, t);
        drawWifi(g, t, now);
        drawAnswer(g, t, now);
        drawNote(g, t, now);
        drawNotice(g, now, t);
        drawAlarm(g, now, t);
        drawToast(g, t, now);
        if (map != null && mapSpring.value > 0.003f) map.draw(g, width, height, d, now, cards.bikes, Math.min(1, mapSpring.value));
        if (heartsActive(now)) Pop.hearts(g, heartKind, 0, 0, width, height, d, (now - heartsAt) / 1000f, heartSeed);
        // Idle: the whole screen settles to a dim, quiet state, as a living-room lamp would.
        float dimA = dim.value * (0.38f + 0.22f * (1 - look.value));
        if (dimA > 0.002f) g.fillRect(0, 0, width, height, Pixels.alpha(0xFF000000, dimA));
    }

    private void computeWallRect(float t) {
        // Ken Burns at a walking pace: 3.5% bigger than the screen, drifting over minutes.
        boolean calm = reduced || still;
        float s = calm ? 1.0f : 1.05f + 0.012f * (float) Math.sin(t * 2 * Math.PI / 173);
        float cx = width / 2f, cy = height / 2f;
        if (!calm) {
            cx += width * 0.014f * (float) Math.sin(t * 2 * Math.PI / 97);
            cy += height * 0.011f * (float) Math.cos(t * 2 * Math.PI / 131);
        }
        wallRect[0] = cx - width * s / 2;
        wallRect[1] = cy - height * s / 2;
        wallRect[2] = cx + width * s / 2;
        wallRect[3] = cy + height * s / 2;
    }

    private void drawWallpaper(Gfx g) {
        float[] r = wallRect;
        if (wall == null || wall.full == null) {
            g.fillRectLinear(0, 0, width, height, 0, 0, width * 0.3f, height, FALLBACK_SKY, THREE_STOPS, 1f);
        } else if (oldWall != null && oldWall.full != null && fade.value < 1f) {
            g.drawImage(oldWall.full, r[0], r[1], r[2], r[3], 255);
            g.drawImage(wall.full, r[0], r[1], r[2], r[3], Math.round(255 * fade.value));
        } else {
            g.drawImage(wall.full, r[0], r[1], r[2], r[3], 255);
        }
        // Night: the photograph steps back and warms, so the room is not lit by the TV.
        float night = 1 - look.value;
        if (night > 0.003f) {
            g.fillRect(0, 0, width, height, Pixels.alpha(0xFF0B0805, 0.24f * night));
            g.fillRect(0, 0, width, height, Pixels.alpha(0xFF3A1E08, 0.07f * night));
        }
    }

    /**
     * The photo's name and place in small mono capitals, right-aligned under the top controls;
     * while moving an app the same line gives the instructions. In ambient mode it rises to where
     * the controls were.
     */
    private void drawCaption(Gfx g, float uiA, float restV) {
        boolean instruct = !hint.isEmpty();
        String text = instruct ? hint : wall == null ? "" : wall.title;
        if (text.isEmpty() || notice.value > 0.98f) return;
        float topLit = 0;
        for (Spring sp : topSpring) topLit = Math.max(topLit, sp.value);
        float a = (instruct ? uiA : 0.82f * ui.value * (1 - Math.min(1, topLit))) * (1 - notice.value) * (1 - alarmSpring.value);
        if (a <= 0.003f) return;
        float y = lerp(top + capH + dp(34), top + dp(26), restV);
        float r = width - side - dp(2);
        int face = instruct ? Gfx.TEXT_MEDIUM : Gfx.MONO;
        float size = instruct ? dp(14.5f) : dp(11.5f);
        float track = instruct ? 0.01f : 0.1f;
        String main = instruct ? text : upper(text);
        String place = !instruct && wall != null && !wall.place.isEmpty() ? upperPlace(wall.place) : "";
        float total = g.measure(main, face, size, track) + (place.isEmpty() ? 0 : g.measure(place, face, size, track) + dp(22));
        // A feathered dim behind words on a photograph, as visionOS does, instead of a box.
        float sh = dp(32), cy = y - g.capHeight(face, size) / 2;
        drawShadow(g, r - total - dp(8), cy - sh / 2, r + dp(8), cy + sh / 2, sh / 2, dp(22), 0, Math.round(255 * 0.8f * a));
        if (!place.isEmpty()) {
            g.text(place, face, size, track, r, y, Pixels.alpha(ON_PHOTO, a * 0.66f), Gfx.RIGHT, 0.8f);
            r -= g.measure(place, face, size, track) + dp(10);
            g.fillCircle(r - dp(4), cy, dp(1.6f), Pixels.alpha(ON_PHOTO, a * 0.6f));
            r -= dp(12);
        }
        g.text(main, face, size, track, r, y, Pixels.alpha(ON_PHOTO, a), Gfx.RIGHT, 0.8f);
    }

    private String upperFrom, upperTo, placeFrom, placeTo;

    /** The caption in capitals, made again only when the caption changes. */
    private String upper(String s) {
        if (s != upperFrom) {
            upperFrom = s;
            upperTo = s.toUpperCase(Locale.US);
        }
        return upperTo;
    }

    private String upperPlace(String s) {
        if (s != placeFrom) {
            placeFrom = s;
            placeTo = s.toUpperCase(Locale.US);
        }
        return placeTo;
    }

    /** "Good evening", by the hour. */
    private String greeting() {
        if (hour >= 5 && hour < 12) return "Good morning";
        if (hour >= 12 && hour < 17) return "Good afternoon";
        if (hour >= 17 && hour < 22) return "Good evening";
        return "Good night";
    }

    /**
     * The editorial clock: a mono label (day, date, place), the time in large serif numerals, a
     * short rule, and a line in serif italic with the greeting and the sky. In ambient mode it
     * grows a little. Every few minutes the whole block moves by a few pixels, so nothing sits on
     * the same pixels all day.
     */
    private void drawClock(Gfx g, float amb, long now) {
        // A soft shade behind the words, only as strong as the photograph under them is bright, so
        // white type reads on a sunlit sky and a dark photo is left alone.
        float lum = luminance(0, top, width * 0.45f, barBottom() - barH);
        float shade = clamp01((lum - 0.28f) / 0.42f) * ui.value;
        if (shade > 0.01f) {
            g.fillRectLinear(0, 0, width * 0.62f, barBottom() - barH * 0.5f, 0, 0, width * 0.62f, 0, SCRIM, SCRIM_STOPS, 0.8f * shade);
        }
        float shiftT = (now % (37 * 60000L)) / (37 * 60000f) * (float) Math.PI * 2;
        float ox = dp(5) * (float) Math.sin(shiftT) * (0.4f + 0.6f * dim.value);
        float oy = dp(4) * (float) Math.cos(shiftT * 1.3f) * (0.4f + 0.6f * dim.value);
        float x = side + ox;
        float a = 1 - 0.15f * dim.value;
        int on = ON_PHOTO;
        float label = dp(12.5f);
        float y0 = top + dp(18) + oy;
        words(now);
        g.text(eyebrowText, Gfx.MONO, label, 0.1f, x + dp(3), y0, Pixels.alpha(on, 0.78f * a), Gfx.LEFT, 0.5f);
        float size = dp(104) * (1 + 0.16f * amb);
        float base = y0 + dp(14) + g.capHeight(Gfx.SERIF_LIGHT, size);
        g.text(time, Gfx.SERIF_LIGHT, size, -0.025f, x - dp(3), base, Pixels.alpha(on, a), Gfx.LEFT, 0.5f);
        if (!ampm.isEmpty()) {
            float w = g.measure(time, Gfx.SERIF_LIGHT, size, -0.025f);
            g.text(ampm, Gfx.MONO, size * 0.13f, 0.12f, x + w + dp(8), base - size * 0.02f, Pixels.alpha(on, 0.82f * a), Gfx.LEFT, 0.5f);
        }
        float ry = base + dp(18);
        g.fillRectLinear(x, ry, x + dp(84), ry + Math.max(2, dp(1)), x, 0, x + dp(84), 0, RULE, THREE_STOPS, 0.9f * a);
        // The standfirst: the greeting arrives word by word when the home screen comes back.
        float sy = ry + dp(36);
        String greet = greetText;
        float gs = dp(25);
        float appear = reduced ? 1 : Math.max(0, Math.min(1, (now - enteredAt - 250) / 700f));
        float rise = (1 - easeOut(appear)) * dp(10);
        g.text(greet, Gfx.SERIF_ITALIC, gs, 0, x, sy + rise, Pixels.alpha(on, a * appear), Gfx.LEFT, 0.6f);
        float gx = x + g.measure(greet, Gfx.SERIF_ITALIC, gs, 0) + dp(9);
        // Clear of the pinned note where it reaches down beside the standfirst.
        float right = pinnedBox(pinBox) && pinBox[3] > sy - gs ? pinBox[0] - dp(24) : width - side;
        float maxW = right - gx;
        String sky = fitSky(g, skyText, gs * 0.86f, maxW);
        if (!sky.isEmpty()) {
            float appear2 = reduced ? 1 : Math.max(0, Math.min(1, (now - enteredAt - 550) / 700f));
            g.text(sky, Gfx.SERIF, gs * 0.86f, 0, gx, sy + (1 - easeOut(appear2)) * dp(10), Pixels.alpha(on, 0.86f * a * appear2),
                    Gfx.LEFT, 0.6f);
        }
        // A National Weather Service warning, in amber, until it ends.
        Model.Alert serious = null;
        for (int i = 0; i < alerts.size(); i++) {
            Model.Alert x2 = alerts.get(i);
            if (x2.serious() || "Moderate".equals(x2.severity)) {
                serious = x2;
                break;
            }
        }
        float ay = sy + dp(30);
        if (serious != null) {
            g.fillCircle(x + dp(5), ay - dp(5), dp(4), Pixels.alpha(0xFFFFA040, a));
            g.text(Text.fit(g, serious.event + (serious.area.isEmpty() ? "" : "  ·  NWS"), Gfx.TEXT_SEMIBOLD, dp(15), dp(560)),
                    Gfx.TEXT_SEMIBOLD, dp(15), 0, x + dp(16), ay, Pixels.alpha(0xFFFFB866, a), Gfx.LEFT, 0.6f);
            ay += dp(26);
        }
        clockBottom = ay;
        // What is next on the shared calendar. On the dashboard the day's list says it instead.
        float nextA = a * (1 - amb);
        if (!nextText.isEmpty() && nextA > 0.003f) {
            float appear3 = reduced ? 1 : Math.max(0, Math.min(1, (now - enteredAt - 850) / 700f));
            g.fillCircle(x + dp(5), ay - dp(5), dp(3.5f), Pixels.alpha(Tone.ROSE, nextA * appear3));
            g.text(nextText, Gfx.TEXT_SEMIBOLD, dp(15), 0, x + dp(16), ay, Pixels.alpha(on, 0.86f * nextA * appear3), Gfx.LEFT, 0.6f);
        }
    }

    /**
     * The dashboard's day, under the clock: what is on today and tomorrow from the shared
     * calendar, and the nearest countdown. Built once when the calendar or the minute changes.
     */
    private void drawToday(Gfx g, float a, long now) {
        float grown = panel.value;
        for (Spring gs : grow) grown = Math.max(grown, gs.value);
        a *= 1 - clamp01(grown * 1.4f);
        if (a <= 0.003f || todayWhat.length == 0 && todayCount.isEmpty()) return;
        float appear = reduced ? 1 : Math.max(0, Math.min(1, (now - enteredAt - 900) / 700f));
        a *= appear * (1 - 0.15f * dim.value);
        float x = side + dp(2), y = clockBottom + dp(8);
        float limit = barBottom() - barH - dp(34);
        int on = ON_PHOTO;
        for (int i = 0; i < todayWhat.length; i++) {
            if (todayLabel[i] != null) {
                if (y + dp(24) > limit) break;
                g.text(todayLabel[i], Gfx.MONO, dp(11.5f), 0.1f, x, y + dp(4), Pixels.alpha(on, 0.78f * a), Gfx.LEFT, 0.5f);
                y += dp(24);
            }
            if (y + dp(28) > limit) break;
            g.text(todayWhen[i], Gfx.MONO, dp(13), 0.02f, x, y + dp(4), Pixels.alpha(on, 0.78f * a), Gfx.LEFT, 0.5f);
            g.text(Text.fit(g, todayWhat[i], Gfx.TEXT_MEDIUM, dp(18), dp(440)), Gfx.TEXT_MEDIUM, dp(18), 0, x + dp(92), y + dp(5),
                    Pixels.alpha(on, 0.94f * a), Gfx.LEFT, 0.6f);
            y += dp(30);
        }
        if (!todayCount.isEmpty() && y + dp(34) <= limit) {
            g.text(todayCount, Gfx.SERIF_ITALIC, dp(22), 0, x, y + dp(14), Pixels.alpha(on, 0.9f * a), Gfx.LEFT, 0.6f);
        }
    }

    /** "Apps" with a down arrow beside the bar for a few seconds, so the dock is never a secret. */
    private void drawHint(Gfx g, float amb, long now) {
        if (!resting || mode != NONE) return;
        float a = Math.max(0, Math.min(1, (hintUntil - now) / 900f)) * amb * ui.value * (1 - dim.value);
        if (a <= 0.003f) return;
        float cy = barBottom() - barH / 2, x = barRight + dp(18);
        if (x + dp(110) > dockR) return;
        int ink = Pixels.alpha(ON_PHOTO, 0.8f * a);
        g.line(x + dp(2), cy - dp(3), x + dp(8), cy + dp(3), dp(2), ink);
        g.line(x + dp(8), cy + dp(3), x + dp(14), cy - dp(3), dp(2), ink);
        g.text("APPS", Gfx.MONO, dp(12), 0.12f, x + dp(24), cy + g.capHeight(Gfx.MONO, dp(12)) / 2, Pixels.alpha(ON_PHOTO, 0.8f * a), Gfx.LEFT, 0.6f);
    }

    /**
     * The standfirst within {@code maxW}: "Partly cloudy, 53°. Rain from 3:45 PM. Sunset 5:25."
     * loses its last sentences first, so it never runs under the pinned note. Measured only when
     * the words or the room change.
     */
    private String fitSky(Gfx g, String sky, float size, float maxW) {
        if (sky.equals(skyFitFor) && Math.abs(maxW - skyFitW) < 1) return skyFit;
        skyFitFor = sky;
        skyFitW = maxW;
        String fit = sky;
        while (g.measure(fit, Gfx.SERIF, size, 0) > maxW) {
            int cut = fit.lastIndexOf(". ", fit.length() - 2);
            if (cut < 0) {
                fit = Text.fit(g, fit, Gfx.SERIF, size, Math.max(0, maxW));
                break;
            }
            fit = fit.substring(0, cut + 1);
        }
        skyFit = fit;
        return fit;
    }

    /** Rebuilds the clock's words when the minute, the weather, the calendar or the look changes. */
    private void words(long now) {
        long key = now / 20000 * 1000 + (textVersion & 999);
        if (key == textKey) return;
        textKey = key;
        eyebrowText = (date + "  ·  " + city).toUpperCase(Locale.US);
        greetText = greeting() + ".";
        skyText = standfirst(now);
        nextText = nextEvent(now);
        today(now);
    }

    /** Up to four events from now to the end of tomorrow, labelled by day, and the nearest countdown. */
    private void today(long now) {
        List<String> label = new ArrayList<>(), when = new ArrayList<>(), what = new ArrayList<>();
        List<Ical.Event> list = cards.events;
        java.util.TimeZone zone = java.util.TimeZone.getDefault();
        long dayStart = Sun.startOfDay(now, zone), tomorrow = Sun.startOfDay(dayStart + 36 * 3600000L, zone);
        long end = Sun.startOfDay(tomorrow + 36 * 3600000L, zone);
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(cards.h24 ? "H:mm" : "h:mm a", Locale.US);
        String lastDay = null;
        for (int i = 0; list != null && i < list.size() && what.size() < 4; i++) {
            Ical.Event e = list.get(i);
            if (e.end <= now || e.start >= end) continue;
            String day = e.start < tomorrow ? "TODAY" : "TOMORROW";
            label.add(day.equals(lastDay) ? null : day);
            lastDay = day;
            when.add(e.allDay ? "All day" : e.start <= now ? "Now" : f.format(new java.util.Date(e.start)));
            what.add(e.title.isEmpty() ? "Busy" : e.title);
        }
        todayLabel = label.toArray(new String[0]);
        todayWhen = when.toArray(new String[0]);
        todayWhat = what.toArray(new String[0]);
        String count = "";
        int best = Integer.MAX_VALUE;
        List<Plans.Countdown> cds = cards.countdowns;
        for (int i = 0; cds != null && i < cds.size(); i++) {
            Plans.Countdown c = cds.get(i);
            int d = Plans.daysTo(c.date, now, zone);
            if (d < 0 || d >= best) continue;
            best = d;
            count = d == 0 ? c.title + " is today" : d == 1 ? "Tomorrow, " + c.title : d + " days to " + c.title;
        }
        todayCount = count;
    }

    /** "Now  ·  Seminar, until 3:30 PM" or "Next  ·  Dinner at 6:30 PM", for the coming eight hours. */
    private String nextEvent(long now) {
        List<Ical.Event> list = cards.events;
        if (list == null) return "";
        Ical.Event best = null;
        for (Ical.Event e : list) {
            if (e.allDay || e.end <= now || e.start - now > 8 * 3600000L) continue;
            if (best == null || e.start < best.start) best = e;
        }
        if (best == null) return "";
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(cards.h24 ? "H:mm" : "h:mm a", Locale.US);
        String title = best.title.isEmpty() ? "Busy" : best.title;
        if (title.length() > 48) title = title.substring(0, 48).trim() + "…";
        return best.start <= now ? "Now  ·  " + title + ", until " + f.format(new java.util.Date(best.end))
                : "Next  ·  " + title + " at " + f.format(new java.util.Date(best.start));
    }

    /** "Clear, 54°. Sunset 5:41." with the moon at night. */
    private String standfirst(long now) {
        StringBuilder b = new StringBuilder();
        Model.Weather w = weather;
        if (weatherOn && w != null && w.ok) {
            b.append(w.condition).append(", ").append(w.lead(w.tempC)).append('°').append('.');
            if (w.wetSoon) b.append(' ').append(w.rain).append('.');
        }
        if (sunset > 0) {
            boolean beforeSunset = now < sunset && now > sunrise;
            long at = beforeSunset ? sunset : sunrise + (now > sunset ? 86400000L : 0);
            String when = new java.text.SimpleDateFormat(cards.h24 ? "H:mm" : "h:mm", Locale.US).format(new java.util.Date(at));
            if (b.length() > 0) b.append(' ');
            b.append(beforeSunset ? "Sunset " : "Sunrise ").append(when).append('.');
        }
        if (night() && moon > 0) {
            if (b.length() > 0) b.append(' ');
            b.append(Sun.moonName(moon)).append('.');
        }
        return b.toString();
    }

    private static float easeOut(float t) {
        float u = 1 - Math.max(0, Math.min(1, t));
        return 1 - u * u * u;
    }

    // ---- Glass -------------------------------------------------------------------------------

    /**
     * One pane of glass: a soft shadow, the blurred wallpaper slightly magnified (lensing),
     * adaptive dimming from the luminance underneath (or, by day, a frosted light tint), light
     * from above, a refracting edge band and a specular rim whose light direction drifts and
     * reacts to focus moves.
     */
    void glass(Gfx g, float l, float t, float r, float b, float rad, float strength, float extraDim, float time) {
        if (strength <= 0.003f) return;
        glassBody(g, l, t, r, b, rad, strength, extraDim);
        glassRim(g, l, t, r, b, rad, strength, time);
    }

    private void glassBody(Gfx g, float l, float t, float r, float b, float rad, float strength, float extraDim) {
        int a = Math.round(255 * Math.min(1, strength));
        float pad = dp(24);
        drawShadow(g, l, t, r, b, rad, pad, dp(10), Math.round(255 * (0.5f - 0.22f * look.value) * strength));
        float cx = (l + r) / 2, cy = (t + b) / 2;
        backdrop(g, l, t, r, b, rad, cx, cy, a);
        tint(g, l, t, r, b, rad, strength, extraDim, luminance(l, t, r, b));
        g.fillRoundRectLinear(l, t, r, b, rad, 0, t, 0, b, TOP_LIGHT, TOP_LIGHT_STOPS, strength);
        if (wall != null && wall.blur != null) {
            float lens = 1.18f;
            g.fillRingImage(wall.blur, cx + (wallRect[0] - cx) * lens, cy + (wallRect[1] - cy) * lens,
                    cx + (wallRect[2] - cx) * lens, cy + (wallRect[3] - cy) * lens,
                    l, t, r, b, rad, dp(3.5f), Math.round(a * 0.55f));
        }
    }

    /**
     * The glass's own tone. Night: darkened to suit the light behind it, a little more where it
     * carries more text. Day: frosted with warm white, so dark ink reads on it.
     */
    private void tint(Gfx g, float l, float t, float r, float b, float rad, float strength, float extraDim, float lum) {
        float day = look.value;
        if (day < 0.999f) {
            float dimA = (glassDim(lum) + extraDim + 0.06f) * (1 - day);
            g.fillRoundRect(l, t, r, b, rad, Pixels.alpha(0xFF14120F, Math.min(0.85f, dimA) * strength));
        }
        if (day > 0.001f) {
            float lightA = (0.5f + extraDim * 0.6f + Math.max(0, 0.3f - lum) * 0.5f) * day;
            g.fillRoundRect(l, t, r, b, rad, Pixels.alpha(0xFFFAF7F2, Math.min(0.9f, lightA) * strength));
        }
    }

    /** The blurred wallpaper behind a pane, magnified about the pane's centre (lensing). */
    private void backdrop(Gfx g, float l, float t, float r, float b, float rad, float cx, float cy, int a) {
        if (wall == null || wall.blur == null) {
            g.fillRoundRect(l, t, r, b, rad, Pixels.alpha(0xFF1B1F2A, a / 255f));
            return;
        }
        float lens = 1.04f;
        float il = cx + (wallRect[0] - cx) * lens, it = cy + (wallRect[1] - cy) * lens;
        float ir = cx + (wallRect[2] - cx) * lens, ib = cy + (wallRect[3] - cy) * lens;
        if (oldWall != null && oldWall.blur != null && fade.value < 1f) {
            g.fillRoundRectImage(oldWall.blur, il, it, ir, ib, l, t, r, b, rad, a);
            g.fillRoundRectImage(wall.blur, il, it, ir, ib, l, t, r, b, rad, Math.round(a * fade.value));
        } else {
            g.fillRoundRectImage(wall.blur, il, it, ir, ib, l, t, r, b, rad, a);
        }
    }

    private static float glassDim(float lum) {
        return Math.max(0.05f, Math.min(0.46f, 0.08f + (lum - 0.16f) * 0.85f));
    }

    private void glassRim(Gfx g, float l, float t, float r, float b, float rad, float strength, float time) {
        float w = r - l, h = b - t, cx = (l + r) / 2, cy = (t + b) / 2;
        float angle = (float) (-2.3 + 0.16 * Math.sin(time * 0.23) + light.value * 0.18);
        float diag = (float) Math.sqrt(w * w + h * h) / 2;
        float dx = (float) Math.cos(angle) * diag, dy = (float) Math.sin(angle) * diag;
        float half = dp(0.6f);
        g.strokeRoundRectLinear(l + half, t + half, r - half, b - half, rad - half, dp(1.2f),
                cx + dx, cy + dy, cx - dx, cy - dy, RIM, RIM_STOPS, strength);
    }

    /**
     * A soft scroll edge: the dock's own glass, redrawn over the tiles in thin strips whose
     * opacity falls from 1 at the edge to 0, so tiles dissolve into the pane instead of being cut.
     */
    private void scrollEdge(Gfx g, float from, float to, float t, float b, float strength) {
        int strips = 16;
        float cx = (dockL + dockR) / 2, cy = (dockT + dockB) / 2;
        float lum = luminance(dockL, dockT, dockR, dockB);
        for (int i = 0; i < strips; i++) {
            float x0 = from + (to - from) * i / strips, x1 = from + (to - from) * (i + 1) / strips;
            float l = Math.min(x0, x1), r = Math.max(x0, x1) + 0.5f;
            float a = strength * (1 - (i + 0.5f) / strips);
            a = a * a * (3 - 2 * a);
            backdrop(g, l, t, r, b, 0, cx, cy, Math.round(255 * a));
            tint(g, l, t, r, b, 0, a, 0, lum);
            g.fillRectLinear(l, t, r, b, 0, dockT, 0, dockB, TOP_LIGHT, TOP_LIGHT_STOPS, a);
        }
    }

    private float lumKeyL = Float.NaN, lumKeyT, lumKeyR, lumKeyB, lumLast;
    private final Map<LongKey, Float> lumCache = new HashMap<>();
    private Object lumOwner;

    /** Mean luminance of the blurred wallpaper under a rectangle, cached by 8 px cell. */
    private float luminance(float l, float t, float r, float b) {
        Model.Wall w = wall;
        if (w == null || w.blurPixels == null) return 0.15f;
        if (l == lumKeyL && t == lumKeyT && r == lumKeyR && b == lumKeyB && lumOwner == w.blurPixels) return lumLast;
        if (lumOwner != w.blurPixels) {
            lumCache.clear();
            lumOwner = w.blurPixels;
        }
        long key = ((long) Math.round(l / 8) & 0xFFFF) << 48 | ((long) Math.round(t / 8) & 0xFFFF) << 32
                | ((long) Math.round(r / 8) & 0xFFFF) << 16 | ((long) Math.round(b / 8) & 0xFFFF);
        Float hit = lumCache.get(probe.set(key));
        float lum;
        if (hit != null) {
            lum = hit;
        } else {
            float fl = (l - wallRect[0]) / (wallRect[2] - wallRect[0]), fr = (r - wallRect[0]) / (wallRect[2] - wallRect[0]);
            float ft = (t - wallRect[1]) / (wallRect[3] - wallRect[1]), fb = (b - wallRect[1]) / (wallRect[3] - wallRect[1]);
            lum = Pixels.luminance(w.blurPixels, w.blurW, w.blurH, fl, ft, fr, fb);
            if (lumCache.size() > 4096) lumCache.clear();
            lumCache.put(new LongKey(key), lum);
        }
        lumKeyL = l;
        lumKeyT = t;
        lumKeyR = r;
        lumKeyB = b;
        lumLast = lum;
        return lum;
    }

    private static final float SHADOW_SCALE = 0.25f;

    /**
     * A soft shadow under a rounded rectangle, drawn in slices from a small pre-rendered image:
     * corners as rendered, the middle stretched. Shapes taller than the blur's reach use one
     * image per corner radius in nine slices; a thinner shape (a capsule, a caption) is fainter
     * in the middle, so its image keeps the real height, rounded to 8 dp, and is sliced across
     * only. Either way a card changing shape every frame reuses a few images.
     */
    void drawShadow(Gfx g, float l, float t, float r, float b, float rad, float blur, float dy, int alpha) {
        if (alpha <= 0 || r <= l || b <= t) return;
        float q = dp(3);
        rad = Math.round(rad / q) * q;
        float side = 2 * (rad + 1.6f * blur) + 2;
        boolean thin = b - t < side;
        float hq = dp(8);
        float baseH = thin ? Math.max(hq, Math.round((b - t) / hq) * hq) : side;
        long key = (long) Math.round(rad) << 40 | (long) Math.round(blur) << 20 | (thin ? Math.round(baseH) + 1 : 0);
        Object img = shadows.get(probe.set(key));
        if (img == null) {
            int[] px = Pixels.shadow(side, baseH, rad, blur, SHADOW_SCALE);
            int iw = px[0], ih = px[1];
            int[] pixels = new int[iw * ih];
            System.arraycopy(px, 2, pixels, 0, pixels.length);
            img = g.image(pixels, iw, ih);
            shadows.put(new LongKey(key), img);
        }
        int iw = g.imageWidth(img), ih = g.imageHeight(img);
        int cx = iw / 2, cy = ih / 2;
        float cw = cx / SHADOW_SCALE, ch = cy / SHADOW_SCALE;
        float L = l - blur, T = t - blur + dy, R = r + blur, B = b + blur + dy;
        if (R - L < 2 * cw + 1) {
            g.drawImage(img, L, T, R, B, alpha);
            return;
        }
        float x1 = L + cw, x2 = R - cw;
        if (thin) {
            g.drawImagePart(img, 0, 0, cx, ih, L, T, x1, B, alpha);
            g.drawImagePart(img, cx, 0, cx + 1, ih, x1, T, x2, B, alpha);
            g.drawImagePart(img, cx + 1, 0, iw, ih, x2, T, R, B, alpha);
            return;
        }
        float y1 = T + ch, y2 = B - ch;
        g.drawImagePart(img, 0, 0, cx, cy, L, T, x1, y1, alpha);
        g.drawImagePart(img, cx, 0, cx + 1, cy, x1, T, x2, y1, alpha);
        g.drawImagePart(img, cx + 1, 0, iw, cy, x2, T, R, y1, alpha);
        g.drawImagePart(img, 0, cy, cx, cy + 1, L, y1, x1, y2, alpha);
        g.drawImagePart(img, cx, cy, cx + 1, cy + 1, x1, y1, x2, y2, alpha);
        g.drawImagePart(img, cx + 1, cy, iw, cy + 1, x2, y1, R, y2, alpha);
        g.drawImagePart(img, 0, cy + 1, cx, ih, L, y2, x1, B, alpha);
        g.drawImagePart(img, cx, cy + 1, cx + 1, ih, x1, y2, x2, B, alpha);
        g.drawImagePart(img, cx + 1, cy + 1, iw, ih, x2, y2, R, B, alpha);
    }

    // ---- Top controls ------------------------------------------------------------------------

    /**
     * The rectangle of top control {@code id}: circles for Fire TV home, mirroring, phone and
     * settings, a pill for Sound that names the format. Null Gfx estimates widths (for focus math).
     */
    private float[] topRect(int id, Gfx g) {
        float r = width - side;
        float y0 = top + dp(2), y1 = y0 + capH;
        float circle = capH;
        float audioW = capH + dp(10) + textWidth(g, audioLabel(), Gfx.TEXT_MEDIUM, dp(16)) + dp(20);
        float gap = dp(12);
        float x = r;
        for (int i = T_COUNT - 1; i >= 0; i--) {
            float w = i == T_AUDIO ? audioW : circle;
            if (i == id) return new float[] {x - w, y0, x, y1};
            x -= w + gap;
        }
        return new float[] {r - circle, y0, r, y1};
    }

    private float textWidth(Gfx g, String s, int face, float size) {
        if (g != null) return g.measure(s, face, size, 0);
        return s.length() * size * 0.55f;
    }

    private String audioLabel() {
        if (audio != null && "Bluetooth".equals(audio.route)) return audio.device.isEmpty() ? "Headphones" : audio.device;
        return audio == null ? "Sound" : audio.headline;
    }

    private void drawTop(Gfx g, float uiA, float t) {
        if (uiA <= 0.003f) return;
        float rise = (1 - ui.value) * dp(-14);
        for (int id = 0; id < T_COUNT; id++) {
            float[] r = topRect(id, g);
            float f = topSpring[id].value;
            float s = 1 + 0.1f * f;
            float cx = (r[0] + r[2]) / 2, cy = (r[1] + r[3]) / 2 + rise;
            int save = g.save();
            g.translate(0, rise);
            g.scale(s, cx, cy);
            float rad = (r[3] - r[1]) / 2;
            glass(g, r[0], r[1], r[2], r[3], rad, uiA, 0, t);
            if (f > 0.003f) g.fillRoundRect(r[0], r[1], r[2], r[3], rad, Tone.focus(0.93f * f * uiA));
            int ink = Tone.onFocus(f, uiA);
            float mid = (r[1] + r[3]) / 2;
            switch (id) {
                case T_GEAR:
                    Icons.gear(g, cx, mid, dp(24), ink);
                    break;
                case T_PHONE:
                    Icons.phone(g, cx, mid, dp(22), ink);
                    break;
                case T_MIRROR:
                    Icons.mirror(g, cx, mid, dp(22), ink);
                    break;
                case T_EXIT:
                    Icons.exit(g, cx, mid, dp(22), ink);
                    break;
                case T_ASK:
                    Icons.sparkle(g, cx, mid, dp(24), ink);
                    break;
                case T_CALL:
                    Icons.video(g, cx, mid, dp(22), ink);
                    break;
                case T_MAP:
                    Icons.map(g, cx, mid, dp(22), ink);
                    break;
                default: {
                    float iconC = r[0] + capH / 2;
                    boolean bt = audio != null && "Bluetooth".equals(audio.route);
                    if (bt) Icons.earbuds(g, iconC + dp(4), mid, dp(22), ink);
                    else Icons.sound(g, iconC + dp(4), mid, dp(22), ink);
                    g.text(audioLabel(), Gfx.TEXT_MEDIUM, dp(16), 0, iconC + capH / 2 + dp(2),
                            mid + g.capHeight(Gfx.TEXT_MEDIUM, dp(16)) / 2, ink, Gfx.LEFT, 0);
                }
            }
            g.restore(save);
            // The focused control names itself beneath, the way tvOS labels focused icons.
            if (f > 0.02f && id != T_AUDIO) {
                g.text(TOP_NAMES[id], Gfx.TEXT_MEDIUM, dp(13), 0.01f, cx, r[3] + dp(24) + rise, Pixels.alpha(ON_PHOTO, f * uiA),
                        Gfx.CENTER, 0.8f);
            }
        }
    }

    // ---- Pinned note ---------------------------------------------------------------------------

    private final float[] sizeBuf = new float[2];

    static final float NOTE_DRAW = 460, PIN_DRAW = 300, PIN_SCALE = 0.62f;

    /** The popped-up note's scale: as large as fits, with room for the hint below it. */
    private float noteScale(float[] size) {
        return Math.min(1f, Math.min((height - dp(96)) / dp(size[1]), (width - dp(240)) / dp(size[0])));
    }

    /**
     * The note kept on the home screen: a small card on the right, leaning back a few degrees as
     * if propped against the glass. It writes itself once when it appears.
     */
    private void drawPinned(Gfx g, float uiA, float t) {
        Notes.Note n = pinned;
        float p = pinSpring.value;
        if (!pinnedBox(pinBox)) return;
        // The note belongs to the dashboard: it steps away while the apps are up.
        float a = p * uiA * (1 - modal.value) * (1 - notice.value);
        if (a <= 0.003f) return;
        float l = pinBox[0], tp = pinBox[1], r = pinBox[2], h = pinBox[3] - tp, k = pinBox[4];
        int save = g.save();
        if (!reduced) g.tilt(1.5f, -6f + 1.2f * (float) Math.sin(t * 0.7), (l + r) / 2, tp + h / 2);
        g.translate((1 - p) * dp(30), 0);
        Pop.note(g, n, pinnedInk, l, tp, r, tp + h, d * k, Math.min(1, p * 1.2f), a, t, cards.h24);
        g.restore(save);
    }

    /**
     * Where the pinned note sits, as {left, top, right, bottom, scale}: the popped-up note at a
     * smaller scale, sized to the room between the wallpaper's caption and the glance bar, so a
     * drawing never runs under the bar. False when no note is pinned.
     */
    private boolean pinnedBox(float[] out) {
        Notes.Note n = pinned;
        if (n == null || pinSpring.value <= 0.003f || mode == NOTE) return false;
        float[] size = Pop.noteSize(n, PIN_DRAW, sizeBuf);
        float tp = lerp(top + capH + dp(60), top + dp(52), rest.value);
        float room = dockB - barH - dp(18) - tp;
        float k = Math.max(0.3f, Math.min(PIN_SCALE, room / dp(size[1])));
        out[0] = width - side - dp(size[0]) * k;
        out[1] = tp;
        out[2] = width - side;
        out[3] = tp + dp(size[1]) * k;
        out[4] = k;
        return true;
    }

    // ---- Glance bar ----------------------------------------------------------------------------

    private static final int[] DIVIDER = {0x00FFFFFF, 0x2EFFFFFF, 0x00FFFFFF};

    /**
     * One pane of glass holding a segment per subject, separated by hairlines. The focused
     * segment grows up out of the bar into a card (the same glass changing shape), lifting and
     * catching the light; its compact line fades as the card's detail fades in.
     */
    private void drawGlance(Gfx g, float alpha, float t) {
        if (alpha <= 0.003f) return;
        cards.transitShown = 2;
        for (int k = 0; k < Cards.COUNT; k++) segW[k] = cards.compactWidth(g, k);
        // Music, weather, bikes and two shuttle lines can be wider than the screen on their own:
        // the shuttles then show the next one only.
        if (coreWidth(weatherOn && weather != null && weather.ok) > dockR - barL) {
            cards.transitShown = 1;
            segW[Cards.TRANSIT] = cards.compactWidth(g, Cards.TRANSIT);
        }
        int[] kinds = glanceKinds();
        float bottom = barBottom(), topY = bottom - barH;
        float total = 0;
        for (int k : kinds) total += segW[k];
        float drop = (1 - ui.value) * dp(20);
        int save = g.save();
        g.translate(glanceLight.value * dp(1.5f), drop);
        float l = barL, r = barL + total, rad = barH / 2;
        barRight = r;
        glass(g, l, topY, r, bottom, rad, alpha, 0, t);
        float x = l;
        float grown = 0;
        for (int k : kinds) grown = Math.max(grown, grow[k].value);
        for (int i = 0; i < kinds.length; i++) {
            int k = kinds[i];
            float e = grow[k].value;
            // Out-of-focus segments step back while a card is up, so the card reads as in front.
            float a = alpha * clamp01(1 - e * 2.2f) * (1 - 0.5f * Math.max(0, grown - e));
            cards.compact(g, k, x, topY, x + segW[k], bottom, a);
            if (i > 0) {
                float hx = x;
                g.fillRectLinear(hx - dp(0.5f), topY + dp(10), hx + dp(0.5f), bottom - dp(10), 0, topY + dp(10), 0, bottom - dp(10),
                        DIVIDER, THREE_STOPS, alpha);
            }
            x += segW[k];
        }
        // The grown card, unless a panel has taken the glass over.
        for (int k : kinds) {
            float e = grow[k].value;
            if (e <= 0.003f) continue;
            if (panel.value > 0.003f && k == panelKind) continue;
            float[] c = cardRect(k, e);
            float lift = 1 + 0.03f * e;
            int s2 = g.save();
            g.scale(lift, (c[0] + c[2]) / 2, c[3]);
            float crad = lerp(rad, dp(30), e);
            glassLifted(g, c[0], c[1], c[2], c[3], crad, alpha, e, t);
            float ca = alpha * clamp01((e - 0.45f) / 0.55f);
            cards.expanded(g, k, c[0], c[1], c[2], c[3], ca);
            float sa = alpha * clamp01(1 - e * 2.2f);
            if (sa > 0.003f) cards.compact(g, k, c[0], c[3] - barH, c[0] + segW[k], c[3], sa);
            g.restore(s2);
        }
        g.restore(save);
    }

    /** Glass with a focus lift: a deeper shadow and a brighter rim, scaled by {@code f}. */
    private void glassLifted(Gfx g, float l, float t, float r, float b, float rad, float alpha, float f, float time) {
        glassLifted(g, l, t, r, b, rad, alpha, f, time, 0.06f);
    }

    /** {@code extraDim} thickens the glass where it carries more text (Apple: thicker for more text). */
    void glassLifted(Gfx g, float l, float t, float r, float b, float rad, float alpha, float f, float time, float extraDim) {
        float pad = dp(28);
        drawShadow(g, l, t, r, b, rad, pad, dp(14) * f, Math.round(255 * (0.4f - 0.18f * look.value) * f * alpha));
        glass(g, l, t, r, b, rad, alpha, extraDim, time);
        float half = dp(0.75f);
        g.strokeRoundRectLinear(l + half, t + half, r - half, b - half, rad - half, dp(1.5f), l, t, r, b, TILE_RIM, THREE_STOPS,
                alpha * f * 0.7f);
    }

    // ---- Panels ------------------------------------------------------------------------------

    /** The panel: the focused card's glass, grown to the middle of the screen. */
    private void drawPanel(Gfx g, float t) {
        float p = panel.value;
        if (p <= 0.003f) return;
        float pw = panelW.value, ph = panelH.value;
        float tl = (width - pw) / 2, tt = (height - ph) / 2 - dp(6);
        float l = lerp(panelFrom[0], tl, p), top0 = lerp(panelFrom[1], tt, p);
        float r = lerp(panelFrom[2], tl + pw, p), b = lerp(panelFrom[3], tt + ph, p);
        float rad = lerp(dp(30), dp(40), p);
        glassLifted(g, l, top0, r, b, rad, 1f, 1f - 0.6f * p, t, lerp(0.06f, 0.16f, p));
        float cardA = clamp01(1 - p * 2.4f);
        if (cardA > 0.003f) cards.expanded(g, panelKind, l, top0, l + cards.expandedWidth(panelKind), top0 + dp(cards.cardHeight(panelKind)), cardA);
        float a = clamp01((p - 0.5f) / 0.5f);
        if (a <= 0.003f) return;
        int clip = g.save();
        g.clipRect(l, top0, r, b);
        float sw = panelSwap.value;
        if (sw < 0.999f && panelPrev != panelKind) {
            cards.panel(g, panelPrev, l, top0, r, b, a * clamp01(1 - sw * 2), 0, rad);
        }
        cards.panel(g, panelKind, l, top0, r, b, a * clamp01(sw * 1.4f - 0.2f), button.value, rad);
        g.restore(clip);
        dots(g, (l + r) / 2, b + dp(22), a);
    }

    /** Page dots under a panel: Left and Right move between the subjects. */
    private void dots(Gfx g, float cx, float cy, float a) {
        int[] kinds = glanceKinds();
        float gap = dp(14);
        float x = cx - gap * (kinds.length - 1) / 2;
        for (int k : kinds) {
            boolean on = k == panelKind;
            g.fillCircle(x, cy, dp(on ? 3.5f : 3f), Pixels.alpha(ON_PHOTO, (on ? 0.95f : 0.4f) * a));
            x += gap;
        }
    }

    // ---- Alerts and moments ----------------------------------------------------------------------

    /** Bike finder's alert on the home screen: top right, on real glass, above everything. */
    private void drawNotice(Gfx g, long now, float t) {
        float p = notice.value;
        if (p <= 0.003f || noticeSpec == null) return;
        float w = dp(Notice.CARD_W), h = dp(Notice.CARD_H);
        float r = width - side, top0 = top;
        float l = r - w, b = top0 + h;
        int save = g.save();
        g.translate((1 - p) * dp(18), 0);
        g.scale(0.92f + 0.08f * p, r, top0);
        float rad = h / 4;
        glassLifted(g, l, top0, r, b, rad, p, p, t);
        if (noticeSpec.kind == Notice.FOUND) {
            g.strokeRoundRect(l + dp(1), top0 + dp(1), r - dp(1), b - dp(1), rad - dp(1), dp(2), Pixels.alpha(noticeSpec.accent, 0.55f * p));
        }
        Notice.content(g, l, top0, r, b, d, noticeSpec, p, (now - noticeAt) / 1000f, reduced);
        g.restore(save);
    }

    /** The bike alarm, top centre, on glass. Any button stops it. */
    private void drawAlarm(Gfx g, long now, float t) {
        float p = alarmSpring.value;
        if (p <= 0.003f || alarmFinder == null) return;
        float w = dp(Pop.ALARM_W), h = dp(Pop.ALARM_H);
        float l = (width - w) / 2, tp = dp(48) - (1 - p) * dp(30);
        int save = g.save();
        g.scale(0.94f + 0.06f * p, width / 2f, tp);
        glassLifted(g, l, tp, l + w, tp + h, dp(40), p, 1, t, 0.14f);
        Pop.alarm(g, alarmFinder, alarmStation, l, tp, d, p, (now - alarmAt) / 1000f, true, reduced);
        g.restore(save);
    }

    /** A new note, big in the middle of the screen, writing itself. */
    private void drawNote(Gfx g, float t, long now) {
        float p = noteSpring.value;
        Notes.Note n = popNote;
        if (p <= 0.003f || n == null) return;
        float[] size = Pop.noteSize(n, NOTE_DRAW, sizeBuf);
        float k = noteScale(size);
        float w = dp(size[0]) * k, h = dp(size[1]) * k;
        float l = (width - w) / 2, tp = (height - h) / 2 - dp(18);
        float since = (now - noteAt) / 1000f;
        float progress = reduced ? 1 : Math.max(0, Math.min(1, (since - 0.45f) / Ink.seconds(n)));
        int save = g.save();
        g.scale(0.86f + 0.14f * p, width / 2f, height / 2f);
        if (!reduced) g.tilt((1 - p) * 10f, 0, width / 2f, tp + h);
        Pop.note(g, n, popInk, l, tp, l + w, tp + h, d * k, progress, Math.min(1, p), since, cards.h24);
        g.restore(save);
        if (p > 0.6f) {
            g.text("OK or Back to close", Gfx.TEXT_MEDIUM, dp(14), 0.01f, width / 2f, tp + h + dp(32),
                    Pixels.alpha(ON_PHOTO, 0.7f * p), Gfx.CENTER, 0.6f);
        }
    }

    /** A short pill at the top centre: headphones connected, a photo arrived. */
    private void drawToast(Gfx g, float t, long now) {
        float p = toastSpring.value;
        if (p <= 0.003f || toastTitle.isEmpty()) return;
        float h = dp(64);
        float tw = Math.max(g.measure(toastTitle, Gfx.TEXT_SEMIBOLD, dp(17), 0), g.measure(toastDetail, Gfx.TEXT_MEDIUM, dp(14), 0));
        float w = dp(84) + tw + dp(28);
        // Bottom left, above the glance bar: clear of the clock, the top controls and the pinned note.
        float l = side - dp(8), tp = barBottom() - barH - dp(22) - h + (1 - p) * dp(24);
        glassLifted(g, l, tp, l + w, tp + h, h / 2, p, 1, t, 0.1f);
        float ix = l + dp(38), iy = tp + h / 2;
        g.fillCircle(ix, iy, dp(22), Tone.focus(0.9f * p));
        int ink = Tone.onFocus(1, p);
        switch (toastIcon) {
            case TOAST_EARBUDS:
                Icons.earbuds(g, ix, iy, dp(24), ink);
                break;
            case TOAST_SOUNDBAR:
                Icons.sound(g, ix, iy, dp(22), ink);
                break;
            case TOAST_PHOTO:
                Icons.calendar(g, ix, iy, dp(22), ink, Tone.ROSE);
                break;
            case TOAST_PHONE:
                Icons.phone(g, ix, iy, dp(22), ink);
                break;
            default:
                Icons.check(g, ix, iy, dp(20), ink);
        }
        float tx = l + dp(72);
        g.text(toastTitle, Gfx.TEXT_SEMIBOLD, dp(17), 0, tx, tp + dp(28), Tone.text(Tone.PRIMARY, p), Gfx.LEFT, 0);
        g.text(toastDetail, Gfx.TEXT_MEDIUM, dp(14), 0, tx, tp + dp(48), Tone.text(Tone.SECONDARY, p), Gfx.LEFT, 0);
    }

    // ---- Modal sheets: pairing, asking, Wi-Fi, mirroring ----------------------------------------------

    /** A QR code as a crisp image (one pixel per module): drawn scaled without smoothing. */
    private Object qrImage(Gfx g, boolean[][] m) {
        int n = m.length, q = 4, s = n + 2 * q;
        int[] px = new int[s * s];
        for (int i = 0; i < px.length; i++) px[i] = 0xFFFFFFFF;
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) if (m[y][x]) px[(y + q) * s + x + q] = 0xFF111111;
        return g.image(px, s, s);
    }

    private void sheetFrame(Gfx g, float l, float tp, float w, float h, float p, float t) {
        int save = g.save();
        g.scale(0.94f + 0.06f * p, l + w / 2, tp + h / 2);
        glassLifted(g, l, tp, l + w, tp + h, dp(40), p, 1, t, 0.16f);
        g.restore(save);
    }

    private void drawPair(Gfx g, float t, long now) {
        float p = mode == PAIR ? modal.value : 0;
        if (p <= 0.003f || pairWindow == null) return;
        float w = dp(780), h = dp(420), l = (width - w) / 2, tp = (height - h) / 2;
        sheetFrame(g, l, tp, w, h, p, t);
        float q = dp(300), qx = l + dp(40), qy = tp + (h - q) / 2;
        g.fillRoundRect(qx - dp(10), qy - dp(10), qx + q + dp(10), qy + q + dp(10), dp(22), Pixels.alpha(0xFFFFFFFF, p));
        if (pairQr != null) g.drawImage(pairQr, qx, qy, qx + q, qy + q, Math.round(255 * p));
        float x = qx + q + dp(48), right = l + w - dp(40);
        g.text("PHONE CONTROL", Gfx.MONO, dp(12.5f), 0.1f, x, tp + dp(70), Tone.text(Tone.SECONDARY, p), Gfx.LEFT, 0);
        g.text("Scan with your phone", Gfx.SERIF, dp(36), -0.01f, x, tp + dp(116), Tone.text(Tone.PRIMARY, p), Gfx.LEFT, 0);
        Pop.lines(g, "Your phone opens Vestor's control center. Then press OK on the remote to let it in.", Gfx.TEXT_MEDIUM, dp(16), x,
                tp + dp(152), right - x, dp(23), 3, Tone.text(Tone.SECONDARY, p));
        String pin = pairWindow[1];
        if (pin.length() == 6) {
            g.text("OR TYPE THIS CODE AT", Gfx.MONO, dp(11.5f), 0.1f, x, tp + dp(242), Tone.text(Tone.TERTIARY, p), Gfx.LEFT, 0);
            g.text(Text.fit(g, pairWindow[2], Gfx.TEXT_SEMIBOLD, dp(17), right - x), Gfx.TEXT_SEMIBOLD, dp(17), 0, x, tp + dp(268),
                    Tone.text(Tone.PRIMARY, p), Gfx.LEFT, 0);
            String shown = pin.substring(0, 3) + " " + pin.substring(3);
            g.text(shown, Gfx.SERIF_LIGHT, dp(64), 0.04f, x - dp(2), tp + dp(338), Pixels.alpha(Tone.ACCENT, p), Gfx.LEFT, 0);
        } else {
            // After too many wrong codes only the QR works, which holds a secret no one can guess.
            Pop.lines(g, "Typing a code is off for now after too many wrong tries. Scanning still works.", Gfx.TEXT_MEDIUM, dp(15),
                    x, tp + dp(262), right - x, dp(22), 3, Pixels.alpha(Tone.AMBER, p));
        }
        long left = Math.max(0, pairUntil - now);
        float frac = left / (float) Keys.WINDOW_MS;
        float cx = right - dp(22), cy = tp + dp(66);
        g.strokeCircle(cx, cy, dp(16), dp(3), Tone.text(0.16f, p));
        g.arc(cx, cy, dp(16), -90, -360 * frac, dp(3), Tone.text(Tone.PRIMARY, p));
        g.text("Back to cancel", Gfx.TEXT_MEDIUM, dp(13.5f), 0, x, tp + h - dp(30), Tone.text(Tone.TERTIARY, p), Gfx.LEFT, 0);
    }

    private void drawAsk(Gfx g, float t) {
        float p = mode == ASK ? modal.value : 0;
        if (p <= 0.003f) return;
        float w = dp(640), h = dp(260), l = (width - w) / 2, tp = (height - h) / 2;
        sheetFrame(g, l, tp, w, h, p, t);
        float cx = l + dp(70), cy = tp + dp(96);
        g.fillCircle(cx, cy, dp(36), Tone.focus(0.9f * p));
        Icons.phone(g, cx, cy, dp(36), Tone.onFocus(1, p));
        float x = l + dp(132), right = l + w - dp(40);
        g.text("ALLOW THIS DEVICE?", Gfx.MONO, dp(12.5f), 0.1f, x, tp + dp(72), Tone.text(Tone.SECONDARY, p), Gfx.LEFT, 0);
        // The three digits the phone shows share the name's row, so two requests cannot be mixed up.
        float nameRight = right;
        if (!askMatch.isEmpty()) {
            g.text("THE PHONE SHOWS", Gfx.MONO, dp(11.5f), 0.1f, right, tp + dp(72), Tone.text(Tone.TERTIARY, p), Gfx.RIGHT, 0);
            g.text(askMatch, Gfx.SERIF_LIGHT, dp(40), 0.02f, right, tp + dp(116), Pixels.alpha(Tone.ACCENT, p), Gfx.RIGHT, 0);
            nameRight = right - dp(150);
        }
        float ns = Pop.fitSize(g, askName, Gfx.SERIF, dp(36), dp(24), nameRight - x, d);
        g.text(Text.fit(g, askName, Gfx.SERIF, ns, nameRight - x), Gfx.SERIF, ns, -0.01f, x, tp + dp(116), Tone.text(Tone.PRIMARY, p),
                Gfx.LEFT, 0);
        Pop.lines(g, "It will see what is on the home screen, send notes and photos, and change Vestor's settings.", Gfx.TEXT_MEDIUM, dp(15.5f),
                x, tp + dp(150), right - x, dp(22), 2, Tone.text(Tone.SECONDARY, p));
        float by = tp + h - dp(44);
        float kw = Icons.keycap(g, x, by, dp(26), "OK", Tone.text(Tone.PRIMARY, p));
        g.text("Allow", Gfx.TEXT_SEMIBOLD, dp(17), 0, x + kw + dp(10), by + dp(6), Tone.text(Tone.PRIMARY, p), Gfx.LEFT, 0);
        float bx = x + kw + dp(90);
        float bw = Icons.keycap(g, bx, by, dp(26), "Back", Tone.text(Tone.SECONDARY, p));
        g.text("Refuse", Gfx.TEXT_SEMIBOLD, dp(17), 0, bx + bw + dp(10), by + dp(6), Tone.text(Tone.SECONDARY, p), Gfx.LEFT, 0);
    }

    private void drawWifi(Gfx g, float t, long now) {
        float p = mode == WIFI ? modal.value : 0;
        if (p <= 0.003f || wifiQr == null) return;
        float w = dp(640), h = dp(380), l = (width - w) / 2, tp = (height - h) / 2;
        sheetFrame(g, l, tp, w, h, p, t);
        float q = dp(280), qx = l + dp(40), qy = tp + (h - q) / 2;
        g.fillRoundRect(qx - dp(10), qy - dp(10), qx + q + dp(10), qy + q + dp(10), dp(22), Pixels.alpha(0xFFFFFFFF, p));
        g.drawImage(wifiQr, qx, qy, qx + q, qy + q, Math.round(255 * p));
        float x = qx + q + dp(44), right = l + w - dp(36);
        Icons.wifi(g, x + dp(18), tp + dp(70), dp(34), Pixels.alpha(Tone.ACCENT, p));
        g.text("GUEST WI-FI", Gfx.MONO, dp(12.5f), 0.1f, x, tp + dp(124), Tone.text(Tone.SECONDARY, p), Gfx.LEFT, 0);
        float ss = Pop.fitSize(g, wifiSsid, Gfx.SERIF, dp(34), dp(22), right - x, d);
        g.text(Text.fit(g, wifiSsid, Gfx.SERIF, ss, right - x), Gfx.SERIF, ss, -0.01f, x, tp + dp(166), Tone.text(Tone.PRIMARY, p),
                Gfx.LEFT, 0);
        Pop.lines(g, "Point a phone's camera at the code to join.", Gfx.TEXT_MEDIUM, dp(16), x, tp + dp(206), right - x, dp(23), 3,
                Tone.text(Tone.SECONDARY, p));
        g.text("Closes by itself in " + Math.max(1, (wifiUntil - now + 999) / 1000) + " s", Gfx.TEXT_MEDIUM, dp(13.5f), 0, x, tp + h - dp(36),
                Tone.text(Tone.TERTIARY, p), Gfx.LEFT, 0);
    }

    private void drawMirror(Gfx g, float t) {
        float p = mode == MIRROR ? modal.value : 0;
        if (p <= 0.003f) return;
        float w = dp(700), h = dp(360), l = (width - w) / 2, tp = (height - h) / 2;
        sheetFrame(g, l, tp, w, h, p, t);
        float x = l + dp(36), right = l + w - dp(36);
        // The two ways slide out to the left as the AirPlay steps come in from the right.
        float s = clamp01(mirrorPage.value), ways = p * (1 - s), steps = p * s;
        int save = g.save();
        g.clipRect(l, tp, l + w, tp + h);
        if (steps > 0.003f) {
            g.translate(dp(32) * (1 - s), 0);
            drawAirPlaySteps(g, x, right, tp, steps);
            g.translate(-dp(32) * (1 - s), 0);
        }
        if (ways > 0.003f) {
            g.translate(-dp(32) * s, 0);
            drawMirrorWays(g, x, right, tp, ways);
        }
        g.restore(save);
    }

    /** The Mirror sheet's first page: Apple devices, or Android phones and Windows PCs. */
    private void drawMirrorWays(Gfx g, float x, float right, float tp, float p) {
        g.text("MIRROR A SCREEN", Gfx.MONO, dp(12.5f), 0.1f, x, tp + dp(54), Tone.text(Tone.SECONDARY, p), Gfx.LEFT, 0);
        g.text("Show a phone or laptop here", Gfx.SERIF, dp(34), -0.01f, x, tp + dp(98), Tone.text(Tone.PRIMARY, p), Gfx.LEFT, 0);
        float rowH = dp(92), y0 = tp + dp(126);
        float py = y0 + mirrorPill.value * (rowH + dp(10));
        g.fillRoundRect(x - dp(12), py, right + dp(12), py + rowH, dp(24), Tone.focus(0.93f * p));
        String[] titles = {"iPhone, iPad or Mac", "Android phone or Windows PC"};
        String[] details = {"Goes to the Samsung's own AirPlay. OK shows how.", "Opens Fire TV's mirroring. On Windows, press Win and K."};
        for (int i = 0; i < 2; i++) {
            float y = y0 + i * (rowH + dp(10));
            float near = clamp01(1 - Math.abs(mirrorPill.value - i));
            int ink = Tone.onFocus(near, p);
            Icons.mirror(g, x + dp(28), y + rowH / 2, dp(34), ink);
            g.text(titles[i], Gfx.TEXT_SEMIBOLD, dp(19), 0, x + dp(66), y + dp(38), ink, Gfx.LEFT, 0);
            g.text(Text.fit(g, details[i], Gfx.TEXT_MEDIUM, dp(14), right - x - dp(70)), Gfx.TEXT_MEDIUM, dp(14), 0, x + dp(66), y + dp(64),
                    Pixels.alpha(ink, 0.72f * p), Gfx.LEFT, 0);
        }
    }

    /**
     * The Mirror sheet's AirPlay page. The Samsung's own AirPlay has nothing to open on this side:
     * it is started on the Apple device, and the TV switches its input to it by itself.
     */
    private void drawAirPlaySteps(Gfx g, float x, float right, float tp, float p) {
        g.text("AIRPLAY TO THE SAMSUNG", Gfx.MONO, dp(12.5f), 0.1f, x, tp + dp(54), Tone.text(Tone.SECONDARY, p), Gfx.LEFT, 0);
        g.text("Start it on the Apple device", Gfx.SERIF, dp(34), -0.01f, x, tp + dp(98), Tone.text(Tone.PRIMARY, p), Gfx.LEFT, 0);
        String[] steps = {"On it, open Control Center and choose Screen Mirroring.", "Pick the Samsung. The first time, type the code it shows.",
            "The TV switches over by itself. Home on this remote comes back."};
        float r = dp(13), num = dp(13), tx = x + dp(40);
        for (int i = 0; i < steps.length; i++) {
            float base = tp + dp(152) + i * dp(42), cy = base - dp(6);
            g.strokeCircle(x + r, cy, r, dp(1.5f), Tone.text(Tone.TERTIARY, p));
            g.text(String.valueOf(i + 1), Gfx.MONO, num, 0, x + r, cy + g.capHeight(Gfx.MONO, num) / 2, Tone.text(Tone.SECONDARY, p),
                    Gfx.CENTER, 0);
            g.text(Text.fit(g, steps[i], Gfx.TEXT_MEDIUM, dp(17), right - tx), Gfx.TEXT_MEDIUM, dp(17), 0, tx, base,
                    Tone.text(Tone.PRIMARY, p), Gfx.LEFT, 0);
        }
        String note = airplayBeacon
                ? "This TV's Bluetooth points Apple devices nearby at the Samsung, so it shows up even on a Wi-Fi that hides it, "
                        + "like MIT's. Their Bluetooth needs to be on."
                : "No Samsung in the list? The Wi-Fi is hiding it, as MIT's does. A Mac can still reach it with Vestor's AirPlay "
                        + "helper, and a phone with a cable.";
        Pop.lines(g, note, Gfx.TEXT_MEDIUM, dp(14.5f), x, tp + dp(290), right - x, dp(21), 3, Tone.text(Tone.TERTIARY, p));
    }

    // ---- Dock --------------------------------------------------------------------------------

    private float tileX(int i) {
        return innerL + i * (tileW + tileGap) - scroll.value;
    }

    private void drawDock(Gfx g, float uiA, float t) {
        if (uiA <= 0.003f) return;
        float drop = (1 - ui.value) * dp(26) + ambient.value * dp(64);
        int save = g.save();
        g.translate(0, drop);
        glassBody(g, dockL, dockT, dockR, dockB, dockRad, uiA, 0);
        if (apps.isEmpty()) {
            g.text("Your apps will appear here", Gfx.TEXT_MEDIUM, dp(17), 0, (dockL + dockR) / 2,
                    (dockT + dockB) / 2 + dp(6), Tone.text(0.7f, uiA), Gfx.CENTER, 0);
            glassRim(g, dockL, dockT, dockR, dockB, dockRad, uiA, t);
            g.restore(save);
            return;
        }
        int clip = g.save();
        float edgeL = dockL + dp(10), edgeR = dockR - dp(10);
        g.clipRect(edgeL, 0, edgeR, height);
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < apps.size(); i++) {
                float f = tileFocus.get(i).value;
                boolean raised = f > 0.02f;
                if (pass == 0 && raised || pass == 1 && !raised) continue;
                float x = tileX(i);
                if (x > edgeR + tileW || x + tileW < edgeL - tileW) continue;
                drawTile(g, i, x, tileTop, f, uiA, true);
            }
        }
        g.restore(clip);
        float bandT = dockT + dp(12), bandB = dockB - dp(12), band = dp(46);
        float step = tileW + tileGap;
        float max = Math.max(0, apps.size() * step - tileGap - (innerR - innerL));
        float leftOn = clamp01(scroll.value / dp(24)), rightOn = clamp01((max - scroll.value) / dp(24));
        if (leftOn > 0.01f) scrollEdge(g, edgeL, edgeL + band, bandT, bandB, uiA * leftOn);
        if (rightOn > 0.01f) scrollEdge(g, edgeR, edgeR - band, bandT, bandB, uiA * rightOn);
        glassRim(g, dockL, dockT, dockR, dockB, dockRad, uiA, t);
        // Light gathers on the dock's top edge above the focused tile and follows focus along it.
        float lit = focus < tileFocus.size() ? tileFocus.get(focus).value : 0;
        if (lit > 0.01f) {
            float fx = tileX(focus) + tileW / 2, spread = tileW * 0.9f;
            int clip2 = g.save();
            g.clipRect(dockL, dockT - dp(2), dockR, dockT + dockRad);
            float half = dp(1.1f);
            g.strokeRoundRectLinear(dockL + dp(2), dockT + dp(2), dockR - dp(2), dockB - dp(2), dockRad - dp(2), dp(5),
                    fx - spread * 1.3f, 0, fx + spread * 1.3f, 0, EDGE_GLINT, THREE_STOPS, uiA * lit * 0.22f);
            g.strokeRoundRectLinear(dockL + half, dockT + half, dockR - half, dockB - half, dockRad - half, dp(2.2f),
                    fx - spread, 0, fx + spread, 0, EDGE_GLINT, THREE_STOPS, uiA * lit);
            g.restore(clip2);
        }
        g.restore(save);
    }

    private void drawTile(Gfx g, int i, float x, float y, float f, float alpha, boolean label) {
        if (alpha <= 0.003f || i >= apps.size()) return;
        Model.App app = apps.get(i);
        boolean moving = mode == MOVE && i == focus;
        float s = 1 + 0.09f * f;
        float cx = x + tileW / 2, cy = y + tileH / 2;
        int save = g.save();
        g.scale(s, cx, cy);
        if (f > 0.01f && !reduced) g.tilt(tiltY.value * 4f * f, -tiltX.value * 5f * f, cx, cy);
        if (f > 0.01f) {
            float pad = dp(18);
            drawShadow(g, x, y, x + tileW, y + tileH, tileRad, pad, dp(10) * f, Math.round(255 * 0.62f * f * alpha));
        }
        int a255 = Math.round(255 * alpha);
        float r = x + tileW, b = y + tileH;
        g.fillRoundRect(x, y, r, b, tileRad, Pixels.alpha(app.color, alpha));
        if (app.banner != null) {
            g.fillRoundRectImage(app.banner, x, y, r, b, x, y, r, b, tileRad, a255);
        } else {
            if (app.icon != null) {
                float size = tileH * 0.5f;
                float icx = cx, icy = y + tileH * 0.42f;
                g.drawImage(app.icon, icx - size / 2, icy - size / 2, icx + size / 2, icy + size / 2, a255);
            }
            String name = fit(g, app.label, Gfx.TEXT_MEDIUM, dp(13), tileW - dp(18));
            g.text(name, Gfx.TEXT_MEDIUM, dp(13), 0, cx, app.icon != null ? b - dp(10) : cy + dp(5),
                    Pixels.white(0.92f * alpha), Gfx.CENTER, 0);
        }
        if (f > 0.01f) {
            float gx = x + tileW * (0.32f + 0.3f * tiltX.value);
            g.fillRoundRectLinear(x, y, r, b, tileRad, gx - tileW * 0.55f, y - tileH * 0.2f, gx + tileW * 0.55f, b + tileH * 0.2f,
                    GLARE, GLARE_STOPS, f * alpha);
        }
        if (moving) g.fillRoundRect(x, y, r, b, tileRad, Pixels.alpha(0xFF000000, 0.35f * alpha));
        float half = dp(0.5f);
        g.strokeRoundRectLinear(x + half, y + half, r - half, b - half, tileRad - half, dp(1.0f + 0.5f * f),
                x, y, r, b, f > 0.5f ? TILE_RIM : TILE_RIM_IDLE, THREE_STOPS, alpha * (0.6f + 0.4f * f));
        if (moving) {
            Icons.chevron(g, x + dp(18), cy, dp(16), false, Pixels.white(alpha));
            Icons.chevron(g, r - dp(18), cy, dp(16), true, Pixels.white(alpha));
        }
        g.restore(save);
        if (label && f > 0.02f) {
            String name = fit(g, app.label, Gfx.TEXT_MEDIUM, dp(15), tileW + dp(40));
            g.text(name, Gfx.TEXT_MEDIUM, dp(15), 0.005f, cx, b + dp(24) + (s - 1) * tileH / 2,
                    Tone.text(0.95f * f, alpha), Gfx.CENTER, 0.3f * (1 - look.value));
        }
    }

    // ---- Settings sheet ----------------------------------------------------------------------

    private void drawSheet(Gfx g, float t) {
        float p = sheet.value;
        if (p <= 0.003f) return;
        float r = width - dp(40) + (1 - p) * dp(70), l = r - dp(SHEET_W);
        float top0 = dp(34), bot = height - dp(34);
        float rad = dp(34);
        glass(g, l, top0, r, bot, rad, p, 0.1f, t);
        g.text("VestorOS", Gfx.SERIF, dp(34), -0.01f, l + dp(30), top0 + dp(58), Tone.text(Tone.PRIMARY, p), Gfx.LEFT, 0);
        g.text(("Version " + BuildInfo.VERSION + "  ·  " + (night() ? "Night" : "Day")).toUpperCase(Locale.US), Gfx.MONO, dp(11.5f), 0.1f,
                l + dp(31), top0 + dp(86), Tone.text(Tone.TERTIARY, p), Gfx.LEFT, 0);

        float areaT = top0 + dp(110), areaB = bot - dp(18);
        float rowH = dp(54), stepH = dp(58);
        int clip = g.save();
        g.clipRect(l, areaT - dp(6), r, areaB);
        float py = areaT + sheetPill.value * stepH - sheetScroll.value;
        float inset = dp(14);
        g.fillRoundRect(l + inset, py, r - inset, py + rowH, rad - inset, Tone.focus(0.93f * p));
        for (int i = 0; i < options.size(); i++) {
            Model.Option o = options.get(i);
            float y = areaT + i * stepH - sheetScroll.value;
            if (y > areaB || y + rowH < areaT - dp(6)) continue;
            float near = clamp01(1 - Math.abs(sheetPill.value - i));
            // Rows dissolve into the sheet's edges instead of being cut (a soft scroll edge).
            float edgeFade = Math.min(clamp01((areaB - (y + rowH)) / dp(36) + 1), clamp01((y - areaT) / dp(36) + 1));
            int ink2 = Tone.onFocus(near, p * edgeFade);
            float base = y + rowH / 2 + g.capHeight(Gfx.TEXT_MEDIUM, dp(17)) / 2;
            fitRow(g, i, o);
            g.text(rowTitle[i], Gfx.TEXT_MEDIUM, dp(17), 0, l + dp(32), base, ink2, Gfx.LEFT, 0);
            String value = rowValue[i];
            float vs = rowValueSize[i];
            float vr = r - dp(32);
            if (o.action) {
                Icons.chevron(g, vr - dp(4), y + rowH / 2, dp(13), true, Pixels.alpha(ink2, 0.7f));
                vr -= dp(20);
            } else if (i == row) {
                Icons.chevron(g, vr - dp(4), y + rowH / 2, dp(12), true, Pixels.alpha(ink2, 0.55f));
                vr -= dp(20);
            }
            if (!value.isEmpty()) {
                g.text(value, Gfx.TEXT_REGULAR, vs, 0, vr, base, Pixels.alpha(ink2, 0.72f), Gfx.RIGHT, 0);
                if (!o.action && i == row) {
                    float vw = g.measure(value, Gfx.TEXT_REGULAR, vs, 0);
                    Icons.chevron(g, vr - vw - dp(14), y + rowH / 2, dp(12), false, Pixels.alpha(ink2, 0.55f));
                }
            }
        }
        g.restore(clip);
    }

    /**
     * Row {@code i}'s title and value, cut so they never run into each other: the value first
     * steps down a size, then is cut (never below half the row), then the title is cut. Room is
     * kept for both chevrons a focused choice shows, so a row does not reflow as focus moves.
     * Measured again only when the text changes.
     */
    private void fitRow(Gfx g, int i, Model.Option o) {
        String title = o.title, value = o.name();
        if (title == rowTitleFrom[i] && value == rowValueFrom[i]) return;
        rowTitleFrom[i] = title;
        rowValueFrom[i] = value;
        float room = rowRoom(o);
        float tw = g.measure(title, Gfx.TEXT_MEDIUM, dp(17), 0);
        float size = dp(16);
        float vw = value.isEmpty() ? 0 : g.measure(value, Gfx.TEXT_REGULAR, size, 0);
        if (tw + vw > room && vw > 0) {
            size = dp(14);
            vw = g.measure(value, Gfx.TEXT_REGULAR, size, 0);
            if (tw + vw > room) {
                value = Text.fit(g, value, Gfx.TEXT_REGULAR, size, Math.max(room - tw, room / 2));
                vw = g.measure(value, Gfx.TEXT_REGULAR, size, 0);
            }
        }
        if (tw + vw > room) title = Text.fit(g, title, Gfx.TEXT_MEDIUM, dp(17), room - vw);
        rowTitle[i] = title;
        rowValue[i] = value;
        rowValueSize[i] = size;
    }

    /** What a row's title and value share: the sheet less its padding, the chevrons and the gap between them. */
    private float rowRoom(Model.Option o) {
        return dp(SHEET_W - 64) - (o.action ? dp(20) : dp(46)) - dp(18);
    }

    /** Settings row {@code i} as drawn: its title's and value's widths, and the room they share (for the desk tests). */
    float[] rowFit(Gfx g, int i) {
        Model.Option o = options.get(i);
        fitRow(g, i, o);
        float room = rowRoom(o);
        return new float[] {g.measure(rowTitle[i], Gfx.TEXT_MEDIUM, dp(17), 0),
            rowValue[i].isEmpty() ? 0 : g.measure(rowValue[i], Gfx.TEXT_REGULAR, rowValueSize[i], 0), room};
    }

    // ---- Tile menu ---------------------------------------------------------------------------

    private void drawMenu(Gfx g, float t) {
        float p = menu.value;
        if (p <= 0.003f || focus >= apps.size()) return;
        float w = dp(270), rowH = dp(50), pad = dp(10), head = dp(44);
        float h = head + MENU_ROWS.length * rowH + pad;
        float ax = tileX(focus) + tileW / 2;
        float bottom = tileTop - dp(26);
        float l = Math.max(dp(40), Math.min(width - dp(40) - w, ax - w / 2));
        float top0 = bottom - h;
        int save = g.save();
        g.scale(0.9f + 0.1f * p, ax, bottom);
        float rad = dp(26);
        glass(g, l, top0, l + w, bottom, rad, p, 0.08f, t);
        Model.App app = apps.get(focus);
        g.text(fit(g, app.label.toUpperCase(Locale.US), Gfx.MONO, dp(12), w - dp(40)), Gfx.MONO, dp(12), 0.08f,
                l + dp(22), top0 + dp(29), Tone.text(0.6f, p), Gfx.LEFT, 0);
        float py = top0 + head + menuPill.value * rowH;
        g.fillRoundRect(l + pad, py, l + w - pad, py + rowH - dp(4), rad - pad, Tone.focus(0.93f * p));
        for (int i = 0; i < MENU_ROWS.length; i++) {
            float y = top0 + head + i * rowH;
            float near = clamp01(1 - Math.abs(menuPill.value - i));
            int ink = Tone.onFocus(near, p);
            g.text(MENU_ROWS[i], Gfx.TEXT_MEDIUM, dp(17), 0, l + dp(24), y + (rowH - dp(4)) / 2 + g.capHeight(Gfx.TEXT_MEDIUM, dp(17)) / 2,
                    ink, Gfx.LEFT, 0);
        }
        g.restore(save);
    }

    // ---- Sound popover ---------------------------------------------------------------------------

    private void drawPop(Gfx g, float t) {
        float p = pop.value;
        if (p <= 0.003f) return;
        float[] anchor = topRect(T_AUDIO, g);
        float w = dp(460);
        float r = Math.min(width - dp(40), Math.max(anchor[2], dp(40) + w));
        float l = r - w;
        float top0 = anchor[3] + dp(14);
        float h = audioHeight();
        // Many formats and the Picture lines can make the card taller than the room below the
        // top row: then it is drawn smaller rather than cut off by the screen's edge.
        float fit = Math.min(1f, (height - top0 - dp(24)) / h);
        int save = g.save();
        g.scale((0.9f + 0.1f * p) * fit, (anchor[0] + anchor[2]) / 2, anchor[3]);
        float rad = dp(30);
        glass(g, l, top0, r, top0 + h, rad, p, 0.1f, t);
        drawAudio(g, l, top0, r, p, rad);
        g.restore(save);
    }

    private float audioHeight() {
        int n = audio == null ? 0 : audio.formats.size();
        return dp(250) + Math.max(1, n) * dp(30) + pictureHeight();
    }

    /** The Picture lines in the Sound card: the HDMI mode, then a fix or how apps and films draw. */
    private float pictureHeight() {
        Model.Picture pic = picture;
        return pic == null || pic.line.isEmpty() ? 0 : dp(84);
    }

    private void drawAudio(Gfx g, float l, float top0, float r, float p, float rad) {
        Model.Audio a = audio;
        boolean bt = a != null && "Bluetooth".equals(a.route);
        g.text("Sound", Gfx.SERIF, dp(30), -0.01f, l + dp(26), top0 + dp(48), Tone.text(Tone.PRIMARY, p), Gfx.LEFT, 0);
        String sub = bt ? "Playing on " + (a.device.isEmpty() ? "Bluetooth headphones" : a.device)
                : a == null || !a.known ? "Your TV did not report its audio formats"
                : (a.atmos ? "Dolby Atmos ready" : a.headline) + (a.channels > 0 ? "  ·  up to " + channels(a.channels) : "");
        g.text(sub, Gfx.TEXT_REGULAR, dp(15), 0, l + dp(27), top0 + dp(74), Tone.text(Tone.SECONDARY, p), Gfx.LEFT, 0);
        g.text("ACCEPTED OVER HDMI", Gfx.MONO, dp(11), 0.1f, l + dp(27), top0 + dp(108), Tone.text(Tone.TERTIARY, p), Gfx.LEFT, 0);
        float y = top0 + dp(136);
        if (a != null) {
            for (String f : a.formats) {
                Icons.check(g, l + dp(36), y - dp(5), dp(14), Tone.text(0.9f, p));
                g.text(f, Gfx.TEXT_MEDIUM, dp(16), 0, l + dp(54), y, Tone.text(Tone.PRIMARY, p), Gfx.LEFT, 0);
                y += dp(30);
            }
        }
        Model.Picture pic = picture;
        if (pic != null && !pic.line.isEmpty()) {
            g.text("PICTURE OVER HDMI", Gfx.MONO, dp(11), 0.1f, l + dp(27), y + dp(8), Tone.text(Tone.TERTIARY, p), Gfx.LEFT, 0);
            Icons.check(g, l + dp(36), y + dp(31), dp(14), Tone.text(0.9f, p));
            g.text(pic.line, Gfx.TEXT_MEDIUM, dp(16), 0, l + dp(54), y + dp(36), Tone.text(Tone.PRIMARY, p), Gfx.LEFT, 0);
            if (pic.problem.isEmpty()) {
                g.text(pic.note, Gfx.TEXT_REGULAR, dp(13), 0, l + dp(27), y + dp(58), Tone.text(Tone.SECONDARY, p), Gfx.LEFT, 0);
            } else {
                g.text(pic.problem, Gfx.TEXT_SEMIBOLD, dp(13), 0, l + dp(27), y + dp(58), Pixels.alpha(Tone.AMBER, p), Gfx.LEFT, 0);
                g.text(pic.fix, Gfx.TEXT_REGULAR, dp(13), 0, l + dp(27), y + dp(77), Tone.text(Tone.SECONDARY, p), Gfx.LEFT, 0);
            }
            y += pictureHeight();
        }
        y += dp(6);
        g.text(bt ? "Headphones play in stereo, the soundbar rests." : "For music: soundbar Music mode, Virtual:X on, surround up.",
                Gfx.TEXT_REGULAR, dp(13), 0, l + dp(27), y, Tone.text(Tone.SECONDARY, p), Gfx.LEFT, 0);
        g.text(bt ? "Two pairs at once: an iPhone's Share Audio, or a dual transmitter." : "Atmos music: Amazon Music Unlimited.",
                Gfx.TEXT_REGULAR, dp(13), 0, l + dp(27), y + dp(19), Tone.text(Tone.SECONDARY, p), Gfx.LEFT, 0);
        float bt0 = y + dp(36), bb = bt0 + dp(48);
        float inset = dp(14);
        g.fillRoundRect(l + inset, bt0, r - inset, bb, rad - inset, Tone.focus(0.93f * p));
        Icons.earbuds(g, l + dp(48), (bt0 + bb) / 2, dp(22), Tone.onFocus(1, p));
        g.text("Bluetooth Headphones", Gfx.TEXT_MEDIUM, dp(17), 0, (l + r) / 2 + dp(14), (bt0 + bb) / 2 + g.capHeight(Gfx.TEXT_MEDIUM, dp(17)) / 2,
                Tone.onFocus(1, p), Gfx.CENTER, 0);
    }

    private static String channels(int n) {
        if (n >= 8) return "7.1";
        if (n >= 6) return "5.1";
        return n + " channels";
    }

    // ---- Helpers -----------------------------------------------------------------------------

    private String fit(Gfx g, String s, int face, float size, float max) {
        return Text.fit(g, s, face, size, max);
    }

    float dp(float v) {
        return v * d;
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
