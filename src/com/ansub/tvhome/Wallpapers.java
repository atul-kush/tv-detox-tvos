package com.ansub.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

// The background picture: the built-in collection (assets/walls) plus downloaded ones (cache/walls),
// one in memory at a time, drawn at the screen's size. Which one and when come from PictureRules.
final class Wallpapers {
    private static final String TAG = "tvhome";
    private static final int RECENT = 10;

    private final Context context;
    private final SharedPreferences prefs;
    private final int width, height;
    private final Random random = new Random();
    private Picture current;
    private Bitmap shown, previous;

    Wallpapers(Context c) {
        context = c.getApplicationContext();
        prefs = context.getSharedPreferences("walls", 0);
        width = context.getResources().getDisplayMetrics().widthPixels;
        height = context.getResources().getDisplayMetrics().heightPixels;
    }

    static File cacheDir(Context c) {
        return new File(c.getCacheDir(), "walls");
    }

    Picture current() {
        return current;
    }

    // The themes picked in Control Center (all of them until someone changes it).
    // Themes added in an update (like Abstract and Wildlife) are switched on once for people who had
    // already picked their themes; one they then switch off stays off.
    Set<String> themes() {
        String saved = prefs.getString("themes", null);
        if (saved == null) return new java.util.LinkedHashSet<>(Arrays.asList(PictureRules.THEMES));
        Set<String> seen = new HashSet<>(csv(prefs.getString("themesSeen", "landscapes,cities,space,art")));
        Set<String> themes = PictureRules.withNewThemes(new java.util.LinkedHashSet<>(csv(saved)), seen);
        if (seen.size() < PictureRules.THEMES.length) setThemes(themes);
        return themes;
    }

    void setThemes(Set<String> themes) {
        prefs.edit().putString("themes", TextUtils.join(",", themes))
                .putString("themesSeen", TextUtils.join(",", PictureRules.THEMES)).apply();
    }

    // How often the picture changes, one of PictureRules.EVERY.
    long every() {
        return prefs.getLong("every", PictureRules.DEFAULT_EVERY);
    }

    void setEvery(long every) {
        prefs.edit().putLong("every", every).apply();
    }

    // The picture to show now, or null when the one on screen should stay: a new one when the chosen
    // "Change picture" setting says so, the last one again when nothing is showing yet.
    Bitmap showNext(boolean screenWasOff) {
        return pick(PictureRules.shouldChange(prefs.getLong("lastChange", 0), System.currentTimeMillis(), screenWasOff, every()));
    }

    // "Change now": the next picture in the chosen themes, whatever the setting.
    Bitmap changeNow() {
        return pick(true);
    }

    private Bitmap pick(boolean due) {
        long now = System.currentTimeMillis();
        if (!due && shown != null) return null;
        List<Picture> all = pool();
        if (!due) {
            String id = prefs.getString("currentId", null);
            for (Picture p : all) if (p.id.equals(id)) return show(p);
        }
        List<Picture> pool = PictureRules.inThemes(all, themes());
        Set<String> bad = new HashSet<>(csv(prefs.getString("bad", "")));
        for (int tries = 0; tries < 5; tries++) {
            Picture p = PictureRules.pickNext(pool, csv(prefs.getString("recent", "")), bad,
                    prefs.getString("lastCategory", null), random);
            if (p == null) return null;
            Bitmap b = show(p);
            if (b != null) {
                List<String> recent = new ArrayList<>(csv(prefs.getString("recent", "")));
                recent.remove(p.id);
                recent.add(0, p.id);
                while (recent.size() > RECENT) recent.remove(recent.size() - 1);
                prefs.edit().putLong("lastChange", now).putString("recent", TextUtils.join(",", recent))
                        .putString("lastCategory", p.category).apply();
                return b;
            }
            bad.add(p.id);
        }
        return null;
    }

