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
import android.widget.TextView;

/**
 * "A new version is ready" prompt - Later/Update buttons, styled to match
 * the app's other header-band dialogs. Shows the release's changelog body
 * (from GitHub) when present, or a generic line otherwise.
 */
public class UpdateAvailableDialog extends Dialog {

    public interface OnUpdateListener {
        void onUpdate();
    }

    private final String displayVersion;
    private final String releaseNotes;
    private final OnUpdateListener onUpdate;

    public UpdateAvailableDialog(Context context, String displayVersion, String releaseNotes, OnUpdateListener onUpdate) {
        super(context);
        this.displayVersion = displayVersion;
        this.releaseNotes = releaseNotes;
        this.onUpdate = onUpdate;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_update_available);

        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
            window.setLayout((int) (metrics.widthPixels * 0.85), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        setCancelable(false); // an update prompt shouldn't dismiss on a stray back-press/tap

        TextView tvVersion = findViewById(R.id.tvUpdateVersion);
        TextView tvNotes = findViewById(R.id.tvUpdateNotes);
        Button btnLater = findViewById(R.id.btnUpdateLater);
        Button btnUpdate = findViewById(R.id.btnUpdateNow);

        tvVersion.setText("Version " + displayVersion);

        boolean hasNotes = releaseNotes != null && !releaseNotes.trim().isEmpty();
        tvNotes.setText(hasNotes ? releaseNotes.trim() : "A new version is ready to download.");

        btnLater.setOnClickListener(v -> dismiss());
        btnUpdate.setOnClickListener(v -> {
            dismiss();
            if (onUpdate != null) onUpdate.onUpdate();
        });
    }
}
