# DECISIONS — NongMay Camera v1.1

Date: 2026-11-22. Branch: `feat/nongmay-v1.1-master`. Everything here was decided on a cloud build machine
with **no Android device, no emulator (no KVM) and no access to Google Maven / Maven Central**. Anything that
needs the phone is marked UNVERIFIED in `FEATURE_MATRIX.md`.

## D1. Source state and safety
- The repository (`jiepol/Nongmay-camera`, public) contains only `README.md`, `NongMayCamera-1.0.apk` and
  `screenshots/`. **No v1.0 source exists in the repo** and the v1.0 signing key is not available.
- v1.1 is therefore a **new standalone source tree** in `nongmay-v1.1-android/`. The v1.0 APK, README and
  screenshots are untouched. The v1.0 APK (`app.nongmay.camera`, versionCode 1, minSdk 26) was only inspected
  for identification; none of its code was reused and no "recovered" source was reconstructed from it.
- Application id **`com.nongmay.camera.v11`** (namespace `com.nongmay.camera`): it installs **next to** v1.0
  instead of pretending to be a signed upgrade. v1.0 voice data is not migrated (different app, different storage).
- Debug signing only, with a key generated on the build machine (`~/.nongmay/nongmay-v11-debug.jks`). The key is
  **not committed** (public repo, `.gitignore` blocks `*.jks`). No release/production signing config exists.
- Work happens on a feature branch; `main` is never pushed; no force-push.

## D2. Toolchain: Java 8 + platform APIs, scripted offline build
- Gradle/AGP, CameraX, Media3, Kotlin and any Maven dependency were unreachable (proxy policy). The verified
  build is `tools/build_apk.sh`: aapt2 → javac (`-source 8 -target 8`, android.jar API 35) → dx (min-sdk 26,
  so **no lambdas**; anonymous classes only) → zipalign → apksigner (APK Signature Scheme v3).
- `settings.gradle`, `build.gradle`, `app/build.gradle` describe the same app for Android Studio (AGP 8.7.3,
  compileSdk 35, minSdk 29, Java 8, JUnit 4.13.2). **They have never been run** — no wrapper jar is included
  because `services.gradle.org` was also blocked.
- minSdk 29 (Android 10): MediaStore `IS_PENDING`, `CONTROL_ZOOM_RATIO` (API 30, gated), Photo Picker via
  `ACTION_PICK_IMAGES` when present (fallback `ACTION_OPEN_DOCUMENT`).

## D3. Camera: Camera2 directly (not CameraX)
- CameraX would have been the default choice, but it is a Maven dependency. Camera2 also gives the Pro pillar
  what it needs anyway: manual ISO/exposure/focus/white balance, RAW, per-physical-camera capabilities.
- One `CameraController` thread owns the device. Slider changes are **repeating-request updates**, never session
  rebuilds. Session configuration falls back (drop analysis stream → drop RAW) instead of failing.
- A `sessionGen` counter discards callbacks from stale sessions (fast mode/lens switching).
- Lens list = what Camera2 exposes (logical + physical ids). The lens label shows the zoom ratio plus the
  physical lens actually in use, read from `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` in each capture result
  ("เลนส์จริง …"); the app never calls a ratio "optical" by itself. Samsung may hide some physical cameras from
  third-party apps — the inspector reports what is actually exposed.
- Fixed aperture is reported as fixed (`LENS_INFO_AVAILABLE_APERTURES`); no fake "f-stop" slider.
- Fast still: `TEMPLATE_STILL_CAPTURE` with `CONTROL_ENABLE_ZSL` when the device advertises it and the user
  leaves ZSL on; AE precapture only when flash is needed. The shutter acknowledgement is drawn on the next
  Choreographer frame; "saved" is only shown after MediaStore `IS_PENDING=0`.

## D4. Voice Shutter: on-device, speaker-dependent template matching (no SpeechRecognizer)
- Rejected: Android `SpeechRecognizer` (needs a Thai offline model that may not exist, can silently use the
  cloud on some devices, adds ~1 s of end-pointing latency, would mis-hear the invented word "น้องเม").
  Rejected: bundling a neural KWS model (no Maven, no model download, no Thai keyword model to start from).
