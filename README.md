# LEDCAR-01 VW CC — Head Unit Edition

Head-unit-compatible fork of
[**LedCar**](https://github.com/FuzzBC/ledcar_vw_cc), the Android
controller for **LEDCAR-01** interior ambient LED strip kits. Same app,
same protocol - this repo exists specifically for older/embedded Android
head units (Junsun and similar brands) that the phone build's `minSdk`
doesn't reach.

**If you're running this on a phone or tablet, use
[ledcar_vw_cc](https://github.com/FuzzBC/ledcar_vw_cc) instead** - that's
the actively-developed main line. This repo tracks it, pulling over fixes
as they land, but isn't where new features start.

## What's different from the phone build

| | Phone build (`ledcar_vw_cc`) | This repo |
|---|---|---|
| `minSdk` | 26 | **21** - covers older head-unit Android builds |
| `connectGatt` | 5-arg overload (API 26+ only) | Falls back to the classic 3-arg overload below API 26 |
| Orientation | Locked portrait | Unlocked (head units are usually landscape; dedicated landscape layout still open, see `TODO.md`) |
| Update checks against | `FuzzBC/ledcar_vw_cc` releases | `FuzzBC/ledcar_vw_cc_headunit` releases (this repo) |
| App name shown on device | FuZz CarAmbient | FuZz CarAmbient HU |
| Version numbering | Independent | Independent (starts at 1.001 here) |

Everything else - the BLE protocol, zone/color/effect logic, UI - is the
same code. See [`PROTOCOL.md`](PROTOCOL.md) and the main repo's README for
the full feature list and protocol reference; not duplicated here to avoid
the two copies drifting out of sync.

## What's in this repo

| Path | What it is |
|---|---|
| [`LedCar_Headunit/`](LedCar_Headunit) | The Android app - same source as `LedCar`, adapted per the table above |
| [`LedCar_Simulator/`](LedCar_Simulator) | The same BLE peripheral simulator used by the phone build, for testing without real hardware |
| [`PROTOCOL.md`](PROTOCOL.md) | Same protocol reference as the main repo |
| [`TODO.md`](TODO.md) | Follow-up work: landscape layout, Tasker/IFTTT/Automate integration, background keep-alive, Agama Launcher support - all of it head-unit-specific |

## Building

```bash
cd LedCar_Headunit
./gradlew assembleDebug
./gradlew assembleRelease
```

**Publishing a release** (maintainers): same flow as the phone build - bump
`version.properties`, `assembleRelease`, then `publish_release.ps1` (needs
a GitHub token in a local, gitignored `github_release.properties` - copy
`github_release.properties.example`). Tags `V<versionMajor>.<versionCode>`
on **this** repo, independent of the phone build's version numbers.
