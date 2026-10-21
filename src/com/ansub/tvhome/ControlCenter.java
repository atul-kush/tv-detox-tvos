package com.ansub.tvhome;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

// The panel the pill opens, over the top right of the home screen: time, date and account, then
// Settings, Audio and Inputs, then Wi-Fi and Bluetooth with their status, and the picture's credit.
// Inputs swaps the buttons for a list of HDMI inputs. Back steps out: the list, then the panel.
final class ControlCenter {
    interface InputSource {
        List<TvInputInfo> list();
        String label(TvInputInfo i);
        void open(String id);
    }

    private final Activity activity;
    private final FrameLayout parent;
    private final Wallpapers walls;
    private final InputSource inputs;
    private final Runnable changeNow;
    private final Fetch fetch;

    // Get new pictures: fetch for the ticked themes now; done gets what to tell the person.
    interface Fetch { void start(Fetcher.Done done); }
    private final int screenW;
    private View dim;
    private LinearLayout panel, body;
    private TextView credit;
    private View settingsTile, inputsTile, wallpaperTile;
    private boolean showingInputs, showingWallpaper;

    // changeNow puts the next picture on screen (the home screen owns the picture's view).
    ControlCenter(Activity a, FrameLayout overlayParent, Wallpapers w, InputSource inputs, Runnable changeNow, Fetch fetch) {
        activity = a;
        parent = overlayParent;
        walls = w;
        this.inputs = inputs;
        this.changeNow = changeNow;
        this.fetch = fetch;
        screenW = a.getResources().getDisplayMetrics().widthPixels;
    }

    boolean isShowing() {
        return panel != null;
    }