- Chosen: **enroll-by-example**. The owner says the phrase 3 times; the app keeps 26-band mel power envelopes
  of those takes plus the background level (never audio; app-private storage; `allowBackup=false`).
  Live audio (`AudioRecord`, `VOICE_RECOGNITION`, 16 kHz, foreground only) → energy VAD with background
  tracking → isolated segment → **noise-compensated** mel matching (both sides rebuilt as "clean estimate + the
  same background", clamp 1.25×) and a second **virtual-room** hypothesis (late reverb RT60 0.3 s / DRR +6 dB)
  → MFCC + CMVN + deltas → DTW against each take → threshold calibrated from the owner's own repetitions ×
  sensitivity (STRICT 1.20 / NORMAL 1.30 / SENSITIVE 1.45), median agreement, duration gate, 1.5 s cooldown.
- Works for any phrase or language (it matches sound, not text): default `น้องเม`, custom `โฮ่ง ๆ`, `ชาลี`,
  `ถ่ายเลย`, `cheese`, … It is **not** a lock: a similar voice can pass (synthetic test: ~15% at NORMAL).
- The first prototype (plain MFCC/DTW) failed completely in noise and reverb in the synthetic test (0% in
  every noisy condition). The compensation and room hypothesis were added and tuned with `tools/voice_eval`
  (`VOICE_EVAL.md`). Because the tuning used the same synthetic corpus, those numbers are optimistic.
- Lifecycle: engine starts only when the camera screen is resumed and Voice Shutter is enabled; it stops in
  `onPause`, when video recording starts (the microphone belongs to the soundtrack) and in Settings/Story screens.
  The app's own shutter sound is suppressed for the matcher. Never falls back to remote speech (no INTERNET permission).

## D5. StoryMax: platform codecs, explicit colour management
- Photo: `BitmapRegionDecoder` in sRGB, EXIF orientation applied, ≤2× downscale steps, JPEG q95 with an
  embedded **sRGB ICC v2 profile generated by `tools/make_srgb_icc.py`** (LittleCMS round trip error 0). GPS/EXIF
  are not copied unless the user turns "keep EXIF" on (GPS is always stripped).
- Video: MediaExtractor → MediaCodec decoder → SurfaceTexture → GLES 2 quad (crop/fit/rotation, `QuadMath`)
  → H.264 High encoder (VBR, BT.709 SDR tags) → MediaMuxer; AAC audio passthrough; frame-rate cap by dropping
  frames on timestamps (`FrameRatePolicy`), never duplicating. HDR sources: decoder `KEY_COLOR_TRANSFER_REQUEST`
  SDR when supported, otherwise a GL_EXT_YUV_target tone-map shader (`ToneMap`), otherwise an honest
  "HDR → SDR may look flat" warning. True HDR retention (`V_HDR_TEST`) is NOT IMPLEMENTED.
- Outputs are always new MediaStore items; the source is opened read-only. No Instagram API: sharing is a normal
  `ACTION_SEND` (optionally targeted at `com.instagram.android`), the user posts manually.
- Only claim allowed before evidence: "Story-optimized export for Galaxy S25 Ultra". Whether Instagram keeps more
  detail from any profile is an **A/B question** (`STORY_AB_TEST.md`), not a fact.

## D6. Speed measurement
- Jetpack Macrobenchmark / Baseline Profiles need AGP → BLOCKED. Instead `PerfLog` records local markers
  (process start, camera open, session configured, first frame, touch → ack frame, touch → request,
  touch → `SENSOR_TIMESTAMP`, touch → JPEG, touch → MediaStore saved, voice end → trigger/request, switches,
  video start/stop, export speed), persists them, and reports p50/p95 in Diagnostics → `Download/NongMay/PERFORMANCE_*.md/csv`.
- Voice latency uses `AudioRecord.getTimestamp` (BOOTTIME) to map "end of speech" onto the same clock as the capture request.

## D7. Privacy
- Permissions: CAMERA, RECORD_AUDIO. **No INTERNET**, no location, no storage permission (MediaStore + pickers).
- No telemetry. Diagnostics files contain device capabilities and timings only — no media, no audio, no GPS,
  no account data — and are written only when the user taps a Diagnostics button.

## D8. Supported software
- Target: Galaxy S25 Ultra (Android 15/16, One UI 7/8). Runs on any Android 10+ phone with Camera2; Pro features
  are capability-gated per camera. Built against API 35.
