# Changelog

Head-unit variant of [LedCar](https://github.com/FuzzBC/ledcar_vw_cc) -
independent version numbering, separate from the phone build. Each entry's
heading is the exact `versionName` (matches the app's settings "Version"
label and the GitHub release tag `V<versionName>`). Newest first.

Used as the release notes body when publishing via `publish_release.ps1`
(the script pulls the entry matching the current `versionName` straight out
of this file).

## 1.005
- Fixes the scan shimmer/lock stopping early: `MainActivity` used to infer
  "still scanning" by substring-matching `BleDeviceManager`'s free-text
  status messages, so the moment the first found device started
  connecting ("Connecting to X...") it looked like scanning had ended -
  shimmer off, controls unlocked - even though the scan was still actively
  running in the background for up to 12 more seconds. `BleDeviceManager`
  now reports scan start/stop as its own explicit event
  (`Listener.onScanningChanged`), independent of status text, so the
  effect stays on for exactly as long as scanning is actually happening.
- Scanning now stops itself shortly after the first device is found
  (2.5s grace window, to still catch a second/third unit advertising a
  beat later) instead of always running the full ~12s timeout regardless
  of whether anything's already been found - "found fast" now actually
  finishes fast.

## 1.004
- **Fixes "connected but no command works" on real head-unit hardware.**
  `BleDeviceManager` previously left the write characteristic's write type
  unset (defaults to "Write With Response") and never checked whether
  `gatt.writeCharacteristic()` actually started - on head-unit BLE stacks
  that reject a with-response write against a peripheral only declaring
  "Write Without Response" (which is what these LEDCAR-01 modules are),
  the very first write after connecting could fail synchronously with no
  callback ever firing, permanently latching the internal write-in-flight
  flag and silently dropping every command from then on, while the device
  still showed "connected".
  - The write characteristic's write type is now set explicitly from its
    actual declared GATT properties instead of relying on the stack default.
  - The synchronous return value of `writeCharacteristic()` is now checked;
    a rejected write is requeued and retried instead of leaving the queue
    stuck.
  - A 4-second watchdog now force-clears a stuck write and retries if
    `onCharacteristicWrite` never lands at all, for stacks that fail in
    stranger ways than a synchronous `false`.

## 1.003
- Reworked `res/layout-land/activity_main.xml` into the "Command Strip"
  arrangement: Settings, Power, the zone pill, Mode and Scan/Connect now
  live in one persistent full-width band across the top edge - the easiest
  reach zone on a dash-mounted screen - instead of Power/Mode sitting in a
  separate card below a left-column-only top bar. Body underneath keeps
  V1.002's left (color-picking) / right (preview + brightness/speed) split.
  Picked from a 5-variant design review; see the project's design notes for
  the other options considered.
- `colorCard` no longer wraps a `powerModeRow` in the landscape layout (that
  content moved to the top strip) - the portrait phone layout is
  unaffected, this only changes `layout-land`.

## 1.002
- Implements TODO.md items 2-4 (Tasker/Automate/IFTTT command intent, head-unit
  landscape layout, background keep-alive service) - see that file for the
  full writeup of what shipped and what's still open per item.
- New `CommandReceiver` (manifest broadcast receiver, action
  `com.ledcar01.controller.headunit.ACTION_COMMAND`) lets Tasker's "Send
  Intent", Automate's "Send intent", or IFTTT-via-Tasker drive color/
  brightness/speed/power/mode on either or both zones without opening the
  app - see its class doc for the extras contract and a worked example.
- New `LedCarBackgroundService`, a foreground service that keeps
  `BleDeviceManager`'s connection alive and listening for `CommandReceiver`
  commands while the app is backgrounded or closed. Starts automatically
  the moment a command arrives, or can be switched on ahead of time from
  Settings → "Background automation". Auto-stops after 5 minutes idle with
  no connection.
- New `res/layout-land/activity_main.xml`: a real side-by-side landscape
  layout (controls left, preview + brightness/speed right) instead of the
  phone's vertical stack rendered sideways - picked up automatically since
  this repo already leaves orientation unlocked.
- Researched Agama Launcher integration (TODO.md item 5): no documented
  API for Agama to send commands into a third-party app exists, so nothing
  was implemented against it - written up in TODO.md rather than guessed
  at. `CommandReceiver`'s generic broadcast contract would already support
  a future Agama automation feature if one ever ships.

## 1.001
- Forked from [LedCar](https://github.com/FuzzBC/ledcar_vw_cc) V1.008 for
  compatibility with older Android head units (Junsun and similar brands).
- `minSdk` lowered from 26 to 21 - the practical floor for the BLE APIs this
  app depends on.
- `BleDeviceManager` now falls back to the classic 3-arg `connectGatt()` on
  API < 26 instead of the 5-arg overload (which needs 26+); its callbacks
  land on a binder thread instead of `mainHandler` directly, which every
  callback in that class already accounts for.
- Dropped the phone build's portrait-orientation lock - head units are
  usually landscape, and forcing portrait would make the app unusable on
  them. A dedicated landscape layout is still open (see `TODO.md` item 3);
  for now the phone's portrait-first layout just renders in whatever
  orientation the device provides.
- Checks `FuzzBC/ledcar_vw_cc_headunit` for updates instead of the phone
  repo - the two must never cross-check each other's releases.
