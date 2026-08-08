package com.ledcar01.controller;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Command builder for the LEDCAR-01 BLE protocol, reverse engineered from the
 * original vendor app (com.home.net.NetConnectBle) and confirmed against real
 * vendor-app traffic. Every command is a fixed 9-byte frame.
 *
 * <p>Most commands come in two variants depending on {@link Zone}: the "RGB"
 * tab uses a 0x7E-header frame family shared with the older LEDBLE devices,
 * the "DMX" tab uses the CAR01-specific 0x7B-header frame family. See
 * PROTOCOL.md ("Three color paths" / "Brightness paths" / "Mode paths") for
 * how this was determined. Power and direction are not zone-split — no
 * RGB-tab-specific variant of either has been observed.
 */
public final class Car01Protocol {

    public static final String DEVICE_NAME_PREFIX = "LEDCAR-01-";

    public enum Zone {
        RGB, DMX
    }

    private Car01Protocol() {
    }

    public static final class Mode {
        public final String name;
        public final int id;

        public Mode(String name, int id) {
            this.name = name;
            this.id = id;
        }
    }

    /** RGB-tab preset effects (car_mode resource, ids 135-157). */
    public static final Mode[] MODES = {
            new Mode("Tricolor jump", 135),
            new Mode("Seven-color jump", 136),
            new Mode("Tricolor gradient", 137),
            new Mode("Seven-color gradient", 138),
            new Mode("Red gradient", 139),
            new Mode("Green gradient", 140),
            new Mode("Blue gradient", 141),
            new Mode("Yellow gradient", 142),
            new Mode("Cyan gradient", 143),
            new Mode("Purple gradient", 144),
            new Mode("White gradient", 145),
            new Mode("Red-green gradient", 146),
            new Mode("Red-blue gradient", 147),
            new Mode("Green-blue gradient", 148),
            new Mode("Seven-color flash", 149),
            new Mode("Red flash", 150),
            new Mode("Green flash", 151),
            new Mode("Blue flash", 152),
            new Mode("Yellow flash", 153),
            new Mode("Cyan flash", 154),
            new Mode("Purple flash", 155),
            new Mode("White flash", 156),
            new Mode("Seven-color breath", 157),
    };

    /** SPI color-order ids, from the vendor app's rgb_sort_ble resource. */
    public static final Map<Integer, String> COLOR_ORDERS = new LinkedHashMap<>();

    static {
        COLOR_ORDERS.put(1, "RGB");
        COLOR_ORDERS.put(2, "RBG");
        COLOR_ORDERS.put(3, "GRB");
        COLOR_ORDERS.put(4, "GBR");
        COLOR_ORDERS.put(5, "BRG");
        COLOR_ORDERS.put(6, "BGR");
    }

    public static final int DEFAULT_COLOR_ORDER = 3; // GRB

    // -- Power / direction: single command, not zone-split --

    /**
     * byte3 = 0x07, the "LED tab" flag that powers both zones at once - the
     * same variant the vendor app sends, confirmed against captured traffic.
     * (0x01 here is the DMX-zone-only variant and is intentionally unused.)
     */
    public static byte[] powerOn() {
        return car(0xFF, 0x04, 0x07, 0xFF, 0xFF, 0xFF, 0xFF);
    }

    /** byte3 = 0x06, the "LED tab" both-zones-off counterpart to {@link #powerOn()}. */
    public static byte[] powerOff() {
        return car(0xFF, 0x04, 0x06, 0xFF, 0xFF, 0xFF, 0xFF);
    }

    /** dir: 0 = forward, 1 = reverse. */
    public static byte[] setDirection(int dir) {
        return car(0xFF, 0x0D, dir, 0xFF, 0xFF, 0xFF, 0xFF);
    }

    // -- Color / brightness / speed / mode: zone-split --

