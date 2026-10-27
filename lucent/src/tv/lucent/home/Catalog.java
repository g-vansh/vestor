package tv.lucent.home;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.DisplayMetrics;

import java.io.File;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which apps go on the dock and in what order. TV apps (those with a Leanback launcher entry)
 * show by default; phone-style apps and Amazon's store and utilities start hidden. The order is
 * the user's once they move a tile; before that, the big streaming apps lead.
 */
final class Catalog {
    /** Default lead order, by Fire TV package name. Anything else follows alphabetically. */
    private static final String[] LEAD = {
        "com.netflix.ninja",
        "com.amazon.firetv.youtube",
        "com.google.android.youtube.tv",
        "com.amazon.avod",
        "com.amazon.firebat",
        "com.disney.disneyplus",
        "com.hulu.plus",
        "com.wbd.stream",
        "com.hbo.hbonow",
        "com.apple.atve.amazon.appletv",
        "com.peacocktv.peacockandroid",
        "com.cbs.ott",
        "com.spotify.tv.android",
        "com.plexapp.android",
        "com.amazon.amazonvideo.livingroom",
    };

    /** Shown only if the user asks: stores, browsers and system utilities. */
    private static final String[] QUIET = {
        "com.amazon.venezia",
        "com.amazon.tv.launcher",
        "com.amazon.tv.settings.v2",
        "com.amazon.cloud9",
        "com.amazon.bueller.photos",
        "com.amazon.bueller.music",
        "com.amazon.ssm",
        "com.amazon.tv.notificationcenter",
    };

    private Catalog() {}

    static final class Entry {
        final String pkg;
        final CharSequence label;
        final Intent intent;
        final ActivityInfo info;
        final boolean leanback;

        Entry(String pkg, CharSequence label, Intent intent, ActivityInfo info, boolean leanback) {
            this.pkg = pkg;
            this.label = label;
            this.intent = intent;
            this.info = info;
            this.leanback = leanback;
        }
    }

    /**
     * Amazon's own video apps may have no launcher entry, because Amazon's home opens them
     * directly. They are looked up by package so Prime Video still gets a tile.
     */
    private static final String[] BY_PACKAGE = {
        "com.amazon.firebat",
        "com.amazon.avod",
        "com.amazon.amazonvideo.livingroom",
    };

    /** Every launchable app, TV apps first. Call off the UI thread. */
    static List<Entry> all(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, Entry> found = new LinkedHashMap<>();
        collect(pm, found, Intent.CATEGORY_LEANBACK_LAUNCHER, true, c.getPackageName());
        collect(pm, found, Intent.CATEGORY_LAUNCHER, false, c.getPackageName());
        for (String pkg : BY_PACKAGE) {
            if (found.containsKey(pkg)) continue;
            try {
                Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
                if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
                if (launch == null) continue;
                ResolveInfo ri = pm.resolveActivity(launch, 0);
                if (ri == null || ri.activityInfo == null) continue;
                found.put(pkg, new Entry(pkg, label(ri.loadLabel(pm), pkg), launch, ri.activityInfo, true));
            } catch (RuntimeException | OutOfMemoryError | StackOverflowError ignored) {
                // not installed, or a label that cannot be loaded
            }
        }
        return new ArrayList<>(found.values());
    }

