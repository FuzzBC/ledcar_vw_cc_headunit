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
 * <p>
 * Tapping the bubble again while this is the foreground activity minimizes
 * it instead of opening a second one - see {@link #minimizeIfVisible} and
 * {@code BroadcastMonitorService.openConsole()}. Runs in its own task
 * ({@code singleTask} + a distinct {@code taskAffinity} in the manifest)
 * so that minimize only ever affects this console, never MainActivity's
 * own task underneath it.
 */
public class MonitorConsoleActivity extends AppCompatActivity {

    /** Set while this is the resumed, foreground instance - null otherwise. Used only to answer "is the console currently open?" from the Service; not a general-purpose Activity reference. */
    private static MonitorConsoleActivity activeInstance;

    private ArrayAdapter<String> adapter;
    private SavedColorStore store;

    private final MonitorLog.Listener logListener = entry -> runOnUiThread(this::refreshList);

    /** Minimizes the console's own task if it's currently in the foreground. Returns true if it did so (caller should not also start a new instance). */
    static boolean minimizeIfVisible() {
        if (activeInstance != null) {
            activeInstance.moveTaskToBack(true);
            return true;
        }
        return false;
    }

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

    @Override
    protected void onResume() {
        super.onResume();
        activeInstance = this;
    }

    @Override
    protected void onPause() {
        if (activeInstance == this) {
            activeInstance = null;
        }
        super.onPause();
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
