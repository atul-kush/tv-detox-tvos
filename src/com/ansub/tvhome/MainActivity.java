package com.ansub.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

// A tvOS-style home screen: a picture behind everything, a glass dock of six apps, every other app
// in rows below, and a pill with the time and account that opens Control Center.
//
// The apps come from AppLine (dock slots, then rows). An install can set the dock:
//   am start -n com.ansub.tvhome/.MainActivity --es apps "pkg1,pkg2" --es stamp <unique>
// (the stamp makes it apply once, see saveConfig). On the TV, hold OK on an app (or press Menu)
// to move it, pin it to the dock, take it off the dock or off the home screen.
// --ez fetch true fetches fresh pictures now instead of waiting for the day to pass.
public class MainActivity extends Activity {

    private static final String PREFS = "config";
    private static final List<String> NOT_APPS = Arrays.asList("com.android.tv.settings",
            "com.android.tv", "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx",
            "com.google.android.leanbacklauncher", "ca.dstudio.atvlauncher.free");
    private static final String ADD = "add";
    private static final int PER_ROW = AppLine.DOCK;

    // Layout as a share of the screen width W, matching the approved mockup.
    private static final float MARGIN = 0.035f, PAD = 0.018f, GAP = 0.02f, RADIUS = 0.013f, DOCK_RADIUS = 0.026f;
    private static final float ROW_GAP = 0.032f, ROW_GAP_SCROLLED = 0.04f, DOCK_BOTTOM = 0.0355f, TOP_SCROLLED = 0.024f;
    private static final float FOCUS_SCALE = 1.12f;

    private static Typeface regular, semibold;

    private int screenW, screenH, tileW, tileH;
    private FrameLayout grid;        // dock and rows; slides up for the all-apps view
    private View dockGlass;
    private FrameLayout pill;
    private TextView moveHint;
    private ImageView wallView, blurView;
    private FrameLayout overlay;     // Control Center goes here
    private ControlCenter controlCenter;

    private AppLine line;
    private final Map<String, View> tiles = new HashMap<>();   // key -> focusable card
    private final Map<String, View> wraps = new HashMap<>();   // key -> card plus its name label
    private final List<View> gaps = new ArrayList<>();         // empty dock slots, by slot
    private boolean scrolled, dirty = true;
    private java.util.Locale shownLocale;
    private String focusedKey, movingKey, moveBefore;
    private boolean longPressed;
    // The tile OK went down on. An app that closes on OK's key-down (Netflix's "Exit") hands the matching
    // key-up to the tile still focused here, so a key-up only opens the app it went down on (#30).
    private String okDownKey;

