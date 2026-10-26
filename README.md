# VR device control

Tablet controller + Meta Quest 3 player for synchronized content experiences. Design: [PLAN.md](PLAN.md). Day-to-day operation (pairing, importing, running a session): [docs/operations.md](docs/operations.md).

| Module | What it is |
| --- | --- |
| `shared-protocol/` | Versioned wire DTOs, codec/validation, error codes (pure Kotlin/JVM) |
| `shared-domain/` | Clock estimator, headset session reducer, run rules, dedup, transfer planner (pure Kotlin/JVM) |
| `vr-controller/` | Tablet APK: Compose UI, embedded Ktor server, NSD, pairing, library, transfers, runs |
| `vr-headset/` | Quest APK: Spatial SDK scene, Media3 player, start scheduler, downloads, journal |
| `tools/headset-simulator/` | Dev-only simulated headsets (real reducer + protocol, fake player) |
| `test-fixtures/` | `make-fixtures.sh` → sync and eye-order test clips |
| `docs/` | [protocol](docs/protocol-v1.md), [operations](docs/operations.md), [qualification](docs/qualification.md), [media](docs/media-compatibility.md) |

| APK | Application ID | Runs on | Output (debug) |
| --- | --- | --- | --- |
| Tablet controller ("Experience Control") | `com.vrdevicecontrol.controller` | Android 10+ (API 29), landscape tablet | `vr-controller/build/outputs/apk/debug/vr-controller-debug.apk` |
| Headset player ("VR Experience Player") | `com.vrdevicecontrol.headset` | Meta Quest 3 / 3S only (arm64, Horizon OS) | `vr-headset/build/outputs/apk/debug/vr-headset-debug.apk` |

## 1. Prerequisites (one-time)

1. **JDK 17 or newer** to run Gradle. The JDK bundled with Android Studio works (tested with its JDK 25); older JDKs such as 16 fail. Point `JAVA_HOME` at it, e.g. on macOS:
   ```sh
   export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
   ```
2. **Android SDK** with platform 35 and build-tools 35:
   ```sh
   sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
   ```
3. **`local.properties`** in the repository root pointing at the SDK (Android Studio creates it automatically; it is git-ignored):
   ```properties
   sdk.dir=/Users/<you>/Library/Android/sdk
   ```
4. **`adb`** on your `PATH` (it lives in `<sdk>/platform-tools`).

No separate Gradle install is needed: always use the wrapper (`./gradlew`), which downloads the pinned Gradle 9.2.1. The first build also downloads all dependencies and takes several minutes.

## 2. Build

From the repository root:

```sh
# Run the automated tests (optional but recommended)
./gradlew :shared-protocol:test :shared-domain:test :vr-headset:testDebugUnitTest

# Build both debug APKs
./gradlew :vr-controller:assembleDebug :vr-headset:assembleDebug
```

Alternatively open the folder in Android Studio and choose the `vr-controller` or `vr-headset` run configuration.

## 3. Prepare the devices (one-time per device)

**Tablet**
1. Settings → About tablet → tap *Build number* seven times to enable Developer options.
2. Settings → System → Developer options → enable *USB debugging*.
3. Connect by USB and accept the "Allow USB debugging?" prompt on the tablet.

**Each Quest headset**
1. The headset must be in **developer mode**: in the Meta Horizon phone app, open the headset's settings → *Developer mode* → on. This requires a Meta account that belongs to a (free) developer organization. Reboot the headset afterwards.
2. Connect the headset to the computer with a USB-C data cable.
3. Put the headset on and accept *Allow USB debugging* (tick *Always allow from this computer*).

Check that everything is visible:

```sh
adb devices -l
```

Each device should be listed as `device`. `unauthorized` means the prompt has not been accepted yet.

## 4. Install

With a single device connected:

```sh
adb install -r vr-controller/build/outputs/apk/debug/vr-controller-debug.apk   # tablet
adb install -r vr-headset/build/outputs/apk/debug/vr-headset-debug.apk         # headset
```

With several devices connected, pick each one by serial (first column of `adb devices`):

```sh
adb -s <tablet-serial> install -r vr-controller/build/outputs/apk/debug/vr-controller-debug.apk

# Install the headset APK on every connected Quest
for s in $(adb devices -l | awk '/model:Quest/ {print $1}'); do
  echo "Installing on $s"; adb -s "$s" install -r vr-headset/build/outputs/apk/debug/vr-headset-debug.apk
done
```

