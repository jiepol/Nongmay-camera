# DEVICE CAPABILITIES — Galaxy S25 Ultra

**Status: NOT RUN.** No S25 Ultra (or any Android device) was connected to the build machine, so no capability
data exists yet. `DEVICE_CAPABILITIES.json` in this folder is a placeholder that says exactly that. Nothing
below is assumed from spec sheets: the app shows only what Camera2 / MediaCodec report at runtime.

## How to generate the real report (1 minute)
1. Install `NongMayCamera-1.1.0-debug.apk`, open it, allow Camera.
2. Settings (⚙) → **Diagnostics** → **ตรวจความสามารถกล้อง (DEVICE_CAPABILITIES)**.
3. Files appear in `Download/NongMay/DEVICE_CAPABILITIES_<time>.json` and `.md` (no photos, audio, location or
   account data). Commit them here to replace the placeholder.

## What the generator records (`camera/inspect/CapabilityInspector.java`)
Per camera id (logical and every exposed physical camera): facing, hardware level, capability list,
sensor orientation and physical size, focal lengths and 35 mm equivalent, apertures and whether aperture is
adjustable, ISO / exposure-time / frame-duration ranges, EV range and step, AE/AF/AWB modes and locks,
max 3A regions, min focus distance and focus calibration, Kelvin WB possibility (colour calibration),
flash, OIS and video stabilisation modes, zoom ratio range and max digital zoom, zoom presets backed by
physical lenses, active array, top stream sizes, max-resolution mode sizes, dynamic-range (10-bit) profiles,
ZSL request key, face-detect modes, timestamp source, camera extensions, concurrent camera sets, and the
video modes this app can actually use. Device-wide: H.264/HEVC encoders and their limits, thermal status,
free storage, and whether an on-device Thai speech recognizer exists (reported only; Voice Shutter does not use it).

## Items to verify on the S25 Ultra (all UNVERIFIED)
- Which physical lenses (0.6×, 1×, 3×, 5×) Samsung exposes to third-party apps, and whether 10× is a crop.
- `MANUAL_SENSOR`, `MANUAL_POST_PROCESSING` (Kelvin WB), `RAW` per lens.
- Real ISO and shutter ranges per lens; fixed apertures.
- `CONTROL_ENABLE_ZSL` support; 4K60 / 8K30 H.264 and HEVC encoder limits; HDR10/HLG 10-bit profiles.
- Self-test (Diagnostics → Self-test) results per camera: JPEG save, manual ISO/shutter/focus/WB echo in
  the capture result, DNG write, video record, StoryMax photo/video export, microphone loop.
