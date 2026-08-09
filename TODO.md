# TODO / Roadmap

## 1. Compatibility with older Android versions (e.g. Junsun head units) — done in code, unverified on hardware

Implemented as of `LedCar_Headunit` V1.001: `minSdk` lowered 26 → 21, and
`BleDeviceManager.connectGattCompat()` falls back to the classic 3-arg
`connectGatt()` below API 26 (the 5-arg overload with an explicit PHY
needs 26+). The runtime permission flow already branched correctly on
`Build.VERSION.SDK_INT >= 31` before this, so it needed no changes.

Still open: this has **not been tested on an actual low-end/older head
unit** (or even an old-API emulator image) - it compiles and the API
choices are correct on paper, but the whole point of this repo is
hardware that doesn't reliably match what a phone/emulator shows.

## 2. IFTTT / Tasker / Automate integration (time-of-day color presets) — done

Implemented as of V1.002: `CommandReceiver` (a plain manifest
`BroadcastReceiver`, `android:exported="true"`) listens for
`com.ledcar01.controller.headunit.ACTION_COMMAND` and maps its extras
(`command` = `color`/`brightness`/`speed`/`power`/`mode`, `zone` =
`rgb`/`dmx`/`both`, plus per-command values) onto the same `Car01Protocol`
calls `MainActivity` uses. See `CommandReceiver`'s class doc for the full
contract and a worked Tasker "Send Intent" example.

Deliberately a raw broadcast contract, not a formal
`com.twofortyfouram.locale` Tasker plugin - it works unchanged from
Tasker's built-in "Send Intent" action *and* Automate's "Send intent"
block (IFTTT-on-Android bridges through Tasker), with no plugin SDK
dependency, at the cost of no in-Tasker picker UI. Revisit only if that UX
gap actually bites someone.

Runs headless via `LedCarBackgroundService` (item 4) - no Activity/UI
needs to be open for a command to land.

## 3. Dedicated layout for Android head units — done

Implemented as of V1.002: `res/layout-land/activity_main.xml`, a genuine
side-by-side arrangement (left column = zone pill + color wheel + saved
colors, right column = ambient preview + brightness/speed) instead of a
stretched copy of the phone's vertical stack. Every view keeps the same
`android:id` as the portrait layout, so `MainActivity` needed zero code
changes - Android picks this layout automatically whenever the device is
in landscape, which is the orientation this repo already leaves unlocked.

Still open: only tuned/eyeballed in the emulator at a couple of common
head-unit resolutions, not on real head-unit hardware (same caveat as
item 1) - and it's one layout for all landscape sizes, not a dedicated
`sw`-qualifier variant per screen class.

## 4. Keep-alive on head units for background automation — done

Implemented as of V1.002: `LedCarBackgroundService`, a foreground
`Service` (persistent low-priority notification, `connectedDevice`
foreground service type) that starts a BLE scan/connect on its own and
keeps `BleDeviceManager`'s singleton connection alive independent of
`MainActivity`. `CommandReceiver` starts it automatically the moment a
command arrives; it can also be switched on ahead of time from Settings
→ "Background automation" so the connection is already warm before the
first automation fires. Stops itself after 5 minutes with no active
connection and no new command, rather than running forever once enabled.

Still open: no battery-optimization / "allow background activity"
exemption handling - this varies a lot by OEM skin on head-unit devices
and needs real hardware to get right, not something to guess at from an
emulator.

## 5. Receiving commands from Agama Launcher — not implementable as scoped; descoped

Researched, not implemented. Agama Launcher does not expose a documented
API for sending commands *into* a third-party app. The only two real
integration points found:

- **Media-notification interception**: Agama reads now-playing metadata
  that an app posts via Android's standard notification/`MediaSession`
  APIs. This is one-directional (app → Agama) and is designed for media
  players; it has no meaningful fit for a lighting-control app and
  wouldn't let Agama send anything back.
- **"Autorun app on startup"**: a launcher-side setting (long-press an
  app icon in Agama's own UI, pick a startup delay) that just fires the
  same standard `LAUNCHER` intent any launcher uses to open an app - no
  app-side code needed, and this app already satisfies it as-is (it's a
  normal launcher-activity app).

Neither is "Agama sends a command to this app" in the sense item 5
originally asked for. Building fake integration code against an
undocumented, unconfirmed contract isn't worth doing - if Agama publishes
a real plugin/intent API in the future (or someone can point to concrete
developer docs), this should be revisited then. In the meantime, item 2's
`CommandReceiver` (a plain broadcast, package `com.ledcar01.controller`,
action `com.ledcar01.controller.headunit.ACTION_COMMAND`) is already a
generic enough target that *any* Android automation surface capable of
sending an explicit broadcast - including, if Agama ever grows one, a
future Agama automation feature - could drive this app through it without
further changes here.

As of V1.007, **Broadcast Monitor** (the button next to Settings) is the
practical way to actually find out what Junsun's own launcher/OS or Agama
broadcasts on a real unit - a floating bubble opens a live log of
broadcast Intents this app can see, and lets you add a custom action
string to watch (e.g. one found by decompiling the launcher APK with
jadx) without rebuilding. If that turns up a real, confirmed action
string worth reacting to, that's the point to revisit this item for real.

---

*Items 2-4 all shipped together in V1.002 since they're interdependent
(the intent contract in item 2 is only useful headless because of item
4's service; item 3 is purely a layout change and doesn't touch any of
the others). Item 1 remains hardware-unverified; item 5 is descoped
pending real Agama documentation.*
