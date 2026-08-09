package com.ledcar01.controller;

import android.content.Intent;
import android.os.Bundle;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * In-memory, process-wide log of broadcast Intents BroadcastMonitorService
 * has seen, plus whoever's currently watching it live (the bubble's unread
 * badge, MonitorConsoleActivity while it's open). Deliberately not
 * persisted to disk - this is a live debugging aid for one investigation
 * session, not a permanent record, and broadcasts can carry Bundle extras
 * whose contents shouldn't outlive the app process.
 */
public final class MonitorLog {

    public static final class Entry {
        public final long timestampMs;
        public final String action;
        /**
         * Best-effort only - Android does not expose a broadcast's sender
         * to receivers as a rule. Populated when the Intent itself carries
         * a package (some senders set one) or was explicitly targeted at
         * one of this app's own components (e.g. CommandReceiver via
         * Tasker); otherwise labelled generically.
         */
        public final String source;
        public final String extrasText;

        Entry(long timestampMs, String action, String source, String extrasText) {
            this.timestampMs = timestampMs;
            this.action = action;
            this.source = source;
            this.extrasText = extrasText;
        }

        public String formattedTime() {
            return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date(timestampMs));
        }
    }

    public interface Listener {
        void onEntryAdded(Entry entry);
    }

    private static final int MAX_ENTRIES = 300;
    private static final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private static final List<Listener> listeners = new ArrayList<>();

    private MonitorLog() {
    }

    public static synchronized void record(Intent intent) {
        String action = intent.getAction() != null ? intent.getAction() : "(no action)";
        String source = resolveSource(intent);
        Entry entry = new Entry(System.currentTimeMillis(), action, source, formatExtras(intent.getExtras()));
        entries.addLast(entry);
        while (entries.size() > MAX_ENTRIES) {
            entries.removeFirst();
        }
        for (Listener l : new ArrayList<>(listeners)) {
            l.onEntryAdded(entry);
        }
    }

    private static String resolveSource(Intent intent) {
        if (intent.getPackage() != null) {
            return intent.getPackage();
        }
        if (intent.getComponent() != null) {
            return intent.getComponent().getPackageName();
        }
        // Android doesn't tell a receiver who broadcast an implicit Intent -
        // this is the honest answer for the common case, not a bug.
        return "(sender not exposed by Android)";
    }

    private static String formatExtras(Bundle extras) {
        if (extras == null || extras.isEmpty()) {
            return "(no extras)";
        }
        StringBuilder sb = new StringBuilder();
        for (String key : extras.keySet()) {
            Object value;
            try {
                value = extras.get(key);
            } catch (RuntimeException e) {
                value = "<unreadable>";
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(key).append('=').append(value);
        }
        return sb.toString();
    }

    public static synchronized List<Entry> snapshot() {
        return new ArrayList<>(entries);
    }

    public static synchronized void clear() {
        entries.clear();
    }

    public static synchronized void addListener(Listener listener) {
        listeners.add(listener);
    }

    public static synchronized void removeListener(Listener listener) {
        listeners.remove(listener);
    }
}
