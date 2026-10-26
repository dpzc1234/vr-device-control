# Operations runbook (draft)

Status: written against the M0–M5 code. Steps that depend on hardware behaviour (sleep, tracking, venue Wi-Fi) are marked **unverified** until qualification.

## Build and install

```sh
./gradlew :shared-protocol:test :shared-domain:test :vr-headset:testDebugUnitTest   # JVM tests
./gradlew :vr-controller:assembleDebug :vr-headset:assembleDebug
adb -s <tablet> install -r vr-controller/build/outputs/apk/debug/vr-controller-debug.apk
adb -s <quest>  install -r vr-headset/build/outputs/apk/debug/vr-headset-debug.apk
```

Toolchain: JDK 17+ to run Gradle (tested with the JDK 25 bundled in Android Studio), Gradle 9.2.1 wrapper, AGP 8.11.1, Kotlin 2.3.0, Meta Spatial SDK 0.14.0. Release signing keys are kept outside the repository; configure a `signingConfig` locally or in CI before building release APKs. Uninstalling the headset app deletes its managed video copies.

## Developer mode and customer handover

Customer mode hides library editing (import, delete, rename, layout) so customers cannot add their own content. Running sessions, pairing, and sending or removing library videos on headsets stay available.

- **Fresh install:** the app opens in developer mode with an amber banner. Import and check the videos, then tap **Set PIN** (6–8 digits). Write down the **recovery code** shown once; it is not stored on the tablet in readable form. The tablet then locks after 15 minutes idle, on every app restart, or via **Lock** in the developer bar.
- **Unlocking on site:** Diagnostics → tap the **Tablet app** version 7 times quickly → enter the PIN. Five wrong attempts lock the prompt for 30 s, doubling after each further failure.
- **Forgotten PIN:** in the PIN prompt choose *Forgot PIN?*, enter the recovery code and a new PIN. A new recovery code replaces the old one.
- **Known limit:** this is a UI lock. Clearing the app's data in Android settings returns it to a fresh install (developer mode, no PIN), though that also removes the library and all pairings.

## Tablet power settings (Honor / MagicOS and similar)

Some vendor builds (Honor MagicOS, Huawei EMUI, Xiaomi, Oppo and others) close apps aggressively in the background. The tablet's server runs inside the app, and the app is briefly in the background whenever the system file picker is open during an import. Once per tablet:

- **MagicOS:** Settings → Apps → Experience Control → *App launch* → switch off *Manage automatically*, then enable *Auto-launch*, *Secondary launch* and *Run in background*.
- Settings → Battery → (More settings / App battery usage) → set Experience Control to *Unrestricted* or *Don't optimise*.
- Optionally lock the app in Recents (swipe down on its card) so "clear all" does not close it.

Exact menu names vary by OS version. If headsets drop offline every time the tablet screen turns off or another app opens, revisit these settings first.

## First-time setup

1. Open **Experience Control** on the tablet. Keep it open during setup, transfers and runs (the screen stays awake). Background hosting is not supported.
2. **Headsets → Pairing mode** on.
3. On each Quest open **VR Experience Player**. The setup panel lists tablets found on the network; choose yours. A 6-digit code appears.
4. On the tablet, approve the request only if the code matches. Rename headsets (e.g. "Headset 1"); the name appears inside the headset.
5. If a headset finds nothing: Setup → *Enter address manually* with the address and port from the tablet's **Diagnostics** screen → *Test connection*. "Cannot reach" plus "discovery found nothing" usually means client isolation on the Wi-Fi; use an allowed local network or hotspot.

**Start on boot:** this is enabled by default per headset. After `BOOT_COMPLETED`, the player opens
and makes best-effort foreground retries at 3, 8 and 15 seconds if a Horizon OS panel covers it.
Android does not allow a normal app to force-stop the Store or other apps. If the Store remains in
front, open the player manually. For unattended, exclusive-app boot, qualify a supported Meta
managed-device/kiosk deployment rather than relying on process-killing commands.

