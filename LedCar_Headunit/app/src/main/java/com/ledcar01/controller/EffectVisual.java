package com.ledcar01.controller;

import android.content.Context;
import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a given effect id/name actually looks like, for the live-animated
 * preview swatch in the Mode picker (see {@link EffectPreviewView}).
 * <p>
 * The 23 RGB-tab ids (135-157) come from <code>res/raw/effects.json</code> -
 * not a guess from the name text. They were read directly out of the vendor
 * app's own animated preview assets
 * (<code>res/drawable-hdpi/ble_gifmode1.gif</code> through
 * <code>ble_gifmode23.gif</code> in the decompiled APK, cross-referenced
 * against <code>res/values/arrays.xml</code>'s <code>ble_mode</code> array
 * for the id mapping): real per-frame colors and frame delays were
 * extracted and classified into one of the archetypes below. Retuning an
 * effect's look is a JSON edit, not a Java change - see that file's own
 * comment for the field shapes.
 * <p>
 * The 211 DMX-tab ids (1-210, 255) don't get the same per-id treatment -
 * decompiling confirmed the vendor app itself only shows a generic
 * wavy-dot color indicator for these too (<code>res/drawable/gifdmx*.gif</code>),
 * not a true positional pixel-chase simulation (that only happens on the
 * real hardware, same as here) - so there was nothing more precise to
 * extract per-id than what name-based color/archetype inference already
 * gives. DMX colors are derived from the name text (RD/GN/BU/...
 * abbreviations, "N Colors" implying a jump/rainbow set), matching the
 * vendor app's own preview fidelity rather than claiming to exceed it.
 */
public final class EffectVisual {

    public enum Archetype {
        /** Hard-cut between solid colors, no fade. */
        JUMP,
        /** Smooth continuous interpolation between colors (2+), cycling. */
        GRADIENT,
        /** A single hue, brightness pulsing smoothly between full and ~20%. */
        BREATHE,
        /** A set of hues, each cycling through with a brightness-pulse envelope. */
        BREATHE_MULTI,
        /** On/off blink - one color, or several colors each getting a blink in turn. */
        FLASH,
    }

    public final Archetype archetype;
    public final int[] colors;
    /** Milliseconds to hold each color (JUMP/FLASH) or cross-fade one step (GRADIENT/BREATHE). */
    public final int stepMs;

    private EffectVisual(Archetype archetype, int[] colors, int stepMs) {
        this.archetype = archetype;
        this.colors = colors;
        this.stepMs = stepMs;
    }

    // -- Shared palette for DMX name-derived colors --

    private static final int RED = Color.parseColor("#FF3B30");
    private static final int GREEN = Color.parseColor("#2ECC40");
    private static final int BLUE = Color.parseColor("#3B82F6");
    private static final int YELLOW = Color.parseColor("#F5D400");
    private static final int CYAN = Color.parseColor("#2DD4CF");
    private static final int PURPLE = Color.parseColor("#B026E8");
    private static final int WHITE = Color.parseColor("#F5F5F2");
    private static final int[] SEVEN = {RED, GREEN, BLUE, YELLOW, CYAN, PURPLE, WHITE};

    private static final Map<String, Integer> KEYWORD_COLORS = new LinkedHashMap<>();

    static {
        KEYWORD_COLORS.put("RD", RED);
        KEYWORD_COLORS.put("RED", RED);
        KEYWORD_COLORS.put("GN", GREEN);
        KEYWORD_COLORS.put("GREEN", GREEN);
        KEYWORD_COLORS.put("BU", BLUE);
        KEYWORD_COLORS.put("BLUE", BLUE);
        KEYWORD_COLORS.put("YE", YELLOW);
        KEYWORD_COLORS.put("YELLOW", YELLOW);
        KEYWORD_COLORS.put("CN", CYAN);
        KEYWORD_COLORS.put("CYAN", CYAN);
        KEYWORD_COLORS.put("VT", PURPLE);
        KEYWORD_COLORS.put("PURPLE", PURPLE);
        KEYWORD_COLORS.put("VIOLET", PURPLE);
        KEYWORD_COLORS.put("WH", WHITE);
        KEYWORD_COLORS.put("WHITE", WHITE);
    }