**Without a cable (optional).** While the headset is still on USB, run `adb -s <serial> tcpip 5555`. Unplug it and run `adb connect <headset-ip>:5555`. After that, install over Wi-Fi as above. This lasts until the headset reboots.

## 5. Launch

- **Tablet:** open *Experience Control* from the launcher, or run `adb -s <tablet-serial> shell am start -n com.vrdevicecontrol.controller/.MainActivity`.
- **Headset:** in the headset, open *Library* → filter *Unknown sources* → *VR Experience Player*. Or run `adb -s <serial> shell am start -n com.vrdevicecontrol.headset/.HeadsetActivity`.

On first launch, pair each headset with the tablet. See [docs/operations.md](docs/operations.md#first-time-setup).

Start on boot is enabled by default and configurable per headset on the tablet. At boot the player
also makes three best-effort attempts to bring its own task back to the foreground if a Horizon OS
panel such as the Store covers it. A normal APK cannot force-stop Meta system apps; deployments
that require exclusive single-app behavior should use supported managed-device/kiosk policies.

## 6. Updating

Rebuild, then run the same `adb install -r` commands. `-r` keeps the app's data: pairing, downloaded videos and run history all survive an update.

> **Do not uninstall the headset app unless you mean to.** Uninstalling deletes its downloaded videos (they must be sent again) and its pairing (it must be paired again). The same applies if Android refuses an update because of a signature change (see Troubleshooting), since the only fix is to uninstall first.

Increase `versionCode` / `versionName` in `vr-controller/build.gradle.kts` and `vr-headset/build.gradle.kts` for every build you hand out, so devices can be checked for the right version (shown on the tablet's Headsets and Diagnostics screens).

## 7. Release builds

Release signing is intentionally **not** configured in the repository; keep keys outside it. To produce signed release APKs:

1. Create a keystore once and store it securely, outside the repository:
   ```sh
   keytool -genkeypair -v -keystore ~/keys/vr-device-control.jks -alias release -keyalg RSA -keysize 4096 -validity 10000
   ```
2. Build unsigned release APKs:
   ```sh
   ./gradlew :vr-controller:assembleRelease :vr-headset:assembleRelease
   ```
   Outputs: `*/build/outputs/apk/release/*-release-unsigned.apk`.
3. Sign and verify each one with the build-tools `apksigner`:
   ```sh
   APKSIGNER=<sdk>/build-tools/35.0.0/apksigner
   $APKSIGNER sign --ks ~/keys/vr-device-control.jks --out vr-headset-release.apk \
     vr-headset/build/outputs/apk/release/vr-headset-release-unsigned.apk
   $APKSIGNER verify --verbose vr-headset-release.apk
   ```
   Do the same for the controller APK.
4. Record the SHA-256 of each delivered APK (`shasum -a 256 *.apk`) with the release notes.

Debug and release builds are signed with different keys. Moving a device from one to the other requires an uninstall, which loses the data described in section 6. Pick one per deployment and stay with it.

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| `SDK location not found` | Create `local.properties` with `sdk.dir=…` (step 1.3) |
| Gradle fails with an unsupported Java/class-file version error | `JAVA_HOME` points at a JDK older than 17 (step 1.1) |
| `adb devices` shows `unauthorized` | Accept the USB debugging prompt on the device (inside the headset for Quest) |
| Quest not listed at all | Developer mode not enabled, or a charge-only cable |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Installed copy was signed with a different key; `adb uninstall <application id>` first (loses data, see section 6) |
| `INSTALL_FAILED_NO_MATCHING_ABIS` for the headset APK | You are installing on a non-Quest device or emulator; the headset APK is arm64 and Horizon OS only |
| Headset app not in the main app list | It is under *Library → Unknown sources* |
| Store appears over the player after boot | Wait up to 15 seconds for the boot focus retries. If it remains, open the player manually. The app cannot force-stop Meta system apps; qualify managed-device/kiosk deployment if exclusive launch is required. |

## Developing without headsets

See [docs/operations.md](docs/operations.md#developing-without-headsets) for running the tablet app against `tools/headset-simulator`.
