package com.ansub.tvhome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

// The decisions behind the background picture: which shapes fit, when to change and fetch,
// which one comes next, and the credit line. Plain Java, so it's tested on a laptop.
final class PictureRules {
    static final long FETCH_EVERY = 86_400_000L;

    // "Change picture" choices, in the order the button cycles through them. ON_TV_ON changes only when
    // the TV comes on; the others are timers; NEVER keeps the picture until "Change now".
    static final long ON_TV_ON = 0, NEVER = -1;
    static final long[] EVERY = {ON_TV_ON, 1_800_000L, 3_600_000L, 10_800_000L, 86_400_000L, NEVER};
    private static final String[] EVERY_LABELS = {"Every time the TV turns on", "Every 30 minutes", "Every hour",
            "Every 3 hours", "Once a day", "Never (keep this one)"};
    static final long DEFAULT_EVERY = 3_600_000L;

    // Picture themes, as Picture.category values, and how they're named on screen.
    static final String[] THEMES = {"landscapes", "cities", "space", "art", "abstract", "wildlife"};
    private static final String[] THEME_LABELS = {"Landscapes", "Cities", "Space", "Art", "Abstract", "Wildlife"};

    private PictureRules() {}

    // Photos must be roughly the screen's shape: no panoramas, no portraits.
    static boolean fitsScreen(int w, int h) {
        if (w <= 0 || h <= 0) return false;
        float r = (float) w / h;
        return r >= 1.45f && r <= 1.9f;
    }

    static boolean shouldChange(long lastChange, long now, boolean screenWasOff, long every) {
        if (lastChange == 0) return true;
        if (every == NEVER) return false;
        if (every == ON_TV_ON) return screenWasOff;
        return now - lastChange >= every;
    }

    static long nextEvery(long current) {
        for (int i = 0; i < EVERY.length; i++) if (EVERY[i] == current) return EVERY[(i + 1) % EVERY.length];
        return EVERY[0];
    }

    static String everyLabel(long every) {
        for (int i = 0; i < EVERY.length; i++) if (EVERY[i] == every) return EVERY_LABELS[i];
        return EVERY_LABELS[2];
    }

    // The pictures in the chosen themes; no themes chosen means all of them.
    static List<Picture> inThemes(List<Picture> pool, Set<String> themes) {
        if (themes.isEmpty()) return new ArrayList<>(pool);
        List<Picture> out = new ArrayList<>();
        for (Picture p : pool) if (themes.contains(p.category)) out.add(p);
        return out;
    }

    // Turns a theme on or off. The last one left stays on, so there's always something to show.
    static Set<String> toggleTheme(Set<String> themes, String theme) {
        Set<String> out = new LinkedHashSet<>(themes);
        if (!out.remove(theme)) out.add(theme);
        return out.isEmpty() ? new LinkedHashSet<>(themes) : out;
    }

    static String themeLabel(String theme) {
        for (int i = 0; i < THEMES.length; i++) if (THEMES[i].equals(theme)) return THEME_LABELS[i];
        return theme;
    }

    static String themesSummary(Set<String> themes) {
        if (themes.isEmpty() || themes.size() >= THEMES.length) return "All themes";
        if (themes.size() == 1) return themeLabel(themes.iterator().next());
        return themes.size() + " themes";
    }

    // What to ask Unsplash for, per theme. Art takes paintings two days in three and modern abstract
    // art on the third, so both turn up but paintings lead.
    static String unsplashQuery(String theme, long day) {
        switch (theme) {
            case "landscapes": return "landscape nature";
            case "cities": return "city skyline";
            case "space": return "galaxy nebula stars";
            case "abstract": return "abstract background";
            case "wildlife": return "wildlife animals";
            default: return day % 3 == 2 ? "abstract art" : "painting";
        }
    }

    // Abstract backgrounds come from Unsplash's illustrations (swirls, flowing lines, colour waves),
    // which are searched separately from photos; every other theme is photos.
    static boolean unsplashIllustrations(String theme) {
        return theme.equals("abstract");
    }

    // Themes added in an update start ticked, once. A theme the person has seen and switched off stays off.
    static Set<String> withNewThemes(Set<String> chosen, Set<String> seen) {
        Set<String> out = new LinkedHashSet<>(chosen);
        for (String t : THEMES) if (!seen.contains(t)) out.add(t);
        return out;
    }

    // Unsplash's free key allows 50 requests an hour. Stay far below it: at most 20 of our own in any
    // hour, and none once Unsplash says 10 or fewer are left (remaining < 0: not known yet).
    static final int UNSPLASH_PER_HOUR = 20, UNSPLASH_KEEP_FREE = 10;

    static boolean mayAskUnsplash(List<Long> requestTimes, long now, int remaining) {
        if (remaining >= 0 && remaining <= UNSPLASH_KEEP_FREE) return false;
        return lastHour(requestTimes, now).size() < UNSPLASH_PER_HOUR;
    }

    static List<Long> lastHour(List<Long> times, long now) {
        List<Long> out = new ArrayList<>();
        for (long t : times) if (now - t < 3_600_000L) out.add(t);
        return out;
    }

    static boolean shouldFetch(long lastFetch, long now) {
        return lastFetch == 0 || now - lastFetch >= FETCH_EVERY;
    }

    // The newest downloaded picture not shown lately, otherwise a random one from another category
    // than the last. When that leaves nothing, the category and then the "lately" rule are dropped.
    static Picture pickNext(List<Picture> pool, List<String> recentIds, Set<String> badIds, String lastCategory, Random r) {
        List<Picture> ok = new ArrayList<>();
        for (Picture p : pool) if (!badIds.contains(p.id)) ok.add(p);
        if (ok.isEmpty()) return null;
        Picture newest = null;
        for (Picture p : ok) {
            if (p.fresh && !recentIds.contains(p.id) && (newest == null || p.added > newest.added)) newest = p;
        }
        if (newest != null) return newest;
        List<Picture> choice = new ArrayList<>();
        for (Picture p : ok) if (!recentIds.contains(p.id) && !p.category.equals(lastCategory)) choice.add(p);
        if (choice.isEmpty()) for (Picture p : ok) if (!recentIds.contains(p.id)) choice.add(p);
        if (choice.isEmpty()) choice = ok;
        return choice.get(r.nextInt(choice.size()));
    }

    static String credit(Picture p) {
        String by = p.author == null || p.author.isEmpty() ? "" : p.author + " · ";
        return "About this picture: " + p.title + "\n" + (p.art ? "" : "Photo: ") + by + p.licence + " · " + p.source;
    }

    // The downloaded pictures to delete so only the newest `keep` remain, oldest first.
    static List<Picture> prune(List<Picture> cached, int keep) {
        List<Picture> sorted = new ArrayList<>(cached);
        Collections.sort(sorted, (a, b) -> Long.compare(a.added, b.added));
        return sorted.size() <= keep ? new ArrayList<Picture>() : new ArrayList<>(sorted.subList(0, sorted.size() - keep));
    }
}
