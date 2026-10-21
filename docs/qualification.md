# Qualification status

Nothing in this repository has been run on Quest 3 or on the target tablet yet. Every hardware gate below is **pending**; unit tests and the simulator do not close them.

| Gate (PLAN.md) | Status | Evidence so far |
| --- | --- | --- |
| Both debug APKs build | Done | `./gradlew :vr-controller:assembleDebug :vr-headset:assembleDebug` |
| Embedded rendering of actual content (projection, stereo, 8K decode, first frame behind standby) | Pending | Code path follows Meta's MediaPlayer/PremiumMedia samples (`VideoSurfacePanelRegistration`); hidden-panel surface delivery is assumed, must be verified |
| Android embedded server on the tablet | Partly | Ktor CIO compiles and packages; WebSocket/HTTP-range behaviour on a real tablet not yet exercised |
| TLS hosting | Open | Plaintext + bearer credential in M0 (see protocol doc) |
| Measured start skew < 200 ms (30 four-headset starts) | Pending | Use `test-fixtures/out/sync_360_mono.mp4` (frame counter, flash + beep each second) |
| Venue LAN reachability / client isolation | Pending | Diagnostics screen + headset connection test |
| Headset lifecycle (sleep, unmount, tracking dialogs) | Pending | Mapped from `onSessionStateChanged`, `onVRPause/onVRReady`, `onHMDMounted/Unmounted` |

Record here, per test session: tablet model + Android version, Horizon OS version, app versions, network, media tuple (codec/profile/bit depth/resolution/fps/bitrate), and median/p95/max skew with unknown counts.

## Automated tests

| Suite | Count | Covers |
| --- | --- | --- |
| `shared-protocol` | 10 | round trip, string timestamps, unknown fields, version mismatch, unknown/oversize/malformed, fingerprint, credentials |
| `shared-domain` | 29 | clock estimator (offsets, asymmetry, outliers, ageing, invalid samples); session reducer (readiness, start validation, frozen deadline, early/late wake, lifecycle, controls, duplicates/supersession); run phases; dedup; transfer planner; backoff |
| `vr-headset` (JVM) | 7 | range resume after mid-body disconnect, `200` restart, corrupt bytes, low space, pinned removal, launch reconciliation |

## Emulator + simulator session (2026-10-22)

Tablet app on an API 34 x86_64 emulator (Pixel C profile), four simulated headsets over `adb forward`. Verified working on the Android runtime: Ktor CIO server start on 47800, NSD registration, pairing with code confirmation, credential delivery and reconnect, SAF import with checksum/metadata/thumbnail, HTTP transfers verified by hash on all four, run draft → prepare, clock-gated readiness with the reason shown, controller restart restoring the run as "previous epoch" without re-commanding.

Not verified: the timed start, playing/offline/completed states and controls. The host was heavily loaded (load average ~330) and emulator round trips grew to 0.25–10 s, so clock estimates were (correctly) unusable. Repeat on real devices or a lightly loaded machine.
