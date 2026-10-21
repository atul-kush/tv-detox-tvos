package com.ansub.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

// Fresh pictures from the web, at most once a day and only while Home is in front: one new
// landscape, city and space photo from Wikimedia Commons and one painting from The Met, kept in
// cache/walls with a .tsv line each. Anything that goes wrong just means no new picture today.
final class Fetcher {
    private static final String TAG = "tvhome";
    private static final int KEEP = 60, PER_THEME = 5, TIMEOUT = 10_000;
    private static final String[][] WIKIMEDIA = {{"landscapes", "Featured_pictures_of_landscapes"},
            {"cities", "Featured_pictures_of_cityscapes"}, {"space", "Featured_pictures_of_astronomy"}};
    private static final String[] ART_QUERIES = {"landscape", "seascape", "mountains", "garden", "city view"};

    private final Context context;
    private final Wallpapers walls;
    private final SharedPreferences prefs;
    private final Random random = new Random();
    private volatile boolean cancelled;
    private volatile HttpURLConnection open;
    private volatile int unsplashLeft = -1; // what Unsplash last said was left this hour; -1 not known
    private Thread thread;

    Fetcher(Context c, Wallpapers walls) {
        context = c.getApplicationContext();
        this.walls = walls;
        prefs = context.getSharedPreferences("walls", 0);
    }

    // Forgets when the last fetch was, so the next startIfDue fetches (adb: --ez fetch true).
    void forget() {
        prefs.edit().remove("lastFetch").apply();
    }

    void startIfDue() {
        if (thread != null && thread.isAlive()) return;
        int downloaded = 0;
        for (Picture p : walls.pool()) if (p.fresh) downloaded++;
        if (!PictureRules.shouldFetch(prefs.getLong("lastFetch", 0), System.currentTimeMillis(), downloaded,
                walls.themes().size()) || !online()) return;
        cancelled = false;
        thread = new Thread(this::run, "tvhome-fetch");
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    // Home went to the background: stop now, the next visit tries again.
    void cancel() {
        if (thread == null || !thread.isAlive()) return;
        cancelled = true;
        HttpURLConnection c = open;
        if (c != null) c.disconnect();
        thread.interrupt();
    }

    private boolean online() {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm != null && cm.getActiveNetwork() != null;
        } catch (Exception e) {
            return false;
        }
    }

    private void run() {
        Log.i(TAG, "fetching new pictures");
        File dir = Wallpapers.cacheDir(context);
        dir.mkdirs();
        Set<String> known = new HashSet<>();
        for (Picture p : walls.pool()) known.add(p.id);
        Set<String> themes = walls.themes();
        if (!Keys.UNSPLASH.isEmpty()) fromUnsplash(themes, known, dir);
        else fromWikimediaAndMet(themes, known, dir);
        if (cancelled) {
            Log.i(TAG, "fetch cancelled");
            return;
        }
        prefs.edit().putLong("lastFetch", System.currentTimeMillis()).apply();
        List<Picture> cached = new ArrayList<>();
        for (Picture p : walls.pool()) if (p.fresh) cached.add(p);
        for (Picture p : PictureRules.prune(cached, KEEP)) {
            new File(p.file).delete();
            new File(dir, Wallpapers.safe(p.id) + ".tsv").delete();
        }
    }

    // Five new photos per chosen theme in a single request each, cropped by Unsplash to the screen's size.
    // Every request is counted so the TV never comes near the free key's 50 an hour (see
    // PictureRules.mayAskUnsplash). Unsplash is told a photo was used when it's shown (pingShown).
    private void fromUnsplash(Set<String> themes, Set<String> known, File dir) {
        int w = context.getResources().getDisplayMetrics().widthPixels;
        int h = context.getResources().getDisplayMetrics().heightPixels;
        long day = System.currentTimeMillis() / PictureRules.FETCH_EVERY;
        for (String theme : PictureRules.THEMES) {
            if (cancelled || !themes.contains(theme)) continue;
            try {
                if (!mayAskUnsplash()) {
                    Log.i(TAG, "unsplash: leaving the rest of this hour's requests alone");
                    return;
                }
                String query = PictureRules.unsplashQuery(theme, day);
                // Illustrations are searched, a different page of the matches each day so they don't repeat.
                String url = PictureRules.unsplashIllustrations(theme)
                        ? Sources.unsplashIllustrationsUrl(query, 1 + random.nextInt(40), PER_THEME) : Sources.unsplashUrl(query, PER_THEME);
                List<Sources.Found> found = Sources.parseUnsplash(unsplash(url), theme, w, h);
                for (Sources.Found f : found) {
                    if (cancelled || known.contains(f.picture.id) || !save(f.picture, dir)) continue;
                    known.add(f.picture.id);
                }
            } catch (Exception e) {
                if (!cancelled) Log.w(TAG, "no new " + theme + " from unsplash: " + e);
            }
        }
    }