    // After the new picture is on screen, the one it replaced can go.
    void freePrevious() {
        if (previous != null && previous != shown) previous.recycle();
        previous = null;
    }

    void markBad(String id) {
        List<String> bad = new ArrayList<>(csv(prefs.getString("bad", "")));
        if (!bad.contains(id)) bad.add(id);
        prefs.edit().putString("bad", TextUtils.join(",", bad)).apply();
    }

    List<Picture> pool() {
        List<Picture> out = new ArrayList<>();
        try (InputStream in = context.getAssets().open("walls/index.tsv")) {
            readTsv(in, out);
        } catch (Exception e) {
            Log.w(TAG, "no built-in pictures: " + e);
        }
        File[] cached = cacheDir(context).listFiles((d, n) -> n.endsWith(".tsv"));
        if (cached != null) for (File f : cached) {
            try (InputStream in = new FileInputStream(f)) {
                readTsv(in, out);
            } catch (Exception ignored) {}
        }
        return out;
    }

    private static void readTsv(InputStream in, List<Picture> out) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        for (String line; (line = r.readLine()) != null; ) {
            Picture p = Picture.fromTsv(line);
            if (p != null) out.add(p);
        }
    }

    // Decodes p at the screen's size. A picture that won't decode is marked bad (and a downloaded
    // one deleted), so it's never tried again.
    private Bitmap show(Picture p) {
        Bitmap src = null;
        try {
            src = decode(p);
            if (src == null) throw new IllegalStateException("can't decode");
            // A photo has no see-through parts: 16-bit colour with dithering looks the same at TV distance
            // and halves the biggest thing the home screen keeps in memory.
            Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            Canvas c = new Canvas(out);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
            // Every picture, paintings included, is zoomed to fill the screen (centre kept, edges trimmed).
            c.drawBitmap(src, cover(src.getWidth(), src.getHeight()), new Rect(0, 0, width, height), paint);
            src.recycle();
            src = null;
            Glass.setBlurred(Glass.blur(out));
            // On screen the picture only needs to live on the graphics chip: a hardware bitmap keeps no
            // second copy in the app's own memory.
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                Bitmap hw = out.copy(Bitmap.Config.HARDWARE, false);
                if (hw != null) {
                    out.recycle();
                    out = hw;
                }
            }
            previous = shown;
            shown = out;
            current = p;
            prefs.edit().putString("currentId", p.id).apply();
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "skipping picture " + p.id + ": " + t);
            if (src != null) src.recycle();
            markBad(p.id);
            if (p.fresh) {
                new File(p.file).delete();
                new File(cacheDir(context), safe(p.id) + ".tsv").delete();
            }
            return null;
        }
    }

    // The centre part of a w x h picture with the screen's shape.
    private Rect cover(int w, int h) {
        float s = Math.max((float) width / w, (float) height / h);
        int cw = Math.round(width / s), ch = Math.round(height / s);
        return new Rect((w - cw) / 2, (h - ch) / 2, (w + cw) / 2, (h + ch) / 2);
    }

    private Bitmap decode(Picture p) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        read(p, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= width && o.outHeight / (sample * 2) >= height) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        // Decoded straight to 16-bit: the source is drawn once into the screen-sized picture and freed,
        // and at half the size it doesn't leave a big hole in the native heap behind it.
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return read(p, o);
    }

    private Bitmap read(Picture p, BitmapFactory.Options o) throws Exception {
        if (p.fresh) return BitmapFactory.decodeFile(p.file, o);
        try (InputStream in = context.getAssets().open("walls/" + p.file)) {
            return BitmapFactory.decodeStream(in, null, o);
        }
    }

    // Picture ids ("wm:123") as file names.
    static String safe(String id) {
        return id.replace(':', '_').replace('/', '_');
    }

    private static List<String> csv(String s) {
        List<String> out = new ArrayList<>();
        for (String p : Arrays.asList(s.split(","))) if (!p.isEmpty()) out.add(p);
        return out;
    }
}
