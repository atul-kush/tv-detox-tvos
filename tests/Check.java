package com.ansub.tvhome;

import java.util.Objects;

// Tiny assertion helper for the JVM tests: no JUnit, so the tests build with javac alone.
final class Check {
    static void eq(Object expected, Object actual, String what) {
        if (!Objects.equals(expected, actual)) fail(what + ": expected <" + expected + "> but was <" + actual + ">");
    }

    static void yes(boolean c, String what) {
        if (!c) fail(what);
    }

    static void fail(String why) {
        System.err.println("FAIL " + why);
        System.exit(1);
    }
}
