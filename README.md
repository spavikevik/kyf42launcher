# KYF42 Launcher

A KaiOS-style home launcher for the **Kyocera KYF42 (au/KDDI GRATINA)** — a
non-touch Android 10 flip phone (Snapdragon 210 / MSM8909, armeabi-v7a, 1 GB RAM,
480×854 hdpi). Built because no third-party launcher fits a portrait, D-pad-only,
no-touchscreen device: TV launchers lock landscape, phone launchers assume touch.

## Features

- Portrait-locked (fixes the landscape rotation of TV launchers).
- Clock/date home screen over a generated wallpaper, with an info card:
  weather (Open-Meteo, no API key), next calendar event, next alarm.
- Configurable favorites **dock** (defaults resolved from system apps) + an
  **All apps** tile.
- Full app **grid** (3 columns), D-pad focus navigation, KaiOS blue selection
  ring, keypad search, soft-key **Options** menu (app info / uninstall / dock).
- Custom **status bar**: WiFi bars (via `ConnectivityManager`, no location perm),
  cellular signal / "No SIM", battery %, clock — also drawn **over other apps**
  as an overlay (auto-hides in fullscreen/immersive apps).
- **Lock screen** (KaiOS/iOS-style; hands off to the real keyguard when a
  system PIN is set) and a **control center** (torch, brightness, ringer/DND,
  bluetooth).
- **Notification panel** backed by a `NotificationListenerService`.
- Color **themes** (accent + wallpaper pairs) and a first-run **setup wizard**.
- Semantic **key bindings** layer — any D-pad phone can be mapped, not just the
  KYF42.
- Hidden system nav + status bars (immersive).
- App grid refreshes live on package install/remove/update.

## Device key map (`/system/usr/keylayout/matrix_keypad.kl`)

| Key | Android keycode |
|-----|-----------------|
| D-pad ↑↓←→ | `DPAD_UP/DOWN/LEFT/RIGHT` |
| Center/OK | `DPAD_CENTER` (23) = SELECT |
| Back/Clear | `BACK` (4) |
| Soft keys | `F1` (131) / `F2` (132) |
| Call | `CALL` (5) · digits 0–9, `#` |

There is **no HOME keycode** — "home" is reached via Back / closing the flip.

## Build

Requires JDK 17, Android SDK (platform 36). Wrapper pins Gradle 9.2.1 (AGP 9.0).

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew assembleDebug
```

For the signed, installable release APK see [RELEASE.md](RELEASE.md).

## Install + set as home

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell cmd package set-home-activity dev.stefan.kyf42launcher/.MainActivity
# signal in the status bar needs READ_PHONE_STATE (grant without a dialog):
adb shell pm grant dev.stefan.kyf42launcher android.permission.READ_PHONE_STATE
```

Revert to stock launcher:

```sh
adb shell cmd package set-home-activity jp.kyocera.afphome/.homemain.HomeMainActivity
```

## Notes / gotchas

- **Pointer mode**: Kyocera's D-pad cursor is a global setting (not app-controllable,
  state not in Android settings, would need root). Keep it **off** for this launcher;
  turn it on only for touch-only apps. D-pad focus nav breaks if a key is consumed
  before `ViewRootImpl` runs focus traversal — the launcher only consumes keys it uses.
- Framebuffer capture (`screencap`) returns blank on this device; verify on-device or
  via `uiautomator dump`.

## License

BSD 3-Clause — see [LICENSE](LICENSE). Third-party libraries and services are
documented in [THIRD_PARTY.md](THIRD_PARTY.md); weather data by
[Open-Meteo.com](https://open-meteo.com/) (CC BY 4.0).