    // The background picture: changed when the TV comes back on and every hour while Home is in front.
    private Wallpapers walls;
    private Fetcher fetcher;
    private boolean screenWasOff = true; // a fresh start counts as the TV coming on
    private final Handler ticker = new Handler();
    private final Runnable recheck = new Runnable() {
        @Override public void run() {
            showPicture();
            ticker.postDelayed(this, 60_000);
        }
    };
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { screenWasOff = true; }
    };

    // Apps installed, removed or updated. This often lands once Home is already back in front (an app
    // uninstalled from the menu is gone only after Android's prompt has closed), so catch up then.
    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            dirty = true;
            refreshApps();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        loadFonts();
        screenW = getResources().getDisplayMetrics().widthPixels;
        screenH = getResources().getDisplayMetrics().heightPixels;
        shownLocale = java.util.Locale.getDefault();
        SharedPreferences prefs = getSharedPreferences(PREFS, 0);
        String saved = prefs.getString("line", null);
        line = saved != null ? AppLine.load(saved)
                : AppLine.fromLegacy(prefs.getString("apps", android.text.TextUtils.join(",", Apps.LIST)));
        saveConfig(getIntent());

        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(0xFF0A0809);
        wallView = new ImageView(this);
        wallView.setScaleType(ImageView.ScaleType.FIT_XY);
        page.addView(wallView, new FrameLayout.LayoutParams(-1, -1));
        // The all-apps view: the same picture, blurred and dimmed.
        blurView = new ImageView(this);
        blurView.setScaleType(ImageView.ScaleType.FIT_XY);
        ColorMatrix dim = new ColorMatrix();
        dim.setScale(0.55f, 0.55f, 0.55f, 1f);
        blurView.setColorFilter(new ColorMatrixColorFilter(dim));
        blurView.setVisibility(View.GONE);
        page.addView(blurView, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x40000000, 0x00000000, 0x00000000, 0x00000000, 0x59000000}));
        page.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        grid = new FrameLayout(this);
        grid.setClipChildren(false);
        page.setClipChildren(false);
        page.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        dockGlass = new View(this);
        dockGlass.setBackground(new Glass.Backdrop(dockGlass, w(DOCK_RADIUS), 0x29FFFFFF));
        grid.addView(dockGlass);

        moveHint = text(w(0.0125f), 0xD9FFFFFF, regular);
        moveHint.setText(Strings.t("Use the arrows to move it, then press OK"));
        moveHint.setVisibility(View.GONE);
        moveHint.setShadowLayer(w(0.004f), 0, 0, 0x99000000);
        page.addView(moveHint, at(Math.round(w(MARGIN)), Math.round(w(0.026f)), -2, -2));

        page.addView(makePill());
        overlay = new FrameLayout(this);
        page.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        setContentView(page);

        walls = new Wallpapers(this);
        fetcher = new Fetcher(this, walls);
        controlCenter = new ControlCenter(this, overlay, walls, new ControlCenter.InputSource() {
            @Override public List<TvInputInfo> list() { return hdmiInputs(); }
            @Override public String label(TvInputInfo i) { return inputLabel(i); }
            @Override public void open(String id) { openInput(id); }
        }, () -> applyPicture(walls.changeNow()));
        registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(packagesChanged, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(packagesChanged, f);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packagesChanged);
        unregisterReceiver(screenOff);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Home pressed while Home is already in front: close Control Center and come back to the dock.
        if (Intent.ACTION_MAIN.equals(intent.getAction()) && intent.hasCategory(Intent.CATEGORY_HOME)) {
            controlCenter.dismiss();
            if (scrolled || movingKey != null || getCurrentFocus() == null || getCurrentFocus().getTag() == null) {
                int[] c = focusedKey == null ? null : cell(focusedKey);
                setScrolled(false);
                if (c == null || !go(nearest(0, c[1]))) focusFirst();
            }
        }
        if (intent.getBooleanExtra("fetch", false)) {
            fetcher.forget();
            fetcher.startIfDue();
        }
        if (saveConfig(intent)) {
            focusedKey = null;
            setScrolled(false);
            buildTiles();
        }
    }

    // An app list passed with --es apps "..." becomes the dock. Returns true if it changed.
    // When Android kills the home screen in the background, it can start it again with the same old
    // intent; the stamp install.sh sends stops that from undoing changes made on the TV since.
    private boolean saveConfig(Intent intent) {
        String apps = intent == null ? null : intent.getStringExtra("apps");
        if (apps == null) return false;
        SharedPreferences prefs = getSharedPreferences(PREFS, 0);
        String stamp = intent.getStringExtra("stamp");
        if (stamp != null && stamp.equals(prefs.getString("stamp", null))) return false;
        if (stamp != null) prefs.edit().putString("stamp", stamp).apply();
        apps = apps.trim();
        AppLine before = line;
        line = AppLine.fromLegacy(apps.isEmpty() || apps.equalsIgnoreCase("auto")
                ? android.text.TextUtils.join(",", Apps.LIST) : apps);
        // Apps taken off the home screen stay off, unless the new list puts them in the dock.
        if (before != null) for (String h : before.hidden()) if (line.positionOf(h) < 0) line.hide(h);
        saveLine();
        dirty = true;
        return true;
    }

    // Every app this TV can open from a home screen, except home screens and Settings' own package
    // (Settings has its own tile). With stockOnly, just the phone-style entries that came with the
    // TV: on TVs they're service and factory tools (TvServices, a clock), so they start hidden.
    private List<String> launchablePackages() {
        return launchable(false);
    }

    private List<String> launchable(boolean stockOnly) {
        PackageManager pm = getPackageManager();
        List<String> out = new ArrayList<>();
        for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                String p = ri.activityInfo.packageName;
                int flags = ri.activityInfo.applicationInfo.flags;
                boolean stockSystem = (flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                boolean tool = category.equals(Intent.CATEGORY_LAUNCHER) && stockSystem && !p.equals(AppLine.PLAY_STORE)
                        && pm.getLeanbackLaunchIntentForPackage(p) == null;
                if (stockOnly && !tool) continue;
                if (!out.contains(p) && !NOT_APPS.contains(p) && !p.equals(getPackageName())) out.add(p);
            }
        }
        return out;
    }

    private void saveLine() {
        getSharedPreferences(PREFS, 0).edit().putString("line", line.save()).apply();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (dirty) {
            dirty = false;
            buildTiles();
        }
        ticker.removeCallbacks(recheck);
        recheck.run();
        fetcher.startIfDue();
        View f = getCurrentFocus();
        if (f == null || f.getTag() == null) focusFirst();
    }

    @Override
    protected void onPause() {
        ticker.removeCallbacks(recheck);
        fetcher.cancel();
        controlCenter.dismiss();
        super.onPause();
    }

    private void showPicture() {
        Bitmap b = walls.showNext(screenWasOff);
        screenWasOff = false;
        applyPicture(b);
    }

    private void applyPicture(Bitmap b) {
        if (b == null) return;
        wallView.setImageBitmap(b);
        blurView.setImageBitmap(Glass.blurred());
        walls.freePrevious();
        Runtime.getRuntime().gc(); // the picture it replaced and its decode buffers can go now
        dockGlass.invalidate();
        pill.invalidate();
    }

    // Back from a menu or a window on top (like the uninstall prompt): catch up on apps that changed.
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) refreshApps();
        else okDownKey = null; // an OK still held finishes in the window that took over
    }

    // Rebuilds the tiles if apps changed, but only while Home is in front with no menu open and no
    // app being moved; otherwise the next onResume, focus change or end of the move does it.
    private void refreshApps() {
        if (!dirty || !hasWindowFocus() || movingKey != null) return;
        dirty = false;
        buildTiles();
    }

    // The TV's language changed: start over so the labels and the date follow it.
    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (!java.util.Locale.getDefault().equals(shownLocale)) recreate();
    }

    @Override
    public void onBackPressed() {
        if (controlCenter.back()) return;
        if (scrolled) {
            setScrolled(false);
            focusFirst();
        }
    }

    // ---- The pill: time and account, opens Control Center ----

    private View makePill() {
        pill = new FrameLayout(this);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Math.round(w(0.016f)), Math.round(w(0.0055f)), Math.round(w(0.007f)), Math.round(w(0.0055f)));
        final TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("H:mm");
        time.setTextSize(TypedValue.COMPLEX_UNIT_PX, w(0.024f));
        time.setTextColor(Color.WHITE);
        time.setTypeface(semibold);
        time.setIncludeFontPadding(false);
        time.setFontFeatureSettings("tnum");
        row.addView(time);
        final ImageView acct = new ImageView(this);
        final Drawable account = Glyph.account();
        acct.setImageDrawable(account);
        int a = Math.round(w(0.031f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(a, a);
        lp.leftMargin = Math.round(w(0.011f));
        row.addView(acct, lp);
        pill.addView(row);
        final float r = w(0.021f);
        final Glass.Backdrop glass = new Glass.Backdrop(pill, r, 0x2EFFFFFF);
        final GradientDrawable white = new GradientDrawable();
        white.setCornerRadius(r);
        white.setColor(0xEBFFFFFF);
        pill.setBackground(glass);
        pill.setFocusable(true);
        pill.setTag("pill");
        pill.setOnFocusChangeListener((v, has) -> {
            pill.setBackground(has ? white : glass);
            time.setTextColor(has ? 0xFF111111 : Color.WHITE);
            account.setTint(has ? 0xFF111111 : 0xFFFFFFFF);
            pill.setScaleX(has ? 1.08f : 1f);
            pill.setScaleY(has ? 1.08f : 1f);
            if (has) focusedKey = "pill";
        });
        pill.setOnClickListener(v -> controlCenter.show());
        pill.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) { focusBelowPill(); return true; }
            return code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_LEFT
                    || code == KeyEvent.KEYCODE_DPAD_RIGHT;
        });
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        p.topMargin = Math.round(w(0.026f));
        p.rightMargin = Math.round(w(MARGIN));
        pill.setLayoutParams(p);
        return pill;
    }

    // ---- Dock and rows ----

    private void buildTiles() {
        // Tools that came with the TV start hidden; "Add app" can bring any of them in.
        for (String p : launchable(true)) if (line.positionOf(p) < 0 && !line.hidden().contains(p)) line.hide(p);
        line.reconcile(launchablePackages());
        saveLine();
        String want = focusedKey;
        grid.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        for (View v : wraps.values()) grid.removeView(v);
        for (View v : gaps) grid.removeView(v);
        wraps.clear();
        tiles.clear();
        gaps.clear();
        movingKey = null;
        moveHint.setVisibility(View.GONE);

        float inner = screenW - 2 * w(MARGIN) - 2 * w(PAD) - (PER_ROW - 1) * w(GAP);
        tileW = Math.round(inner / PER_ROW);
        tileH = tileW * 3 / 5;
        PackageManager pm = getPackageManager();
        for (int i = 0; i < AppLine.DOCK; i++) {
            String key = line.dock().get(i);
            if (key == null) gaps.add(addGap());
            else {
                addTile(pm, key);
                gaps.add(null);
            }
        }
        for (String key : line.rows()) addTile(pm, key);
        addTile(pm, ADD);
        grid.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        relayout();
        // The full-size banners and icons other apps hand out are only needed to draw the tiles once;
        // let them go now rather than whenever the next collection happens.
        Runtime.getRuntime().gc();
        View restore = want == null ? null : tiles.get(want);
        if (restore != null) restore.requestFocus();
        else if (!"pill".equals(want)) focusFirst();
    }

    // An empty dock slot: a faint outline that focus skips.
    private View addGap() {
        View v = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(w(RADIUS));
        g.setColor(0x14FFFFFF);
        g.setStroke(1, 0x40FFFFFF);
        v.setBackground(g);
        grid.addView(v, new FrameLayout.LayoutParams(tileW, tileH));
        return v;
    }

    private void addTile(PackageManager pm, final String key) {
        FrameLayout card = new FrameLayout(this);
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(w(RADIUS));
        String label = labelOf(key);
        Drawable banner = null, icon = null, glyph = null;
        if (ADD.equals(key)) {
            label = Strings.t("Add app");
            glyph = Glyph.plus();
        } else {
            Intent li = AppLine.SETTINGS.equals(key) ? new Intent(Settings.ACTION_SETTINGS) : launchIntent(pm, key);
            try {
                if (li != null) banner = pm.getActivityBanner(li);
                if (banner == null && !AppLine.SETTINGS.equals(key)) banner = pm.getApplicationBanner(key);
                if (banner == null && li != null) icon = pm.getActivityIcon(li);
            } catch (Exception ignored) {}
            if (banner == null && icon == null) glyph = AppLine.SETTINGS.equals(key) ? Glyph.gear() : Glyph.play();
            if (AppLine.SETTINGS.equals(key)) label = Strings.t("Settings");
        }
        if (banner != null) {
            bg.setColor(0xFF1E1A1C);
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(fit(banner, tileW, tileH));
            card.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            // A plain glass tile: the icon, with the name under it.
            bg.setColor(0x24FFFFFF);
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            ImageView iv = new ImageView(this);
            int size = glyph != null ? Math.round(tileH * 0.32f) : Math.round(tileH * 0.42f);
            iv.setImageDrawable(glyph != null ? glyph : fit(icon, size, size));
            box.addView(iv, new LinearLayout.LayoutParams(size, size));
            TextView t = text(w(0.0105f), 0xE6FFFFFF, regular);
            t.setText(label);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER);
            t.setPadding(Math.round(w(0.006f)), 0, Math.round(w(0.006f)), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = Math.round(tileH * 0.06f);
            box.addView(t, lp);
            card.addView(box, new FrameLayout.LayoutParams(-1, -1));
        }
        card.setBackground(bg);
        card.setClipToOutline(true);
        card.setFocusable(true);
        card.setTag(key);
        card.setContentDescription(label);

        // The name under the tile, shown only in the all-apps view while it has focus.
        final TextView name = text(w(0.0106f), Color.WHITE, semibold);
        name.setText(label);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER_HORIZONTAL);
        name.setShadowLayer(w(0.003f), 0, w(0.001f), 0x99000000);
        name.setVisibility(View.INVISIBLE);

        card.setOnFocusChangeListener((v, has) -> {
            if (has) {
                focusedKey = key;
                // Focus that lands below the dock (after a menu, a move or a restart) opens the all-apps view.
                int[] c = cell(key);
                if (c != null && c[0] > 0 && !scrolled) setScrolled(true);
                keepVisible(key);
            }
            float s = key.equals(movingKey) ? 1.18f : has ? FOCUS_SCALE : 1f;
            v.setScaleX(s);
            v.setScaleY(s);
            v.setTranslationZ(has ? w(0.02f) : 0);
            name.setVisibility(has && scrolled ? View.VISIBLE : View.INVISIBLE);
        });
        final boolean editable = !ADD.equals(key);
        card.setOnClickListener(v -> open(key));
        card.setOnKeyListener((v, code, ev) -> onTileKey(key, editable, code, ev));

        FrameLayout wrap = new FrameLayout(this);
        wrap.setClipChildren(false);
        // The wrap is a gap wider than the tile, so a long name can spill a little either side.
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(tileW, tileH);
        cp.leftMargin = Math.round(w(GAP) / 2);
        wrap.addView(card, cp);
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(tileW + Math.round(w(GAP)), -2);
        np.topMargin = Math.round(tileH * (1 + (FOCUS_SCALE - 1) / 2) + w(0.0045f));
        wrap.addView(name, np);
        grid.addView(wrap, new FrameLayout.LayoutParams(tileW + Math.round(w(GAP)), -2));
        wraps.put(key, wrap);
        tiles.put(key, card);
    }

    // Places the dock and every tile for the current view (home or all apps).
    private void relayout() {
        float m = w(MARGIN), pad = w(PAD), gap = w(GAP), rowGap = w(scrolled ? ROW_GAP_SCROLLED : ROW_GAP);
        int dockH = Math.round(tileH + 2 * pad);
        int dockTop = scrolled ? Math.round(w(TOP_SCROLLED)) : Math.round(screenH - w(DOCK_BOTTOM) - dockH);
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(Math.round(screenW - 2 * m), dockH);
        dl.leftMargin = Math.round(m);
        dl.topMargin = dockTop;
        dockGlass.setLayoutParams(dl);
        for (int i = 0; i < AppLine.DOCK; i++) {
            String key = line.dock().get(i);
            View v = key == null ? gaps.get(i) : wraps.get(key);
            place(v, Math.round(m + pad + i * (tileW + gap)), Math.round(dockTop + pad), key == null);
        }
        int rowsTop = dockTop + dockH + Math.round(rowGap);
        List<String> rows = new ArrayList<>(line.rows());
        rows.add(ADD);
        for (int k = 0; k < rows.size(); k++) {
            View v = wraps.get(rows.get(k));
            if (v == null) continue;
            place(v, Math.round(m + pad + (k % PER_ROW) * (tileW + gap)), rowsTop + (k / PER_ROW) * Math.round(tileH + rowGap), false);
        }
        pill.setVisibility(scrolled ? View.INVISIBLE : View.VISIBLE);
        blurView.setVisibility(scrolled ? View.VISIBLE : View.GONE);
        if (!scrolled) grid.setTranslationY(0);
        View focused = focusedKey == null ? null : tiles.get(focusedKey);
        for (Map.Entry<String, View> e : wraps.entrySet()) {
            View label = ((ViewGroup) e.getValue()).getChildAt(1);
            label.setVisibility(scrolled && tiles.get(e.getKey()) == focused && focused != null && focused.hasFocus()
                    ? View.VISIBLE : View.INVISIBLE);
        }
        dockGlass.invalidate();
    }

    private void place(View v, int x, int y, boolean gap) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
        if (gap) {
            lp.width = tileW;
            lp.height = tileH;
        }
        lp.leftMargin = gap ? x : x - Math.round(w(GAP) / 2);
        lp.topMargin = y;
        v.setLayoutParams(lp);
    }

    // In the all-apps view, slides the rows so the focused one stays on screen.
    private void keepVisible(String key) {
        if (!scrolled) return;
        View wrap = wraps.get(key);
        if (wrap == null) return;
        int top = ((FrameLayout.LayoutParams) wrap.getLayoutParams()).topMargin;
        float bottomLimit = screenH - w(0.06f);
        float shift = Math.min(0, bottomLimit - (top + tileH));
        grid.setTranslationY(shift);
        dockGlass.invalidate();
    }

    void setScrolled(boolean s) {
        if (s == scrolled) return;
        scrolled = s;
        relayout();
    }

    // ---- The remote ----

    // Where each key sits: row 0 is the dock (slots 0-5), then the rows of six, "Add app" last.
    private int[] cell(String key) {
        int p = ADD.equals(key) ? AppLine.DOCK + line.rows().size() : line.positionOf(key);
        if (p < 0) return null;
        return p < AppLine.DOCK ? new int[] {0, p} : new int[] {1 + (p - AppLine.DOCK) / PER_ROW, (p - AppLine.DOCK) % PER_ROW};
    }

    private String at(int row, int col) {
        if (row == 0) return col >= 0 && col < AppLine.DOCK ? line.dock().get(col) : null;
        int k = (row - 1) * PER_ROW + col;
        List<String> rows = line.rows();
        if (col < 0 || col >= PER_ROW || k < 0) return null;
        if (k < rows.size()) return rows.get(k);
        return k == rows.size() ? ADD : null;
    }

    private int lastRow() {
        return 1 + line.rows().size() / PER_ROW; // the row "Add app" is in
    }

    // The nearest app in a row to a column, looking outward; null if the row is empty.
    private String nearest(int row, int col) {
        for (int d = 0; d < PER_ROW; d++) {
            String k = at(row, col - d);
            if (k != null) return k;
            k = at(row, col + d);
            if (k != null) return k;
        }
        return null;
    }

    private boolean go(String key) {
        View t = key == null ? null : tiles.get(key);
        if (t == null) return false;
        t.requestFocus();
        return true;
    }

    // The first app in the dock. With nothing in the dock, Home stays as it is with the pill focused
    // (so the time and Control Center are right there); Down from the pill goes on to the rows.
    void focusFirst() {
        if (scrolled) setScrolled(false);
        for (String k : line.dock()) if (k != null && go(k)) return;
        pill.requestFocus();
    }

    private void focusBelowPill() {
        for (String k : line.dock()) if (k != null && go(k)) return;
        setScrolled(true);
        go(nearest(1, 0));
    }

    private boolean navigate(String key, int code) {
        int[] c = cell(key);
        if (c == null) return false;
        int row = c[0], col = c[1];
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                int step = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
                for (int x = col + step; x >= 0 && x < PER_ROW; x += step) if (go(at(row, x))) return true;
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_UP:
                if (row == 0) {
                    if (scrolled) setScrolled(false);
                    else pill.requestFocus();
                    return true;
                }
                if (row == 1 && nearest(0, col) == null) {
                    // Nothing in the dock: Up goes straight on to the pill.
                    setScrolled(false);
                    pill.requestFocus();
                    return true;
                }
                go(row == 1 ? nearest(0, col) : nearest(row - 1, col));
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (row >= lastRow()) return true;
                String below = nearest(row + 1, Math.min(col, PER_ROW - 1));
                if (below == null) return true;
                if (row == 0) setScrolled(true);
                go(below);
                return true;
            default:
                return false;
        }
    }

    private boolean onTileKey(String key, boolean editable, int code, KeyEvent ev) {
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean down = ev.getAction() == KeyEvent.ACTION_DOWN;
        if (key.equals(movingKey)) return moveKey(key, code, ok, down);
        if (down && navigate(key, code)) return true;
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) return true;
        if (editable && code == KeyEvent.KEYCODE_MENU) {
            if (!down) showEditMenu(key);
            return true;
        }
        if (!ok) return false;
        if (down) {
            if (ev.getRepeatCount() == 0) {
                longPressed = false;
                okDownKey = key;
            } else if (editable && !longPressed && (ev.isLongPress()
                    || ev.getEventTime() - ev.getDownTime() >= ViewConfiguration.getLongPressTimeout())) {
                longPressed = true;
                showEditMenu(key);
            }
        } else {
            // Only the key-up of an OK that went down on this tile opens the app, and not a cancelled
            // one (focus left while OK was held). View does the same for a click with isPressed().
            boolean pressed = key.equals(okDownKey) && !ev.isCanceled();
            okDownKey = null;
            if (pressed && !longPressed) open(key);
        }
        return true;
    }

    private void open(String key) {
        if (ADD.equals(key)) {
            showAddMenu();
            return;
        }
        Intent i = AppLine.SETTINGS.equals(key) ? new Intent(Settings.ACTION_SETTINGS) : launchIntent(getPackageManager(), key);
        if (i == null) return;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception ignored) {}
    }

    // ---- Arranging apps ----

    // Hold OK (or Menu) on an app. Removing from home only hides the tile: the app stays installed and
    // "Add app" brings it back. Apps the user installed can be uninstalled; apps that came with the TV
    // get App info instead, where Android offers to turn them off.
    private void showEditMenu(final String key) {
        final boolean docked = line.positionOf(key) >= 0 && line.positionOf(key) < AppLine.DOCK;
        final boolean settings = AppLine.SETTINGS.equals(key);
        final boolean user = !settings && userInstalled(key);
        List<String> items = new ArrayList<>(Arrays.asList("Move", docked ? "Remove from dock" : "Pin to dock", "Remove from home"));
        if (!settings) items.add(user ? "Uninstall" : "App info");
        final boolean full = !docked && line.dockFull();
        boolean[] dim = new boolean[items.size()];
        dim[1] = full;
        dropdown(tiles.get(key), items.toArray(new String[0]), dim, which -> {
            if (which == 0) startMove(key);
            else if (which == 1) {
                if (docked) line.removeFromDock(key);
                else if (full) {
                    Toast.makeText(this, Strings.t("Dock is full. Use Move to swap an app in."), Toast.LENGTH_LONG).show();
                    return;
                } else line.pin(key);
                saveLine();
                focusedKey = key;
                buildTiles();
            } else if (which == 2) {
                line.hide(key);
                saveLine();
                focusedKey = null;
                buildTiles();
            } else {
                Uri pkg = Uri.parse("package:" + key);
                try { startActivity(user ? new Intent(Intent.ACTION_DELETE, pkg)
                        : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)); }
                catch (Exception ignored) {}
            }
        });
    }

    // Move mode: the app lifts, the arrows slide it along the line (others nudge over), OK keeps it
    // and Back puts everything back as it was.
    private void startMove(String key) {
        View card = tiles.get(key);
        if (card == null) return;
        movingKey = key;
        moveBefore = line.save();
        card.setScaleX(1.18f);
        card.setScaleY(1.18f);
        moveHint.setVisibility(View.VISIBLE);
        card.requestFocus();
    }

    private boolean moveKey(String key, int code, boolean ok, boolean down) {
        int step = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1
                : code == KeyEvent.KEYCODE_DPAD_UP ? -PER_ROW : code == KeyEvent.KEYCODE_DPAD_DOWN ? PER_ROW : 0;
        if (step != 0) {
            if (down) {
                int before = line.positionOf(key), after = line.slide(key, step);
                if (after != before) {
                    if (after >= AppLine.DOCK && !scrolled) setScrolled(true);
                    rebuildGaps();
                    relayout();
                    keepVisible(key);
                }
            }
            return true;
        }
        boolean back = code == KeyEvent.KEYCODE_BACK;
        if (ok || back || code == KeyEvent.KEYCODE_MENU) {
            if (!down) {
                if (back) line = AppLine.load(moveBefore);
                movingKey = null;
                longPressed = false;
                moveHint.setVisibility(View.GONE);
                saveLine();
                focusedKey = key;
                buildTiles();
            }
            return true;
        }
        return true; // everything else stays put while moving
    }

    // Empty dock slots change while an app slides through the dock: redo their outlines.
    private void rebuildGaps() {
        for (View v : gaps) if (v != null) grid.removeView(v);
        gaps.clear();
        for (int i = 0; i < AppLine.DOCK; i++) gaps.add(line.dock().get(i) == null ? addGap() : null);
        for (View v : gaps) if (v != null) v.bringToFront();
        for (View v : wraps.values()) v.bringToFront();
    }

    // The apps taken off the home screen, by name, to bring one back.
    private void showAddMenu() {
        final PackageManager pm = getPackageManager();
        TreeMap<String, String> byLabel = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<String> installed = launchablePackages();
        for (String p : line.hidden()) {
            if (AppLine.SETTINGS.equals(p) || installed.contains(p)) byLabel.put(labelOf(p), p);
        }
        if (byLabel.isEmpty()) {
            panel(Strings.t("Every app is already here"), Collections.singletonList("OK"), which -> {});
            return;
        }
        final List<String> pkgs = new ArrayList<>(byLabel.values());
        panel(Strings.t("Add an app"), new ArrayList<>(byLabel.keySet()), pos -> AppLine.SETTINGS.equals(pkgs.get(pos))
                ? Glyph.gear() : fit(pm.getApplicationIcon(pkgs.get(pos)), w(0.0375f), w(0.0375f)), Math.round(w(0.0375f)), which -> {
            line.unhide(pkgs.get(which));
            saveLine();
            focusedKey = pkgs.get(which);
            buildTiles();
        });
    }

    private String labelOf(String pkg) {
        if (AppLine.SETTINGS.equals(pkg)) return Strings.t("Settings");
        PackageManager pm = getPackageManager();
        try { return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); }
        catch (Exception e) { return pkg; }
    }

    // ---- Menus ----

    interface Pick { void at(int which); }
    interface Icon { Drawable at(int which) throws Exception; }

    private void panel(String title, List<String> labels, Pick pick) {
        panel(title, labels, null, 0, pick);
    }

    // A side panel in the home screen's own look: a title, then one row per choice. Back closes it.
    // icon (or null) puts an icon of that size by each name.
    private void panel(String title, final List<String> labels, final Icon icon, final int size, final Pick pick) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Math.round(w(0.025f));
        box.setPadding(p, Math.round(w(0.048f)), p, p);
        TextView head = text(w(0.021f), Color.WHITE, semibold);
        head.setText(title);
        head.setSingleLine(true);
        head.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.setPadding(Math.round(w(0.0125f)), 0, Math.round(w(0.0125f)), 0);
        box.addView(head);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setVerticalScrollBarEnabled(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        GradientDrawable focus = new GradientDrawable();
        focus.setCornerRadius(w(RADIUS));
        focus.setColor(0x38FFFFFF);
        list.setSelector(focus);
        final Drawable[] icons = new Drawable[labels.size()];
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return labels.size(); }
            @Override public Object getItem(int pos) { return labels.get(pos); }
            @Override public long getItemId(int pos) { return pos; }
            @Override public View getView(int pos, View convert, ViewGroup parent) {
                TextView t = convert != null ? (TextView) convert : text(w(0.0156f), 0xE6FFFFFF, regular);
                t.setText(labels.get(pos));
                t.setSingleLine(true);
                t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                t.setGravity(Gravity.CENTER_VERTICAL);
                t.setMinHeight(Math.round(w(0.046f)));
                t.setPadding(Math.round(w(0.0125f)), 0, Math.round(w(0.0125f)), 0);
                if (icon != null && icons[pos] == null) {
                    try { icons[pos] = icon.at(pos); }
                    catch (Exception e) { icons[pos] = new ColorDrawable(Color.TRANSPARENT); }
                    icons[pos].setBounds(0, 0, size, size);
                }
                t.setCompoundDrawablesRelative(icon != null ? icons[pos] : null, null, null, null);
                t.setCompoundDrawablePadding(Math.round(w(0.0125f)));
                return t;
            }
        });
        list.setOnItemClickListener((parent, v, pos, id) -> {
            d.dismiss();
            pick.at(pos);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1f);
        lp.topMargin = Math.round(w(0.019f));
        box.addView(list, lp);
        d.setContentView(box);
        Window win = d.getWindow();
        win.setBackgroundDrawable(new ColorDrawable(0xF7161113));
        win.setLayout(Math.round(w(0.333f)), -1);
        win.setGravity(Gravity.END);
        win.setDimAmount(0.5f);
        d.show();
    }

    // A small menu that drops down under a tile. Opens above the tile when there's no room below.
    // dim[i] draws item i faded (it still answers, so it can say why it can't be done). Back closes it.
    private void dropdown(View anchor, String[] items, boolean[] dim, final Pick pick) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(w(0.0052f)), rowH = Math.round(w(0.033f));
        box.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(w(RADIUS));
        bg.setColor(0xF2231D1F);
        bg.setStroke(1, 0x17FFFFFF);
        box.setBackground(bg);
        float widest = 0;
        for (int i = 0; i < items.length; i++) {
            final int which = i;
            final boolean faded = dim != null && dim[i];
            final TextView t = text(w(0.0135f), faded ? 0x66FFFFFF : 0xD9FFFFFF, regular);
            t.setText(Strings.t(items[i]));
            t.setSingleLine(true);
            t.setGravity(Gravity.CENTER_VERTICAL);
            int tp = Math.round(w(0.0115f));
            t.setPadding(tp, 0, tp, 0);
            widest = Math.max(widest, t.getPaint().measureText(t.getText().toString()) + 2 * tp + 2 * pad);
            final GradientDrawable focus = new GradientDrawable();
            focus.setCornerRadius(w(RADIUS) - pad);
            focus.setColor(Color.TRANSPARENT);
            t.setBackground(focus);
            t.setFocusable(true);
            t.setOnFocusChangeListener((v, has) -> {
                focus.setColor(has ? 0x38FFFFFF : Color.TRANSPARENT);
                t.setTextColor(faded ? (has ? 0x8CFFFFFF : 0x66FFFFFF) : has ? Color.WHITE : 0xD9FFFFFF);
            });
            t.setOnClickListener(v -> {
                d.dismiss();
                pick.at(which);
            });
            box.addView(t, new LinearLayout.LayoutParams(-1, rowH));
        }
        d.setContentView(box);
        int[] at = new int[2];
        anchor.getLocationOnScreen(at);
        int tileBottom = at[1] + Math.round(anchor.getHeight() * anchor.getScaleY());
        int width = Math.max(Math.max(Math.round(anchor.getWidth() * anchor.getScaleX()), Math.round(w(0.167f))), (int) Math.ceil(widest));
        int height = items.length * rowH + 2 * pad;
        int y = tileBottom + Math.round(w(0.012f));
        if (y + height > screenH - w(0.0125f)) y = at[1] - height - Math.round(w(0.008f));
        Window win = d.getWindow();
        win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        win.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        win.setGravity(Gravity.TOP | Gravity.START);
        android.view.WindowManager.LayoutParams lp = win.getAttributes();
        lp.x = Math.min(at[0], screenW - width - Math.round(w(0.0125f)));
        lp.y = y;
        lp.width = width;
        lp.height = height;
        win.setAttributes(lp);
        d.show();
    }

    // ---- HDMI inputs (shown in Control Center) ----

    // Live TV apps an HDMI input can open, in order. Droidlogic (Amlogic) TVs ship Live TV under their
    // own package, with TvActivity as its entry point.
    private static final String[][] LIVE_TV = {{"com.android.tv", "com.android.tv.MainActivity"},
            {"com.droidlogic.android.tv", "com.android.tv.TvActivity"}};
    private static final String INPUT_PLAYER = "com.google.android.tv.inputplayer";
    private static final int MAX_INPUTS = 4;

    // Opens an HDMI input: the stock Live TV app first, then Droidlogic's, then whatever the TV picks.
    // Each one is tried only when the one before it can't start, so a TV where the stock app already
    // worked takes exactly the same path as before. The one exception: when the TV itself sends HDMI to
    // Google TV's input player, that goes first, because some Google TVs ship com.android.tv as a stub
    // that starts fine but only says "Hello World!" (issue #5).
    private void openInput(String id) {
        Intent view = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id));
        List<Intent> tries = new ArrayList<>();
        ResolveInfo def = getPackageManager().resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY);
        if (def != null && def.activityInfo != null && INPUT_PLAYER.equals(def.activityInfo.packageName))
            tries.add(new Intent(view).setClassName(INPUT_PLAYER, def.activityInfo.name));
        for (String[] live : LIVE_TV) {
            Intent t = new Intent(view).setClassName(live[0], live[1]);
            if (t.resolveActivity(getPackageManager()) != null) tries.add(t);
        }
        tries.add(view);
        for (Intent t : tries) {
            t.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(t);
                return;
            } catch (Exception ignored) {}
        }
    }

    // The TV's own HDMI inputs (not devices behind them), in order, skipping hidden ones.
    private List<TvInputInfo> hdmiInputs() {
        List<TvInputInfo> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return out;
        for (TvInputInfo in : tim.getTvInputList()) {
            if (in.getType() == TvInputInfo.TYPE_HDMI && in.getParentId() == null && !in.isHidden(this)) out.add(in);
        }
        Collections.sort(out, (a, b) -> a.getId().compareTo(b.getId()));
        return out.size() > MAX_INPUTS ? out.subList(0, MAX_INPUTS) : out;
    }

    // The name set in Settings > Inputs (like "Bedroom"), else the TV's own name (like "HDMI 1").
    private String inputLabel(TvInputInfo in) {
        CharSequence custom = in.loadCustomLabel(this);
        if (custom != null && custom.length() > 0) return withoutDeviceType(custom.toString());
        CharSequence label = in.loadLabel(this);
        return label != null && label.length() > 0 ? withoutDeviceType(label.toString()) : "HDMI";
    }

    // Philips stores a name as "name&DEVICE_TYPE" (like "Computer&COMPUTER"), so show the name only.
    // Only on Philips TVs: elsewhere a name like "AUDIO&VIDEO" is the name itself.
    private static String withoutDeviceType(String label) {
        if (!"Philips".equalsIgnoreCase(android.os.Build.MANUFACTURER)) return label;
        int amp = label.lastIndexOf('&');
        return amp > 0 && label.substring(amp + 1).matches("[A-Z][A-Z0-9_]{2,}") ? label.substring(0, amp).trim() : label;
    }

    // ---- Helpers ----

    private boolean userInstalled(String pkg) {
        try { return (getPackageManager().getApplicationInfo(pkg, 0).flags & ApplicationInfo.FLAG_SYSTEM) == 0; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    private static Intent launchIntent(PackageManager pm, String pkg) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        return i != null ? i : pm.getLaunchIntentForPackage(pkg);
    }

    // Xiaomi TVs ship MiLan Pro but don't register it by name; every other TV gets Roboto.
    private static void loadFonts() {
        if (regular != null) return;
        regular = fromFile("/system/fonts/MILanPro_Normal.ttf", Typeface.create("sans-serif", Typeface.NORMAL));
        semibold = fromFile("/system/fonts/MILanPro_SemiBold.ttf", Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    static Typeface regular() { loadFonts(); return regular; }
    static Typeface semibold() { loadFonts(); return semibold; }

    private static Typeface fromFile(String path, Typeface fallback) {
        try { return new File(path).exists() ? Typeface.createFromFile(path) : fallback; }
        catch (Exception e) { return fallback; }
    }

    // Draws a banner once into a tile-sized bitmap (center-cropped). Some apps ship huge banners
    // that Android 9 refuses to draw ("Canvas: trying to draw too large bitmap"); this also saves RAM.
    private Drawable fit(Drawable d, float wf, float hf) {
        int w = Math.max(1, Math.round(wf)), h = Math.max(1, Math.round(hf));
        try {
            // Banners are opaque, so half the memory per pixel does; icons keep their see-through edges.
            boolean opaque = d.getOpacity() == android.graphics.PixelFormat.OPAQUE;
            Bitmap b = Bitmap.createBitmap(w, h, opaque ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888);
            int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
            if (iw <= 0 || ih <= 0) { iw = w; ih = h; }
            float s = Math.max((float) w / iw, (float) h / ih);
            int dw = Math.round(iw * s), dh = Math.round(ih * s);
            d.setBounds((w - dw) / 2, (h - dh) / 2, (w - dw) / 2 + dw, (h - dh) / 2 + dh);
            d.draw(new Canvas(b));
            // Tiles are drawn as they are from now on: keep them on the graphics chip only.
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                Bitmap hw = b.copy(Bitmap.Config.HARDWARE, false);
                if (hw != null) {
                    b.recycle();
                    b = hw;
                }
            }
            return new BitmapDrawable(getResources(), b);
        } catch (Throwable t) {
            return d;
        }
    }

    // A share of the screen width, in pixels.
    float w(float share) {
        return share * screenW;
    }

    private TextView text(float px, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        t.setTextColor(color);
        if (tf != null) t.setTypeface(tf);
        return t;
    }

    private static FrameLayout.LayoutParams at(int x, int y, int w, int h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = x;
        lp.topMargin = y;
        return lp;
    }
}
