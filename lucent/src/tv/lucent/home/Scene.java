package tv.lucent.home;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole home screen: layout, focus, motion and drawing. It knows nothing about Android, so
 * the desktop preview renders exactly what the TV renders.
 *
 * Design rules, after Apple's Liquid Glass guidance: glass is only the functional layer (dock,
 * glance bar, top controls, sheets) floating over the photograph, never glass on glass; one
 * material variant (regular, adaptive dimming) everywhere; corners concentric (inner radius =
 * outer radius minus padding); colour comes from the wallpaper, the apps and the data, not from
 * tinting the chrome; focus is shown by lift, light and a white fill rather than an outline.
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
    }

    static final int LEFT = 1, RIGHT = 2, UP = 3, DOWN = 4, OK = 5, LONG_OK = 6, BACK = 7, MENU = 8;

    private static final int NONE = 0, SETTINGS = 1, TILE_MENU = 2, AUDIO = 4, MOVE = 5, PANEL = 6;
    private static final int DOCK = 0, TOP = 1, GLANCE = 2;
    private static final int T_AUDIO = 0, T_GEAR = 1;
    private static final String[] MENU_ROWS = {"Move", "Hide from Home", "App Info"};

    private static final int INK = Tone.INK;

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
    final Cards cards = new Cards();
    private boolean skyOn = true;
    private String time = "", ampm = "", date = "";
    private final List<Model.Option> options = new ArrayList<>();
    private boolean reduced, weatherOn = true;
    private long ambientAfter = 5 * 60000L;
    private String hint = "";

    // Focus and overlays.
    private int zone = DOCK, focus, topFocus = T_GEAR, mode = NONE, row, menuRow;
    private int glanceFocus = Cards.BIKES, panelKind = Cards.BIKES, panelPrev = Cards.BIKES;
    private final List<Spring> tileFocus = new ArrayList<>();
    private final Spring[] topSpring = {new Spring(0, 0.3f, 0.1f), new Spring(0, 0.3f, 0.1f)};
    /** How far each glance segment has grown into its card. */
    private final Spring[] grow = new Spring[Cards.COUNT];
    /** Compact widths as last measured, for focus moves between draws. */
    private final float[] segW = new float[Cards.COUNT];
    private final Spring panel = new Spring(0, 0.46f, 0.06f), panelSwap = new Spring(1, 0.3f, 0);
    private final Spring panelW = new Spring(0, 0.4f, 0.04f), panelH = new Spring(0, 0.4f, 0.04f);
    private final Spring button = new Spring(0, 0.25f, 0.05f);
    private final float[] panelFrom = new float[4];
    private final Spring notice = new Spring(0, 0.45f, 0.08f);
    private Notice.Spec noticeSpec;
    private long noticeAt, noticeUntil;
    private int finderState = Model.Finder.IDLE;
    private final Spring scroll = new Spring(0, 0.5f, 0);
    private final Spring tiltX = new Spring(0, 0.6f, 0.25f), tiltY = new Spring(0, 0.6f, 0.25f);
    private final Spring ambient = new Spring(0, 1.6f, 0), ui = new Spring(1, 0.55f, 0);
    private final Spring sheet = new Spring(0, 0.45f, 0.06f), menu = new Spring(0, 0.34f, 0.12f), pop = new Spring(0, 0.34f, 0.1f);
    private final Spring glanceLight = new Spring(0, 0.5f, 0);
    private final Spring sheetScroll = new Spring(0, 0.4f, 0), sheetPill = new Spring(0, 0.28f, 0.08f), menuPill = new Spring(0, 0.25f, 0.08f);
    private final Spring light = new Spring(0, 1.4f, 0.2f);
    private final Spring fade = new Spring(1, 1.5f, 0);
    private long lastInput, start = -1, wallSince, nowMs;
    private float[] wallRect = new float[4];

    private Gfx cacheOwner;
    /**
     * Pre-rendered nine-slice shadows by corner radius and blur, least recently used dropped.
     */
    private final Map<String, Object> shadows = new LinkedHashMap<String, Object>(16, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Object> e) {
            return size() > 64;
        }
    };

    Scene(Host host) {
        this.host = host;
        for (int i = 0; i < Cards.COUNT; i++) grow[i] = new Spring(0, 0.38f, 0.1f);
    }

    // ---- Inputs from the platform ----------------------------------------------------------

    void setSize(int w, int h, float density) {
        width = w;
        height = h;
        d = density;
        cards.density(density);
        side = dp(48);
        top = dp(40);
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
        weather = w;
        cards.weather = w;
    }

    void setAudio(Model.Audio a) {
        audio = a;
    }

    void setBikes(Model.Bikes b) {
        cards.bikes = b;
    }

    void setTransit(Model.Transit t) {
        cards.transit = t;
    }

    void setSky(Model.Sky s) {
        cards.sky = s;
    }

    void setSkyOn(boolean on) {
        skyOn = on;
        if (!on && glanceFocus == Cards.SKY) glanceFocus = Cards.TRANSIT;
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
        lastInput = now;
        ambient.to(0);
        notice.to(1);
    }

    void setClock(String time, String ampm, String date) {
        this.time = time;
        this.ampm = ampm == null ? "" : ampm;
        this.date = date;
    }

    void setOptions(List<Model.Option> list) {
        options.clear();
        options.addAll(list);
        row = Math.max(0, Math.min(row, options.size() - 1));
    }

    void setReduced(boolean on) {
        reduced = on;
        cards.still = on;
    }

    void setWeatherOn(boolean on) {
        weatherOn = on;
        if (!on && glanceFocus == Cards.WEATHER) glanceFocus = Cards.BIKES;
    }

    void setClock24(boolean on) {
        cards.h24 = on;
    }

    void setAmbientAfter(long ms) {
        ambientAfter = ms;
    }

    /** Glass materialises: called when the home screen comes back to the front. */
    void enter(long now) {
        lastInput = now;
        ambient.snap(0);
        if (!reduced) ui.snap(0);
        ui.to(1);
        closeOverlays();
    }

    /** HOME pressed while already home: back to the first app, overlays closed. */
    void reset(long now) {
        lastInput = now;
        ambient.to(0);
        closeOverlays();
        notice.to(0);
        zone = DOCK;
        focus = 0;
    }

    /** The label of whatever has focus, for the system's screen reader. */
    String focusedLabel() {
        if (mode == SETTINGS && row < options.size()) return options.get(row).title + ", " + options.get(row).name();
        if (mode == PANEL) return Cards.name(panelKind);
        if (zone == DOCK && focus < apps.size()) return apps.get(focus).label;
        if (zone == GLANCE) return Cards.name(glanceFocus);
        return zone == TOP ? (topFocus == T_GEAR ? "Settings" : "Sound") : "";
    }

    // ---- Keys --------------------------------------------------------------------------------

    boolean key(int k, long now) {
        lastInput = now;
        if (ambient.target > 0.5f) {
            ambient.to(0);
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
                if (next >= T_AUDIO && next <= T_GEAR) {
                    topFocus = next;
                    light.kick(k == LEFT ? -1.5f : 1.5f);
                    host.sound(false);
                }
                return true;
            }
            case DOWN: {
                float[] r = topRect(topFocus, null);
                zone = GLANCE;
                glanceFocus = nearestGlance((r[0] + r[2]) / 2);
                light.kick(2f);
                host.sound(false);
                return true;
            }
            case BACK:
                zone = DOCK;
                light.kick(2f);
                host.sound(false);
                return true;
            case OK:
                host.sound(true);
                if (topFocus == T_GEAR) openSheet();
                else openPop();
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
            case BACK:
                zone = DOCK;
                light.kick(2f);
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
                    button.to(panelKind == Cards.BIKES ? 1 : 0);
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
                } else {
                    closePanel();
                }
                return;
            case BACK:
            case MENU:
            case DOWN:
                closePanel();
                return;
            default:
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
            host.fireSettings();
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
        button.to(kind == Cards.BIKES ? 1 : 0);
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
        mode = NONE;
        hint = "";
        sheet.to(0);
        menu.to(0);
        pop.to(0);
        panel.to(0);
    }

    /** The subjects in the glance bar, left to right. */
    private int[] glanceKinds() {
        boolean w = weatherOn && weather != null && weather.ok;
        boolean s = skyOn && cards.sky != null && cards.sky.ok;
        if (s && !(mode == PANEL && panelKind == Cards.SKY)) {
            // Planes are the extra: they step aside when the bar would run past the dock.
            float total = segWidth(Cards.BIKES) + segWidth(Cards.TRANSIT) + segWidth(Cards.SKY) + (w ? segWidth(Cards.WEATHER) : 0);
            if (total > dockR - barL) s = false;
        }
        int n = 2 + (w ? 1 : 0) + (s ? 1 : 0);
        int[] out = new int[n];
        int i = 0;
        if (w) out[i++] = Cards.WEATHER;
        out[i++] = Cards.BIKES;
        out[i++] = Cards.TRANSIT;
        if (s) out[i] = Cards.SKY;
        return out;
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
        for (int id = T_AUDIO; id <= T_GEAR; id++) {
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
        float h = lerp(barH, dp(Cards.CARD_H), e);
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
        nowMs = now;
        cards.now = now;
        if (start < 0) start = now;
        cards.time = (now - start) / 1000f;
        if (wallSince < 0) wallSince = now;
        if (mode == NONE && ambientAfter > 0 && now - lastInput > ambientAfter && notice.target < 0.5f) ambient.to(1);
        if (notice.target > 0.5f && now > noticeUntil) notice.to(0);
        for (int i = 0; i < tileFocus.size(); i++) {
            boolean on = zone == DOCK && i == focus && mode != SETTINGS && ambient.target < 0.5f;
            tileFocus.get(i).to(on ? 1 : 0);
        }
        for (int id = 0; id < topSpring.length; id++) {
            topSpring[id].to(zone == TOP && topFocus == id && mode == NONE ? 1 : 0);
        }
        int[] kinds = glanceKinds();
        boolean shown = false;
        for (int k : kinds) shown |= k == glanceFocus;
        // A subject can leave the bar (weather switched off, planes out of room): focus moves to
        // its neighbour instead of resting on something invisible.
        if (!shown && kinds.length > 0) glanceFocus = kinds[Math.min(kinds.length - 1, glanceFocus >= Cards.SKY ? kinds.length - 1 : 0)];
        for (int k = 0; k < Cards.COUNT; k++) {
            boolean on = zone == GLANCE && glanceFocus == k && (mode == NONE || mode == PANEL) && ambient.target < 0.5f;
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
        for (Spring s : tileFocus) moving |= s.step(dt, false);
        for (Spring s : topSpring) moving |= s.step(dt, false);
        for (Spring s : grow) moving |= s.step(dt, false);
        moving |= scroll.step(dt, false);
        moving |= tiltX.step(dt, instant);
        moving |= tiltY.step(dt, instant);
        moving |= light.step(dt, instant);
        moving |= glanceLight.step(dt, instant);
        moving |= ambient.step(dt, false);
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
        moving |= fade.step(dt, false);
        if (!fade.moving() && oldWall != null) oldWall = null;
        // The found alert's rings and bounce play out at full frame rate.
        if (!reduced && notice.value > 0.01f && (now - noticeAt) / 1000f < Notice.PULSE_SECONDS) moving = true;
        return moving;
    }

    /** Whether something shows seconds (bike finder's timer), which needs a few frames a second. */
    boolean ticking() {
        return cards.finding() || notice.value > 0.01f;
    }

    /** Whether the wallpaper drifts, which needs slow continuous frames. */
    boolean drifting() {
        return !reduced && wall != null;
    }

    // ---- Drawing -----------------------------------------------------------------------------

    void draw(Gfx g, long now) {
        if (g != cacheOwner) {
            cacheOwner = g;
            shadows.clear();
        }
        if (start < 0) start = now;
        float t = (now - start) / 1000f;
        computeWallRect(t);
        drawWallpaper(g);

        float amb = ambient.value;
        float uiA = ui.value * (1 - amb);
        drawClock(g, amb);
        drawCaption(g, uiA, amb);
        drawTop(g, uiA, t);
        drawDock(g, uiA, t);
        drawGlance(g, ui.value, t);

        float modal = Math.max(sheet.value, Math.max(menu.value * 0.75f, Math.max(pop.value * 0.55f, panel.value * 0.6f)));
        if (modal > 0.002f) {
            g.fillRect(0, 0, width, height, Pixels.alpha(0xFF000000, 0.34f * modal));
            if (menu.value > 0.002f && focus < apps.size()) {
                // The app the menu belongs to stays lit above the dimming.
                drawTile(g, focus, tileX(focus), tileTop, 1f, 1f, false);
            }
        }
        drawPanel(g, t);
        drawSheet(g, t);
        drawMenu(g, t);
        drawPop(g, t);
        drawNotice(g, now, t);
    }

    private void computeWallRect(float t) {
        float s = reduced ? 1.0f : 1.05f + 0.012f * (float) Math.sin(t * 2 * Math.PI / 173);
        float cx = width / 2f, cy = height / 2f;
        if (!reduced) {
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
            return;
        }
        if (oldWall != null && oldWall.full != null && fade.value < 1f) {
            g.drawImage(oldWall.full, r[0], r[1], r[2], r[3], 255);
            g.drawImage(wall.full, r[0], r[1], r[2], r[3], Math.round(255 * fade.value));
        } else {
            g.drawImage(wall.full, r[0], r[1], r[2], r[3], 255);
        }
    }

    /**
     * The photo's name and place, right-aligned under the top controls; while moving an app the
     * same line gives the instructions. In ambient mode it rises to where the controls were.
     */
    private void drawCaption(Gfx g, float uiA, float amb) {
        String text = hint.isEmpty() ? (wall == null ? "" : wall.title) : hint;
        if (text.isEmpty() || notice.value > 0.98f) return;
        float a = (hint.isEmpty() ? 0.8f * Math.max(uiA, amb) : uiA) * (1 - notice.value);
        float y = lerp(top + capH + dp(32), top + dp(28), amb);
        float r = width - side - dp(2);
        float size = dp(14.5f);
        float total = g.measure(text, Gfx.TEXT_MEDIUM, size, 0.01f);
        if (hint.isEmpty() && wall != null && !wall.place.isEmpty()) {
            total += g.measure(wall.place, Gfx.TEXT_REGULAR, size, 0.01f) + dp(9);
        }
        // A feathered dim behind words on a photograph, as visionOS does, instead of a box.
        float sh = dp(34), cy = y - g.capHeight(Gfx.TEXT_MEDIUM, size) / 2;
        drawShadow(g, r - total - dp(8), cy - sh / 2, r + dp(8), cy + sh / 2, sh / 2, dp(22), 0, Math.round(255 * 0.85f * a));
        if (hint.isEmpty() && wall != null && !wall.place.isEmpty()) {
            g.text(wall.place, Gfx.TEXT_REGULAR, size, 0.01f, r, y, Pixels.white(a * 0.8f), Gfx.RIGHT, 0.8f);
            r -= g.measure(wall.place, Gfx.TEXT_REGULAR, size, 0.01f) + dp(9);
        }
        g.text(text, Gfx.TEXT_MEDIUM, size, 0.01f, r, y, Pixels.white(a), Gfx.RIGHT, 0.8f);
    }

    private void drawClock(Gfx g, float amb) {
        float size = dp(74) * (1 + 0.18f * amb);
        float x = side;
        float base = top + g.capHeight(Gfx.DISPLAY_LIGHT, size) + dp(4);
        g.text(time, Gfx.DISPLAY_LIGHT, size, -0.015f, x, base, 0xFFFFFFFF, Gfx.LEFT, 0.45f);
        if (!ampm.isEmpty()) {
            float w = g.measure(time, Gfx.DISPLAY_LIGHT, size, -0.015f);
            g.text(ampm, Gfx.TEXT_SEMIBOLD, size * 0.27f, 0.04f, x + w + dp(8), base, Pixels.white(0.82f), Gfx.LEFT, 0.45f);
        }
        g.text(date, Gfx.TEXT_MEDIUM, dp(19) * (1 + 0.1f * amb), 0.005f, x + dp(3), base + dp(36) * (1 + 0.12f * amb),
                Pixels.white(0.86f), Gfx.LEFT, 0.45f);
    }

    // ---- Glass -------------------------------------------------------------------------------

    /**
     * One pane of glass: a soft shadow, the blurred wallpaper slightly magnified (lensing),
     * adaptive dimming from the luminance underneath, light from above, a refracting edge band
     * and a specular rim whose light direction drifts and reacts to focus moves.
     */
    private void glass(Gfx g, float l, float t, float r, float b, float rad, float strength, float extraDim, float time) {
        if (strength <= 0.003f) return;
        glassBody(g, l, t, r, b, rad, strength, extraDim);
        glassRim(g, l, t, r, b, rad, strength, time);
    }

    private void glassBody(Gfx g, float l, float t, float r, float b, float rad, float strength, float extraDim) {
        int a = Math.round(255 * Math.min(1, strength));
        float pad = dp(24);
        drawShadow(g, l, t, r, b, rad, pad, dp(10), Math.round(255 * 0.5f * strength));
        float cx = (l + r) / 2, cy = (t + b) / 2;
        backdrop(g, l, t, r, b, rad, cx, cy, a);
        float lum = luminance(l, t, r, b);
        float dim = glassDim(lum) + extraDim;
        g.fillRoundRect(l, t, r, b, rad, Pixels.alpha(0xFF000000, dim * strength));
        g.fillRoundRectLinear(l, t, r, b, rad, 0, t, 0, b, TOP_LIGHT, TOP_LIGHT_STOPS, strength);
        if (wall != null && wall.blur != null) {
            float lens = 1.18f;
            g.fillRingImage(wall.blur, cx + (wallRect[0] - cx) * lens, cy + (wallRect[1] - cy) * lens,
                    cx + (wallRect[2] - cx) * lens, cy + (wallRect[3] - cy) * lens,
                    l, t, r, b, rad, dp(3.5f), Math.round(a * 0.55f));
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
        float dim = glassDim(luminance(dockL, dockT, dockR, dockB));
        for (int i = 0; i < strips; i++) {
            float x0 = from + (to - from) * i / strips, x1 = from + (to - from) * (i + 1) / strips;
            float l = Math.min(x0, x1), r = Math.max(x0, x1) + 0.5f;
            float a = strength * (1 - (i + 0.5f) / strips);
            a = a * a * (3 - 2 * a);
            backdrop(g, l, t, r, b, 0, cx, cy, Math.round(255 * a));
            g.fillRect(l, t, r, b, Pixels.alpha(0xFF000000, dim * a));
            g.fillRectLinear(l, t, r, b, 0, dockT, 0, dockB, TOP_LIGHT, TOP_LIGHT_STOPS, a);
        }
    }

    private float luminance(float l, float t, float r, float b) {
        Model.Wall w = wall;
        if (w == null || w.blurPixels == null) return 0.15f;
        String key = Math.round(l / 8) + ":" + Math.round(t / 8) + ":" + Math.round(r / 8) + ":" + Math.round(b / 8);
        Map<String, Float> per = lumMap.get(w.blurPixels);
        if (per == null) {
            per = new HashMap<>();
            lumMap.clear();
            lumMap.put(w.blurPixels, per);
        }
        Float hit = per.get(key);
        if (hit != null) return hit;
        float fl = (l - wallRect[0]) / (wallRect[2] - wallRect[0]), fr = (r - wallRect[0]) / (wallRect[2] - wallRect[0]);
        float ft = (t - wallRect[1]) / (wallRect[3] - wallRect[1]), fb = (b - wallRect[1]) / (wallRect[3] - wallRect[1]);
        float lum = Pixels.luminance(w.blurPixels, w.blurW, w.blurH, fl, ft, fr, fb);
        per.put(key, lum);
        return lum;
    }

    private final Map<Object, Map<String, Float>> lumMap = new HashMap<>();

    private static final float SHADOW_SCALE = 0.25f;

    /**
     * A soft shadow under a rounded rectangle, drawn in slices from a small pre-rendered image:
     * corners as rendered, the middle stretched. Shapes taller than the blur's reach use one
     * image per corner radius in nine slices; a thinner shape (a capsule, a caption) is fainter
     * in the middle, so its image keeps the real height, rounded to 8 dp, and is sliced across
     * only. Either way a card changing shape every frame reuses a few images.
     */
    private void drawShadow(Gfx g, float l, float t, float r, float b, float rad, float blur, float dy, int alpha) {
        if (alpha <= 0 || r <= l || b <= t) return;
        float q = dp(3);
        rad = Math.round(rad / q) * q;
        float side = 2 * (rad + 1.6f * blur) + 2;
        boolean thin = b - t < side;
        float hq = dp(8);
        float baseH = thin ? Math.max(hq, Math.round((b - t) / hq) * hq) : side;
        String key = Math.round(rad) + "b" + Math.round(blur) + (thin ? "h" + Math.round(baseH) : "");
        Object img = shadows.get(key);
        if (img == null) {
            int[] px = Pixels.shadow(side, baseH, rad, blur, SHADOW_SCALE);
            int iw = px[0], ih = px[1];
            int[] pixels = new int[iw * ih];
            System.arraycopy(px, 2, pixels, 0, pixels.length);
            img = g.image(pixels, iw, ih);
            shadows.put(key, img);
        }
        int iw = g.imageWidth(img), ih = g.imageHeight(img);
        int cx = iw / 2, cy = ih / 2;
        float cw = cx / SHADOW_SCALE, ch = cy / SHADOW_SCALE;
        float L = l - blur, T = t - blur + dy, R = r + blur, B = b + blur + dy;
        if (R - L < 2 * cw + 1) {
            g.drawImage(img, L, T, R, B, alpha);
            return;
        }
        float[] xs = {L, L + cw, R - cw, R};
        int[] sx = {0, cx, cx + 1, iw};
        if (thin) {
            for (int i = 0; i < 3; i++) g.drawImagePart(img, sx[i], 0, sx[i + 1], ih, xs[i], T, xs[i + 1], B, alpha);
            return;
        }
        float[] ys = {T, T + ch, B - ch, B};
        int[] sy = {0, cy, cy + 1, ih};
        for (int j = 0; j < 3; j++) {
            for (int i = 0; i < 3; i++) {
                g.drawImagePart(img, sx[i], sy[j], sx[i + 1], sy[j + 1], xs[i], ys[j], xs[i + 1], ys[j + 1], alpha);
            }
        }
    }

    // ---- Top controls ------------------------------------------------------------------------

    /** The rectangle of top control {@code id}; null Gfx means estimate widths (for focus math). */
    private float[] topRect(int id, Gfx g) {
        float r = width - side;
        float y0 = top + dp(2), y1 = y0 + capH;
        float gearW = capH;
        float audioW = capH + dp(10) + textWidth(g, audioLabel(), Gfx.TEXT_MEDIUM, dp(16)) + dp(20);
        float gap = dp(12);
        if (id == T_GEAR) return new float[] {r - gearW, y0, r, y1};
        return new float[] {r - gearW - gap - audioW, y0, r - gearW - gap, y1};
    }

    private float textWidth(Gfx g, String s, int face, float size) {
        if (g != null) return g.measure(s, face, size, 0);
        return s.length() * size * 0.55f;
    }

    private String audioLabel() {
        return audio == null ? "Sound" : audio.headline;
    }

    private void drawTop(Gfx g, float uiA, float t) {
        if (uiA <= 0.003f) return;
        float rise = (1 - ui.value) * dp(-14);
        for (int id = T_AUDIO; id <= T_GEAR; id++) {
            float[] r = topRect(id, g);
            float f = topSpring[id].value;
            float s = 1 + 0.1f * f;
            float cx = (r[0] + r[2]) / 2, cy = (r[1] + r[3]) / 2 + rise;
            int save = g.save();
            g.translate(0, rise);
            g.scale(s, cx, cy);
            float rad = (r[3] - r[1]) / 2;
            glass(g, r[0], r[1], r[2], r[3], rad, uiA, 0, t);
            if (f > 0.003f) g.fillRoundRect(r[0], r[1], r[2], r[3], rad, Pixels.white(0.93f * f * uiA));
            int ink = Pixels.alpha(Pixels.lerp(Tone.TEXT, INK, f), uiA);
            float iconC = r[0] + capH / 2;
            float mid = (r[1] + r[3]) / 2;
            if (id == T_GEAR) {
                Icons.gear(g, cx, mid, dp(24), ink);
            } else {
                Icons.sound(g, iconC + dp(4), mid, dp(22), ink);
                g.text(audioLabel(), Gfx.TEXT_MEDIUM, dp(16), 0, iconC + capH / 2 + dp(2), mid + g.capHeight(Gfx.TEXT_MEDIUM, dp(16)) / 2,
                        ink, Gfx.LEFT, 0);
            }
            g.restore(save);
        }
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
        for (int k = 0; k < Cards.COUNT; k++) segW[k] = cards.compactWidth(g, k);
        int[] kinds = glanceKinds();
        float bottom = barBottom(), topY = bottom - barH;
        float total = 0;
        for (int k : kinds) total += segW[k];
        float drop = (1 - ui.value) * dp(20);
        int save = g.save();
        g.translate(glanceLight.value * dp(1.5f), drop);
        float l = barL, r = barL + total, rad = barH / 2;
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
    private void glassLifted(Gfx g, float l, float t, float r, float b, float rad, float alpha, float f, float time,
            float extraDim) {
        float pad = dp(28);
        drawShadow(g, l, t, r, b, rad, pad, dp(14) * f, Math.round(255 * 0.4f * f * alpha));
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
        float rad = lerp(dp(30), dp(36), p);
        glassLifted(g, l, top0, r, b, rad, 1f, 1f - 0.6f * p, t, lerp(0.06f, 0.16f, p));
        float cardA = clamp01(1 - p * 2.4f);
        if (cardA > 0.003f) cards.expanded(g, panelKind, l, top0, l + cards.expandedWidth(panelKind), top0 + dp(Cards.CARD_H), cardA);
        float a = clamp01((p - 0.5f) / 0.5f);
        if (a <= 0.003f) return;
        int clip = g.save();
        g.clipRect(l, top0, r, b);
        float sw = panelSwap.value;
        if (sw < 0.999f && panelPrev != panelKind) {
            cards.panel(g, panelPrev, l, top0, r, b, a * clamp01(1 - sw * 2), 0, rad);
        }
        cards.panel(g, panelKind, l, top0, r, b, a * clamp01(sw * 1.4f - 0.2f), button.value, rad);
        dots(g, (l + r) / 2, b + dp(22), a);
        g.restore(clip);
    }

    /** Page dots under a panel: Left and Right move between the subjects. */
    private void dots(Gfx g, float cx, float cy, float a) {
        int[] kinds = glanceKinds();
        float gap = dp(14);
        float x = cx - gap * (kinds.length - 1) / 2;
        for (int k : kinds) {
            boolean on = k == panelKind;
            g.fillCircle(x, cy, dp(on ? 3.5f : 3f), Pixels.white((on ? 0.95f : 0.4f) * a));
            x += gap;
        }
    }

    // ---- Alerts --------------------------------------------------------------------------------

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

    // ---- Dock --------------------------------------------------------------------------------

    private float tileX(int i) {
        return innerL + i * (tileW + tileGap) - scroll.value;
    }

    private void drawDock(Gfx g, float uiA, float t) {
        if (uiA <= 0.003f) return;
        float drop = (1 - ui.value) * dp(26);
        int save = g.save();
        g.translate(0, drop);
        glassBody(g, dockL, dockT, dockR, dockB, dockRad, uiA, 0);
        if (apps.isEmpty()) {
            g.text("Your apps will appear here", Gfx.TEXT_MEDIUM, dp(17), 0, (dockL + dockR) / 2,
                    (dockT + dockB) / 2 + dp(6), Pixels.white(0.7f * uiA), Gfx.CENTER, 0);
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
        if (f > 0.01f && !reduced) g.tilt(tiltY.value * 4f * f, -tiltX.value * 6f * f, cx, cy);
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
                    Pixels.white(0.95f * f * alpha), Gfx.CENTER, 0.3f);
        }
    }

    // ---- Settings sheet ----------------------------------------------------------------------

    private void drawSheet(Gfx g, float t) {
        float p = sheet.value;
        if (p <= 0.003f) return;
        float r = width - dp(40) + (1 - p) * dp(70), l = r - dp(430);
        float top0 = dp(34), bot = height - dp(34);
        float rad = dp(34);
        glass(g, l, top0, r, bot, rad, p, 0.1f, t);
        float ink = p;
        g.text("Lucent", Gfx.DISPLAY_SEMIBOLD, dp(30), -0.01f, l + dp(30), top0 + dp(58), Pixels.white(ink), Gfx.LEFT, 0);
        g.text("Home screen settings", Gfx.TEXT_REGULAR, dp(15), 0, l + dp(31), top0 + dp(84), Pixels.white(0.62f * ink), Gfx.LEFT, 0);

        float areaT = top0 + dp(110), areaB = bot - dp(18);
        float rowH = dp(54), stepH = dp(58);
        int clip = g.save();
        g.clipRect(l, areaT - dp(6), r, areaB);
        float py = areaT + sheetPill.value * stepH - sheetScroll.value;
        float inset = dp(14);
        g.fillRoundRect(l + inset, py, r - inset, py + rowH, rad - inset, Pixels.white(0.93f * p));
        for (int i = 0; i < options.size(); i++) {
            Model.Option o = options.get(i);
            float y = areaT + i * stepH - sheetScroll.value;
            if (y > areaB || y + rowH < areaT - dp(6)) continue;
            float near = clamp01(1 - Math.abs(sheetPill.value - i));
            // Rows dissolve into the sheet's edges instead of being cut (a soft scroll edge).
            float edgeFade = Math.min(clamp01((areaB - (y + rowH)) / dp(36) + 1), clamp01((y - areaT) / dp(36) + 1));
            int ink2 = Pixels.alpha(Pixels.lerp(0xFFFFFFFF, INK, near), p * edgeFade);
            float base = y + rowH / 2 + g.capHeight(Gfx.TEXT_MEDIUM, dp(17)) / 2;
            g.text(o.title, Gfx.TEXT_MEDIUM, dp(17), 0, l + dp(32), base, ink2, Gfx.LEFT, 0);
            String value = o.name();
            float vr = r - dp(32);
            if (o.action) {
                Icons.chevron(g, vr - dp(4), y + rowH / 2, dp(13), true, Pixels.alpha(ink2, 0.7f));
                vr -= dp(20);
            } else if (i == row) {
                Icons.chevron(g, vr - dp(4), y + rowH / 2, dp(12), true, Pixels.alpha(ink2, 0.55f));
                vr -= dp(20);
            }
            if (!value.isEmpty()) {
                g.text(value, Gfx.TEXT_REGULAR, dp(16), 0, vr, base, Pixels.alpha(ink2, 0.72f), Gfx.RIGHT, 0);
                if (!o.action && i == row) {
                    float vw = g.measure(value, Gfx.TEXT_REGULAR, dp(16), 0);
                    Icons.chevron(g, vr - vw - dp(14), y + rowH / 2, dp(12), false, Pixels.alpha(ink2, 0.55f));
                }
            }
        }
        g.restore(clip);
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
        g.text(fit(g, app.label, Gfx.TEXT_SEMIBOLD, dp(14), w - dp(40)), Gfx.TEXT_SEMIBOLD, dp(14), 0.02f,
                l + dp(22), top0 + dp(29), Pixels.white(0.6f * p), Gfx.LEFT, 0);
        float py = top0 + head + menuPill.value * rowH;
        g.fillRoundRect(l + pad, py, l + w - pad, py + rowH - dp(4), rad - pad, Pixels.white(0.93f * p));
        for (int i = 0; i < MENU_ROWS.length; i++) {
            float y = top0 + head + i * rowH;
            float near = clamp01(1 - Math.abs(menuPill.value - i));
            int ink = Pixels.alpha(Pixels.lerp(0xFFFFFFFF, INK, near), p);
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
        float w = dp(430);
        float r = Math.min(width - dp(40), Math.max(anchor[2], dp(40) + w));
        float l = r - w;
        float top0 = anchor[3] + dp(14);
        float h = audioHeight();
        int save = g.save();
        g.scale(0.9f + 0.1f * p, (anchor[0] + anchor[2]) / 2, anchor[3]);
        float rad = dp(30);
        glass(g, l, top0, r, top0 + h, rad, p, 0.08f, t);
        drawAudio(g, l, top0, r, p, rad);
        g.restore(save);
    }

    private float audioHeight() {
        int n = audio == null ? 0 : audio.formats.size();
        return dp(212) + Math.max(1, n) * dp(30);
    }

    private void drawAudio(Gfx g, float l, float top0, float r, float p, float rad) {
        Model.Audio a = audio;
        int white = Pixels.white(p);
        g.text("Sound", Gfx.DISPLAY_SEMIBOLD, dp(26), -0.01f, l + dp(26), top0 + dp(46), white, Gfx.LEFT, 0);
        String sub = a == null || !a.known ? "Your TV did not report its audio formats"
                : (a.atmos ? "Dolby Atmos ready" : a.headline) + (a.channels > 0 ? "  ·  up to " + channels(a.channels) : "");
        g.text(sub, Gfx.TEXT_REGULAR, dp(15), 0, l + dp(27), top0 + dp(70), Pixels.white(0.72f * p), Gfx.LEFT, 0);
        g.text("ACCEPTED OVER HDMI", Gfx.TEXT_SEMIBOLD, dp(11), 0.12f, l + dp(27), top0 + dp(104), Pixels.white(0.5f * p), Gfx.LEFT, 0);
        float y = top0 + dp(132);
        if (a != null) {
            for (String f : a.formats) {
                Icons.check(g, l + dp(36), y - dp(5), dp(14), Pixels.white(0.9f * p));
                g.text(f, Gfx.TEXT_MEDIUM, dp(16), 0, l + dp(54), y, white, Gfx.LEFT, 0);
                y += dp(30);
            }
        }
        y += dp(6);
        g.text("Tip: Display & Sounds \u203A Audio \u203A Surround Sound: Best Available", Gfx.TEXT_REGULAR, dp(13), 0, l + dp(27), y, Pixels.white(0.6f * p),
                Gfx.LEFT, 0);
        float bt = y + dp(18), bb = bt + dp(48);
        float inset = dp(14);
        g.fillRoundRect(l + inset, bt, r - inset, bb, rad - inset, Pixels.white(0.93f * p));
        g.text("Open Fire TV Settings", Gfx.TEXT_MEDIUM, dp(17), 0, (l + r) / 2, (bt + bb) / 2 + g.capHeight(Gfx.TEXT_MEDIUM, dp(17)) / 2,
                Pixels.alpha(INK, p), Gfx.CENTER, 0);
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

    private float dp(float v) {
        return v * d;
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
