package com.colortap;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** User settings, persisted in SharedPreferences. */
final class Settings {
    int[] colors = {0xFF0000}; // RGB, no alpha
    int tolerance = 30;        // max per-channel difference (0-255)
    int intervalMs = 300;      // time between scans
    int minHits = 3;           // matching sample points needed to count as a hit
    boolean tapEachColor;      // true: tap every target each cycle; false: only one spot
    int matchPct = 85;         // % of an image's sample points that must match

    private static final String PREFS = "settings";

    static Settings load(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Settings s = new Settings();
        int[] colors = parseColors(p.getString("colors", "#FF0000"));
        if (colors != null) s.colors = colors;
        s.tolerance = p.getInt("tolerance", s.tolerance);
        s.intervalMs = p.getInt("intervalMs", s.intervalMs);
        s.minHits = p.getInt("minHits", s.minHits);
        s.tapEachColor = p.getBoolean("tapEachColor", s.tapEachColor);
        s.matchPct = p.getInt("matchPct", s.matchPct);
        return s;
    }

    void save(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("colors", formatColors(colors))
                .putInt("tolerance", tolerance)
                .putInt("intervalMs", intervalMs)
                .putInt("minHits", minHits)
                .putBoolean("tapEachColor", tapEachColor)
                .putInt("matchPct", matchPct)
                .apply();
    }

    /** Parses "#FF0000, 00ff00 ..." (comma/space/newline separated). Returns null if any entry is invalid. */
    static int[] parseColors(String text) {
        List<Integer> out = new ArrayList<>();
        for (String t : text.split("[\\s,、]+")) {
            if (t.isEmpty()) continue;
            if (t.startsWith("#")) t = t.substring(1);
            if (t.length() != 6) return null;
            try {
                out.add(Integer.parseInt(t, 16));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        int[] a = new int[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    static String formatColors(int[] colors) {
        StringBuilder sb = new StringBuilder();
        for (int c : colors) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(String.format("#%06X", c));
        }
        return sb.toString();
    }
}
