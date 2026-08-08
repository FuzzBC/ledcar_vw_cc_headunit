package com.ledcar01.controller;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

/**
 * Precise numeric color entry: three separate R/G/B sliders (0-255), opened
 * by tapping the RGB readout under the color wheel. Sends live, debounced at
 * the same 100ms interval as every other continuous control in the app.
 * Rounded, wide card with a live combined-color preview swatch.
 */
public class RgbSliderDialog extends Dialog {

    public interface OnColorChangeListener {
        void onColorChanged(int r, int g, int b);
    }

    private final OnColorChangeListener listener;
    private final int initialR;
    private final int initialG;
    private final int initialB;

    private static final long SEND_THROTTLE_MS = 100;

    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingSend;
    private long lastSendAt = 0L;
    private boolean sendScheduled = false;

    private SeekBar seekR;
    private SeekBar seekG;
    private SeekBar seekB;
    private TextView tvR;
    private TextView tvG;
    private TextView tvB;
    private View previewSwatch;
    private TextView tvHex;

    public RgbSliderDialog(Context context, int r, int g, int b, OnColorChangeListener listener) {
        super(context);
        this.initialR = r;
        this.initialG = g;
        this.initialB = b;
        this.listener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_rgb_sliders);

        Window window = getWindow();
        if (window != null) {
            // Transparent window background so the content view's own rounded
            // corners (bg_dialog_card) show through instead of a square dialog frame.
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
            window.setLayout(
                    (int) (metrics.widthPixels * 0.92),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        seekR = findViewById(R.id.seekDialogR);
        seekG = findViewById(R.id.seekDialogG);
        seekB = findViewById(R.id.seekDialogB);
        tvR = findViewById(R.id.tvDialogRValue);
        tvG = findViewById(R.id.tvDialogGValue);
        tvB = findViewById(R.id.tvDialogBValue);
        previewSwatch = findViewById(R.id.rgbDialogPreviewSwatch);
        tvHex = findViewById(R.id.tvRgbDialogHex);
        Button btnDone = findViewById(R.id.btnRgbDone);

        seekR.setProgress(initialR);
        seekG.setProgress(initialG);
        seekB.setProgress(initialB);
        tvR.setText(String.valueOf(initialR));
        tvG.setText(String.valueOf(initialG));
        tvB.setText(String.valueOf(initialB));
        updatePreviewSwatch();

        SeekBar.OnSeekBarChangeListener onChange = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvR.setText(String.valueOf(seekR.getProgress()));
                tvG.setText(String.valueOf(seekG.getProgress()));
                tvB.setText(String.valueOf(seekB.getProgress()));
                updatePreviewSwatch();
                if (fromUser) {
                    scheduleSend();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        };
        seekR.setOnSeekBarChangeListener(onChange);
        seekG.setOnSeekBarChangeListener(onChange);
        seekB.setOnSeekBarChangeListener(onChange);

        btnDone.setOnClickListener(v -> dismiss());
    }

    private void updatePreviewSwatch() {
        int r = seekR.getProgress();
        int g = seekG.getProgress();
        int b = seekB.getProgress();
        int color = Color.rgb(r, g, b);
        previewSwatch.setBackground(buildSwatchGlow(color));
        tvHex.setText(String.format(Locale.US, "#%02X%02X%02X", r, g, b));
    }

    /** Same layered-ring glow language as the power button, tinted to the live color instead of red/green. */
    private Drawable buildSwatchGlow(int color) {
        GradientDrawable outer = new GradientDrawable();
        outer.setShape(GradientDrawable.OVAL);
        outer.setColor(Color.TRANSPARENT);
        outer.setStroke(dp(5), withAlpha(color, 80));

        GradientDrawable middle = new GradientDrawable();
        middle.setShape(GradientDrawable.OVAL);
        middle.setColor(Color.TRANSPARENT);
        middle.setStroke(dp(3), withAlpha(color, 170));

        GradientDrawable core = new GradientDrawable();
        core.setShape(GradientDrawable.OVAL);
        core.setColor(color);

        LayerDrawable layered = new LayerDrawable(new Drawable[]{outer, middle, core});
        layered.setLayerInset(1, dp(3), dp(3), dp(3), dp(3));
        layered.setLayerInset(2, dp(6), dp(6), dp(6), dp(6));
        return layered;
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private int dp(int value) {
        float density = getContext().getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    /**
     * True throttle, not a debounce: the first change in a quiet period sends
     * immediately, and further changes while still dragging coalesce into at
     * most one trailing send per {@link #SEND_THROTTLE_MS}, carrying the
     * latest values - a plain debounce would never fire until the drag
     * paused, which read as sluggish/unresponsive on real hardware.
     */
    private void scheduleSend() {
        pendingSend = () -> listener.onColorChanged(seekR.getProgress(), seekG.getProgress(), seekB.getProgress());
        long elapsed = SystemClock.uptimeMillis() - lastSendAt;
        if (elapsed >= SEND_THROTTLE_MS) {
            runPendingSend();
        } else if (!sendScheduled) {
            sendScheduled = true;
            debounceHandler.postDelayed(this::runPendingSend, SEND_THROTTLE_MS - elapsed);
        }
    }

    private void runPendingSend() {
        sendScheduled = false;
        lastSendAt = SystemClock.uptimeMillis();
        Runnable send = pendingSend;
        pendingSend = null;
        if (send != null) {
            send.run();
        }
    }

    @Override
    public void dismiss() {
        debounceHandler.removeCallbacksAndMessages(null);
        super.dismiss();
    }
}
