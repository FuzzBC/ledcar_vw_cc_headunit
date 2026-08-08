package com.ledcar01.controller;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Window;
import android.widget.Button;

/** Long-press popup for a saved color egg: replace it with the current live color, or remove it. */
public class EggActionDialog extends Dialog {

    public interface Callback {
        void onUpdate();

        void onRemove();
    }

    private final Callback callback;

    public EggActionDialog(Context context, Callback callback) {
        super(context);
        this.callback = callback;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_egg_action);

        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
            window.setLayout(
                    (int) (metrics.widthPixels * 0.85),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        Button btnCancel = findViewById(R.id.btnEggActionCancel);
        Button btnUpdate = findViewById(R.id.btnEggActionUpdate);
        Button btnRemove = findViewById(R.id.btnEggActionRemove);

        btnCancel.setOnClickListener(v -> dismiss());
        btnUpdate.setOnClickListener(v -> {
            callback.onUpdate();
            dismiss();
        });
        btnRemove.setOnClickListener(v -> {
            callback.onRemove();
            dismiss();
        });
    }
}
