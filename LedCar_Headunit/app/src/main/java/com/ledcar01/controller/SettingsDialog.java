package com.ledcar01.controller;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.widget.SwitchCompat;

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
        SwitchCompat switchBackgroundService = findViewById(R.id.switchBackgroundService);

        switchBackgroundService.setChecked(store.getBackgroundServiceEnabled());
        switchBackgroundService.setOnCheckedChangeListener((buttonView, isChecked) -> {
            store.setBackgroundServiceEnabled(isChecked);
            if (isChecked) {
                LedCarBackgroundService.start(getContext());
                Toast.makeText(getContext(), "Listening for automation commands - resumes automatically after a reboot", Toast.LENGTH_SHORT).show();
                maybeRequestBatteryExemption();
            } else {
                LedCarBackgroundService.stop(getContext());
            }
        });

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

    /**
     * Being a foreground service is the platform-standard way to survive
     * backgrounding, but plenty of OEM battery managers (common on
     * head-unit Android builds) still kill things they think are idle
     * unless the app is explicitly exempted from battery optimization.
     * This is the one such exemption that's a documented, requestable API;
     * OEM-specific "autostart manager"/"protected apps" toggles are not
     * (see TODO.md item 4) and can't be prompted for from here.
     */
    private void maybeRequestBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        Context context = getContext();
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm == null || pm.isIgnoringBatteryOptimizations(context.getPackageName())) {
            return;
        }
        Toast.makeText(context,
                "For reliable background automation, also allow this app to ignore battery optimizations on the next screen",
                Toast.LENGTH_LONG).show();
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + context.getPackageName()));
        context.startActivity(intent);
    }
}
