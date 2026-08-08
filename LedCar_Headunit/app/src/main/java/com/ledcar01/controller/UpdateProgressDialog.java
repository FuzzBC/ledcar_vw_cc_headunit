package com.ledcar01.controller;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

/** Live download percent/size/speed while an update APK downloads, with Cancel. */
public class UpdateProgressDialog extends Dialog {

    public interface OnCancelListener {
        void onCancel();
    }

    private final String displayVersion;
    private final OnCancelListener onCancelListener;

    private TextView tvPercent;
    private TextView tvStatus;
    private ProgressBar progressBar;
    private boolean cancelled = false;

    public UpdateProgressDialog(Context context, String displayVersion, OnCancelListener onCancelListener) {
        super(context);
        this.displayVersion = displayVersion;
        this.onCancelListener = onCancelListener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_update_progress);

        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
            window.setLayout((int) (metrics.widthPixels * 0.85), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        setCancelable(false); // must go through the Cancel button, so the in-flight download is always cleaned up

        TextView tvVersion = findViewById(R.id.tvProgressVersion);
        tvPercent = findViewById(R.id.tvProgressPercent);
        tvStatus = findViewById(R.id.tvProgressStatus);
        progressBar = findViewById(R.id.progressBarUpdate);
        Button btnCancel = findViewById(R.id.btnProgressCancel);

        tvVersion.setText("Version " + displayVersion);

        btnCancel.setOnClickListener(v -> {
            cancelled = true;
            dismiss();
            if (onCancelListener != null) onCancelListener.onCancel();
        });
    }

    /** @param percent 0-100 (negative if not yet known). speedBps in bytes/sec (0 if unknown). */
    public void setProgress(int percent, long downloaded, long total, double speedBps) {
        if (cancelled) return;
        int p = Math.max(0, Math.min(100, percent));
        tvPercent.setText(p + "%");
        progressBar.setProgress(p);
        String sizeText = total > 0 ? (fmtBytes(downloaded) + " / " + fmtBytes(total)) : fmtBytes(downloaded);
        String speedText = speedBps > 0 ? (" · " + fmtBytes((long) speedBps) + "/s") : "";
        tvStatus.setText(sizeText + speedText);
    }

    public void setStatus(String text) {
        if (!cancelled) tvStatus.setText(text);
    }

    private String fmtBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0);
        return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
