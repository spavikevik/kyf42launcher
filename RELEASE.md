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

The build needs JDK 17:

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

## Automated releases (GitHub Actions)

Pushing a tag matching `v*` (e.g. `v0.1`) runs `.github/workflows/release.yml`,
which builds the release APK and attaches it to a GitHub Release as
`kyf42launcher-<tag>.apk` with auto-generated release notes.

To get a **signed** APK from CI, add these repository secrets
(Settings → Secrets and variables → Actions):

| Secret              | Value                                          |
|---------------------|------------------------------------------------|
| `KEYSTORE_BASE64`   | `base64 -i release.keystore` output            |
| `KEYSTORE_PASSWORD` | keystore store password                        |
| `KEY_ALIAS`         | key alias (e.g. `kyf42launcher`)               |
| `KEY_PASSWORD`      | key password                                   |

Without the secrets the workflow still runs and publishes an unsigned APK.

Cutting a release:

```sh
git tag v0.1
git push origin v0.1
```
