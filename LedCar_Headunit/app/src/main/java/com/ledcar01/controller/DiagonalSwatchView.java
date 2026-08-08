package com.ledcar01.controller;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * Saved-color swatch ("egg"): a stand-up pill (taller than wide, so the
 * rounded caps land on top and bottom, not the sides) matching the app's own
 * button/zone-selector language, split by a clean corner-to-corner diagonal
 * from bottom-left to top-right - DMX color to the left, RGB color to the
 * right.
 */
public class DiagonalSwatchView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clipPath = new Path();
    private final Path splitPath = new Path();
    private int dmxColor = Color.RED;
    private int rgbColor = Color.BLUE;

    public DiagonalSwatchView(Context context) {
        this(context, null);
    }

    public DiagonalSwatchView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setStyle(Paint.Style.FILL);
    }

    public void setColors(int dmxColor, int rgbColor) {
        this.dmxColor = dmxColor;
        this.rgbColor = rgbColor;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuildPaths();
    }

    private void rebuildPaths() {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        clipPath.reset();
        // The rounded-cap radius is capped by the SHORTER side - for a
        // stand-up pill (taller than wide) that's the width, giving rounded
        // top/bottom caps and straight sides, not the other way around.
        float r = Math.min(w, h) / 2f;
        clipPath.addRoundRect(0, 0, w, h, r, r, Path.Direction.CW);

        // Clean corner-to-corner diagonal, bottom-left to top-right. RGB
        // fills the upper-right triangle, DMX fills everything else.
        splitPath.reset();
        splitPath.moveTo(w, 0);
        splitPath.lineTo(w, h);
        splitPath.lineTo(0, h);
        splitPath.close();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        canvas.clipPath(clipPath);
        paint.setColor(dmxColor);
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        paint.setColor(rgbColor);
        canvas.drawPath(splitPath, paint);
        canvas.restore();
    }
}
