package com.ledcar01.controller;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Selection border for the zone pill, shaped to a half-pill (rounded outer
 * end, square inner edge) or a full pill using the same per-corner radii
 * format as GradientDrawable.setCornerRadii. Two modes:
 *  - Single zone selected: dashed "marching ants", RGB and DMX running in
 *    opposite directions (see setReversed) so the two read as distinct.
 *  - Both zones selected: a two-color band (the live RGB and DMX colors)
 *    that sweeps back and forth around the pill, Knight Rider-style, with a
 *    soft glow that's allowed to bleed a little past the pill's true edge -
 *    the view itself is sized bigger than the pill in this mode (see
 *    MainActivity.applyZoneUi's ZONE_GLOW_MARGIN_DP) specifically to give
 *    that glow real canvas to spread into.
 */
public class MarchingAntsView extends View {

    private static final float DASH_ON_DP = 7f;
    private static final float DASH_OFF_DP = 5f;
    private static final float DASH_STROKE_DP = 2f;
    private static final long DASH_CYCLE_MS = 700;
    private static final long SCAN_CYCLE_MS = 2600;
    private static final float SCAN_STROKE_DP = 4f;
    private static final float SCAN_GLOW_STROKE_DP = 12f;
    /**
     * How far the path is inset from this view's own bounds while breathing.
     * Must match MainActivity's ZONE_GLOW_MARGIN_DP - that's how much extra
     * width/height the view is given around the pill in "both zones" mode,
     * and insetting the path back by the same amount recovers the pill's
     * true edge for the crisp stroke, leaving the surrounding margin free
     * for the blurred glow pass to spread into.
     */
    private static final float GLOW_MARGIN_DP = 8f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Matrix shaderMatrix = new Matrix();
    private float[] radii = new float[8];
    private ValueAnimator animator;
    private boolean reversed = false;
    private boolean breathing = false;
    private int rgbColor = Color.RED;
    private int dmxColor = Color.BLUE;
    private SweepGradient scanShader;
    private float scanProgress = 0f; // 0..1, ping-ponged into a triangle wave in onDraw
    private float pivotX;
    private float pivotY;

    public MarchingAntsView(Context context) {
        this(context, null);
    }

    public MarchingAntsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(DASH_STROKE_DP));
        paint.setColor(Color.WHITE);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(dp(SCAN_GLOW_STROKE_DP));
        glowPaint.setColor(Color.WHITE);
        setWillNotDraw(false);
    }

    /** DMX runs the marching ants the opposite way around the pill from RGB, so the two read as distinct. */
    public void setReversed(boolean reversed) {
        this.reversed = reversed;
    }

    /** Both zones active: swap the dashed marching border for the bounce-scanner effect. */
    public void setBreathingMode(boolean breathing) {
        if (this.breathing == breathing) {
            return;
        }
        this.breathing = breathing;
        setLayerType(breathing ? LAYER_TYPE_SOFTWARE : LAYER_TYPE_NONE, null);
        rebuildPath();
        invalidate();
        if (isAttachedToWindow()) {
            restartAnimation();
        }
    }

    /** Live per-zone colors, so the scanner band matches what's actually selected. */
    public void setZoneColors(int rgbColor, int dmxColor) {
        this.rgbColor = rgbColor;
        this.dmxColor = dmxColor;
        if (breathing) {
            rebuildScanShader();
            invalidate();
        }
    }

    public void setCornerRadii(float[] radii) {
        this.radii = radii;
        rebuildPath();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuildPath();
    }

    private void rebuildPath() {
        path.reset();
        float inset = dp(breathing ? GLOW_MARGIN_DP : 1f);
        RectF rect = new RectF(inset, inset, getWidth() - inset, getHeight() - inset);
        if (rect.width() <= 0 || rect.height() <= 0) {
            return;
        }
        if (breathing) {
            // The scanner only ever applies to the full (both-zones) pill.
            // Insetting by GLOW_MARGIN_DP recovers the pill's true edge
            // (the view itself is sized GLOW_MARGIN_DP bigger on every side
            // in this mode - see the class doc) - recompute the radius from
            // this inset rect itself so it stays a true stadium shape
            // instead of reusing the outer radius (sized for the un-inset
            // view) which would now be too large for the smaller rect.
            float r = rect.height() / 2f;
            path.addRoundRect(rect, r, r, Path.Direction.CW);
            pivotX = rect.centerX();
            pivotY = rect.centerY();
            rebuildScanShader();
        } else {
            path.addRoundRect(rect, radii, Path.Direction.CW);
        }
    }

    private void rebuildScanShader() {
        int[] colors = {Color.TRANSPARENT, rgbColor, dmxColor, Color.TRANSPARENT, Color.TRANSPARENT};
        float[] positions = {0f, 0.18f, 0.30f, 0.42f, 1f};
        scanShader = new SweepGradient(pivotX, pivotY, colors, positions);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        restartAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopAnimating();
    }

    private void restartAnimation() {
        stopAnimating();
        if (breathing) {
            startScanning();
        } else {
            startDashing();
        }
    }

    private void startDashing() {
        paint.setAlpha(255);
        paint.clearShadowLayer();
        paint.setShader(null); // clear any shader left over from "both zones" mode
        paint.setStrokeWidth(dp(DASH_STROKE_DP));
        float dashOn = dp(DASH_ON_DP);
        float dashOff = dp(DASH_OFF_DP);
        animator = ValueAnimator.ofFloat(0f, dashOn + dashOff);
        animator.setDuration(DASH_CYCLE_MS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            float phase = (float) a.getAnimatedValue();
            paint.setPathEffect(new DashPathEffect(new float[]{dashOn, dashOff}, reversed ? -phase : phase));
            invalidate();
        });
        animator.start();
    }

    private void startScanning() {
        paint.setPathEffect(null);
        paint.setAlpha(255);
        paint.setStrokeWidth(dp(SCAN_STROKE_DP));
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(SCAN_CYCLE_MS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            scanProgress = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private void stopAnimating() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (breathing && scanShader != null) {
            // Ping-pong the sweep back and forth (triangle wave) instead of
            // spinning one direction continuously - reads as scanning, not rotating.
            float triangle = scanProgress < 0.5f ? scanProgress * 2f : (1f - scanProgress) * 2f;
            shaderMatrix.setRotate(triangle * 360f, pivotX, pivotY);
            scanShader.setLocalMatrix(shaderMatrix);
            glowPaint.setShader(scanShader);
            glowPaint.setAlpha(210);
            glowPaint.setMaskFilter(new BlurMaskFilter(dp(8), BlurMaskFilter.Blur.NORMAL));
            canvas.drawPath(path, glowPaint);
            paint.setShader(scanShader);
        }
        canvas.drawPath(path, paint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
