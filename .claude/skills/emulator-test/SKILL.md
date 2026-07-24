---
name: emulator-test
description: Build the launcher debug APK, boot the headless Android 8 (API 26) emulator, install, drive it with adb key events, and capture screenshots. Use when asked to run, test, or screenshot the app in an emulator.
---

# Test the launcher in the API 26 emulator

The KYF42 launcher targets a d-pad flip phone on Android 10, with minSdk 26.
This skill runs it on the lowest supported release (Android 8.0, API 26) in a
headless emulator on this Apple Silicon Mac.

## SDK location

The Android SDK lives at `/opt/homebrew/share/android-commandlinetools`
(see `local.properties`). `adb` is on PATH via `/opt/homebrew/bin/adb`.

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
```

## One-time setup (already done on this machine — verify, don't redo)

```bash
# Check: emulator binary + API 26 arm64 image + AVD
ls $ANDROID_HOME/emulator/emulator
ls $ANDROID_HOME/system-images/android-26/default/arm64-v8a
$ANDROID_HOME/emulator/emulator -list-avds   # expect: api26
```

If missing:

```bash
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager \
    "emulator" "system-images;android-26;default;arm64-v8a"
echo no | $ANDROID_HOME/cmdline-tools/latest/bin/avdmanager create avd \
    -n api26 -k "system-images;android-26;default;arm64-v8a" --force
```

Use the `default` (AOSP) arm64-v8a image, not google_apis — it boots faster
and allows `adb root` (needed to start non-exported activities like
`LockActivity` directly).

## Build, boot, install, launch

```bash
./gradlew :app:assembleDebug -q

# Boot headless in the background (~60–90 s to full boot)
$ANDROID_HOME/emulator/emulator -avd api26 -no-window -no-audio -no-boot-anim -no-snapshot &

# Wait for full boot
adb wait-for-device shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done; echo BOOTED'

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.stefan.kyf42launcher/.MainActivity
```

First launch redirects to `SetupActivity` (6-page wizard).

## Driving the app

Screen is 320x640. Screenshot with:

```bash
adb shell screencap -p /sdcard/s.png && adb pull /sdcard/s.png <local>
```

Input quirks learned the hard way:

- **Setup wizard**: the center key does NOT advance pages; tap the Next
  button at `input tap 267 608` instead. Back is at `49 609`.
- **Soft-key labels** ("Alerts"/"Controls" at the bottom) are NOT tappable —
  they mirror hardware keys. Left soft = F1 (`keyevent 131`), right soft =
  F2 (`keyevent 132`). F2 opens the control center from home.
- **D-pad**: up/down/left/right = keyevents 19/20/21/22, center (OK) = 23,
  back = 4. Center on the home dock launches the focused app; navigate with
  d-pad first.
- **LockActivity** is not exported. `adb root` first (works on the default
  image), then `am start -n dev.stefan.kyf42launcher/.LockActivity`.
  OK key (23) unlocks back to home.
- **Wifi toggle** for status-bar icon tests: `adb shell svc wifi disable` /
  `enable`. The launcher draws its own status bar; the wifi icon appears
  only with validated connectivity (a few seconds after enable).

## Known emulator-image bug (not app code)

Starting a show-when-locked window (LockActivity) crashes
`com.android.systemui` on this AOSP API 26 image with an NPE in
`NavigationBarFragment.onKeyguardOccludedChanged` ("System UI has stopped"
dialog). Dismiss it and continue; the launcher process itself is unaffected.
Do not chase this as an app bug.

## Crash check and teardown

```bash
adb logcat -d | grep AndroidRuntime | grep dev.stefan   # expect no output
adb emu kill
```
