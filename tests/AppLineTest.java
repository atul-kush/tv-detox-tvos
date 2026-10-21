package com.ansub.tvhome;

import java.util.Arrays;
import java.util.List;

import static java.util.Arrays.asList;

// Notation in comments: [dock | rows], _ = empty dock slot.
public class AppLineTest {
    static final String STORE = AppLine.PLAY_STORE, SET = AppLine.SETTINGS;

    // Builds a line from a picture like "a b _ d e f | g h".
    static AppLine line(String picture) {
        String[] halves = picture.split("\\|", -1);
        String[] dock = halves[0].trim().split("\\s+");
        String rows = halves.length > 1 ? halves[1].trim() : "";
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < AppLine.DOCK; i++) {
            if (i > 0) d.append(',');
            if (i < dock.length && !dock[i].equals("_") && !dock[i].isEmpty()) d.append(dock[i]);
        }
        return AppLine.load("dock=" + d + "\nrows=" + rows.replaceAll("\\s+", ",") + "\nhidden=");
    }

    static List<String> dock(String... k) {
        return Arrays.asList(k);
    }

    public static void main(String[] args) {
        // legacyBecomesDock
        AppLine l = AppLine.fromLegacy("a,b,c,d,e,f,g");
        Check.eq(dock("a", "b", "c", "d", "e", "f"), l.dock(), "legacy dock");
        Check.eq(asList("g"), l.rows(), "legacy rows");

        // saveRoundTrips
        l = line("a b _ d e f | g h");
        l.hide("h");
        AppLine r = AppLine.load(l.save());
        Check.eq(l.dock(), r.dock(), "round trip dock");
        Check.eq(l.rows(), r.rows(), "round trip rows");
        Check.eq(l.hidden(), r.hidden(), "round trip hidden");

        // emptyDockStillHasSixSlots
        l = AppLine.load("");
        Check.eq(dock(null, null, null, null, null, null), l.dock(), "empty dock");
        Check.eq(6, AppLine.load(null).dock().size(), "null load");

        // reconcileAddsNewBeforeStoreAndSettings
        l = line("a _ _ _ _ _ | x " + STORE + " " + SET);
        l.reconcile(asList("a", "x", STORE, "n"));
        Check.eq(asList("x", "n", STORE, SET), l.rows(), "new app before store/settings");

        // reconcileEmptiesSlotOfUninstalledApp
        l = line("a b c d e f | g");
        l.reconcile(asList("a", "b", "d", "e", "f", "g", STORE));
        Check.eq(dock("a", "b", null, "d", "e", "f"), l.dock(), "uninstalled leaves gap");
        Check.eq(asList("g", STORE, SET), l.rows(), "store and settings ensured");

        // reconcileSkipsHidden
        l = line("a b c d e f | g");
        l.hide("g");
        l.reconcile(asList("a", "b", "c", "d", "e", "f", "g"));
        Check.yes(!l.dock().contains("g") && !l.rows().contains("g"), "hidden stays out");
        l.hide(SET);
        l.reconcile(asList("a", "b", "c", "d", "e", "f", "g"));
        Check.yes(!l.rows().contains(SET), "hidden settings stays out");

        // pinFillsFirstGap
        l = line("a _ c _ _ _ | x");
        Check.yes(l.pin("x"), "pin returns true");
        Check.eq(dock("a", "x", "c", null, null, null), l.dock(), "pin dock");
        Check.eq(asList(), l.rows(), "pin rows");

        // pinRefusedWhenFull
        l = line("a b c d e f | x");
        Check.yes(l.dockFull(), "dock full");
        Check.yes(!l.pin("x"), "pin refused");
        Check.eq(asList("x"), l.rows(), "unchanged rows");

        // removeFromDockLeavesGap
        l = line("a b c d e f | x");
        l.removeFromDock("c");
        Check.eq(dock("a", "b", null, "d", "e", "f"), l.dock(), "remove gap");
        Check.eq(asList("c", "x"), l.rows(), "removed to row start");

        // slideRightSwapsNeighbours
        l = line("a b c d e f | g");
        Check.eq(1, l.slide("a", 1), "slide right pos");
        Check.eq(dock("b", "a", "c", "d", "e", "f"), l.dock(), "slide right");

        // slideIntoGapFills
        l = line("a _ c d e f | g");
        l.slide("a", 1);
        Check.eq(dock(null, "a", "c", "d", "e", "f"), l.dock(), "slide into gap");

        // slideUpIntoFullDockPushesLastDown
        l = line("a b c d e f | g h");
        Check.eq(0, l.slide("g", -6), "slide up pos");
        Check.eq(dock("g", "a", "b", "c", "d", "e"), l.dock(), "slide up dock");
        Check.eq(asList("f", "h"), l.rows(), "slide up rows");

        // slideUpStopsAtGap
        l = line("a b _ d e f | g");
        l.slide("g", -6);
        Check.eq(dock("g", "a", "b", "d", "e", "f"), l.dock(), "gap absorbs nudge");
        Check.eq(asList(), l.rows(), "rows after gap absorb");

        // slideDockToRowsLeavesGap
        l = line("a b c d e f | g");
        Check.eq(6, l.slide("f", 1), "dock to rows pos");
        Check.eq(dock("a", "b", "c", "d", "e", null), l.dock(), "dock to rows gap");
        Check.eq(asList("f", "g"), l.rows(), "dock to rows rows");

        // slideOffEndRefused
        l = line("a b c d e f | g");
        Check.eq(0, l.slide("a", -1), "off end");
        Check.eq(dock("a", "b", "c", "d", "e", "f"), l.dock(), "off end unchanged");
        Check.eq(6, l.slide("g", 6), "below last row refused");

        // slideWithinRows
        l = line("a b c d e f | g h i");
        Check.eq(7, l.slide("g", 1), "rows slide 1");
        Check.eq(8, l.slide("g", 1), "rows slide 2");
        Check.eq(asList("h", "i", "g"), l.rows(), "rows nudged");

        // hideRemovesAndUnhideReturnsBeforeStore
        l = line("a b c d e f | x " + STORE + " " + SET);
        l.hide("b");
        Check.eq(dock("a", null, "c", "d", "e", "f"), l.dock(), "hide leaves gap");
        Check.yes(l.hidden().contains("b"), "hidden set");
        l.unhide("b");
        Check.eq(asList("x", "b", STORE, SET), l.rows(), "unhide before store");
        Check.yes(!l.hidden().contains("b"), "unhidden");

        // positionOf
        l = line("a _ c d e f | g h");
        Check.eq(2, l.positionOf("c"), "dock pos");
        Check.eq(7, l.positionOf("h"), "row pos");
        Check.eq(-1, l.positionOf("zz"), "absent");
    }
}
