# FEATURE MATRIX — NongMay Camera v1.1 (1.1.0-debug, versionCode 11)

**Read this first:** nothing in this table has run on a phone yet (no device or emulator was available).
- **WORKING** is used only for things actually verified on the build machine (build, APK checks, JVM unit tests,
  synthetic voice evaluation).
- **CODE COMPLETE · UNVERIFIED** = implemented end-to-end and compiled into the APK, but never executed on Android.
- **DEVICE-GATED** = shown only when the camera/encoder reports the capability; also unverified.
- **EXPERIMENTAL / BLOCKED / NOT IMPLEMENTED** as in the spec.

Paths are relative to `app/src/main/java/com/nongmay/camera/`. Tests: `app/src/test/java/…` (31 JUnit tests, all pass).

## Build & integrity
| Feature | Status | Evidence |
|---|---|---|
| Offline reproducible debug build (`tools/build_apk.sh`) | WORKING | built 2026-11-22, see README §Build; badging `com.nongmay.camera.v11`, minSdk 29, target 35 |
| APK signature (v3), 269 dex classes, no `invoke-custom` | WORKING | `apksigner verify`, `dexdump` |
| Gradle / Android Studio build files | CODE COMPLETE · UNVERIFIED | `settings.gradle`, `app/build.gradle` never run (AGP unreachable) |
| Side-by-side install with v1.0 (`app.nongmay.camera`) | CODE COMPLETE · UNVERIFIED | different application id; not installed anywhere yet |
| No INTERNET / location / storage permission | WORKING | badging lists only CAMERA + RECORD_AUDIO |

## Pillar A — StoryMax
| Feature | Status | Evidence |
|---|---|---|
| 9:16 crop/fit math (cover, pan, zoom, EXIF orientation, sample size) | WORKING | `storymax/CropMath`, `StoryMathTest` |
| Export quad geometry, landscape-encoder fallback, H.264 level choice | WORKING | `storymax/QuadMath`, `StoryMathTest` (3 tests) |
| Frame-rate cap by dropping frames on timestamps | WORKING | `storymax/FrameRatePolicy`, unit test |
| HDR→SDR tone curve (PQ/HLG → BT.709) | WORKING (math only) | `storymax/ToneMap`, unit test; GPU path unverified |
| Import from Photo Picker / Gallery "Share → StoryMax" / own launcher icon | CODE COMPLETE · UNVERIFIED | `storymax/StoryActivity` (`ACTION_PICK_IMAGES`, `ACTION_SEND`) |
| Story canvas: pinch/drag reframe, fit/cover, safe-zone overlay | CODE COMPLETE · UNVERIFIED | `storymax/StoryCanvasView` |
| Photo export 1080×1920 JPEG q95 + embedded sRGB ICC, GPS stripped | CODE COMPLETE · UNVERIFIED | `storymax/PhotoExporter`, `util/JpegIcc` (ICC unit-tested) |
| Video export 1080×1920 H.264 High, BT.709 SDR, AAC passthrough | CODE COMPLETE · UNVERIFIED | `storymax/VideoTranscoder`, `gl/*`; MediaCodec never executed |
| HDR source → SDR (decoder transfer request → GL shader → warning) | CODE COMPLETE · UNVERIFIED | `VideoTranscoder`, `gl/QuadRenderer` |
| Non-AAC source audio (re-encode) | NOT IMPLEMENTED | export continues without that track and says so |
| Export profiles: P1080_SDR, V1080_SDR (stable) | CODE COMPLETE · UNVERIFIED | `storymax/StoryProfiles` |
| P1440_TEST, V1080_BR8/12/16, V1440_TEST, DIRECT_ORIGINAL | EXPERIMENTAL | A/B hypotheses, `STORY_AB_TEST.md` |
| V_HDR_TEST (HDR retention for IG) | NOT IMPLEMENTED | needs 10-bit GL + HEVC Main10 path |
| Save as new MediaStore item (source untouched) + share (Sharesheet / Instagram intent) | CODE COMPLETE · UNVERIFIED | `camera/MediaStoreUtil`, `storymax/ShareUtil` |
| Capture-to-Story mode (shoot 9:16 framing → opens StoryMax) | CODE COMPLETE · UNVERIFIED | `ui/CameraActivity` mode STORY |
| Quality Lab: same source × profiles, export time/size/bitrate/colour tags, blind labels | CODE COMPLETE · UNVERIFIED | `storymax/quality/LabActivity`, `LabStore` |
| A/B worksheet generator (blind codes) | WORKING | `storymax/quality/AbWorksheet`, unit test |
| Posting to Instagram / measuring IG's recompression | BLOCKED (by policy) | the user posts manually; no IG API, no scraping |

