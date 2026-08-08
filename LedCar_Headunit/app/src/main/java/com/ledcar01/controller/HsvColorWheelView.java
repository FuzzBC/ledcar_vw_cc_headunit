package com.ledcar01.controller;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.Locale;

/**
 * Canvas-drawn HSV color wheel (hue = angle, saturation = radius, fixed
 * value = 1.0). The gradient is generated once per size change and cached as
 * a bitmap; onDraw only blits it plus a small marker/preview overlay, so
 * dragging stays smooth regardless of view size. While a finger is down, a
 * floating RGB readout is drawn directly on the canvas near the touch point.
 * Holding a touch in place for {@link #LONG_PRESS_MS} (without dragging past
 * touch slop) opens the precise numeric color popup via
 * {@link OnColorLongPressListener}.
 */
public class HsvColorWheelView extends View {

    public interface OnColorChangeListener {
        void onColorChanged(int r, int g, int b, boolean fromUser);
    }

    public interface OnColorLongPressListener {
        void onColorLongPress(int r, int g, int b);
    }

    /** Fired when a drag ends (finger lifted or cancelled), not on every color change. */
    public interface OnTouchEndListener {
        void onTouchEnd();
    }

    private static final long LONG_PRESS_MS = 1000;

    private Bitmap wheelBitmap;
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint previewSwatchPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint previewTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint previewBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float centerX, centerY, radius;
    private float hue;
    private float sat;
    private boolean touching;
    private float touchX, touchY;
    private float downX, downY;
    private final int touchSlop;
    private final Runnable longPressRunnable = this::triggerLongPress;

    private OnColorChangeListener listener;
    private OnColorLongPressListener longPressListener;
    private OnTouchEndListener touchEndListener;

    public HsvColorWheelView(Context context) {
        this(context, null);
    }

    public HsvColorWheelView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public HsvColorWheelView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        markerPaint.setStyle(Paint.Style.STROKE);
        markerPaint.setStrokeWidth(dp(3));
        markerPaint.setColor(Color.WHITE);

        previewTextPaint.setColor(Color.WHITE);
        previewTextPaint.setTextSize(sp(13));
        previewTextPaint.setTextAlign(Paint.Align.LEFT);

        previewBgPaint.setColor(Color.parseColor("#CC000000"));

        setWillNotDraw(false);
    }

    public void setOnColorChangeListener(OnColorChangeListener listener) {
        this.listener = listener;
    }

    public void setOnColorLongPressListener(OnColorLongPressListener listener) {
        this.longPressListener = listener;
    }

    public void setOnTouchEndListener(OnTouchEndListener listener) {
        this.touchEndListener = listener;
    }

    /** Sets the wheel's selection to match an externally-known color, without notifying the listener. */
    public void setColor(int r, int g, int b) {
        float[] hsv = new float[3];
        Color.RGBToHSV(r, g, b, hsv);
        hue = hsv[0];
        sat = hsv[1];
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        int size = (w > 0 && h > 0) ? Math.min(w, h) : Math.max(w, h);
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        centerX = w / 2f;
        centerY = h / 2f;
        radius = Math.min(w, h) / 2f - dp(4);
        wheelBitmap = generateWheelBitmap((int) (radius * 2));
    }

    private Bitmap generateWheelBitmap(int diameter) {
        if (diameter <= 0) {
            return null;
        }
        int[] pixels = new int[diameter * diameter];
        float r = diameter / 2f;
        float[] hsv = {0f, 0f, 1f};
        for (int y = 0; y < diameter; y++) {
            float dy = y - r;
            for (int x = 0; x < diameter; x++) {
                float dx = x - r;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                int index = y * diameter + x;
                if (dist <= r) {
                    float angle = (float) Math.toDegrees(Math.atan2(dy, dx));
                    if (angle < 0) {
                        angle += 360f;
                    }
                    hsv[0] = angle;
                    hsv[1] = Math.min(1f, dist / r);
                    pixels[index] = Color.HSVToColor(hsv);
                } else {
                    pixels[index] = Color.TRANSPARENT;
                }
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(diameter, diameter, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, diameter, 0, 0, diameter, diameter);
        return bitmap;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (wheelBitmap != null) {
            canvas.drawBitmap(wheelBitmap, centerX - radius, centerY - radius, null);
        }

        double angleRad = Math.toRadians(hue);
        float markerDist = sat * radius;
        float markerX = centerX + (float) (Math.cos(angleRad) * markerDist);
        float markerY = centerY + (float) (Math.sin(angleRad) * markerDist);
        canvas.drawCircle(markerX, markerY, dp(9), markerPaint);

        if (touching) {
            drawPreview(canvas);
        }
    }

    private void drawPreview(Canvas canvas) {
        int color = currentColor();
        String label = String.format(Locale.US, "R %d  G %d  B %d",
                Color.red(color), Color.green(color), Color.blue(color));
        float boxWidth = previewTextPaint.measureText(label) + dp(40);
        float boxHeight = dp(36);
        float boxLeft = clamp(touchX - boxWidth / 2, 0, getWidth() - boxWidth);
        float boxTop = clamp(touchY - dp(56), 0, getHeight() - boxHeight);
        RectF box = new RectF(boxLeft, boxTop, boxLeft + boxWidth, boxTop + boxHeight);

        canvas.drawRoundRect(box, dp(8), dp(8), previewBgPaint);
        previewSwatchPaint.setColor(color);
        canvas.drawCircle(box.left + dp(18), box.centerY(), dp(8), previewSwatchPaint);
        canvas.drawText(label, box.left + dp(34), box.centerY() + dp(4), previewTextPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            // setEnabled(false) alone does nothing for a custom onTouchEvent
            // override - without this check the wheel stays interactive even
            // while visually disabled (e.g. no device connected).
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touching = true;
                touchX = event.getX();
                touchY = event.getY();
                downX = touchX;
                downY = touchY;
                updateFromTouch(touchX, touchY);
                removeCallbacks(longPressRunnable);
                postDelayed(longPressRunnable, LONG_PRESS_MS);
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                touchX = event.getX();
                touchY = event.getY();
                if (Math.abs(touchX - downX) > touchSlop || Math.abs(touchY - downY) > touchSlop) {
                    removeCallbacks(longPressRunnable);
                }
                updateFromTouch(touchX, touchY);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touching = false;
                removeCallbacks(longPressRunnable);
                invalidate();
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                if (touchEndListener != null) {
                    touchEndListener.onTouchEnd();
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void triggerLongPress() {
        // End our own gesture tracking right now, before opening the dialog -
        // otherwise the still-active touch stream on this view (and the
        // disallow-intercept request held on the parent ScrollView) can delay
        // the new dialog window from actually appearing until the finger lifts.
        touching = false;
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(false);
        }
        invalidate();

        if (longPressListener != null) {
            int color = currentColor();
            longPressListener.onColorLongPress(Color.red(color), Color.green(color), Color.blue(color));
        }
    }

    private void updateFromTouch(float x, float y) {
        float dx = x - centerX;
        float dy = y - centerY;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        float angle = (float) Math.toDegrees(Math.atan2(dy, dx));
        if (angle < 0) {
            angle += 360f;
        }
        hue = angle;
        // Clamp rather than ignore touches outside the circle, so there's no dead zone at the edge.
        sat = radius > 0 ? Math.min(1f, dist / radius) : 0f;
        invalidate();
        if (listener != null) {
            int color = currentColor();
            listener.onColorChanged(Color.red(color), Color.green(color), Color.blue(color), true);
        }
    }

    private int currentColor() {
        return Color.HSVToColor(new float[]{hue, sat, 1f});
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }

    private float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
