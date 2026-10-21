package com.ansub.tvhome;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class PictureRulesTest {
    static Picture pic(String id, String category, boolean fresh, long added) {
        return new Picture(id, id + ".jpg", "T " + id, "A", "CC BY-SA 4.0", "Wikimedia Commons", category, false, fresh, added);
    }

    static final List<String> NONE = Collections.emptyList();
    static final Set<String> NO_BAD = Collections.emptySet();

    public static void main(String[] args) {
        // fitsScreen
        Check.yes(PictureRules.fitsScreen(1920, 1080), "1920x1080");
        Check.yes(PictureRules.fitsScreen(1280, 720), "1280x720");
        Check.yes(PictureRules.fitsScreen(5184, 3456), "3:2");
        Check.yes(!PictureRules.fitsScreen(8916, 1273), "banner");
        Check.yes(!PictureRules.fitsScreen(3000, 4000), "portrait");
        Check.yes(PictureRules.fitsScreen(1450, 1000), "1.45 edge");
        Check.yes(PictureRules.fitsScreen(1900, 1000), "1.9 edge");
        Check.yes(!PictureRules.fitsScreen(1910, 1000), "over 1.9");
        Check.yes(!PictureRules.fitsScreen(0, 0), "zero size");

        // shouldChange: hourly
        long t = 1_000_000_000L, hour = 3_600_000L;
        Check.yes(PictureRules.shouldChange(0, t, false, hour), "never changed");
        Check.yes(!PictureRules.shouldChange(t, t + 3_599_999, false, hour), "under an hour");
        Check.yes(PictureRules.shouldChange(t, t + 3_600_000, false, hour), "an hour");
        Check.yes(!PictureRules.shouldChange(t, t + 1, true, hour), "hourly ignores the TV coming on");
        // shouldChange: every time the TV turns on
        Check.yes(PictureRules.shouldChange(t, t + 1, true, PictureRules.ON_TV_ON), "TV came on");
        Check.yes(!PictureRules.shouldChange(t, t + 86_400_000L, false, PictureRules.ON_TV_ON), "no timer when on-only");
        // shouldChange: never
        Check.yes(!PictureRules.shouldChange(t, t + 86_400_000L, true, PictureRules.NEVER), "never keeps it");
        Check.yes(PictureRules.shouldChange(0, t, false, PictureRules.NEVER), "never still shows a first picture");
        // shouldChange: every 30 minutes, once a day
        Check.yes(PictureRules.shouldChange(t, t + 1_800_000L, false, 1_800_000L), "half hour");
        Check.yes(!PictureRules.shouldChange(t, t + 86_399_999L, false, 86_400_000L), "under a day");

        // the "Change picture" choices cycle in order and wrap
        Check.eq(6, PictureRules.EVERY.length, "six choices");
        Check.eq(PictureRules.ON_TV_ON, PictureRules.EVERY[0], "first choice");
        Check.eq(PictureRules.NEVER, PictureRules.EVERY[5], "last choice");
        Check.eq(1_800_000L, PictureRules.nextEvery(PictureRules.ON_TV_ON), "next after TV on");
        Check.eq(PictureRules.ON_TV_ON, PictureRules.nextEvery(PictureRules.NEVER), "wraps around");
        Check.eq(PictureRules.EVERY[0], PictureRules.nextEvery(12345L), "unknown value restarts");
        Check.eq("Every hour", PictureRules.everyLabel(hour), "hour label");
        Check.eq("Never (keep this one)", PictureRules.everyLabel(PictureRules.NEVER), "never label");

        // themes
        Set<String> all = new HashSet<>(Arrays.asList(PictureRules.THEMES));
        List<Picture> mixed = Arrays.asList(pic("l", "landscapes", false, 0), pic("c", "cities", false, 0),
                pic("s", "space", false, 0), pic("a", "art", false, 0));
        Check.eq(4, PictureRules.inThemes(mixed, all).size(), "all themes");
        List<Picture> spaceOnly = PictureRules.inThemes(mixed, new HashSet<>(Arrays.asList("space")));
        Check.eq(1, spaceOnly.size(), "one theme");
        Check.eq("s", spaceOnly.get(0).id, "space picture");
        Check.eq(4, PictureRules.inThemes(mixed, new HashSet<String>()).size(), "no themes means all");
        Set<String> two = PictureRules.toggleTheme(new HashSet<>(Arrays.asList("space", "art")), "art");
        Check.eq(new HashSet<>(Arrays.asList("space")), two, "toggle off");
        Check.eq(new HashSet<>(Arrays.asList("space")), PictureRules.toggleTheme(two, "space"), "last theme stays on");
        Check.eq(new HashSet<>(Arrays.asList("space", "cities")), PictureRules.toggleTheme(two, "cities"), "toggle on");
        Check.eq("All themes", PictureRules.themesSummary(all), "summary all");
        Check.eq("Space", PictureRules.themesSummary(new HashSet<>(Arrays.asList("space"))), "summary one");
        Check.eq("2 themes", PictureRules.themesSummary(new HashSet<>(Arrays.asList("space", "art"))), "summary some");
        Check.eq("Landscapes", PictureRules.themeLabel("landscapes"), "theme label");

        // Unsplash searches per theme; art leans to paintings, two days in three
        Check.eq("landscape nature", PictureRules.unsplashQuery("landscapes", 0), "landscape query");
        Check.eq("city skyline", PictureRules.unsplashQuery("cities", 0), "city query");
        Check.eq("galaxy nebula stars", PictureRules.unsplashQuery("space", 0), "space query");
        Check.eq("painting", PictureRules.unsplashQuery("art", 0), "art day 0");
        Check.eq("painting", PictureRules.unsplashQuery("art", 1), "art day 1");
        Check.eq("abstract art", PictureRules.unsplashQuery("art", 2), "art day 2");
        Check.eq("painting", PictureRules.unsplashQuery("art", 3), "art day 3");

        // Unsplash budget: at most 20 requests in any hour, and stop when Unsplash says 10 or fewer are left
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < 19; i++) times.add(t - 60_000L * i);
        Check.yes(PictureRules.mayAskUnsplash(times, t, -1), "19 in the last hour");
        times.add(t - 1000);
        Check.yes(!PictureRules.mayAskUnsplash(times, t, -1), "20 in the last hour");
        Check.yes(PictureRules.mayAskUnsplash(times, t + 3_600_001L + 60_000L * 19, -1), "an hour later");
        Check.yes(!PictureRules.mayAskUnsplash(new ArrayList<Long>(), t, 10), "10 left");
        Check.yes(PictureRules.mayAskUnsplash(new ArrayList<Long>(), t, 11), "11 left");
        Check.eq(Arrays.asList(t), PictureRules.lastHour(Arrays.asList(t - 3_600_000L, t), t), "only the last hour kept");

        // shouldFetch
        Check.yes(PictureRules.shouldFetch(0, t), "never fetched");
        Check.yes(!PictureRules.shouldFetch(t, t + 86_399_999), "under a day");
        Check.yes(PictureRules.shouldFetch(t, t + 86_400_000), "a day");

        // pickNextPrefersNewestFreshUnseen
        List<Picture> pool = Arrays.asList(pic("b1", "landscapes", false, 0), pic("f1", "cities", true, 10), pic("f2", "space", true, 20));
        Check.eq("f2", PictureRules.pickNext(pool, NONE, NO_BAD, null, new Random(1)).id, "newest fresh");
        Check.eq("f1", PictureRules.pickNext(pool, Arrays.asList("f2"), NO_BAD, null, new Random(1)).id, "next fresh");

        // pickNextAvoidsLastCategory
        pool = Arrays.asList(pic("l1", "landscapes", false, 0), pic("c1", "cities", false, 0));
        Random r = new Random(7);
        for (int i = 0; i < 20; i++) Check.eq("c1", PictureRules.pickNext(pool, NONE, NO_BAD, "landscapes", r).id, "other category");

        // pickNextSkipsBadIds
        pool = Arrays.asList(pic("f2", "space", true, 20), pic("b1", "landscapes", false, 0));
        Check.eq("b1", PictureRules.pickNext(pool, NONE, new HashSet<>(Arrays.asList("f2")), null, new Random(1)).id, "bad skipped");

        // pickNextRelaxesWhenAllRecent
        pool = Arrays.asList(pic("b1", "landscapes", false, 0));
        Check.eq("b1", PictureRules.pickNext(pool, Arrays.asList("b1"), NO_BAD, "landscapes", new Random(1)).id, "relaxed");

        // pickNextEmptyPool
        Check.eq(null, PictureRules.pickNext(new ArrayList<Picture>(), NONE, NO_BAD, null, new Random(1)), "empty pool");
        Check.eq(null, PictureRules.pickNext(Arrays.asList(pic("x", "art", false, 0)), NONE, new HashSet<>(Arrays.asList("x")), null, new Random(1)), "all bad");

        // creditPhoto
        Picture photo = new Picture("wm:1", "f", "S-charl towards Alp Sesvenna", "Dominicus Johannes Bergsma", "CC BY-SA 4.0",
                "Wikimedia Commons", "landscapes", false, true, 5);
        Check.eq("About this picture: S-charl towards Alp Sesvenna\nPhoto: Dominicus Johannes Bergsma · CC BY-SA 4.0 · Wikimedia Commons",
                PictureRules.credit(photo), "photo credit");

        // creditArt
        Picture art = new Picture("met:10181", "f", "Landscape", "Ralph Albert Blakelock", "Public domain", "The Met", "art", true, true, 5);
        Check.eq("About this picture: Landscape\nRalph Albert Blakelock · Public domain · The Met", PictureRules.credit(art), "art credit");

        // creditNoAuthor
        Picture anon = new Picture("n:1", "f", "Galaxy", "", "Public domain", "NASA", "space", false, false, 0);
        Check.eq("About this picture: Galaxy\nPhoto: Public domain · NASA", PictureRules.credit(anon), "no author");

        // pruneKeepsNewest
        List<Picture> cached = new ArrayList<>();
        for (int i = 14; i >= 1; i--) cached.add(pic("c" + i, "space", true, i));
        List<Picture> gone = PictureRules.prune(cached, 12);
        Check.eq(2, gone.size(), "prune count");
        Check.eq("c1", gone.get(0).id, "oldest first");
        Check.eq("c2", gone.get(1).id, "second oldest");
        Check.eq(0, PictureRules.prune(cached.subList(0, 5), 12).size(), "nothing to prune");

        // tsvRoundTrip
        Picture back = Picture.fromTsv(photo.toTsv());
        Check.eq(photo.toTsv(), back.toTsv(), "tsv round trip");
        Check.eq("Dominicus Johannes Bergsma", back.author, "author survives");
        Check.yes(back.fresh && !back.art && back.added == 5, "flags survive");
        Check.eq(null, Picture.fromTsv("too\tfew"), "bad tsv");
    }
}
