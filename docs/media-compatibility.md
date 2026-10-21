# Media compatibility

No tuple has been qualified on Quest 3 yet. Add a row only after on-device preparation and playback succeed, with the evidence.

| Container | Codec / profile / bit depth | Resolution | FPS | Bitrate | Projection / stereo | Quest 3 result | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| MP4 | H.264 High, 8-bit | 2048×1024 | 30 | ~0.2 Mb/s | 360° mono | pending | `sync_360_mono.mp4` fixture |
| MP4 | H.264 High, 8-bit | 2048×2048 | 30 | low | 360° top/bottom | pending | `eyes_360_tb.mp4` fixture |
| MP4 | H.264 High, 8-bit | 3840×1080 | 30 | low | flat side-by-side | pending | `eyes_flat_sbs.mp4` fixture |
| MP4 | AV1 Main, 10-bit, level 6.3 | 8192×4090 | 60 | 81 Mb/s | 360° mono | reported working on headsets; this app pending | "Forest MASTER RF45" (854 MB, 83.5 s). Within Meta's 8192×4096 limit. Audio: AAC stereo (default) + AC-3 stereo |
| MP4 | AV1 Main, 10-bit, level 6.3 | 8192×4320 | 60 | 27 Mb/s | 360° mono | reported working on headsets; this app pending | "Lava MASTER RF40" (297 MB, 86.2 s). Height exceeds Meta's 8192×4096 decode limit; 1.9:1 is not 2:1, so import suggests Flat — set 360° manually. Same audio tracks |

Known limits in code: right-eye-first stereo is rejected as `UNSUPPORTED_MEDIA`; cubemaps, HDR, DRM, spatial audio and transcoding are not supported.

"Reported working" means the project team reported (2026-10-22) that the file plays on real headsets; the player used was not recorded. It is not yet verified with this app's embedded Media3 / Spatial SDK player.
