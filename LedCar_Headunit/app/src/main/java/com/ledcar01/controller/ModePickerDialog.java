package com.ledcar01.controller;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Window;
import android.widget.Button;

import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Two-tab (RGB / DMX) effects browser covering all 23 + 211 known preset effects. */
public class ModePickerDialog extends Dialog {

    public interface OnModeSelectedListener {
        void onModeSelected(int id, String name, Car01Protocol.Zone zone);

        /** Cancels whatever effect mode is running and puts the zone back to its own static live color. */
        void onStaticColorSelected(Car01Protocol.Zone zone);
    }

    private final OnModeSelectedListener listener;
    private RecyclerView recyclerView;
    private Button tabRgb;
    private Button tabDmx;
    private Button btnStaticColor;
    private Car01Protocol.Zone currentZone = Car01Protocol.Zone.RGB;
    private int selectedRgbId;
    private int selectedDmxId;

    /** initialRgbId/initialDmxId let the dialog open with the last-applied mode already marked (-1 for none). */
    public ModePickerDialog(Context context, int initialRgbId, int initialDmxId, OnModeSelectedListener listener) {
        super(context);
        this.selectedRgbId = initialRgbId;
        this.selectedDmxId = initialDmxId;
        this.listener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_mode_picker);

        DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(
                    (int) (metrics.widthPixels * 0.80),
                    (int) (metrics.heightPixels * 0.65));
        }

        recyclerView = findViewById(R.id.effectRecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerView.setVerticalScrollBarEnabled(true);
        recyclerView.setScrollbarFadingEnabled(false);
        DividerItemDecoration divider = new DividerItemDecoration(getContext(), DividerItemDecoration.VERTICAL);
        divider.setDrawable(ContextCompat.getDrawable(getContext(), R.drawable.divider_mode_list));
        recyclerView.addItemDecoration(divider);

        tabRgb = findViewById(R.id.tabRgb);
        tabDmx = findViewById(R.id.tabDmx);
        tabRgb.setOnClickListener(v -> showZone(Car01Protocol.Zone.RGB));
        tabDmx.setOnClickListener(v -> showZone(Car01Protocol.Zone.DMX));

        btnStaticColor = findViewById(R.id.btnStaticColor);
        btnStaticColor.setOnClickListener(v -> {
            listener.onStaticColorSelected(currentZone);
            dismiss();
        });

        showZone(Car01Protocol.Zone.RGB);
    }

    private void showZone(Car01Protocol.Zone zone) {
        currentZone = zone;
        boolean isRgb = zone == Car01Protocol.Zone.RGB;
        int accentTextColor = ContextCompat.getColor(getContext(), R.color.bg_page);

        int outlineTextColor = ContextCompat.getColor(getContext(), R.color.btn_settings_color);
        tabRgb.setBackgroundResource(isRgb ? R.drawable.bg_pill_button_accent : R.drawable.bg_outline_settings);
        tabRgb.setTextColor(isRgb ? accentTextColor : outlineTextColor);
        tabDmx.setBackgroundResource(!isRgb ? R.drawable.bg_pill_button_accent : R.drawable.bg_outline_settings);
        tabDmx.setTextColor(!isRgb ? accentTextColor : outlineTextColor);

        List<EffectAdapter.EffectItem> items = new ArrayList<>();
        if (isRgb) {
            for (Car01Protocol.Mode mode : Car01Protocol.MODES) {
                items.add(new EffectAdapter.EffectItem(mode.id, mode.name));
            }
        } else {
            for (Map.Entry<Integer, String> entry : DmxModes.NAMES.entrySet()) {
                items.add(new EffectAdapter.EffectItem(entry.getKey(), entry.getValue()));
            }
        }
        int initialSelectedId = isRgb ? selectedRgbId : selectedDmxId;
        recyclerView.setAdapter(new EffectAdapter(items, initialSelectedId, (id, name) -> {
            if (isRgb) {
                selectedRgbId = id;
            } else {
                selectedDmxId = id;
            }
            listener.onModeSelected(id, name, zone);
        }));
    }
}