    // Unsplash asks to be told when one of its photos is used: sent once, the first time a downloaded
    // photo is shown, in the background, and only while there's room under the hourly limit.
    void pingShown(final Picture p) {
        if (p == null || !p.fresh || !p.id.startsWith("us:") || Keys.UNSPLASH.isEmpty()) return;
        final String id = p.id.substring(3);
        final Set<String> pinged = new HashSet<>(java.util.Arrays.asList(prefs.getString("pinged", "").split(",")));
        if (pinged.contains(id) || !mayAskUnsplash()) return;
        pinged.add(id);
        List<String> keep = new ArrayList<>(pinged);
        prefs.edit().putString("pinged", android.text.TextUtils.join(",", keep.subList(Math.max(0, keep.size() - 300), keep.size()))).apply();
        Thread t = new Thread(() -> {
            try { unsplash("https://api.unsplash.com/photos/" + id + "/download"); }
            catch (Exception e) { Log.w(TAG, "couldn't tell Unsplash about " + p.id + ": " + e); }
        }, "tvhome-unsplash-ping");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private boolean mayAskUnsplash() {
        return PictureRules.mayAskUnsplash(unsplashTimes(), System.currentTimeMillis(), unsplashLeft);
    }

    private List<Long> unsplashTimes() {
        List<Long> out = new ArrayList<>();
        for (String t : prefs.getString("unsplashTimes", "").split(",")) {
            try { out.add(Long.parseLong(t)); } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    // One Unsplash API request with the key, noted down first so it counts even if it fails.
    private String unsplash(String url) throws Exception {
        long now = System.currentTimeMillis();
        List<Long> times = PictureRules.lastHour(unsplashTimes(), now);
        times.add(now);
        prefs.edit().putString("unsplashTimes", android.text.TextUtils.join(",", times)).commit();
        HttpURLConnection c = connect(url, "Client-ID " + Keys.UNSPLASH);
        try (InputStream in = c.getInputStream()) {
            String left = c.getHeaderField("X-Ratelimit-Remaining");
            if (left != null) try { unsplashLeft = Integer.parseInt(left.trim()); } catch (NumberFormatException ignored) {}
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            c.disconnect();
            open = null;
        }
    }

    // No Unsplash key: one landscape, city and space photo from Wikimedia Commons, one painting from The Met.
    private void fromWikimediaAndMet(Set<String> themes, Set<String> known, File dir) {
        for (String[] w : WIKIMEDIA) {
            if (cancelled) break;
            if (!themes.contains(w[0])) continue;
            try {
                List<Picture> found = Sources.parseWikimedia(get(Sources.wikimediaUrl(w[1])), w[0]);
                Log.i(TAG, w[0] + ": " + found.size() + " to choose from");
                Collections.shuffle(found, random);
                for (Picture p : found) if (!known.contains(p.id)) { if (save(p, dir)) known.add(p.id); break; }
            } catch (Exception e) {
                if (!cancelled) Log.w(TAG, "no new " + w[0] + ": " + e);
            }
        }
        if (!cancelled && themes.contains("art")) {
            try {
                String q = ART_QUERIES[(int) ((System.currentTimeMillis() / PictureRules.FETCH_EVERY) % ART_QUERIES.length)];
                List<Integer> ids = Sources.parseMetSearch(get(Sources.metSearchUrl(q)));
                Log.i(TAG, "art (" + q + "): " + ids.size() + " to choose from");
                Collections.shuffle(ids, random);
                for (int i = 0; i < Math.min(8, ids.size()) && !cancelled; i++) {
                    Picture p = Sources.parseMetObject(get(Sources.metObjectUrl(ids.get(i))));
                    if (p != null && !known.contains(p.id) && save(p, dir)) break;
                }
            } catch (Exception e) {
                if (!cancelled) Log.w(TAG, "no new art: " + e);
            }
        }
    }

    // Downloads the picture next to its .tsv line. Only a file that decodes as an image is kept.
    private boolean save(Picture p, File dir) {
        String name = Wallpapers.safe(p.id);
        File part = new File(dir, name + ".jpg.part"), jpg = new File(dir, name + ".jpg");
        try {
            HttpURLConnection c = connect(p.file);
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[16 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) {
                    if (cancelled) throw new InterruptedException();
                    out.write(buf, 0, n);
                }
            } finally {
                c.disconnect();
                open = null;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(part.getPath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0 || !part.renameTo(jpg)) throw new IllegalStateException("not an image");
            Picture kept = new Picture(p.id, jpg.getPath(), p.title, p.author, p.licence, p.source, p.category, p.art, true,
                    System.currentTimeMillis());
            try (OutputStream out = new FileOutputStream(new File(dir, name + ".tsv"))) {
                out.write((kept.toTsv() + "\n").getBytes("UTF-8"));
            }
            Log.i(TAG, "fetched " + p.id);
            return true;
        } catch (Exception e) {
            part.delete();
            jpg.delete();
            if (!cancelled) Log.w(TAG, "couldn't fetch " + p.id + ": " + e);
            return false;
        }
    }

    private String get(String url) throws Exception {
        HttpURLConnection c = connect(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            c.disconnect();
            open = null;
        }
    }

    private HttpURLConnection connect(String url) throws Exception {
        return connect(url, null);
    }

    private HttpURLConnection connect(String url, String auth) throws Exception {
        if (cancelled) throw new InterruptedException();
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        open = c;
        c.setConnectTimeout(TIMEOUT);
        c.setReadTimeout(TIMEOUT);
        c.setRequestProperty("User-Agent", Sources.UA);
        if (auth != null) {
            c.setRequestProperty("Authorization", auth);
            c.setRequestProperty("Accept-Version", "v1");
        }
        c.setInstanceFollowRedirects(true);
        if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
        return c;
    }
}
