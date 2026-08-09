package com.ledcar01.controller;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

/**
 * TODO.md item 4: keep-alive for background automation. {@link BleDeviceManager}
 * is already an application-scoped singleton (survives Activity recreation,
 * never torn down by MainActivity.onDestroy()) - the only thing actually
 * missing for headless automation to work was something to keep the
 * *process* itself alive once MainActivity is gone, and a scan/connect
 * kicked off without an Activity in the picture at all. That's all this
 * class does; every actual BLE/protocol call still goes through the same
 * BleDeviceManager + Car01Protocol the UI uses.
 * <p>
 * Started by {@link CommandReceiver} the moment an external automation
 * command arrives (Tasker/Automate/IFTTT-via-Tasker, see that class), or
 * kept running continuously via Settings -> "Background automation" (see
 * SettingsDialog) for people who want the connection kept warm even before
 * the first command shows up, since a cold BLE scan+connect can take a few
 * seconds and a "sunset" automation firing once a day shouldn't have to eat
 * that latency every time. {@link BootReceiver} resumes it right after
 * device boot if that toggle was left on, so it doesn't need MainActivity
 * to ever be opened by a human to start working again.
 * <p>
 * Deliberately minimal: no work queue, no retry/backoff policy beyond what
 * BleDeviceManager already does for reconnects. If a command arrives before
 * any device is connected, {@link #applyCommand} still queues nothing extra
 * - BleDeviceManager.broadcastCommand() itself only writes to devices that
 * are actually connected, so a command that arrives before the scan finds
 * anything is simply not delivered. That matches how the on-screen app
 * already behaves (see MainActivity.applyColor's own connected-count
 * check) rather than inventing a new "pending command" concept.
 */
public class LedCarBackgroundService extends Service {

    private static final String CHANNEL_ID = "ledcar_background";
    private static final int NOTIFICATION_ID = 1001;

    /** How long to hold the service alive with no active BLE connection before giving up and stopping itself. */
    private static final long IDLE_TIMEOUT_MS = 5 * 60 * 1000L;

    public static final String ACTION_APPLY_COMMAND = "com.ledcar01.controller.headunit.action.APPLY_COMMAND";
    public static final String ACTION_KEEP_ALIVE = "com.ledcar01.controller.headunit.action.KEEP_ALIVE";
    public static final String ACTION_STOP = "com.ledcar01.controller.headunit.action.STOP";

    /** Extra key under which CommandReceiver stashes the original external Intent's extras Bundle. */
    public static final String EXTRA_COMMAND_BUNDLE = "command_bundle";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable idleStop = this::stopSelf;

    private BleDeviceManager bleManager;
    private SavedColorStore store;

