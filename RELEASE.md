# Building a release APK

The `release` build type produces the installable, non-debug APK. It is signed
from a keystore referenced by `keystore.properties` at the project root. Both the
keystore and `keystore.properties` are gitignored — they are never committed.

## One-time setup

1. **Generate a release keystore** (2048-bit RSA, ~27-year validity):

   ```sh
   keytool -genkeypair -v \
     -keystore release.keystore \
     -alias kyf42launcher \
     -keyalg RSA -keysize 2048 -validity 10000
   ```

   Answer the prompts and choose a store/key password. Keep this file safe — an
   app can only be updated in place if re-signed with the **same** key.

2. **Create `keystore.properties`** from the template and fill in your values:

   ```sh
   cp keystore.properties.example keystore.properties
   # then edit keystore.properties
   ```

   ```properties
   storeFile=release.keystore
   storePassword=<your store password>
   keyAlias=kyf42launcher
   keyPassword=<your key password>
   ```

## Build

The build needs JDK 17 (Gradle 8.13 rejects newer JDKs):

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk` (signed).

Install to a connected device:

```sh
adb install -r app/build/outputs/apk/release/app-release.apk
```

> If `keystore.properties` is missing, `assembleRelease` still runs but emits
> `app-release-unsigned.apk`, which cannot be installed until signed.
