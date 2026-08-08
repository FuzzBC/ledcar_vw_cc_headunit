package com.ledcar01.controller;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Window;
import android.widget.Button;
import android.widget.TextView;

/** Small reusable rounded confirm/cancel popup, styled to match the app's other dialogs. */
public class ConfirmDialog extends Dialog {

    public interface OnConfirmListener {
        void onConfirmed();
    }

    private final String title;
    private final String message;
    private final OnConfirmListener onConfirm;

    public ConfirmDialog(Context context, String title, String message, OnConfirmListener onConfirm) {
        super(context);
        this.title = title;
        this.message = message;
        this.onConfirm = onConfirm;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_confirm);

        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
            window.setLayout(
                    (int) (metrics.widthPixels * 0.85),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        TextView tvTitle = findViewById(R.id.tvConfirmTitle);
        TextView tvMessage = findViewById(R.id.tvConfirmMessage);
        Button btnCancel = findViewById(R.id.btnConfirmCancel);
        Button btnAccept = findViewById(R.id.btnConfirmAccept);

        tvTitle.setText(title);
        tvMessage.setText(message);

        btnCancel.setOnClickListener(v -> dismiss());
        btnAccept.setOnClickListener(v -> {
            onConfirm.onConfirmed();
            dismiss();
        });
    }
}