    public static byte[] setColor(int r, int g, int b, Zone zone) {
        int rr = clamp255(r), gg = clamp255(g), bb = clamp255(b);
        if (zone == Zone.RGB) {
            return rgb(0xFF, 0x05, 0x03, rr, gg, bb, 0xFF);
        }
        return car(0x00, 0x07, rr, gg, bb, 0x00, 0xFF);
    }

    /**
     * Single DMX-family packet (byte1 = 0x01, the "LED tab sync" flag) that
     * sets color on both zones at once, instead of sending {@link #setColor}
     * for RGB and DMX separately. Confirmed against real vendor-app traffic.
     */
    public static byte[] setColorBoth(int r, int g, int b) {
        int rr = clamp255(r), gg = clamp255(g), bb = clamp255(b);
        return car(0x01, 0x07, rr, gg, bb, 0x00, 0xFF);
    }

    public static byte[] setBrightness(int percent, Zone zone) {
        int p = clampPercent(percent);
        if (zone == Zone.RGB) {
            return rgb(0xFF, 0x01, p, 0x00, 0xFF, 0xFF, 0xFF);
        }
        int scaled = (p * 32) / 100;
        return car(0xFF, 0x01, scaled, p, 0x00, 0xFF, 0xFF);
    }

    /**
     * Single DMX-family packet (byte5 = 0x02, the "LED tab sync" flag) that
     * sets brightness on both zones at once, instead of sending
     * {@link #setBrightness} for RGB and DMX separately. Confirmed against
     * real vendor-app traffic.
     */
    public static byte[] setBrightnessBoth(int percent) {
        int p = clampPercent(percent);
        int scaled = (p * 32) / 100;
        return car(0xFF, 0x01, scaled, p, 0x02, 0xFF, 0xFF);
    }

    public static byte[] setSpeed(int percent, Zone zone) {
        int p = clampPercent(percent);
        if (zone == Zone.RGB) {
            return rgb(0xFF, 0x02, p, 0x00, 0xFF, 0xFF, 0xFF);
        }
        return car(0xFF, 0x02, p, 0xFF, 0x00, 0xFF, 0xFF);
    }

    /**
     * modeId meaning depends on zone: RGB uses {@link #MODES} (135-157), DMX
     * uses the 211-entry table in {@link DmxModes} (1-210, 255 = auto).
     */
    public static byte[] setMode(int modeId, Zone zone) {
        if (zone == Zone.RGB) {
            return rgb(0xFF, 0x03, modeId, 0x03, 0xFF, 0xFF, 0xFF);
        }
        return car(0xFF, 0x03, modeId, 0xFF, 0xFF, 0xFF, 0xFF);
    }

    // -- Settings --

    public static byte[] setWelcomeMode(boolean on) {
        return rgb(0xFF, 0x12, on ? 0x00 : 0x01, 0xFF, 0xFF, 0xFF, 0xFF);
    }

    public static byte[] setConfigSpi(int pixelCount, int colorOrderId) {
        return car(0xFF, 0x05, 0x04, 0x00, clampPixelCount(pixelCount), colorOrderId, 0xFF);
    }

    // -- Frame builders --

    /** CAR01 frame: 0x7B header, 0xBF trailer. */
    private static byte[] car(int b1, int b2, int b3, int b4, int b5, int b6, int b7) {
        return new byte[]{
                (byte) 0x7B, (byte) b1, (byte) b2, (byte) b3, (byte) b4, (byte) b5, (byte) b6, (byte) b7, (byte) 0xBF,
        };
    }

    /** RGB-tab / LEDBLE-style frame: 0x7E header, 0xEF trailer. */
    private static byte[] rgb(int b1, int b2, int b3, int b4, int b5, int b6, int b7) {
        return new byte[]{
                (byte) 0x7E, (byte) b1, (byte) b2, (byte) b3, (byte) b4, (byte) b5, (byte) b6, (byte) b7, (byte) 0xEF,
        };
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static int clampPercent(int v) {
        return Math.max(0, Math.min(100, v));
    }

    private static int clampPixelCount(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
