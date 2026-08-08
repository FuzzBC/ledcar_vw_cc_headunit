package com.ledcar01.controller;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.List;

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
     * Below 2% the real strip is effectively off, so the preview cuts to fully dark. Above
     * that floor it maps the 0-100 slider range onto a 50-100 visible-intensity range
     * (0->50%, 50->75%, 100->100%) so the glow stays visibly present at low brightness
     * instead of gradually vanishing.
     */
    private static int percentToAlpha(int percent) {
        int clamped = Math.max(0, Math.min(100, percent));
        if (clamped < 2) {
            return 0;
        }
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
     * Mimics a selected DMX/RGB effect in the preview instead of a flat static
     * tint: a single-color effect breathes between full and dim, a multi-color
     * "gradient" effect crossfades smoothly through the list, and a "jump"
     * effect hard-cuts between colors. Stops automatically on the next call to
     * setFootwellGlowColor (see stopFootwellEffectPreview).
     */
    public void previewFootwellEffect(List<Integer> colors, boolean jump) {
        footwellEffectAnimator = startEffectAnimator(imgFootwellLight, footwellEffectAnimator, colors, jump);
    }

    public void previewNeonStripEffect(List<Integer> colors, boolean jump) {
        neonStripEffectAnimator = startEffectAnimator(imgNeonStripLight, neonStripEffectAnimator, colors, jump);
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

    private static ValueAnimator startEffectAnimator(ImageView view, ValueAnimator existing, List<Integer> colors, boolean jump) {
        if (existing != null) {
            existing.cancel();
        }
        List<Integer> sequence = colors;
        if (colors.size() == 1) {
            int c = colors.get(0);
            int dim = Color.rgb(Color.red(c) / 4, Color.green(c) / 4, Color.blue(c) / 4);
            sequence = new ArrayList<>();
            sequence.add(c);
            sequence.add(dim);
        }
        int steps = sequence.size();
        List<Integer> finalSequence = sequence;
        ArgbEvaluator evaluator = new ArgbEvaluator();
        ValueAnimator animator = ValueAnimator.ofFloat(0f, steps);
        animator.setDuration(steps * (jump ? 500L : 900L));
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            int i = ((int) t) % steps;
            int next = (i + 1) % steps;
            int color;
            if (jump) {
                color = finalSequence.get(i);
            } else {
                float frac = t - (int) t;
                color = (int) evaluator.evaluate(frac, finalSequence.get(i), finalSequence.get(next));
            }
            view.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        });
        animator.start();
        return animator;
    }
}
