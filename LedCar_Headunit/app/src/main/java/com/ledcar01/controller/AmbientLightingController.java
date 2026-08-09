package com.ledcar01.controller;

import android.animation.ValueAnimator;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.widget.ImageView;

/**
 * Drives the live car-interior ambient preview: a base interior image with
 * two tintable overlay layers (dashboard/door neon strip, footwell glow)
 * recolored in real time via PorterDuffColorFilter as the user picks a
 * color. The overlay drawables (mask_neon_strips.xml, mask_footwell_glow.xml)
 * are placeholders - swap them for real alpha-mask PNGs traced over your own
 * interior photo (see the drawable files for the expected shape) without
 * changing anything here, since the tint mechanism only cares that the
 * source is white-on-transparent.
 */
public class AmbientLightingController {

    private final ImageView imgFootwellLight;
    private final ImageView imgNeonStripLight;
    private ValueAnimator footwellEffectAnimator;
    private ValueAnimator neonStripEffectAnimator;

    public AmbientLightingController(ImageView footwellView, ImageView stripView) {
        this.imgFootwellLight = footwellView;
        this.imgNeonStripLight = stripView;
    }

    /** Updates the upper neon strip light color in real time. */
    public void setNeonStripColor(int colorRgb) {
        PorterDuffColorFilter filter = new PorterDuffColorFilter(colorRgb, PorterDuff.Mode.SRC_IN);
        imgNeonStripLight.setColorFilter(filter);
    }

    /** Updates the footwell and door handle glow color in real time. */
    public void setFootwellGlowColor(int colorRgb) {
        PorterDuffColorFilter filter = new PorterDuffColorFilter(colorRgb, PorterDuff.Mode.SRC_IN);
        imgFootwellLight.setColorFilter(filter);
    }

    /** Scales the neon strip's visible intensity to match the DMX zone's brightness (0-100). */
    public void setNeonStripBrightness(int percent) {
        imgNeonStripLight.setImageAlpha(percentToAlpha(percent));
    }

    /** Scales the footwell glow's visible intensity to match the RGB zone's brightness (0-100). */
    public void setFootwellGlowBrightness(int percent) {
        imgFootwellLight.setImageAlpha(percentToAlpha(percent));
    }

    /**
     * Maps the 0-100 slider range onto a 50-100 visible-intensity range
     * (0->50%, 50->75%, 100->100%) so the glow stays visibly present at low
     * brightness instead of gradually vanishing - including at the very
     * bottom of the range: 0-2% no longer cuts the preview to fully dark,
     * it just settles at the same 50% floor as any other low setting.
     * Powering the strip off entirely is a separate, explicit action
     * (see turnOffAmbientLights/fadeOut) - this mapping is only about how
     * dim "on" gets to look.
     */
    private static int percentToAlpha(int percent) {
        int clamped = Math.max(0, Math.min(100, percent));
        double mapped = 50.0 + clamped * 0.5;
        return Math.round((float) (mapped / 100.0 * 255));
    }

    /** Clears ambient lighting completely and instantly (no animation). */
    public void turnOffAmbientLights() {
        imgNeonStripLight.animate().cancel();
        imgFootwellLight.animate().cancel();
        imgNeonStripLight.setAlpha(1f);
        imgFootwellLight.setAlpha(1f);
        imgNeonStripLight.setImageAlpha(0);
        imgFootwellLight.setImageAlpha(0);
    }

    private static final long FADE_DURATION_MS = 600;

    /**
     * Fades both preview layers in from invisible - used both when the strip is
     * first found over Bluetooth and when the user powers it back on - so the
     * already-applied color/brightness state eases into view instead of
     * snapping on. Animates the view's own alpha on top of whatever image
     * alpha the brightness/color calls above already set.
     */
    public void fadeIn() {
        fadeView(imgNeonStripLight, 1f);
        fadeView(imgFootwellLight, 1f);
    }

    /** Fades both preview layers out to invisible, e.g. when the user powers the strip off. */
    public void fadeOut() {
        fadeView(imgNeonStripLight, 0f);
        fadeView(imgFootwellLight, 0f);
    }

    private static void fadeView(ImageView view, float targetAlpha) {
        view.animate().cancel();
        if (targetAlpha == 1f) {
            view.setAlpha(0f);
        }
        view.animate().alpha(targetAlpha).setDuration(FADE_DURATION_MS).start();
    }

    /**
     * Plays a selected DMX/RGB effect's <b>real</b> pattern on this preview
     * layer instead of a flat static tint - the actual archetype/color/
     * timing data extracted from the vendor app's own animated preview
     * assets (see {@link EffectVisual}'s class doc), not a guess from the
     * effect's name. This is deliberately the one place in the app that
     * shows what a selected effect really does: it plays out on the shape
     * of the real strip (the neon-strip mask, for DMX) rather than on an
     * abstract preview widget elsewhere. Stops automatically on the next
     * call to setFootwellGlowColor/setNeonStripColor (see
     * stopFootwellEffectPreview/stopNeonStripEffectPreview).
     */
    public void previewFootwellEffect(EffectVisual effect) {
        footwellEffectAnimator = startEffectAnimator(imgFootwellLight, footwellEffectAnimator, effect);
    }

    public void previewNeonStripEffect(EffectVisual effect) {
        neonStripEffectAnimator = startEffectAnimator(imgNeonStripLight, neonStripEffectAnimator, effect);
    }

    /** Stops the footwell effect preview - caller should immediately set a real static color after this. */
    public void stopFootwellEffectPreview() {
        footwellEffectAnimator = stopEffectAnimator(footwellEffectAnimator);
    }

    public void stopNeonStripEffectPreview() {
        neonStripEffectAnimator = stopEffectAnimator(neonStripEffectAnimator);
    }

    private static ValueAnimator stopEffectAnimator(ValueAnimator animator) {
        if (animator != null) {
            animator.cancel();
        }
        return null;
    }

    private static ValueAnimator startEffectAnimator(ImageView view, ValueAnimator existing, EffectVisual effect) {
        if (existing != null) {
            existing.cancel();
        }
        long start = System.currentTimeMillis();
        // Duration is arbitrary - the update listener drives color purely
        // off wall-clock elapsed time via EffectVisual.colorAt(), not off
        // the animator's own fraction, so this only needs to tick often
        // enough to look smooth (~30ms, matching the source GIFs' own
        // frame delay) and never actually needs to finish.
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(30);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.addUpdateListener(a -> {
            long elapsed = System.currentTimeMillis() - start;
            int color = effect.colorAt(elapsed);
            view.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        });
        animator.start();
        return animator;
    }
}