    void show() {
        if (panel != null) return;
        blockOthers(true);
        dim = new View(activity);
        dim.setBackgroundColor(0x59000000);
        parent.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        // A focused button grows a little; let it draw past its row so its corners stay round.
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        int p = px(0.018f);
        panel.setPadding(p, p, p, p);
        panel.setBackground(new Glass.Backdrop(panel, px(0.024f), 0x9E28282C));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px(0.36f), -2, Gravity.TOP | Gravity.END);
        lp.topMargin = px(0.026f);
        lp.rightMargin = px(0.035f);
        parent.addView(panel, lp);
        body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setClipChildren(false);
        panel.addView(body, new LinearLayout.LayoutParams(-1, -2));
        showButtons();
        View line = new View(activity);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, 1);
        ll.topMargin = px(0.014f);
        panel.addView(line, ll);
        credit = text(0.0095f, 0x99FFFFFF, MainActivity.regular());
        credit.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.topMargin = px(0.011f);
        panel.addView(credit, cl);
        refreshCredit();
        settingsTile.requestFocus();
    }

    void dismiss() {
        if (panel == null) return;
        parent.removeView(panel);
        parent.removeView(dim);
        panel = null;
        dim = null;
        showingInputs = false;
        showingWallpaper = false;
        blockOthers(false);
    }

    private void refreshCredit() {
        Picture current = walls.current();
        credit.setText(current == null ? "" : PictureRules.credit(current));
        credit.setVisibility(current == null ? View.GONE : View.VISIBLE);
    }

    // Back: from the inputs list or wallpaper options to the buttons, from the buttons to the home screen.
    boolean back() {
        if (panel == null) return false;
        if (showingInputs || showingWallpaper) {
            boolean wallpaper = showingWallpaper;
            showButtons();
            (wallpaper ? wallpaperTile : inputsTile).requestFocus();
            return true;
        }
        dismiss();
        View pill = ((View) parent.getParent()).findViewWithTag("pill");
        if (pill != null) pill.requestFocus();
        return true;
    }

    // While the panel is open nothing behind it can take focus.
    private void blockOthers(boolean block) {
        ViewGroup page = (ViewGroup) parent.getParent();
        for (int i = 0; i < page.getChildCount(); i++) {
            View c = page.getChildAt(i);
            if (c == parent) continue;
            if (c instanceof ViewGroup) ((ViewGroup) c).setDescendantFocusability(
                    block ? ViewGroup.FOCUS_BLOCK_DESCENDANTS : ViewGroup.FOCUS_AFTER_DESCENDANTS);
            if ("pill".equals(c.getTag())) c.setFocusable(!block);
        }
    }

    private void showButtons() {
        showingInputs = false;
        showingWallpaper = false;
        body.removeAllViews();
        LinearLayout head = new LinearLayout(activity);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.TOP);
        LinearLayout when = new LinearLayout(activity);
        when.setOrientation(LinearLayout.VERTICAL);
        TextClock time = clock("h:mm", "H:mm", 0.032f, Color.WHITE, MainActivity.semibold());
        time.setIncludeFontPadding(false);
        when.addView(time);
        String day = Strings.datePattern();
        TextClock date = clock(day, day, 0.0115f, 0xB3FFFFFF, MainActivity.regular());
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-2, -2);
        dl.topMargin = px(0.004f);
        when.addView(date, dl);
        head.addView(when, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout who = new LinearLayout(activity);
        who.setOrientation(LinearLayout.HORIZONTAL);
        who.setGravity(Gravity.CENTER_VERTICAL);
        ImageView acct = new ImageView(activity);
        acct.setImageDrawable(Glyph.account());
        who.addView(acct, new LinearLayout.LayoutParams(px(0.026f), px(0.026f)));
        TextView name = text(0.0115f, 0xE6FFFFFF, MainActivity.regular());
        name.setText(Strings.t("Account"));
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-2, -2);
        nl.leftMargin = px(0.007f);
        who.addView(name, nl);
        head.addView(who);
        body.addView(head);

        int gap = px(0.011f);
        int inner = px(0.36f) - 2 * px(0.018f);
        int square = (inner - 2 * gap) / 3;
        LinearLayout row1 = new LinearLayout(activity);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setClipChildren(false);
        settingsTile = square(Glyph.gear(), Strings.t("Settings"), () -> openSettings(Settings.ACTION_SETTINGS));
        inputsTile = square(Glyph.hdmi(), Strings.t("Inputs"), this::showInputs);
        wallpaperTile = square(Glyph.picture(), Strings.t("Wallpaper"), () -> showWallpaper(0));
        TextView themes = (TextView) ((ViewGroup) wallpaperTile).findViewWithTag("status");
        themes.setText(Strings.t(PictureRules.themesSummary(walls.themes())));
        themes.setVisibility(View.VISIBLE);
        View[] r1 = {settingsTile, inputsTile, wallpaperTile};
        for (int i = 0; i < 3; i++) {
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(square, square);
            if (i > 0) l.leftMargin = gap;
            row1.addView(r1[i], l);
        }
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.topMargin = px(0.014f);
        body.addView(row1, rl);

        LinearLayout row2 = new LinearLayout(activity);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setClipChildren(false);
        int wide = (inner - gap) / 2;
        View wifi = wideTile(wifiGlyph(), Strings.t("Wi-Fi"), wifiStatus(), () -> openSettings(Settings.ACTION_WIFI_SETTINGS));
        View bt = wideTile(Glyph.bluetooth(), Strings.t("Bluetooth"), bluetoothStatus(), this::openAccessories);
        row2.addView(wifi, new LinearLayout.LayoutParams(wide, wide / 2));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(wide, wide / 2);
        bl.leftMargin = gap;
        row2.addView(bt, bl);
        LinearLayout.LayoutParams r2 = new LinearLayout.LayoutParams(-1, -2);
        r2.topMargin = gap;
        body.addView(row2, r2);
        // The arrows move between the five buttons and stay inside the panel.
        links(settingsTile, null, inputsTile, null, wifi);
        links(inputsTile, settingsTile, wallpaperTile, null, wifi);
        links(wallpaperTile, inputsTile, null, null, bt);
        links(wifi, null, bt, settingsTile, null);
        links(bt, wifi, null, wallpaperTile, null);
    }

    // Wallpaper options: Change now (top right, next to the title), the themes in rows of two, and
    // how often the picture changes. focus says which gets focus: 0 Change now, 1.. the themes,
    // then Change picture.
    private void showWallpaper(int focus) {
        showingWallpaper = true;
        body.removeAllViews();
        int gap = px(0.011f), rowH = px(0.046f);
        int inner = px(0.36f) - 2 * px(0.018f), half = (inner - gap) / 2;
        List<View> rows = new ArrayList<>();

        LinearLayout head = new LinearLayout(activity);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setClipChildren(false);
        LinearLayout words = new LinearLayout(activity);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(0.022f, Color.WHITE, MainActivity.semibold());
        title.setText(Strings.t("Wallpaper"));
        words.addView(title);
        TextView sub = text(0.0105f, 0xB3FFFFFF, MainActivity.regular());
        sub.setText(Strings.t("Themes and how often it changes"));
        words.addView(sub);
        head.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));
        View now = wideTile(Glyph.refresh(), Strings.t("Change now"), null, () -> {
            changeNow.run();
            refreshCredit();
        });
        now.setPadding(px(0.01f), 0, px(0.008f), 0);
        head.addView(now, new LinearLayout.LayoutParams(px(0.15f), px(0.042f)));
        body.addView(head);
        rows.add(now);

        final java.util.Set<String> chosen = walls.themes();
        final int themes = PictureRules.THEMES.length;
        for (int r = 0; r * 2 < themes; r++) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setClipChildren(false);
            for (int c = 0; c < 2 && r * 2 + c < themes; c++) {
                final int index = 1 + r * 2 + c;
                final String theme = PictureRules.THEMES[index - 1];
                View t = wideTile(Glyph.check(chosen.contains(theme)), Strings.t(PictureRules.themeLabel(theme)), null, () -> {
                    walls.setThemes(PictureRules.toggleTheme(walls.themes(), theme));
                    showWallpaper(index);
                });
                LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(half, rowH);
                if (c > 0) tl.leftMargin = gap;
                row.addView(t, tl);
                rows.add(t);
            }
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
            rl.topMargin = r == 0 ? px(0.016f) : gap;
            body.addView(row, rl);
        }

        // Get new pictures: the ticked themes' pictures now, instead of waiting for the next automatic fetch.
        final int getAt = 1 + themes, everyAt = 2 + themes;
        final TextView[] result = new TextView[1];
        View get = wideTile(Glyph.download(), Strings.t("Get new pictures"), Strings.t("For the ticked themes"), () -> {
            result[0].setText(Strings.t("Getting pictures…"));
            fetch.start(summary -> {
                if (result[0].isAttachedToWindow()) result[0].setText(Strings.t(summary));
            });
        });
        result[0] = (TextView) get.findViewWithTag("sub");
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, px(0.056f));
        gl.topMargin = px(0.014f);
        body.addView(get, gl);
        rows.add(get);

        View every = wideTile(Glyph.clock(), Strings.t("Change picture"), Strings.t(PictureRules.everyLabel(walls.every())), () -> {
            walls.setEvery(PictureRules.nextEvery(walls.every()));
            showWallpaper(everyAt);
        });
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(-1, px(0.056f));
        el.topMargin = gap;
        body.addView(every, el);
        rows.add(every);

        // Change now sits above the right-hand column; the themes are a grid of two columns;
        // Change picture is below them.
        View getTile = rows.get(getAt), last = rows.get(everyAt);
        links(rows.get(0), null, null, null, rows.get(Math.min(2, themes)));
        for (int i = 0; i < themes; i++) {
            View t = rows.get(1 + i);
            boolean left = i % 2 == 0;
            View l = left ? null : rows.get(i);
            View r = left && i + 1 < themes ? rows.get(2 + i) : null;
            View u = i < 2 ? rows.get(0) : rows.get(i - 1);
            View d = i + 2 < themes ? rows.get(3 + i) : getTile;
            links(t, l, r, u, d);
        }
        links(getTile, null, null, rows.get(themes - (themes % 2 == 0 ? 1 : 0)), last);
        links(last, null, null, getTile, null);
        rows.get(Math.max(0, Math.min(focus, rows.size() - 1))).requestFocus();
    }

    private void showInputs() {
        showingInputs = true;
        body.removeAllViews();
        TextView title = text(0.022f, Color.WHITE, MainActivity.semibold());
        title.setText(Strings.t("Inputs"));
        body.addView(title);
        TextView sub = text(0.0115f, 0xB3FFFFFF, MainActivity.regular());
        sub.setText(Strings.t("Choose what to watch"));
        body.addView(sub);
        TvInputManager tim = (TvInputManager) activity.getSystemService(Context.TV_INPUT_SERVICE);
        List<View> rows = new ArrayList<>();
        for (final TvInputInfo in : inputs.list()) {
            boolean off = false;
            try { off = tim != null && tim.getInputState(in.getId()) == TvInputManager.INPUT_STATE_DISCONNECTED; }
            catch (Exception ignored) {}
            CharSequence tvName = in.loadLabel(activity);
            String label = inputs.label(in);
            String where = tvName != null && !label.contentEquals(tvName) ? tvName + " · " : "";
            View row = wideTile(Glyph.hdmi(), label, where + Strings.t(off ? "Nothing connected" : "Connected"), () -> {
                dismiss();
                inputs.open(in.getId());
            });
            row.setAlpha(off ? 0.55f : 1f);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, px(0.06f));
            l.topMargin = px(0.011f);
            body.addView(row, l);
            rows.add(row);
        }
        for (int i = 0; i < rows.size(); i++) {
            links(rows.get(i), null, null, i > 0 ? rows.get(i - 1) : null, i + 1 < rows.size() ? rows.get(i + 1) : null);
        }
        if (!rows.isEmpty()) rows.get(0).requestFocus();
    }

    private void openSettings(String action) {
        dismiss();
        for (String a : new String[] {action, Settings.ACTION_SETTINGS}) {
            try {
                activity.startActivity(new Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (Exception ignored) {}
        }
    }

    // Android TV's Remotes & Accessories page lists every Bluetooth device. It has no action of its
    // own, so open it through Settings' slice screen; fall back to Bluetooth settings, then Settings.
    private void openAccessories() {
        Intent page = new Intent("android.settings.SLICE_SETTINGS")
                .putExtra("slice_uri", "content://com.android.tv.settings.accessories.sliceprovider/general")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (page.resolveActivity(activity.getPackageManager()) != null) {
            dismiss();
            try {
                activity.startActivity(page);
                return;
            } catch (Exception ignored) {}
        }
        openSettings(Settings.ACTION_BLUETOOTH_SETTINGS);
    }

    // ---- Status lines ----

    private boolean wired() {
        try {
            ConnectivityManager cm = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities c = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return c != null && !c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Exception e) {
            return false;
        }
    }

    // Whether the active network is Wi-Fi. Asked of the connection itself: Android hides most of
    // WifiInfo (name, network id) from an app without location access, but not this.
    private boolean onWifi() {
        try {
            ConnectivityManager cm = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities c = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Exception e) {
            return false;
        }
    }

    private WifiInfo wifiInfo() {
        try {
            WifiManager wm = (WifiManager) activity.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            return wm == null ? null : wm.getConnectionInfo();
        } catch (Exception e) {
            return null;
        }
    }

    private Drawable wifiGlyph() {
        if (wired()) return Glyph.ethernet();
        if (!onWifi()) return Glyph.offline();
        WifiInfo info = wifiInfo();
        return Glyph.wifi(info == null ? 3 : WifiManager.calculateSignalLevel(info.getRssi(), 3) + 1);
    }

    // The network's name when Android shares it (location access granted), else just "Connected".
    private String wifiStatus() {
        if (wired()) return Strings.t("Ethernet");
        if (!onWifi()) return Strings.t("Not connected");
        WifiInfo info = wifiInfo();
        String ssid = info == null ? null : info.getSSID();
        if (ssid == null || ssid.isEmpty() || ssid.contains("unknown ssid")) return Strings.t("Connected");
        return ssid.replaceAll("^\"|\"$", "");
    }

    private String bluetoothStatus() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) return Strings.t("Off");
            BluetoothManager bm = (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
            List<BluetoothDevice> on = bm.getConnectedDevices(BluetoothProfile.GATT);
            if (on.size() == 1 && on.get(0).getName() != null) return on.get(0).getName();
            if (on.size() > 1) return on.size() + " " + Strings.t("Connected").toLowerCase();
            return Strings.t("On");
        } catch (Exception e) {
            return Strings.t("On");
        }
    }


    // ---- Building blocks ----

    private View square(Drawable glyph, String label, Runnable action) {
        LinearLayout t = new LinearLayout(activity);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        ImageView iv = new ImageView(activity);
        iv.setImageDrawable(glyph);
        t.addView(iv, new LinearLayout.LayoutParams(px(0.027f), px(0.027f)));
        TextView name = text(0.012f, Color.WHITE, MainActivity.regular());
        name.setText(label);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-2, -2);
        nl.topMargin = px(0.006f);
        t.addView(name, nl);
        TextView status = text(0.0095f, 0xB3FFFFFF, MainActivity.regular());
        status.setTag("status");
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        // Kept (empty) on every square, so icons and names line up across the row.
        status.setText(" ");
        // Centred under the name, with room either side so a long one ends in "…" inside the tile.
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        status.setPadding(px(0.006f), 0, px(0.006f), 0);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = px(0.002f);
        t.addView(status, sl);
        button(t, glyph, new TextView[] {name, status}, action);
        return t;
    }

    private View wideTile(Drawable glyph, String title, String status, Runnable action) {
        LinearLayout t = new LinearLayout(activity);
        t.setOrientation(LinearLayout.HORIZONTAL);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(px(0.014f), 0, px(0.012f), 0);
        ImageView iv = new ImageView(activity);
        iv.setImageDrawable(glyph);
        t.addView(iv, new LinearLayout.LayoutParams(px(0.027f), px(0.027f)));
        LinearLayout words = new LinearLayout(activity);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView head = text(0.0135f, Color.WHITE, MainActivity.semibold());
        head.setText(title);
        head.setSingleLine(true);
        head.setEllipsize(android.text.TextUtils.TruncateAt.END);
        words.addView(head);
        TextView sub = text(0.011f, 0xB3FFFFFF, MainActivity.regular());
        sub.setText(status);
        sub.setTag("sub");
        if (status == null) sub.setVisibility(View.GONE);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        words.addView(sub);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(0, -2, 1f);
        wl.leftMargin = px(0.011f);
        t.addView(words, wl);
        button(t, glyph, new TextView[] {head, sub}, action);
        return t;
    }

    // Glass that turns white with dark content when focused.
    private void button(final View t, final Drawable glyph, final TextView[] texts, final Runnable action) {
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(0.016f));
        bg.setColor(0x1FFFFFFF);
        t.setBackground(bg);
        t.setFocusable(true);
        final int[] colors = new int[texts.length];
        for (int i = 0; i < texts.length; i++) colors[i] = texts[i].getCurrentTextColor();
        t.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xFFFFFFFF : 0x1FFFFFFF);
            glyph.setTint(has ? 0xFF111111 : 0xFFFFFFFF);
            for (int i = 0; i < texts.length; i++) texts[i].setTextColor(has ? (i == 0 ? 0xFF111111 : 0xB3111111) : colors[i]);
            v.setScaleX(has ? 1.05f : 1f);
            v.setScaleY(has ? 1.05f : 1f);
        });
        t.setOnClickListener(v -> action.run());
    }

    // Where each arrow goes from v (null: nowhere). Every arrow is kept inside the panel.
    private static void links(final View v, final View left, final View right, final View up, final View down) {
        v.setOnKeyListener((view, code, ev) -> {
            View to;
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT: to = left; break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: to = right; break;
                case KeyEvent.KEYCODE_DPAD_UP: to = up; break;
                case KeyEvent.KEYCODE_DPAD_DOWN: to = down; break;
                default: return false;
            }
            if (ev.getAction() == KeyEvent.ACTION_DOWN && to != null) to.requestFocus();
            return true;
        });
    }

    private TextClock clock(String f12, String f24, float share, int color, Typeface tf) {
        TextClock c = new TextClock(activity);
        c.setFormat12Hour(f12);
        c.setFormat24Hour(f24);
        c.setTextSize(TypedValue.COMPLEX_UNIT_PX, share * screenW);
        c.setTextColor(color);
        c.setTypeface(tf);
        return c;
    }

    private TextView text(float share, int color, Typeface tf) {
        TextView t = new TextView(activity);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, share * screenW);
        t.setTextColor(color);
        t.setTypeface(tf);
        return t;
    }

    private int px(float share) {
        return Math.round(share * screenW);
    }
}
