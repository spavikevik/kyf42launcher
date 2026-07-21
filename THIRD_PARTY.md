# Third-party code, libraries, and services

All application code, vector icons, and wallpapers in this repository are
original work, licensed under BSD 3-Clause (see [LICENSE](LICENSE)). No source
code has been vendored or adapted from other projects.

## Bundled libraries

Resolved by Gradle and compiled into the APK:

| Library | Version | License |
|---------|---------|---------|
| [androidx.core:core-ktx](https://developer.android.com/jetpack/androidx/releases/core) | 1.17.0 | Apache-2.0 |
| [androidx.appcompat:appcompat](https://developer.android.com/jetpack/androidx/releases/appcompat) | 1.7.1 | Apache-2.0 |
| [androidx.recyclerview:recyclerview](https://developer.android.com/jetpack/androidx/releases/recyclerview) | 1.4.0 | Apache-2.0 |
| [Kotlin standard library](https://github.com/JetBrains/kotlin) (via AGP built-in Kotlin) | 2.2.x | Apache-2.0 |

Transitive dependencies of the above are likewise Apache-2.0 (androidx /
Kotlin ecosystem). `org.json` is used via the Android platform API, not
bundled.

## Build-time only (not distributed)

Android Gradle Plugin 9.0.1 (Apache-2.0), Gradle 9.2.1 (Apache-2.0),
Android SDK platform 36 (Android SDK license). None of these ship in the APK.

## Network services

The home-screen info card calls two free web services at runtime:

- **[Open-Meteo](https://open-meteo.com/)** — weather forecast API, no API
  key. Weather data is provided under
  [CC BY 4.0](https://open-meteo.com/en/license); attribution: *Weather data
  by [Open-Meteo.com](https://open-meteo.com/)*.
- **[ipapi.co](https://ipapi.co/)** — IP-based geolocation, used only as a
  fallback when no device location is available (free tier, no key).

Both are optional: the launcher works fully offline, the info card simply
hides its weather row.
