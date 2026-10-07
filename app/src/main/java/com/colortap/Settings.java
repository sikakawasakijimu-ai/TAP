package com.colortap;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** User settings, persisted in SharedPreferences. */
final class Settings {
    /** Targets, highest priority first: "c:RRGGBB" for a color, "i:<file name>" for an image. */
    List<String> targets = new ArrayList<>();
    int tolerance = 30;        // max per-channel difference (0-255)
    int intervalMs = 300;      // time between scans
    int minHits = 3;           // matching sample points needed to count as a color hit
    boolean tapAll;            // true: tap every target found; false: only the highest-priority one
    int matchPct = 85;         // % of an image's sample points that must match

    private static final String PREFS = "settings";

    static Settings load(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Settings s = new Settings();
        String t = p.getString("targets", null);
        if (t != null) {
            for (String e : t.split("\\|")) if (!e.isEmpty()) s.targets.add(e);
        } else {
            // Older versions: a color list, plus images in registration order.
            for (String e : p.getString("colors", "#FF0000").split("[\\s,、]+")) {
                int rgb = parseColor(e);
                if (rgb >= 0) s.targets.add(colorTarget(rgb));
            }
            File[] files = Template.dir(c).listFiles((d, name) -> name.endsWith(".bin"));
            if (files != null) {
                Arrays.sort(files);
                for (File f : files) s.targets.add(imageTarget(f.getName()));
            }
        }
        s.tolerance = p.getInt("tolerance", s.tolerance);
        s.intervalMs = p.getInt("intervalMs", s.intervalMs);
        s.minHits = p.getInt("minHits", s.minHits);
        s.tapAll = p.getBoolean("tapEachColor", s.tapAll);
        s.matchPct = p.getInt("matchPct", s.matchPct);
        return s;
    }

    void save(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("targets", String.join("|", targets))
                .putInt("tolerance", tolerance)
                .putInt("intervalMs", intervalMs)
                .putInt("minHits", minHits)
                .putBoolean("tapEachColor", tapAll)
                .putInt("matchPct", matchPct)
                .apply();
    }

    static String colorTarget(int rgb) {
        return String.format("c:%06X", rgb);
    }

    static String imageTarget(String fileName) {
        return "i:" + fileName;
    }

    static boolean isColor(String target) {
        return target.startsWith("c:");
    }

    static int color(String target) {
        return Integer.parseInt(target.substring(2), 16);
    }

    static String imageName(String target) {
        return target.substring(2);
    }

    /** Parses "#RRGGBB" or "RRGGBB"; returns -1 if invalid. */
    static int parseColor(String text) {
        String t = text.trim();
        if (t.startsWith("#")) t = t.substring(1);
        if (t.length() != 6) return -1;
        try {
            return Integer.parseInt(t, 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
