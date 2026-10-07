package com.colortap;

import android.content.Context;
import android.content.SharedPreferences;

/** User settings, persisted in SharedPreferences. */
final class Settings {
    int color = 0xFF0000;   // RGB, no alpha
    int tolerance = 30;     // max per-channel difference (0-255)
    int intervalMs = 300;   // time between scans
    int minHits = 3;        // matching sample points needed to count as a hit

    private static final String PREFS = "settings";

    static Settings load(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Settings s = new Settings();
        s.color = p.getInt("color", s.color);
        s.tolerance = p.getInt("tolerance", s.tolerance);
        s.intervalMs = p.getInt("intervalMs", s.intervalMs);
        s.minHits = p.getInt("minHits", s.minHits);
        return s;
    }

    void save(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("color", color)
                .putInt("tolerance", tolerance)
                .putInt("intervalMs", intervalMs)
                .putInt("minHits", minHits)
                .apply();
    }
}
