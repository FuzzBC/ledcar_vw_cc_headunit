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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a given effect id/name actually looks like, for the live-animated
 * dot-strip preview in the Mode picker (see {@link EffectPreviewView}).
 * <p>
 * All 234 known preset effects - the 23 RGB-tab ones (ids 135-157) and all
 * 211 DMX-tab ones (ids 1-210, 255) - come from <code>res/raw/effects.json</code>,
 * derived by scanning <i>every frame</i> of the vendor app's own animated
 * preview assets (<code>res/drawable-hdpi/ble_gifmode1.gif</code>..23 for
 * RGB, <code>res/drawable/gifdmx1.gif</code>..210 plus <code>gifdmx255.gif</code>
 * for DMX, in the decompiled APK) rather than guessed from the effect name
 * or sampled at a few points. Every dot in the preview shares one computed
 * color at any instant - confirmed by scanning entire frames for a lit
 * pixel wherever it appeared, always finding exactly one shared color, same
 * as the real hardware/vendor preview (they animate the whole strip as one
 * color, not per-pixel independently) - only their position along the wave
 * differs.
 * <p>
 * RGB ids (135-157) and DMX ids (1-210) occupy the <b>same numeric range</b>
 * but are two entirely different command families (0x7E header vs 0x7B
 * header - see PROTOCOL.md), so a lookup must always specify which zone/tab
 * it means; {@link #forEffect} takes that explicitly rather than trusting
 * the id alone to disambiguate. Retuning an effect's look is a JSON edit,
 * not a Java change - see <code>effects.json</code>'s own comment for the
 * field shapes.
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

    /**
     * The color this effect shows at a given point in its own timeline -
     * shared by anything that needs to actually play the effect back
     * (currently the ambient preview's neon-strip mask; see
     * AmbientLightingController.previewNeonStripEffect). Matches the real
     * vendor preview's own behavior: the whole strip is one shared color at
     * any instant (see this class's doc), so a caller only needs one color
     * per tick, not a per-pixel array.
     */
    public int colorAt(long elapsedMs) {
        switch (archetype) {
            case JUMP: {
                int idx = (int) ((elapsedMs / stepMs) % colors.length);
                return colors[idx];
            }
            case GRADIENT: {
                long cycle = (long) stepMs * colors.length;
                float pos = (elapsedMs % cycle) / (float) stepMs;
                int idx = (int) pos;
                int next = (idx + 1) % colors.length;
                float t = pos - idx;
                return lerp(colors[idx], colors[next], t);
            }
            case BREATHE: {
                float t = (elapsedMs % stepMs) / (float) stepMs;
                float brightness = 0.2f + 0.8f * (0.5f - 0.5f * (float) Math.cos(t * Math.PI * 2));
                return scale(colors[0], brightness);
            }
            case BREATHE_MULTI: {
                long cycle = (long) stepMs * colors.length;
                float pos = (elapsedMs % cycle) / (float) stepMs;
                int idx = (int) pos;
                float t = pos - idx;
                float brightness = 0.2f + 0.8f * (0.5f - 0.5f * (float) Math.cos(t * Math.PI * 2));
                return scale(colors[idx % colors.length], brightness);
            }
            case FLASH: {
                long onMs = stepMs;
                long offMs = (long) (stepMs * 0.7f);
                long cycle = (onMs + offMs) * colors.length;
                long pos = elapsedMs % cycle;
                int idx = (int) (pos / (onMs + offMs));
                long phase = pos % (onMs + offMs);
                boolean on = phase < onMs;
                return on ? colors[idx % colors.length] : Color.argb(40, 120, 120, 130);
            }
            default:
                return colors.length > 0 ? colors[0] : Color.GRAY;
        }
    }

    private static int lerp(int a, int b, float t) {
        int ar = Color.red(a), ag = Color.green(a), ab = Color.blue(a);
        int br = Color.red(b), bg = Color.green(b), bb = Color.blue(b);
        return Color.rgb(
                (int) (ar + (br - ar) * t),
                (int) (ag + (bg - ag) * t),
                (int) (ab + (bb - ab) * t));
    }

    private static int scale(int color, float k) {
        return Color.rgb(
                (int) (Color.red(color) * k),
                (int) (Color.green(color) * k),
                (int) (Color.blue(color) * k));
    }

    private static volatile Map<Integer, EffectVisual> rgbTable;
    private static volatile Map<Integer, EffectVisual> dmxTable;

    public static EffectVisual forEffect(Context context, Car01Protocol.Zone zone, int id, String name) {
        ensureLoaded(context);
        Map<Integer, EffectVisual> table = zone == Car01Protocol.Zone.RGB ? rgbTable : dmxTable;
        EffectVisual known = table.get(id);
        if (known != null) {
            return known;
        }
        // Should only happen if effects.json is ever out of sync with
        // Car01Protocol.MODES/DmxModes.NAMES - fall back to a plain steady
        // swatch rather than crash the mode picker over a cosmetic preview.
        return new EffectVisual(Archetype.BREATHE, new int[]{Color.parseColor("#B026E8")}, 2000);
    }

    private static void ensureLoaded(Context context) {
        if (rgbTable != null) {
            return;
        }
        synchronized (EffectVisual.class) {
            if (rgbTable == null) {
                loadFromJson(context.getApplicationContext());
            }
        }
    }

    private static void loadFromJson(Context context) {
        Map<Integer, EffectVisual> rgb = new LinkedHashMap<>();
        Map<Integer, EffectVisual> dmx = new LinkedHashMap<>();
        try (InputStream in = context.getResources().openRawResource(R.raw.effects)) {
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            JSONObject root = new JSONObject(sb.toString());
            parseSection(root.getJSONArray("rgb"), rgb);
            parseSection(root.getJSONArray("dmx"), dmx);
        } catch (IOException | JSONException e) {
            // Leave whichever table(s) didn't finish loading empty - forEffect()
            // falls back to a plain swatch per id rather than crashing.
        }
        rgbTable = rgb;
        dmxTable = dmx;
    }

    private static void parseSection(JSONArray effects, Map<Integer, EffectVisual> into) throws JSONException {
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
            into.put(id, new EffectVisual(archetype, colors, stepMs));
        }
    }
}
