package com.ansub.tvhome;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// Where every app sits: a dock of six slots (a slot can be empty) followed by rows, as one line.
// Positions 0-5 are the dock slots, 6 and up are the rows. Plain Java, so it's tested on a laptop.
final class AppLine {
    static final int DOCK = 6;
    static final String SETTINGS = "settings";
    static final String PLAY_STORE = "com.android.vending";

    private final String[] dock = new String[DOCK];
    private final List<String> rows = new ArrayList<>();
    private final Set<String> hidden = new LinkedHashSet<>();

    // Reads what save() wrote. Anything missing or unreadable gives an empty line.
    static AppLine load(String saved) {
        AppLine l = new AppLine();
        if (saved == null) return l;
        for (String line : saved.split("\n")) {
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String name = line.substring(0, eq), value = line.substring(eq + 1);
            if (name.equals("dock")) {
                String[] slots = value.split(",", -1);
                for (int i = 0; i < DOCK && i < slots.length; i++) l.dock[i] = slots[i].isEmpty() ? null : slots[i];
            } else if (name.equals("rows")) {
                l.rows.addAll(csv(value));
            } else if (name.equals("hidden")) {
                l.hidden.addAll(csv(value));
            }
        }
        return l;
    }

    // The old saved app list ("pkg1,pkg2,..."): the first six fill the dock, the rest start the rows.
    static AppLine fromLegacy(String appsCsv) {
        AppLine l = new AppLine();
        List<String> apps = csv(appsCsv == null ? "" : appsCsv);
        for (int i = 0; i < apps.size(); i++) {
            if (i < DOCK) l.dock[i] = apps.get(i);
            else l.rows.add(apps.get(i));
        }
        return l;
    }

    String save() {
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < DOCK; i++) {
            if (i > 0) d.append(',');
            if (dock[i] != null) d.append(dock[i]);
        }
        return "dock=" + d + "\nrows=" + join(rows) + "\nhidden=" + join(hidden);
    }

    // Brings the line in step with the TV: uninstalled apps go (a dock slot becomes empty), new ones
    // join the rows before Play Store and Settings, and Play Store and Settings are there unless hidden.
    void reconcile(List<String> installed) {
        for (int i = 0; i < DOCK; i++) if (gone(dock[i], installed)) dock[i] = null;
        for (int i = rows.size() - 1; i >= 0; i--) if (gone(rows.get(i), installed)) rows.remove(i);
        for (String p : installed) {
            if (p.equals(PLAY_STORE) || p.equals(SETTINGS)) continue;
            if (positionOf(p) < 0 && !hidden.contains(p)) rows.add(beforeStore(), p);
        }
        if (installed.contains(PLAY_STORE) && positionOf(PLAY_STORE) < 0 && !hidden.contains(PLAY_STORE)) {
            int s = rows.indexOf(SETTINGS);
            rows.add(s < 0 ? rows.size() : s, PLAY_STORE);
        }
        if (positionOf(SETTINGS) < 0 && !hidden.contains(SETTINGS)) rows.add(SETTINGS);
    }

    List<String> dock() {
        return Collections.unmodifiableList(Arrays.asList(dock.clone()));
    }

    List<String> rows() {
        return Collections.unmodifiableList(new ArrayList<>(rows));
    }

    Set<String> hidden() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(hidden));
    }

    boolean dockFull() {
        for (String s : dock) if (s == null) return false;
        return true;
    }

    boolean dockEmpty() {
        for (String k : dock) if (k != null) return false;
        return true;
    }

    // The dock becomes these apps (the first six), in this order. They leave the rows; apps that
    // were in the dock go to the start of the rows.
    void setDock(List<String> keys) {
        List<String> old = new ArrayList<>();
        for (String k : dock) if (k != null && !keys.contains(k)) old.add(k);
        Arrays.fill(dock, null);
        int i = 0;
        for (String k : keys) {
            if (i == DOCK) break;
            if (k == null || Arrays.asList(dock).contains(k)) continue;
            rows.remove(k);
            dock[i++] = k;
        }
        rows.addAll(0, old);
    }

    // Puts an app from the rows into the first empty dock slot. False if the dock is full.
    boolean pin(String key) {
        if (!rows.contains(key)) return false;
        for (int i = 0; i < DOCK; i++) {
            if (dock[i] == null) {
                rows.remove(key);
                dock[i] = key;
                return true;
            }
        }
        return false;
    }

    // Leaves a gap where the app was and puts it first in the rows.
    void removeFromDock(String key) {
        int i = positionOf(key);
        if (i < 0 || i >= DOCK) return;
        dock[i] = null;
        rows.add(0, key);
    }

    void hide(String key) {
        int i = positionOf(key);
        if (i >= 0 && i < DOCK) dock[i] = null;
        else if (i >= DOCK) rows.remove(key);
        hidden.add(key);
    }

    // Brings a hidden app back where a newly installed one would go.
    void unhide(String key) {
        if (!hidden.remove(key) || positionOf(key) >= 0) return;
        if (key.equals(SETTINGS)) rows.add(key);
        else if (key.equals(PLAY_STORE)) {
            int s = rows.indexOf(SETTINGS);
            rows.add(s < 0 ? rows.size() : s, key);
        } else rows.add(beforeStore(), key);
    }

    int positionOf(String key) {
        for (int i = 0; i < DOCK; i++) if (key.equals(dock[i])) return i;
        int r = rows.indexOf(key);
        return r < 0 ? -1 : DOCK + r;
    }

    // Moves an app one step (±1 sideways, ±6 a row up or down) and returns where it ended up.
    // Apps in the way nudge over toward where it came from; an empty dock slot in the way takes
    // the nudge, and a full dock pushed from below sends its last app to the start of the rows.
    int slide(String key, int step) {
        int p = positionOf(key), q = p + step;
        if (p < 0 || q < 0) return p;
        int last = DOCK + rows.size() - (p >= DOCK ? 1 : 0);
        if (q > last) return p;
        if (p < DOCK && q < DOCK) {
            if (dock[q] == null) {
                dock[q] = key;
                dock[p] = null;
            } else {
                List<String> d = new ArrayList<>(Arrays.asList(dock));
                d.add(q, d.remove(p));
                d.toArray(dock);
            }
        } else if (p < DOCK) {
            dock[p] = null;
            rows.add(q - DOCK, key);
        } else if (q >= DOCK) {
            rows.remove(p - DOCK);
            rows.add(q - DOCK, key);
        } else {
            rows.remove(p - DOCK);
            if (dock[q] != null) {
                int gap = -1;
                for (int i = q + 1; i < DOCK; i++) if (dock[i] == null) { gap = i; break; }
                if (gap < 0) {
                    rows.add(0, dock[DOCK - 1]);
                    gap = DOCK - 1;
                }
                System.arraycopy(dock, q, dock, q + 1, gap - q);
            }
            dock[q] = key;
        }
        return q;
    }

    private boolean gone(String key, List<String> installed) {
        return key != null && !key.equals(SETTINGS) && !installed.contains(key);
    }

    private int beforeStore() {
        for (int i = 0; i < rows.size(); i++) {
            String k = rows.get(i);
            if (k.equals(PLAY_STORE) || k.equals(SETTINGS)) return i;
        }
        return rows.size();
    }

    private static List<String> csv(String s) {
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) if (!p.trim().isEmpty()) out.add(p.trim());
        return out;
    }

    private static String join(Iterable<String> items) {
        StringBuilder b = new StringBuilder();
        for (String s : items) {
            if (b.length() > 0) b.append(',');
            b.append(s);
        }
        return b.toString();
    }
}
