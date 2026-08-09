package com.ledcar01.controller;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Small live-animated swatch for one row in the Mode picker - actually
 * plays the effect's real pattern (jump/gradient/breathe/flash) instead of
 * the flat static gradient rectangle this used to be. Self-drives its own
 * animation loop via {@link #postOnAnimation} while attached to a window,
 * and stops the moment it's detached (RecyclerView recycles rows scrolled
 * off-screen - see EffectAdapter#onViewRecycled) so an off-screen list of
 * up to 211 DMX rows never has more than the ~10-15 currently visible ones
 * actually animating.
 */
public class EffectPreviewView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private EffectVisual effect;
    private long startTime;
    private boolean running;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            invalidate();
            postOnAnimation(this);
        }
    };

    public EffectPreviewView(Context context) {
        super(context);
    }

    public EffectPreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setEffect(EffectVisual effect) {
        this.effect = effect;
        this.startTime = System.currentTimeMillis();
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = true;
        postOnAnimation(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (effect == null || w == 0 || h == 0) {
            return;
        }
        float radius = h * 0.28f;
        rect.set(0, 0, w, h);
        int color = currentColor();
        paint.setColor(color);
        canvas.drawRoundRect(rect, radius, radius, paint);
    }

    private int currentColor() {
        long elapsed = System.currentTimeMillis() - startTime;
        int[] colors = effect.colors;
        int step = effect.stepMs;
        switch (effect.archetype) {
            case JUMP: {
                int idx = (int) ((elapsed / step) % colors.length);
                return colors[idx];
            }
            case GRADIENT: {
                long cycle = (long) step * colors.length;
                float pos = (elapsed % cycle) / (float) step;
                int idx = (int) pos;
                int next = (idx + 1) % colors.length;
                float t = pos - idx;
                return lerp(colors[idx], colors[next % colors.length], t);
            }
            case BREATHE: {
                float t = (elapsed % step) / (float) step;
                float brightness = 0.2f + 0.8f * (0.5f - 0.5f * (float) Math.cos(t * Math.PI * 2));
                return scale(colors[0], brightness);
            }
            case BREATHE_MULTI: {
                long cycle = (long) step * colors.length;
                float pos = (elapsed % cycle) / (float) step;
                int idx = (int) pos;
                float t = pos - idx;
                float brightness = 0.2f + 0.8f * (0.5f - 0.5f * (float) Math.cos(t * Math.PI * 2));
                return scale(colors[idx % colors.length], brightness);
            }
            case FLASH: {
                long onMs = step;
                long offMs = (long) (step * 0.7f);
                long cycle = (onMs + offMs) * colors.length;
                long pos = elapsed % cycle;
                int idx = (int) (pos / (onMs + offMs));
                long phase = pos % (onMs + offMs);
                boolean on = phase < onMs;
                return on ? colors[idx % colors.length] : Color.parseColor("#131C39");
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
}
