package com.ledcar01.controller;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * Live view onto {@link MonitorLog}, opened by tapping the floating bubble
 * (or the notification) that {@link BroadcastMonitorService} shows while
 * running. See that service's class doc for what this can and can't
 * actually see - this is a reverse-engineering aid, not a universal
 * broadcast sniffer.
 */
public class MonitorConsoleActivity extends AppCompatActivity {

    private ArrayAdapter<String> adapter;
    private SavedColorStore store;

    private final MonitorLog.Listener logListener = entry -> runOnUiThread(this::refreshList);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_monitor_console);
        store = new SavedColorStore(this);

        ListView listView = findViewById(R.id.listMonitorEntries);
        adapter = new ArrayAdapter<>(this, R.layout.item_monitor_entry, new ArrayList<>());
        listView.setAdapter(adapter);

        findViewById(R.id.btnMonitorClose).setOnClickListener(v -> finish());

        findViewById(R.id.btnClearLog).setOnClickListener(v -> {
            MonitorLog.clear();
            refreshList();
        });

        EditText editCustomAction = findViewById(R.id.editCustomAction);
        findViewById(R.id.btnWatchAction).setOnClickListener(v -> {
            String action = editCustomAction.getText().toString().trim();
            if (TextUtils.isEmpty(action)) {
                return;
            }
            store.addCustomWatchedAction(action);
            BroadcastMonitorService.refreshWatchList(this);
            editCustomAction.setText("");
            updateWatchedActionsLabel();
            Toast.makeText(this, "Now watching: " + action, Toast.LENGTH_SHORT).show();
        });

        updateWatchedActionsLabel();
        refreshList();
    }

    @Override
    protected void onStart() {
        super.onStart();
        MonitorLog.addListener(logListener);
    }

    @Override
    protected void onStop() {
        MonitorLog.removeListener(logListener);
        super.onStop();
    }

    private void updateWatchedActionsLabel() {
        TextView label = findViewById(R.id.tvWatchedActions);
        List<String> custom = store.getCustomWatchedActions();
        String text = "Built-in: battery, screen, headset, audio-route, Bluetooth, connectivity, this app's own commands.";
        if (!custom.isEmpty()) {
            text += "\nCustom: " + TextUtils.join(", ", custom);
        }
        label.setText(text);
    }

    private void refreshList() {
        List<MonitorLog.Entry> entries = MonitorLog.snapshot();
        adapter.clear();
        // Newest first - the thing you just triggered is what you're looking for.
        for (int i = entries.size() - 1; i >= 0; i--) {
            MonitorLog.Entry e = entries.get(i);
            adapter.add(e.formattedTime() + "  " + e.action
                    + "\nsource: " + e.source
                    + "\nextras: " + e.extrasText);
        }
    }
}