    @Override
    public void onCreate() {
        super.onCreate();
        bleManager = BleDeviceManager.getInstance(this);
        store = new SavedColorStore(this);
        ensureChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());
        handler.removeCallbacks(idleStop);

        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_APPLY_COMMAND.equals(action) && intent.hasExtra(EXTRA_COMMAND_BUNDLE)) {
            applyCommand(intent.getBundleExtra(EXTRA_COMMAND_BUNDLE));
        }

        // Make sure there's something to actually act on a command with. Cheap
        // to call repeatedly - BleDeviceManager.startScan() is a no-op while a
        // scan is already running or Bluetooth is off.
        if (bleManager.isBluetoothEnabled()) {
            bleManager.startScan();
        }

        // Settings -> "Background automation" being ON *is* the "keep this
        // running all the time" contract - it must never self-stop while
        // that's the case, regardless of connection state. The idle-timeout
        // only applies to the transient case this service was originally
        // built for: a single external command arrived while the persistent
        // toggle itself was off, spinning this up just long enough to
        // deliver that one command before cleaning itself up again.
        if (!store.getBackgroundServiceEnabled()) {
            handler.postDelayed(idleStop, IDLE_TIMEOUT_MS);
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(idleStop);
        super.onDestroy();
    }

    /**
     * Maps the documented external-command extras (see CommandReceiver) onto
     * the same Car01Protocol calls MainActivity uses. Deliberately does not
     * touch SavedColorStore's "last state" - that's the on-screen UI's
     * memory of what the wheel/sliders should show next launch, and an
     * automation-driven color change shouldn't silently overwrite it.
     */
    private void applyCommand(android.os.Bundle extras) {
        if (extras == null) {
            return;
        }
        String command = extras.getString("command", "");
        String zoneArg = extras.getString("zone", "both");
        boolean rgb = "rgb".equalsIgnoreCase(zoneArg) || "both".equalsIgnoreCase(zoneArg);
        boolean dmx = "dmx".equalsIgnoreCase(zoneArg) || "both".equalsIgnoreCase(zoneArg);

        switch (command) {
            case "color": {
                int r = clamp255(extras.getInt("r", 255));
                int g = clamp255(extras.getInt("g", 255));
                int b = clamp255(extras.getInt("b", 255));
                if (rgb && dmx) {
                    bleManager.broadcastCommand(Car01Protocol.setColorBoth(r, g, b));
                } else if (rgb) {
                    bleManager.broadcastCommand(Car01Protocol.setColor(r, g, b, Car01Protocol.Zone.RGB));
                } else if (dmx) {
                    bleManager.broadcastCommand(Car01Protocol.setColor(r, g, b, Car01Protocol.Zone.DMX));
                }
                break;
            }
            case "brightness": {
                int percent = clampPercent(extras.getInt("percent", 100));
                if (rgb && dmx) {
                    bleManager.broadcastCommand(Car01Protocol.setBrightnessBoth(percent));
                } else if (rgb) {
                    bleManager.broadcastCommand(Car01Protocol.setBrightness(percent, Car01Protocol.Zone.RGB));
                } else if (dmx) {
                    bleManager.broadcastCommand(Car01Protocol.setBrightness(percent, Car01Protocol.Zone.DMX));
                }
                break;
            }
            case "speed": {
                int percent = clampPercent(extras.getInt("percent", 50));
                if (rgb) {
                    bleManager.broadcastCommand(Car01Protocol.setSpeed(percent, Car01Protocol.Zone.RGB));
                }
                if (dmx) {
                    bleManager.broadcastCommand(Car01Protocol.setSpeed(percent, Car01Protocol.Zone.DMX));
                }
                break;
            }
            case "power": {
                boolean on = extras.getBoolean("on", true);
                bleManager.broadcastCommand(on ? Car01Protocol.powerOn() : Car01Protocol.powerOff());
                break;
            }
            case "mode": {
                int modeId = extras.getInt("mode_id", 0);
                if (rgb) {
                    bleManager.broadcastCommand(Car01Protocol.setMode(modeId, Car01Protocol.Zone.RGB));
                }
                if (dmx) {
                    bleManager.broadcastCommand(Car01Protocol.setMode(modeId, Car01Protocol.Zone.DMX));
                }
                break;
            }
            default:
                // Unknown command string - ignore rather than guess.
                break;
        }
    }

    private int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private int clampPercent(int v) {
        return Math.max(0, Math.min(100, v));
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "Background automation", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("Keeps the LEDCAR-01 connection alive for Tasker/Automate/IFTTT commands");
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE : 0));

        Intent stopIntent = new Intent(this, LedCarBackgroundService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 0, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE : 0));

        String statusText = bleManager.getConnectedCount() > 0
                ? bleManager.summaryText()
                : "Listening for automation commands";

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(statusText)
                .setSmallIcon(R.drawable.ic_bolt)
                .setContentIntent(contentIntent)
                .addAction(0, "Stop", stopPendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    // -- Static helpers, so callers don't need to know the Intent shape --

    public static void enqueueCommand(Context context, android.os.Bundle extras) {
        Intent intent = new Intent(context, LedCarBackgroundService.class)
                .setAction(ACTION_APPLY_COMMAND)
                .putExtra(EXTRA_COMMAND_BUNDLE, extras);
        startCompat(context, intent);
    }

    public static void start(Context context) {
        startCompat(context, new Intent(context, LedCarBackgroundService.class).setAction(ACTION_KEEP_ALIVE));
    }

    public static void stop(Context context) {
        context.startService(new Intent(context, LedCarBackgroundService.class).setAction(ACTION_STOP));
    }

    private static void startCompat(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }
}
