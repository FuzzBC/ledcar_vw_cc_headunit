# Changelog

Head-unit variant of [LedCar](https://github.com/FuzzBC/ledcar_vw_cc) -
independent version numbering, separate from the phone build. Each entry's
heading is the exact `versionName` (matches the app's settings "Version"
label and the GitHub release tag `V<versionName>`). Newest first.

Used as the release notes body when publishing via `publish_release.ps1`
(the script pulls the entry matching the current `versionName` straight out
of this file).

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