## Content

1. **Library → Import video** (system file picker). The file is copied into the tablet and checksummed; nothing streams later.
2. Check **Layout** (projection / stereo / eye order) with a known sample — the import only *suggests* one and shows "Check layout" until confirmed. `test-fixtures/make-fixtures.sh` generates labelled left/right test clips.
3. **Send to headsets**. Offline headsets are queued and receive the video on reconnect; transfers pause while a headset is in a run. The **Transfers** tab shows progress, verification and errors per headset.

## Running a session

1. Put the headsets on the riders and have everyone face the intended forward direction. In **Headsets**, press **Recenter** on each card or **Recenter all**. Wait for *Recenter confirmed*. The action is available only while the player is visible, the headset is worn, and no run is armed/playing/paused.
2. **Run → Choose video**. All online headsets are selected; tap tiles to change the selection.
3. **Prepare**. Participants freeze. Each headset loads the video silently behind its standby scene and syncs its clock. Tiles show *Ready* or the reason they are not.
4. If one is not ready: wait, *Retry not ready*, or remove it from the run via its ⋮ menu. Nothing is removed silently.
5. Press **START** when participants are ready. "Starting…" covers the ~2 s coordination delay; the video (including its own countdown) then plays from the beginning.
6. Tiles distinguish *Start accepted* from *Playing* (observed) and show *Outcome unknown* when no acknowledgement arrived. Headsets out of contact keep playing; their progress is marked *estimated*.
7. Pause / Resume / Stop reach only headsets in contact and report per-device results; nothing is queued for later. **Restart** stops reachable headsets and prepares a new generation from zero — press Start again.
8. When results are in (headsets upload them on reconnect), **New run**.

## Recovery

| Situation | What happens / what to do |
| --- | --- |
| Headset leaves Wi-Fi during playback | Keeps playing to the end, returns to standby, uploads its result when back in range. No action. |
| Headset missed the start | Stays in standby; it never joins late. Include it in the next run. |
| Tablet app restarted mid-run | Headsets are unaffected. The tablet shows the previous run with a banner; results reconcile as headsets report. Close it before a new run. |
| Headset app restarted / slept before Start | Readiness is lost (tile shows the reason). Wake it, then *Retry not ready*. **Unverified** on hardware. |
| Recenter fails | Confirm the headset is worn, the player is visible, it is online, and no run is armed/playing/paused. Face forward and retry. App-level recenter does not change Guardian/boundary data. **Unverified** on hardware. |
| Headset removed or slept during playback | Pauses and records an interruption; resume explicitly or restart. **Unverified** on hardware. |
| "Pairing revoked" in headset | Pair again (Pairing mode on the tablet). |
| Headset shows as out of contact after it was restarted | With **Start on boot** on (default, per headset under *Headsets*), the player should open itself after a reboot. If it does not — first launch after install, or the toggle was off — open *Library → Unknown sources → VR Experience Player*. It finds the tablet even if either device's IP address changed. |
| Headsets drop off when the tablet changes Wi-Fi or gets a new IP | The tablet re-announces itself automatically; headsets reconnect within about 15 s. If not, check both are on the same Wi-Fi (Diagnostics lists the tablet's addresses; Headsets shows each headset's last address). |

## Diagnostics

**Diagnostics → Export diagnostics** writes JSON (devices without credentials, current and past runs, transfers, event log). Headset start timings are logged under the `HeadsetSession` tag (`adb logcat -s HeadsetSession`).

## Developing without headsets

```sh
adb forward tcp:47800 tcp:47800          # if the tablet app runs in an emulator
./gradlew :tools:headset-simulator:run --args="--host 127.0.0.1 --port 47800 --count 4 --offline 3"
```

Simulated headsets run the real session reducer and protocol, download videos over HTTP and verify them, and can simulate leaving coverage (`--offline N`). They are not evidence of start alignment.
