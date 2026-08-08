package com.ledcar01.controller;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * TODO.md item 2: entry point for external automation apps (Tasker,
 * Automate, and IFTTT-on-Android, which typically bridges through Tasker
 * rather than talking to apps directly). Deliberately a plain manifest
 * {@link BroadcastReceiver} instead of a full Tasker/Locale plugin
 * ({@code com.twofortyfouram.locale.intent.action.*}) - a raw broadcast
 * works identically from Tasker's built-in "Send Intent" action *and*
 * Automate's "Send intent" block, with no plugin SDK dependency on either
 * side, whereas a formal plugin only buys Tasker users a nicer in-app
 * picker UI at the cost of one implementation per automation app. If that
 * trade-off is ever worth revisiting, this class is the only thing that
 * would need a sibling, not a rewrite.
 *
 * <h3>Intent contract</h3>
 * Target this receiver explicitly (package {@code com.ledcar01.controller},
 * class {@code com.ledcar01.controller.CommandReceiver}) - Tasker's "Send
 * Intent" action has explicit Package/Class fields for exactly this, which
 * also keeps the broadcast working under Android 8+'s implicit-broadcast
 * background restrictions (those only limit broadcasts with no explicit
 * target).
 * <p>
 * Action: {@code com.ledcar01.controller.headunit.ACTION_COMMAND}
 * <p>
 * Common extras:
 * <ul>
 *   <li>{@code command} (String, required) - one of {@code "color"},
 *       {@code "brightness"}, {@code "speed"}, {@code "power"}, {@code "mode"}</li>
 *   <li>{@code zone} (String, optional, default {@code "both"}) - one of
 *       {@code "rgb"}, {@code "dmx"}, {@code "both"}</li>
 * </ul>
 * Per-command extras:
 * <ul>
 *   <li>{@code color}: {@code r}, {@code g}, {@code b} (int, 0-255)</li>
 *   <li>{@code brightness}: {@code percent} (int, 0-100)</li>
 *   <li>{@code speed}: {@code percent} (int, 0-100)</li>
 *   <li>{@code power}: {@code on} (boolean)</li>
 *   <li>{@code mode}: {@code mode_id} (int - see Car01Protocol.MODES)</li>
 * </ul>
 * Example (Tasker "Send Intent"): Action
 * {@code com.ledcar01.controller.headunit.ACTION_COMMAND}, Package
 * {@code com.ledcar01.controller}, Class
 * {@code com.ledcar01.controller.CommandReceiver}, Extra
 * {@code command:color, zone:both, r:255, g:140, b:20} - a "sunset" profile
 * firing this warms both zones to amber without opening the app.
 * <p>
 * This intentionally never touches the UI or requires MainActivity to be
 * running - it hands the raw extras straight to
 * {@link LedCarBackgroundService}, which owns the actual BLE calls. A
 * receiver's onReceive() has to return in a few seconds and can't safely do
 * BLE I/O itself, which is the whole reason that service exists (see its
 * class doc, and TODO.md item 4).
 */
public class CommandReceiver extends BroadcastReceiver {

    public static final String ACTION_COMMAND = "com.ledcar01.controller.headunit.ACTION_COMMAND";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_COMMAND.equals(intent.getAction())) {
            return;
        }
        Bundle extras = intent.getExtras();
        if (extras == null || !extras.containsKey("command")) {
            return;
        }
        LedCarBackgroundService.enqueueCommand(context, extras);
    }
}