    private static void collect(PackageManager pm, Map<String, Entry> found, String category, boolean leanback,
            String self) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || ai.packageName.equals(self) || found.containsKey(ai.packageName)) continue;
            Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(category).setClassName(ai.packageName, ai.name);
            try {
                found.put(ai.packageName, new Entry(ai.packageName, label(ri.loadLabel(pm), ai.packageName), launch, ai, leanback));
            } catch (RuntimeException | OutOfMemoryError | StackOverflowError e) {
                // An app whose name cannot be loaded (a huge or looping label resource) is left
                // off the dock rather than taking the whole list down with it.
            }
        }
    }

    /** An app's name as any app may set it, cleaned and capped ({@link Text}). */
    private static String label(CharSequence raw, String pkg) {
        String s = Text.clean(raw);
        return s.isEmpty() ? Text.clean(pkg) : s;
    }

    static boolean shownByDefault(Entry e) {
        if (!e.leanback) return false;
        for (String q : QUIET) if (q.equals(e.pkg)) return false;
        return true;
    }

    /** The dock: visible entries in the user's order, then the lead list, then by name. */
    static List<Entry> dock(List<Entry> all, List<String> order, Set<String> hidden, Set<String> shown) {
        final Map<String, Integer> rank = new HashMap<>();
        for (int i = 0; i < order.size(); i++) rank.put(order.get(i), i);
        final Map<String, Integer> lead = new HashMap<>();
        for (int i = 0; i < LEAD.length; i++) lead.put(LEAD[i], i);
        List<Entry> out = new ArrayList<>();
        for (Entry e : all) {
            if (hidden.contains(e.pkg)) continue;
            if (shownByDefault(e) || shown.contains(e.pkg)) out.add(e);
        }
        final Collator collator = Collator.getInstance();
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                int ra = rank.containsKey(a.pkg) ? rank.get(a.pkg) : Integer.MAX_VALUE;
                int rb = rank.containsKey(b.pkg) ? rank.get(b.pkg) : Integer.MAX_VALUE;
                if (ra != rb) return ra < rb ? -1 : 1;
                int la = lead.containsKey(a.pkg) ? lead.get(a.pkg) : Integer.MAX_VALUE;
                int lb = lead.containsKey(b.pkg) ? lead.get(b.pkg) : Integer.MAX_VALUE;
                if (la != lb) return la < lb ? -1 : 1;
                return collator.compare(a.label.toString(), b.label.toString());
            }
        });
        return out;
    }

    /**
     * The tile art, rendered into a bitmap once so the dock can draw it with anti-aliased rounded
     * corners through a shader. Everything comes from the installed app itself, in the order
     * Google's own TV launcher uses, so each tile carries the app's real artwork and logo:
     * 1. a picture you put in the media folder's banners/ directory, named after the package
     *    (for example banners/com.netflix.ninja.png), for apps whose art you want to choose;
     * 2. the TV banner the app ships (android:banner), at the highest resolution it has;
     * 3. the app's wide logo (android:logo), if it has one;
     * 4. a wide launcher icon: some Fire TV apps ship their TV artwork as the icon, because
     *    Amazon's own home shows the icon and not the banner;
     * 5. the app's own square launcher icon at full resolution, set on a tile made from its own
     *    colours ({@link Tiles}).
     * Nothing is drawn in place of an app's art and nothing is downloaded. Slow: call off the UI
     * thread.
     */
    static void art(PackageManager pm, Entry e, Model.App app, File bannerDir) {
        Bitmap own = userBanner(bannerDir, e.pkg);
        if (own != null) {
            set(app, own);
            return;
        }
        Resources r;
        try {
            r = pm.getResourcesForApplication(e.info.applicationInfo);
        } catch (PackageManager.NameNotFoundException | RuntimeException ex) {
            r = null;
        }
        Drawable art = null;
        float inset = 0;
        if (r != null) {
            art = sharpest(r, e.info.getBannerResource(), 0);
            if (art == null) {
                // A logo is a wordmark, usually on clear glass: it gets room around it.
                art = sharpest(r, e.info.getLogoResource(), WIDE);
                if (art != null) inset = 0.14f;
            }
            if (art == null) art = sharpest(r, e.info.getIconResource(), WIDE);
        }
        if (art == null) {
            try {
                art = e.info.loadBanner(pm);
                if (art == null) art = pm.getApplicationBanner(e.pkg);
            } catch (RuntimeException | PackageManager.NameNotFoundException ignored) {
                art = null;
            }
        }
        if (art != null) {
            Bitmap b = render(art, Tiles.W, Tiles.H, true, inset);
            if (b != null) {
                set(app, b);
                return;
            }
        }
        Bitmap composed = composed(pm, e, r);
        if (composed != null) {
            app.color = Tint.dark(composed);
            app.banner = composed;
        }
        app.loaded = true;
    }

    /** Art at least this much wider than tall counts as a banner (16:9 is 1.78). */
    private static final float WIDE = 1.5f;

    private static void set(Model.App app, Bitmap b) {
        app.color = Tint.dark(b);
        app.banner = b;
        app.loaded = true;
    }

    /**
     * A drawable at the highest density the app ships. Most banners are 320 x 180 (xhdpi, the
     * stick's own density) but 4K-minded apps also ship 640 x 360 or larger, and asking for
     * xxxhdpi gets the sharpest there is. With {@code minAspect} above 0, art that is not at least
     * that wide is passed over. Null when there is no such resource.
     */
    private static Drawable sharpest(Resources r, int res, float minAspect) {
        if (res == 0) return null;
        try {
            Drawable d = r.getDrawableForDensity(res, DisplayMetrics.DENSITY_XXXHIGH, null);
            if (d == null) return null;
            if (minAspect > 0) {
                int w = d.getIntrinsicWidth(), h = d.getIntrinsicHeight();
                if (w <= 0 || h <= 0 || w < h * minAspect) return null;
            }
            return d;
        } catch (RuntimeException | OutOfMemoryError ex) {
            return null;
        }
    }

    /**
     * Your own picture for an app, from banners/ in the media folder. That folder is shared
     * storage, so the file must really be a JPEG or a PNG of sane size ({@link Images}).
     */
    private static Bitmap userBanner(File dir, String pkg) {
        // Package names never hold path separators, but a crafted one is refused anyway.
        if (dir == null || !pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+") || pkg.length() > 200) return null;
        for (String ext : new String[] {".png", ".jpg", ".jpeg"}) {
            File f = new File(dir, pkg + ext);
            if (!f.isFile()) continue;
            Bitmap src = Images.decode(f, 8 * 1024 * 1024, 8192, 64000000L, Tiles.W, Tiles.H);
            if (src == null) continue;
            try {
                // Centre-crop to 16:9.
                Bitmap out = Bitmap.createBitmap(Tiles.W, Tiles.H, Bitmap.Config.ARGB_8888);
                float scale = Math.max(Tiles.W / (float) src.getWidth(), Tiles.H / (float) src.getHeight());
                float w = src.getWidth() * scale, h = src.getHeight() * scale;
                Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
                new Canvas(out).drawBitmap(src, null, new RectF((Tiles.W - w) / 2, (Tiles.H - h) / 2, (Tiles.W + w) / 2,
                        (Tiles.H + h) / 2), p);
                src.recycle();
                return out;
            } catch (RuntimeException | OutOfMemoryError ex) {
                return null;
            }
        }
        return null;
    }

    /** A tile built from the launcher icon, using the adaptive icon's layers when it has them. */
    private static Bitmap composed(PackageManager pm, Entry e, Resources r) {
        Drawable icon = r == null ? null : sharpest(r, e.info.getIconResource(), 0);
        if (icon == null) {
            try {
                icon = e.info.loadIcon(pm);
            } catch (RuntimeException ex) {
                icon = null;
            }
        }
        if (icon == null) return null;
        try {
            int n = 192, m = 216;
            int[] iconPx = pixels(icon, n);
            int[] bg = null, fg = null;
            if (Build.VERSION.SDK_INT >= 26 && icon instanceof AdaptiveIconDrawable) {
                AdaptiveIconDrawable ad = (AdaptiveIconDrawable) icon;
                if (ad.getBackground() != null && ad.getForeground() != null) {
                    bg = pixels(ad.getBackground(), m);
                    fg = pixels(ad.getForeground(), m);
                }
            }
            if (iconPx == null) return null;
            int[] px = Tiles.compose(iconPx, n, bg, fg, m);
            return Bitmap.createBitmap(px, Tiles.W, Tiles.H, Bitmap.Config.ARGB_8888);
        } catch (RuntimeException | OutOfMemoryError ex) {
            return null;
        }
    }

    private static int[] pixels(Drawable d, int n) {
        Bitmap b = render(d, n, n);
        if (b == null) return null;
        int[] px = new int[n * n];
        b.getPixels(px, 0, n, 0, 0, n, n);
        return px;
    }

    private static Bitmap render(Drawable d, int w, int h) {
        return render(d, w, h, false, 0);
    }

    /**
     * Draws {@code d} into a w x h bitmap. With {@code keepShape}, art that is not about the
     * bitmap's shape (a banner made 2:1, say) is fitted inside it, centred, instead of stretched,
     * so a logo is never squashed. {@code inset} keeps that fraction of the tile clear on every
     * side. The tile's own colour shows around the art.
     */
    private static Bitmap render(Drawable d, int w, int h, boolean keepShape, float inset) {
        try {
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Drawable copy = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
            int iw = copy.getIntrinsicWidth(), ih = copy.getIntrinsicHeight();
            float want = w / (float) h;
            if (keepShape && iw > 0 && ih > 0 && (inset > 0 || Math.abs(iw / (float) ih - want) / want > 0.04f)) {
                float scale = Math.min(w * (1 - 2 * inset) / iw, h * (1 - 2 * inset) / ih);
                int dw = Math.round(iw * scale), dh = Math.round(ih * scale);
                copy.setBounds((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2);
            } else {
                copy.setBounds(0, 0, w, h);
            }
            copy.draw(new Canvas(b));
            return b;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }
}