    // -- Loaded once from res/raw/effects.json, on first use --

    private static volatile Map<Integer, EffectVisual> rgbTable;

    public static EffectVisual forEffect(Context context, int id, String name) {
        Map<Integer, EffectVisual> table = ensureLoaded(context);
        EffectVisual known = table.get(id);
        if (known != null) {
            return known;
        }
        return forDmxName(name);
    }

    private static Map<Integer, EffectVisual> ensureLoaded(Context context) {
        Map<Integer, EffectVisual> table = rgbTable;
        if (table != null) {
            return table;
        }
        synchronized (EffectVisual.class) {
            if (rgbTable == null) {
                rgbTable = loadFromJson(context.getApplicationContext());
            }
            return rgbTable;
        }
    }

    private static Map<Integer, EffectVisual> loadFromJson(Context context) {
        Map<Integer, EffectVisual> table = new LinkedHashMap<>();
        try (InputStream in = context.getResources().openRawResource(R.raw.effects)) {
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            JSONObject root = new JSONObject(sb.toString());
            JSONArray effects = root.getJSONArray("effects");
            for (int i = 0; i < effects.length(); i++) {
                JSONObject o = effects.getJSONObject(i);
                int id = o.getInt("id");
                Archetype archetype = Archetype.valueOf(o.getString("archetype"));
                JSONArray colorArr = o.getJSONArray("colors");
                int[] colors = new int[colorArr.length()];
                for (int c = 0; c < colorArr.length(); c++) {
                    colors[c] = Color.parseColor(colorArr.getString(c));
                }
                int stepMs = o.getInt("stepMs");
                table.put(id, new EffectVisual(archetype, colors, stepMs));
            }
        } catch (IOException | JSONException e) {
            // Falls back to name-derived DMX-style visuals for every id if the
            // resource is ever malformed - never crash the mode picker over a
            // cosmetic preview.
        }
        return table;
    }

    /**
     * DMX-tab names follow a handful of recurring shapes ("Forward/Backward
     * Dreaming", "Forward N Colors", single or paired color abbreviations,
     * "...Jump"/"...Flash" suffixes) - classify by those rather than a
     * per-id table, since the vendor app's own preview doesn't distinguish
     * "Forward" from "Backward" visually either (both use the same color
     * set; direction only matters on real hardware).
     */
    private static EffectVisual forDmxName(String name) {
        String upper = name.toUpperCase(Locale.US);
        boolean rainbow = upper.contains("7 COLOR") || upper.contains("SEVEN-COLOR") || upper.contains("SEVEN COLOR")
                || upper.contains("6 COLOR") || upper.contains("DREAMING");

        Archetype archetype;
        if (upper.contains("FLASH") || upper.contains("STROBE")) {
            archetype = Archetype.FLASH;
        } else if (upper.contains("JUMP")) {
            archetype = Archetype.JUMP;
        } else {
            archetype = Archetype.GRADIENT;
        }

        int[] colors;
        if (rainbow) {
            colors = SEVEN;
        } else {
            List<Integer> found = new ArrayList<>();
            for (String token : upper.split("[^A-Z]+")) {
                Integer c = KEYWORD_COLORS.get(token);
                if (c != null && !found.contains(c)) {
                    found.add(c);
                }
            }
            if (found.isEmpty()) {
                found.add(PURPLE);
            }
            colors = new int[found.size()];
            for (int i = 0; i < found.size(); i++) {
                colors[i] = found.get(i);
            }
        }

        // A single color with a GRADIENT archetype reads better as a breathing
        // pulse than a self-fade-to-itself, matching how the RGB-tab's own
        // single-color "gradient" effects actually behave (see effects.json).
        if (archetype == Archetype.GRADIENT && colors.length == 1) {
            archetype = Archetype.BREATHE;
        }

        int stepMs = archetype == Archetype.FLASH ? 420 : (archetype == Archetype.BREATHE ? 2600 : 1400);
        return new EffectVisual(archetype, colors, stepMs);
    }
}
