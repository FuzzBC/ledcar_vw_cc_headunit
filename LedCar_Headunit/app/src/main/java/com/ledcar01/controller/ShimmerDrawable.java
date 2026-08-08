package com.ledcar01.controller;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.animation.LinearInterpolator;

/**
 * Foreground overlay for the Scan button while a scan is in progress: a soft
 * highlight band sweeps left-to-right across the button's rounded-pill
 * shape, repeating for as long as scanning runs. Purely additive over
 * whatever background color the button already has (dark when disconnected,
 * blue outline once connected), so it works in either state.
 */
public class ShimmerDrawable extends Drawable {

    private static final long CYCLE_MS = 1300;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clipPath = new Path();
    private final int highlightColor;
    private final int transparentColor;
    private final float cornerRadiusPx;
    private ValueAnimator animator;
    private float phase = 0f;

    public ShimmerDrawable(int highlightColor, float cornerRadiusPx) {
        this.highlightColor = highlightColor;
        this.transparentColor = highlightColor & 0x00FFFFFF;
        this.cornerRadiusPx = cornerRadiusPx;
    }

    public void start() {
        if (animator != null) {
            return;
        }
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(CYCLE_MS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
            invalidateSelf();
        });
        animator.start();
    }

    public void stop() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        clipPath.reset();
        clipPath.addRoundRect(new RectF(bounds), cornerRadiusPx, cornerRadiusPx, Path.Direction.CW);
    }

    @Override
    public void draw(Canvas canvas) {
        if (animator == null) {
            return;
        }
        Rect bounds = getBounds();
        float w = bounds.width();
        float bandWidth = Math.max(w * 0.6f, 1f);
        float startX = bounds.left - bandWidth + phase * (w + bandWidth * 2);

        paint.setShader(new LinearGradient(
                startX, 0, startX + bandWidth, 0,
                new int[]{transparentColor, highlightColor, transparentColor},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP));

        int save = canvas.save();
        canvas.clipPath(clipPath);
        canvas.drawRect(bounds, paint);
        canvas.restoreToCount(save);
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
