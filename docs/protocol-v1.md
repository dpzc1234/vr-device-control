# Control protocol v1

Source of truth: `shared-protocol/` (`Envelope.kt`, `Payloads.kt`, `Http.kt`, `Errors.kt`). This document summarises it; if they disagree, the code wins and this file is out of date.

## Transport

| Item | Value |
| --- | --- |
| Discovery | DNS-SD `_vrctrl._tcp`, TXT `cid` (controller ID), `pv` (protocol major), `name` |
| Preferred port | 47800, falling back to an ephemeral port (always read it from discovery or the Diagnostics screen) |
| Control | `GET /v1/control` → WebSocket, JSON text frames, ≤ 64 KiB each |
| Media | `GET/HEAD /v1/assets/{sha256}`, single `Range: bytes=N-`, strong ETag `"<sha256>"`, `If-Range` honoured |
| Auth | `Authorization: Bearer <deviceId>.<secret>`; never in query strings |
| Heartbeat | 2 s each way; connection stale after 6 s without inbound traffic |
| Reconnect | 0.5 s → 15 s exponential backoff with jitter |

### Transport security (open decision gate)

M0 ships **plaintext HTTP/WebSocket with a per-headset bearer credential**. The plan requires TLS with a pinned controller identity; that remains the open "Android embedded TLS hosting" gate. Until it is closed:

- operate only on a trusted local network;
- pairing uses a 6-digit code shown in the headset and confirmed by the operator on the tablet (protects against approving the wrong device, not against a network eavesdropper);
- credentials are stored hashed (SHA-256) on the tablet and Keystore-encrypted (AES-GCM) on the headset.

Remove `cleartextTrafficPermitted` from `vr-headset/src/main/res/xml/network_security_config.xml` when TLS lands.

## Pairing

1. Operator enables **Pairing mode** on the tablet (auto-off after 10 minutes, at most 8 pending requests).
2. Headset `POST /v1/pairing/requests` `{deviceId, deviceName, pairingCode, nonce}` → `201 {requestId, status: PENDING}`; `403` when pairing mode is off.
3. Headset polls `GET /v1/pairing/requests/{id}` with `X-Pairing-Nonce`. After operator approval the response carries `credentialSecret` **exactly once**.
4. Re-pairing rotates the credential and closes the old connection. "Forget headset" revokes it.

## Envelope

Every message: `protocolMajor, type, messageId, senderId, senderInstanceId, connectionId, payload`, optional `correlationId`. Session commands (`PREPARE, START_AT, CANCEL_START, PAUSE, RESUME, STOP`) also require `runId, controllerEpoch, generation, receiverInstanceId` or are rejected as `MALFORMED`. 64-bit microsecond timestamps are decimal strings.

Headset validation order: receiver process → connection → controller epoch → dedup lookup (same ID + same semantic fingerprint returns the cached reply; same ID + different content → `DUPLICATE_CONFLICT`) → reducer rules (run/generation, state, token, revision, clock, expiry).

## Messages

| Type | Direction | Payload |
| --- | --- | --- |
| `HELLO` / `WELCOME` | H→T / T→H | identity + capabilities / controller ID, epoch, connection ID, heartbeat settings, operator label |
| `HEARTBEAT` | both | `sentAtUs` |
| `STATUS` | H→T | playback state, run, position, app active, worn, battery, storage, last error |
| `CLOCK_PROBE` / `CLOCK_REPLY` | H→T / T→H | `h1` / `h1, c2, c3` (tablet answers on the socket loop, before any app work) |
| `CLOCK_STATUS` | H→T | estimate ID, offset, uncertainty, RTT, age, acceptable |
| `INVENTORY` | H→T | paginated verified assets (150 per page) |
| `ENSURE_ASSET` / `REMOVE_ASSET` / `ASSET_RESULT` | T→H / T→H / H→T | durable content operations |
| `TRANSFER_PROGRESS` | H→T | state, committed bytes, error |
| `PREPARE` → `CONTROL_RESULT`, then `READY` / `NOT_READY` | T→H → H→T | asset + presentation revision, `validUntilControllerUs` |
| `START_AT` → `START_ACCEPTED` or `CONTROL_RESULT(rejected)` | T→H → H→T | token, revision, `clockSampleId`, `startAtControllerUs`, `acceptBeforeControllerUs`, `positionUs = 0` |
| `STARTED` / `COMPLETED` / `RUN_ERROR` | H→T | live observed events (convenience) |
| `RESULT_BATCH` / `RESULTS_ACK` | H→T / T→H | journaled run records — the source of truth after reconnect |
| `CANCEL_START`, `PAUSE`, `RESUME`, `STOP` → `CONTROL_RESULT` | T→H → H→T | `validUntilControllerUs` (3 s window) |
| `DEVICE_CONFIG` | T→H | `startOnBoot` — whether the headset app should launch after reboot (also reported on `STATUS`) |
| `RECENTER` / `RECENTER_RESULT` | T→H / H→T | Immediate operator request to align app space with the rider's current pose; accepted only while worn, active and between runs |
| `ERROR` | both | typed error for undecodable/unknown messages |

## Error codes

`UNAUTHORIZED, PROTOCOL_MISMATCH, UNKNOWN_COMMAND, MESSAGE_TOO_LARGE, MALFORMED, STALE_CONNECTION, STALE_RUN, STALE_PREPARATION, ASSET_MISSING, ASSET_IN_USE, HASH_MISMATCH, UNSUPPORTED_MEDIA, STORAGE_FULL, NOT_ACTIVE, CLOCK_UNCERTAIN, TOO_LATE, ALREADY_ARMED, INVALID_STATE, DUPLICATE_CONFLICT, PLAYBACK_FAILED, TRANSFER_FAILED, INTERRUPTED`. Operator wording lives in `shared-domain/.../OperatorMessages.kt`.

## Timing defaults (`shared-domain/.../Timing.kt`)

12 probes, 100 ms apart; estimate from the median offset of the lowest-RTT half; uncertainty = half best RTT + dispersion + 100 ppm ageing. Start requires an estimate ≤ 10 s old and ≤ 20 ms uncertain. Start is scheduled 2 s ahead; headsets reject it with < 500 ms lead; scheduler lateness > 40 ms is a missed start (`TOO_LATE`), never a late start. All are engineering starting points to be tuned from measurement.
