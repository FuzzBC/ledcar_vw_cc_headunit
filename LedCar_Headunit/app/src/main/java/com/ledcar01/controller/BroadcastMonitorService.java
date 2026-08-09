package com.ledcar01.controller;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

/**
 * Floating "Monitor" bubble + the diagnostic BroadcastReceiver behind it -
 * see the button next to Settings in MainActivity. Head-unit build only.
 * <p>
 * This is a reverse-engineering aid, not a real integration: Android does
 * not let a normal app eavesdrop on <i>every</i> broadcast on the system
 * (there is no wildcard receiver), and it does not expose a broadcast's
 * sender identity to receivers as a rule. What this can actually do is
 * listen for a curated list of common system broadcasts (battery, screen,
 * Bluetooth, audio-routing, connectivity) plus this app's own
 * {@link CommandReceiver} traffic, plus whatever extra action strings you
 * add after finding them for real - e.g. by decompiling a head unit's
 * launcher APK with jadx and searching for {@code sendBroadcast(} /
 * {@code <intent-filter>} entries. See MonitorConsoleActivity for adding
 * a custom action once you have one to try.
 * <p>
 * Tap the bubble to open {@link MonitorConsoleActivity}; drag it anywhere
 * on screen otherwise. Runs as a foreground service so it survives you
 * switching away to Junsun's own launcher (or Agama, or anything else) to
 * go trigger whatever you're trying to catch.
 */
public class BroadcastMonitorService extends Service {

    private static final String CHANNEL_ID = "ledcar_monitor";
    private static final int NOTIFICATION_ID = 2001;
    /** How far a touch has to move before it counts as a drag instead of a tap-to-open. */
    private static final int TAP_SLOP_PX = 12;

    private WindowManager windowManager;
    private View bubbleView;
    private TextView badgeView;
    private WindowManager.LayoutParams bubbleParams;
    private int unreadCount;

    private BroadcastReceiver monitorReceiver;

    private final MonitorLog.Listener logListener = entry -> {
        unreadCount++;
        updateBadge();
    };

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
        MonitorLog.addListener(logListener);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification());
        if (bubbleView == null) {
            addBubble();
        }
        if (ACTION_REFRESH.equals(action) && monitorReceiver != null) {
            unregisterReceiver(monitorReceiver);
            monitorReceiver = null;
        }
        registerMonitorReceiver();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        MonitorLog.removeListener(logListener);
        if (monitorReceiver != null) {
            unregisterReceiver(monitorReceiver);
            monitorReceiver = null;
        }
        removeBubble();
        super.onDestroy();
    }

    // -- The curated watch list --

    /**
     * A dynamically-registered receiver can only match actions it's told
     * about - there's no way to ask Android for "everything". This starter
     * set covers the common broadcasts most likely to matter for a car
     * head unit (power/screen/audio-routing/Bluetooth state); anything
     * beyond it needs a real action string, added via
     * SavedColorStore.addCustomWatchedAction() - see MonitorConsoleActivity.
     */
    private void registerMonitorReceiver() {
        if (monitorReceiver != null) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(CommandReceiver.ACTION_COMMAND);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_HEADSET_PLUG);
        filter.addAction(Intent.ACTION_MEDIA_BUTTON);
        filter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);

        SavedColorStore store = new SavedColorStore(this);
        for (String action : store.getCustomWatchedActions()) {
            filter.addAction(action);
        }

        monitorReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                MonitorLog.record(intent);
            }
        };
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ? Context.RECEIVER_EXPORTED : 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(monitorReceiver, filter, flags);
        } else {
            registerReceiver(monitorReceiver, filter);
        }
    }

    /** Called by MonitorConsoleActivity after adding a custom action, so it takes effect without restarting the whole service. */
    public static void refreshWatchList(Context context) {
        Intent intent = new Intent(context, BroadcastMonitorService.class).setAction(ACTION_REFRESH);
        startCompat(context, intent);
    }

    private static final String ACTION_REFRESH = "com.ledcar01.controller.headunit.action.MONITOR_REFRESH";

    // -- Floating bubble --

    @SuppressWarnings("ClickableViewAccessibility")
    private void addBubble() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) {
            return;
        }
        bubbleView = View.inflate(this, R.layout.overlay_bubble, null);
        badgeView = bubbleView.findViewById(R.id.bubbleBadge);
        updateBadge();

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        bubbleParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = 24;
        bubbleParams.y = 200;

        bubbleView.setOnTouchListener(new View.OnTouchListener() {
            private float startTouchX, startTouchY;
            private int startX, startY;
            private boolean dragged;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startTouchX = event.getRawX();
                        startTouchY = event.getRawY();
                        startX = bubbleParams.x;
                        startY = bubbleParams.y;
                        dragged = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = event.getRawX() - startTouchX;
                        float dy = event.getRawY() - startTouchY;
                        if (Math.abs(dx) > TAP_SLOP_PX || Math.abs(dy) > TAP_SLOP_PX) {
                            dragged = true;
                        }
                        bubbleParams.x = startX + (int) dx;
                        bubbleParams.y = startY + (int) dy;
                        windowManager.updateViewLayout(bubbleView, bubbleParams);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (!dragged) {
                            openConsole();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });

        windowManager.addView(bubbleView, bubbleParams);
    }

    private void removeBubble() {
        if (windowManager != null && bubbleView != null) {
            windowManager.removeView(bubbleView);
        }
        bubbleView = null;
        badgeView = null;
    }

    private void updateBadge() {
        if (badgeView == null) {
            return;
        }
        if (unreadCount <= 0) {
            badgeView.setVisibility(View.GONE);
        } else {
            badgeView.setVisibility(View.VISIBLE);
            badgeView.setText(unreadCount > 99 ? "99+" : String.valueOf(unreadCount));
        }
    }

    private void openConsole() {
        unreadCount = 0;
        updateBadge();
        Intent intent = new Intent(this, MonitorConsoleActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    // -- Foreground notification --

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "Broadcast monitor", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("Floating bubble logging broadcast Intents for diagnostics");
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Intent openApp = new Intent(this, MonitorConsoleActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE : 0));

        Intent stopIntent = new Intent(this, BroadcastMonitorService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 0, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE : 0));

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Broadcast Monitor running")
                .setContentText("Tap the floating bubble to view captured messages")
                .setSmallIcon(R.drawable.ic_monitor)
                .setContentIntent(contentIntent)
                .addAction(0, "Stop", stopPendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    // -- Static helpers --

    public static final String ACTION_STOP = "com.ledcar01.controller.headunit.action.MONITOR_STOP";

    public static void start(Context context) {
        startCompat(context, new Intent(context, BroadcastMonitorService.class));
    }

    public static void stop(Context context) {
        context.startService(new Intent(context, BroadcastMonitorService.class).setAction(ACTION_STOP));
    }

    private static void startCompat(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }
}
