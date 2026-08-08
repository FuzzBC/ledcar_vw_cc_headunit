# Changelog

Head-unit variant of [LedCar](https://github.com/FuzzBC/ledcar_vw_cc) -
independent version numbering, separate from the phone build. Each entry's
heading is the exact `versionName` (matches the app's settings "Version"
label and the GitHub release tag `V<versionName>`). Newest first.

Used as the release notes body when publishing via `publish_release.ps1`
(the script pulls the entry matching the current `versionName` straight out
of this file).

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
