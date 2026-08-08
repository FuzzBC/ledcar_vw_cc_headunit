package com.ledcar01.controller;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists two independent things: an ordered, appendable list of saved
 * "eggs" (each holding both a DMX color and an RGB color, so a single Save
 * captures both zones at once with their own independent brightness), and
 * the last-used live state (restored on app launch so the UI reflects the
 * previous session).
 */
public class SavedColorStore {

    private static final String PREFS_NAME = "ledcar01_prefs";
    private static final String KEY_SAVED_EGGS = "saved_eggs_json";

    private final SharedPreferences prefs;

    public SavedColorStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static final class ColorState {
        public final int r;
        public final int g;
        public final int b;
        public final int brightness;

        public ColorState(int r, int g, int b, int brightness) {
            this.r = r;
            this.g = g;
            this.b = b;
            this.brightness = brightness;
        }
    }

    /** One saved entry: a DMX color (top) and an RGB color (bottom), each with its own brightness. */
    public static final class SavedEgg {
        public final ColorState dmx;
        public final ColorState rgb;

        public SavedEgg(ColorState dmx, ColorState rgb) {
            this.dmx = dmx;
            this.rgb = rgb;
        }
    }

    // -- Saved eggs: an ordered, appendable list --

    /** Newest egg is appended at the end, so the UI can show it at the right end of the row. */
    public void addEgg(ColorState dmx, ColorState rgb) {
        List<SavedEgg> eggs = getEggs();
        eggs.add(new SavedEgg(dmx, rgb));
        writeEggs(eggs);
    }

    /** Overwrites an existing egg's colors/brightnesses in place, keeping its position in the row. */
    public void updateEgg(int index, ColorState dmx, ColorState rgb) {
        List<SavedEgg> eggs = getEggs();
        if (index < 0 || index >= eggs.size()) {
            return;
        }
        eggs.set(index, new SavedEgg(dmx, rgb));
        writeEggs(eggs);
    }

    public void removeEgg(int index) {
        List<SavedEgg> eggs = getEggs();
        if (index < 0 || index >= eggs.size()) {
            return;
        }
        eggs.remove(index);
        writeEggs(eggs);
    }

    public List<SavedEgg> getEggs() {
        List<SavedEgg> eggs = new ArrayList<>();
        String json = prefs.getString(KEY_SAVED_EGGS, null);
        if (json == null) {
            return eggs;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                eggs.add(new SavedEgg(
                        colorStateFromJson(o.getJSONObject("dmx")),
                        colorStateFromJson(o.getJSONObject("rgb"))));
            }
        } catch (JSONException e) {
            return new ArrayList<>();
        }
        return eggs;
    }

    private ColorState colorStateFromJson(JSONObject o) throws JSONException {
        return new ColorState(o.getInt("r"), o.getInt("g"), o.getInt("b"), o.getInt("brightness"));
    }

    private JSONObject colorStateToJson(ColorState c) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("r", c.r);
        o.put("g", c.g);
        o.put("b", c.b);
        o.put("brightness", c.brightness);
        return o;
    }

    private void writeEggs(List<SavedEgg> eggs) {
        JSONArray arr = new JSONArray();
        try {
            for (SavedEgg egg : eggs) {
                JSONObject o = new JSONObject();
                o.put("dmx", colorStateToJson(egg.dmx));
                o.put("rgb", colorStateToJson(egg.rgb));
                arr.put(o);
            }
        } catch (JSONException e) {
            return;
        }
        prefs.edit().putString(KEY_SAVED_EGGS, arr.toString()).apply();
    }

    // -- Last-used live state, restored on launch --

    /** Zones are independently toggleable (both active by default), not mutually exclusive. */
    public void setActiveZones(boolean rgbActive, boolean dmxActive) {
        prefs.edit().putBoolean("rgb_active", rgbActive).putBoolean("dmx_active", dmxActive).apply();
    }

    public boolean getRgbActive() {
        return prefs.getBoolean("rgb_active", true);
    }

    public boolean getDmxActive() {
        return prefs.getBoolean("dmx_active", true);
    }

    public void setLastColor(int r, int g, int b) {
        prefs.edit().putInt("last_r", r).putInt("last_g", g).putInt("last_b", b).apply();
    }

    /** Each zone's own last color, persisted only while a device is connected (see MainActivity). */
    public void setZoneColor(Car01Protocol.Zone zone, int r, int g, int b) {
        String p = "zone_color_" + zone.name();
        prefs.edit().putInt(p + "_r", r).putInt(p + "_g", g).putInt(p + "_b", b).apply();
    }

    public ColorState getZoneColor(Car01Protocol.Zone zone, int fallbackR, int fallbackG, int fallbackB) {
        String p = "zone_color_" + zone.name();
        return new ColorState(
                prefs.getInt(p + "_r", fallbackR),
                prefs.getInt(p + "_g", fallbackG),
                prefs.getInt(p + "_b", fallbackB),
                0);
    }

    public void setLastBrightness(int percent) {
        prefs.edit().putInt("last_brightness", percent).apply();
    }

    public void setLastSpeed(int percent) {
        prefs.edit().putInt("last_speed", percent).apply();
    }

    public void setLastModeId(Car01Protocol.Zone zone, int modeId) {
        prefs.edit().putInt(zone == Car01Protocol.Zone.RGB ? "last_mode_rgb" : "last_mode_dmx", modeId).apply();
    }

    public int getLastModeId(Car01Protocol.Zone zone) {
        return prefs.getInt(zone == Car01Protocol.Zone.RGB ? "last_mode_rgb" : "last_mode_dmx", -1);
    }

    public ColorState getLastColorState() {
        return new ColorState(
                prefs.getInt("last_r", 255),
                prefs.getInt("last_g", 80),
                prefs.getInt("last_b", 80),
                prefs.getInt("last_brightness", 80));
    }

    public int getLastSpeed() {
        return prefs.getInt("last_speed", 50);
    }

    public void setPixelCount(int pixelCount) {
        prefs.edit().putInt("pixel_count", pixelCount).apply();
    }

    public int getPixelCount() {
        return prefs.getInt("pixel_count", 60);
    }

    public void setColorOrderId(int colorOrderId) {
        prefs.edit().putInt("color_order_id", colorOrderId).apply();
    }

    public int getColorOrderId() {
        return prefs.getInt("color_order_id", Car01Protocol.DEFAULT_COLOR_ORDER);
    }
}
