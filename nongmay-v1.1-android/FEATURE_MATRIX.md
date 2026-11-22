# FEATURE MATRIX — NongMay Camera v1.1 (1.1.1-debug, versionCode 12)

**Evidence so far:** the on-device self-test of 1.1.0-debug on Pol's Galaxy S25 Ultra (SM-S938B, Android 17,
2026-11-22: **28 PASS · 0 FAIL · 1 SKIP**, `device-reports/2026-11-22_SM-S938B/`). The self-test drives the camera and
the exporters from code, so screens and finger/voice paths are still unverified.
- **WORKING (device)** = passed on the S25 Ultra self-test. **WORKING** = verified on the build machine only
  (build, APK checks, JVM unit tests, synthetic voice evaluation).
- **CODE COMPLETE · UNVERIFIED** = implemented and compiled into the APK, not yet exercised on the phone.
- **DEVICE-GATED** = shown only when the camera/encoder reports the capability; also unverified.
- **EXPERIMENTAL / BLOCKED / NOT IMPLEMENTED** as in the spec.

Paths are relative to `app/src/main/java/com/nongmay/camera/`. Tests: `app/src/test/java/…` (31 JUnit tests, all pass).

## Build & integrity
| Feature | Status | Evidence |
|---|---|---|
| Offline reproducible debug build (`tools/build_apk.sh`) | WORKING | built 2026-11-22, see README §Build; badging `com.nongmay.camera.v11`, minSdk 29, target 35 |
| APK signature (v3), 269 dex classes, no `invoke-custom` | WORKING | `apksigner verify`, `dexdump` |
| Gradle / Android Studio build files | CODE COMPLETE · UNVERIFIED | `settings.gradle`, `app/build.gradle` never run (AGP unreachable) |
| Installs and runs on Galaxy S25 Ultra, Android 17 (API 37) | WORKING (device) | 1.1.0-debug, Pol, 2026-11-22 |
| Side-by-side with v1.0 (`app.nongmay.camera`); in-place update 1.1.0 → 1.1.1 (same debug key) | CODE COMPLETE · UNVERIFIED | different application id; same signer certificate b51f1b7b… |
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
| Photo export 1080×1920 JPEG q95 + embedded sRGB ICC | WORKING (device) | self-test: 1080×1920, 593 KB, ICC present, 262 ms; GPS stripping not exercised (test photo had no GPS) |
| Video export 1080×1920 H.264, BT.709 SDR | WORKING (device) | self-test: 1080p30 clip → 1080×1920, 30.01 fps, BT.709/SDR tags, 10.4 Mbps (asked 10), exact duration, 706 ms for 2.87 s |
| AAC audio passthrough in video export | CODE COMPLETE · UNVERIFIED | 1.1.0 test clip had no sound (no mic permission); 1.1.1 self-test records with the mic |
| Export from a 4K HEVC source (what Samsung's camera records) | CODE COMPLETE · UNVERIFIED | added to the 1.1.1 self-test |
| HDR source → SDR (decoder transfer request → GL shader → warning) | CODE COMPLETE · UNVERIFIED | `VideoTranscoder`, `gl/QuadRenderer` |
| Non-AAC source audio (re-encode) | NOT IMPLEMENTED | export continues without that track and says so |
| Export profiles: P1080_SDR, V1080_SDR (stable) | CODE COMPLETE · UNVERIFIED | `storymax/StoryProfiles` |
| P1440_TEST, V1080_BR8/12/16, V1440_TEST, DIRECT_ORIGINAL | EXPERIMENTAL | A/B hypotheses, `STORY_AB_TEST.md` |
| V_HDR_TEST (HDR retention for IG) | NOT IMPLEMENTED | needs 10-bit GL + HEVC Main10 path |
| Save as new MediaStore item (`IS_PENDING` → published) | WORKING (device) | every self-test capture/export saved, probed, then removed |
| Share (Sharesheet / Instagram intent) | CODE COMPLETE · UNVERIFIED | `storymax/ShareUtil` |
| Capture-to-Story mode (shoot 9:16 framing → opens StoryMax) | CODE COMPLETE · UNVERIFIED | `ui/CameraActivity` mode STORY |
| Quality Lab: same source × profiles, export time/size/bitrate/colour tags, blind labels | CODE COMPLETE · UNVERIFIED | `storymax/quality/LabActivity`, `LabStore` |
| A/B worksheet generator (blind codes) | WORKING | `storymax/quality/AbWorksheet`, unit test |
| Posting to Instagram / measuring IG's recompression | BLOCKED (by policy) | the user posts manually; no IG API, no scraping |

## Pillar B — True Pro
| Feature | Status | Evidence |
|---|---|---|
| Exposure / white-balance math (EV, shutter labels, Kelvin → CCM gains) | WORKING | `camera/pro/ExposureMath`, `WhiteBalanceMath`, `ProMathTest` |
| Manual ISO / shutter | WORKING (device) | all 4 cameras: asked ISO 400 · 1/100 → result ISO 398 · 1/100 |
| EV, AE/AWB lock | DEVICE-GATED · UNVERIFIED | `CameraController`, `ui/ProPanel` |
| Manual focus (diopters) | WORKING (device) | all 4 cameras: asked 1.00 D → reported 1.00 D |
| AF-S / AF-C, tap-to-focus/meter, focus-before-capture | CODE COMPLETE · UNVERIFIED | 1 AE + 1 AF region on this phone, no AWB region |
| White balance Kelvin (2300–10000 K) via colour gains | WORKING (device) | all 4 cameras: R/B gain ratio asked 0.631 → got 0.631 |
| RAW (DNG) + JPEG | WORKING (device) | all 4 cameras: DNG 16.5–23.8 MB |
| Logical back camera: lens presets 0.6× / 1× / 2.74× / 5.06× backed by real lenses | DEVICE-GATED | inspector: physical ids 2 / 5 / 6 / 7; 1× capture reported physical 5; which lens streams at each preset → 1.1.1 self-test |
| Lens chips, real active-lens label in the viewfinder | CODE COMPLETE · UNVERIFIED | `CameraCaps`, `CameraActivity` |
| OIS on/off toggle | DEVICE-GATED (hidden here) | S25 Ultra exposes no OIS control to third-party apps (mode list = OFF) |
| Fixed aperture shown as fixed | CODE COMPLETE · UNVERIFIED | `CameraCaps` |
| Per-camera / per-mode presets that survive restart; "รีเซ็ต AUTO" | CODE COMPLETE · UNVERIFIED | `CaptureSettings.toPreset`, prefs |
| Drive: single, burst 5/10, AEB ±1×3 / ±2×5, interval 3 s×20 / 10 s×30, self-timer | CODE COMPLETE · UNVERIFIED | `CameraActivity` |
| Flash (off/auto/on/torch) with AE precapture | DEVICE-GATED | `CameraController` |
| Overlays: grids (thirds, golden, crosshair), level, histogram, zebra, focus peaking | CODE COMPLETE · UNVERIFIED | `camera/overlays/*`; from the YUV preview, approximate (disclosed in UI) |
| Video record 1080p30 H.264 | WORKING (device) | self-test: 2.87 s, 8.1 Mbps, no sound (mic not granted) |
| Pro Video: 4K 24/30, 1080p/720p 24/30/60, H.264/HEVC, bitrate, audio, stabilisation | DEVICE-GATED · UNVERIFIED | offered modes match the inspector; 4K30 HEVC and 1080p60 added to the 1.1.1 self-test |
| 4K60 and 8K video | BLOCKED (device) | not offered to third-party apps by the camera HAL (encoders could do it) |
| Manual exposure during video | DEVICE-GATED | same request path as photo |
| 10-bit HLG video | NOT IMPLEMENTED | possible here: HLG10 profile on all cameras + hardware HEVC HDR encoder |
| LOG video | BLOCKED (device) | no LOG dynamic-range profile exposed (HLG10 / STANDARD only) |
| High-speed / slow motion 120–240 fps | NOT IMPLEMENTED | possible here: 1080p and 720p at 120/240 fps on cameras 0 and 2 |
| 200 MP stills | BLOCKED (device) | ultra-high-resolution sensor mode not exposed; max 4080×3060 |
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
| Accuracy on Pol's real voice / S25 Ultra mic, battery, heat | NOT MEASURED | 1.1.0 self-test skipped the mic (permission not granted); 1.1.1 self-test asks for it |
| Voice start/stop video recording | NOT IMPLEMENTED | mic is reserved for the soundtrack while recording |
| Android `SpeechRecognizer` engine | NOT IMPLEMENTED (rejected) | see DECISIONS D4 |
| Migration of v1.0 voice templates | NOT IMPLEMENTED | v1.0 data is in another app; re-enroll |

## Pillar D — Speed & UX
| Feature | Status | Evidence |
|---|---|---|
| Opens straight to live preview, no splash/sign-in; "start in last mode" or "always AUTO" | CODE COMPLETE · UNVERIFIED | `CameraActivity`, `settings/Settings` |
| Big thumb shutter, touch-down shutter option, volume-key shutter, haptics, left-handed layout | CODE COMPLETE · UNVERIFIED | `ui/ShutterButton`, `CameraActivity` |
| Shutter feedback vs. real save state kept separate | CODE COMPLETE · UNVERIFIED | ack on next frame; "saved" only after `IS_PENDING=0` |
| Local timing markers p50/p95 (cold/warm start, first frame, tap→ack/request/sensor/JPEG/saved, voice, switches, video, export) | WORKING (device) | report produced on the phone; 1.1.1 fixes two artefacts found there (see `PERFORMANCE_REPORT.md`) and keeps self-test samples separate |
| Capability inspector → `Download/NongMay/DEVICE_CAPABILITIES_*.json/md` | WORKING (device) | `DEVICE_CAPABILITIES.md/json`; 1.1.1 adds max fps per video size |
| On-device self-test (per-camera capture/manual/RAW, video, Story exports, mic loop) | WORKING (device) | 28 PASS · 0 FAIL · 1 SKIP; 1.1.1 adds lens/zoom, ZSL shutter lag, 4K30 HEVC, 1080p60, sound |
| Thermal status warning | CODE COMPLETE · UNVERIFIED | thermal status readable on the phone (1 = light); warning UI not triggered yet |
| Thai + English UI, content descriptions | CODE COMPLETE · UNVERIFIED | `res/values*/strings.xml`, `setContentDescription` |
| Jetpack Macrobenchmark / Baseline Profiles | BLOCKED | needs AGP/Maven |
| CameraX ZERO_SHOT_LAG | NOT IMPLEMENTED (Camera2 instead) | Camera2 `CONTROL_ENABLE_ZSL` when advertised |
