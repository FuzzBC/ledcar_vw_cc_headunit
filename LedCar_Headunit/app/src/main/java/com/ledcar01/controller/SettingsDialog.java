package com.ledcar01.controller;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Welcome mode (two explicit fire-once commands, not a toggle), RGB color
 * order, and LED pixel count. Color order and pixel count only apply on
 * "Confirm and send", bundled into a single setConfigSpi() call.
 */
public class SettingsDialog extends Dialog {

    public interface Callback {
        void onWelcomeMode(boolean on);

        void onConfigConfirmed(int pixelCount, int colorOrderId);
    }

    private static final int MIN_PIXEL_COUNT = 1;
    private static final int MAX_PIXEL_COUNT = 1024;

    private final Callback callback;
    private final SavedColorStore store;
    private final List<Integer> colorOrderIds = new ArrayList<>();

    public SettingsDialog(Context context, SavedColorStore store, Callback callback) {
        super(context);
        this.store = store;
        this.callback = callback;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_settings);

        DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(
                    (int) (metrics.widthPixels * 0.90),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        Button btnSetWelcome = findViewById(R.id.btnSetWelcome);
        Button btnCancelWelcome = findViewById(R.id.btnCancelWelcome);
        Spinner spinnerColorOrder = findViewById(R.id.spinnerColorOrder);
        EditText editLedCount = findViewById(R.id.editLedCount);
        Button btnConfirm = findViewById(R.id.btnConfirm);

        List<String> labels = new ArrayList<>();
        int selectedIndex = 0;
        int savedOrderId = store.getColorOrderId();
        int i = 0;
        for (Map.Entry<Integer, String> entry : Car01Protocol.COLOR_ORDERS.entrySet()) {
            colorOrderIds.add(entry.getKey());
            labels.add(entry.getValue());
            if (entry.getKey() == savedOrderId) {
                selectedIndex = i;
            }
            i++;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                getContext(), android.R.layout.simple_spinner_dropdown_item, labels);
        spinnerColorOrder.setAdapter(adapter);
        spinnerColorOrder.setSelection(selectedIndex);

        editLedCount.setText(String.valueOf(store.getPixelCount()));

        btnSetWelcome.setOnClickListener(v -> {
            callback.onWelcomeMode(true);
            Toast.makeText(getContext(), "Welcome mode set", Toast.LENGTH_SHORT).show();
        });
        btnCancelWelcome.setOnClickListener(v -> {
            callback.onWelcomeMode(false);
            Toast.makeText(getContext(), "Welcome mode cancelled", Toast.LENGTH_SHORT).show();
        });

        btnConfirm.setOnClickListener(v -> {
            int pixelCount;
            try {
                pixelCount = Integer.parseInt(editLedCount.getText().toString().trim());
            } catch (NumberFormatException e) {
                pixelCount = store.getPixelCount();
            }
            int clamped = Math.max(MIN_PIXEL_COUNT, Math.min(MAX_PIXEL_COUNT, pixelCount));
            if (clamped != pixelCount) {
                Toast.makeText(getContext(),
                        "LED count clamped to " + MIN_PIXEL_COUNT + "-" + MAX_PIXEL_COUNT,
                        Toast.LENGTH_SHORT).show();
            }
            pixelCount = clamped;
            int colorOrderId = colorOrderIds.get(spinnerColorOrder.getSelectedItemPosition());
            store.setPixelCount(pixelCount);
            store.setColorOrderId(colorOrderId);
            callback.onConfigConfirmed(pixelCount, colorOrderId);
            dismiss();
        });
    }
}
