package com.ledcar01.controller;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * TODO.md item 4: if Settings -> "Background automation" was left on,
 * resume {@link LedCarBackgroundService} right after the device boots
 * instead of waiting for a human to ever open {@link MainActivity} again -
 * the whole point of headless automation is that it should keep working
 * across a head-unit reboot without anyone touching the screen.
 * <p>
 * Does nothing if the toggle was left off - this never starts anything on
 * its own initiative, only resumes a state the user explicitly opted into.
 * Android exempts apps that declare a BOOT_COMPLETED receiver from the
 * usual "can't start a foreground service from the background" rule
 * specifically for this case, so calling
 * {@link LedCarBackgroundService#start} here is a supported pattern, not a
 * workaround.
 */
public class BootReceiver extends BroadcastReceiver {

    /** Some OEM ROMs (common on head units) fire this instead of/alongside the standard action - see the manifest's intent-filter for this receiver. */
    private static final String ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        boolean isBoot = Intent.ACTION_BOOT_COMPLETED.equals(action) || ACTION_QUICKBOOT_POWERON.equals(action);
        if (!isBoot) {
            return;
        }
        SavedColorStore store = new SavedColorStore(context);
        if (store.getBackgroundServiceEnabled()) {
            LedCarBackgroundService.start(context);
        }
    }
}