## Pillar B — True Pro
| Feature | Status | Evidence |
|---|---|---|
| Exposure / white-balance math (EV, shutter labels, Kelvin → CCM gains) | WORKING | `camera/pro/ExposureMath`, `WhiteBalanceMath`, `ProMathTest` |
| Manual ISO / shutter / EV / AE-AWB lock | DEVICE-GATED | `CameraController`, `ui/ProPanel`; needs `MANUAL_SENSOR` |
| Manual focus (diopters), AF-S / AF-C, tap-to-focus/meter, focus-before-capture | DEVICE-GATED | `CameraController` (3A regions in zoom-ratio coordinates) |
| White balance: Auto, presets, Kelvin (2300–10000 K) | DEVICE-GATED | Kelvin needs `MANUAL_POST_PROCESSING` |
| RAW (DNG) / RAW+JPEG | DEVICE-GATED | `DngCreator` in `PhotoSaver`; hidden when `RAW_SENSOR` absent |
| Lens selection (logical + exposed physical cameras), zoom chips, real active-lens label | CODE COMPLETE · UNVERIFIED | `CameraCaps`, `CameraActivity` |
| Fixed aperture shown as fixed | CODE COMPLETE · UNVERIFIED | `CameraCaps` |
| Per-camera / per-mode presets that survive restart; "รีเซ็ต AUTO" | CODE COMPLETE · UNVERIFIED | `CaptureSettings.toPreset`, prefs |
| Drive: single, burst 5/10, AEB ±1×3 / ±2×5, interval 3 s×20 / 10 s×30, self-timer | CODE COMPLETE · UNVERIFIED | `CameraActivity` |
| Flash (off/auto/on/torch) with AE precapture | DEVICE-GATED | `CameraController` |
| Overlays: grids (thirds, golden, crosshair), level, histogram, zebra, focus peaking | CODE COMPLETE · UNVERIFIED | `camera/overlays/*`; from the YUV preview, approximate (disclosed in UI) |
| Pro Video: 720p/1080p/4K(/8K if offered), fps, H.264/HEVC, bitrate, audio on/off, EIS/OIS/preview stab | DEVICE-GATED | `camera/VideoRecorder`, `EncoderCaps` |
| Manual exposure during video | DEVICE-GATED | same request path as photo |
| 10-bit HDR / LOG video, high-speed (120/240 fps) sessions, max-resolution (200 MP) stills | NOT IMPLEMENTED | listed in README limitations |
| Time-lapse video | NOT IMPLEMENTED | interval *photo* drive exists |
| Location tagging | NOT IMPLEMENTED | by design (no location permission) |

## Pillar C — Voice Shutter
| Feature | Status | Evidence |
|---|---|---|
| Matcher: VAD + background tracking + noise-compensated mel/MFCC + DTW + virtual-room hypothesis | WORKING (synthetic) | `voice/*`, `VoiceCoreTest` (incl. 12 dB noise test), `VOICE_EVAL.md` |
| Default `น้องเม`, restore default, custom phrases (`โฮ่ง ๆ`, `ชาลี`, `ถ่ายเลย`, `cheese`, any phrase) | CODE COMPLETE · UNVERIFIED | `voice/VoiceActivity`, `VoiceProfileStore` |
| Enrollment (3 takes, consistency + "too short" warnings), live test screen with score vs. threshold | CODE COMPLETE · UNVERIFIED | `VoiceActivity` |
| Sensitivity STRICT / NORMAL / SENSITIVE | WORKING (synthetic) | thresholds tuned in `VOICE_EVAL.md` |
| Offline only, foreground only, stops during video recording, own shutter sound ignored, 1.5 s cooldown | CODE COMPLETE · UNVERIFIED | `voice/AudioKwsEngine`, `CameraActivity.stopVoice()` |
| Mic-active indicator on the viewfinder | CODE COMPLETE · UNVERIFIED | `CameraActivity` |
| Accuracy on Pol's real voice / S25 Ultra mic, battery, heat | NOT MEASURED | checklist in README |
| Voice start/stop video recording | NOT IMPLEMENTED | mic is reserved for the soundtrack while recording |
| Android `SpeechRecognizer` engine | NOT IMPLEMENTED (rejected) | see DECISIONS D4 |
| Migration of v1.0 voice templates | NOT IMPLEMENTED | v1.0 data is in another app; re-enroll |

## Pillar D — Speed & UX
| Feature | Status | Evidence |
|---|---|---|
| Opens straight to live preview, no splash/sign-in; "start in last mode" or "always AUTO" | CODE COMPLETE · UNVERIFIED | `CameraActivity`, `settings/Settings` |
| Big thumb shutter, touch-down shutter option, volume-key shutter, haptics, left-handed layout | CODE COMPLETE · UNVERIFIED | `ui/ShutterButton`, `CameraActivity` |
| Shutter feedback vs. real save state kept separate | CODE COMPLETE · UNVERIFIED | ack on next frame; "saved" only after `IS_PENDING=0` |
| Local timing markers p50/p95 (cold/warm start, first frame, tap→ack/request/sensor/JPEG/saved, voice, switches, video, export) | CODE COMPLETE · UNVERIFIED | `perf/PerfLog`, Diagnostics |
| Capability inspector → `Download/NongMay/DEVICE_CAPABILITIES_*.json/md` | CODE COMPLETE · UNVERIFIED | `camera/inspect/CapabilityInspector` |
| On-device self-test (per-camera capture/manual/RAW, video, Story exports, mic loop) | CODE COMPLETE · UNVERIFIED | `perf/SelfTest` |
| Thermal status warning | CODE COMPLETE · UNVERIFIED | `PowerManager` thermal listener in `CameraActivity` |
| Thai + English UI, content descriptions | CODE COMPLETE · UNVERIFIED | `res/values*/strings.xml`, `setContentDescription` |
| Jetpack Macrobenchmark / Baseline Profiles | BLOCKED | needs AGP/Maven |
| CameraX ZERO_SHOT_LAG | NOT IMPLEMENTED (Camera2 instead) | Camera2 `CONTROL_ENABLE_ZSL` when advertised |
