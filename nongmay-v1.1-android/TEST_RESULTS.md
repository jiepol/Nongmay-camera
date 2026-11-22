# TEST RESULTS — NongMay Camera v1.1

## Galaxy S25 Ultra on-device self-test (1.1.0-debug, 2026-11-22 14:25)
SM-S938B, Android 17 (API 37). Run by Pol from Settings → Diagnostics → Self-test. Raw report:
`device-reports/2026-11-22_SM-S938B/SELFTEST_20261122_1425.md`. **28 PASS · 0 FAIL · 1 SKIP.**

| Area | Result |
|---|---|
| Open + session, cameras 0/1/2/3 | PASS — 440–702 ms including the first result |
| JPEG capture + MediaStore save, all 4 | PASS — 4080×3060 / 4000×3000 / 4080×3060 / 3392×2544; request→saved 652–981 ms; camera 0 reported physical lens 5 |
| Manual ISO + shutter, all 4 | PASS — asked ISO 400 · 1/100, got ISO 398 · 1/100 |
| Manual focus, all 4 | PASS — asked 1.00 D, reported 1.00 D |
| Kelvin white balance gains, all 4 | PASS — R/B ratio asked 0.631, got 0.631 |
| RAW (DNG) + JPEG, all 4 | PASS — DNG 16.5–23.8 MB |
| Video 1080p30 H.264 | PASS — 2.87 s, 8.1 Mbps, no sound (mic not granted) |
| StoryMax video V1080_SDR export | PASS — 1080×1920, 30.01 fps, 86 frames encoded / 0 dropped, BT.709 SDR tags, 10.4 Mbps (asked 10), same duration, 706 ms |
| StoryMax photo P1080_SDR export | PASS — 1080×1920, 593 KB, sRGB ICC present, 262 ms |
| Voice Shutter microphone loop | SKIP — RECORD_AUDIO not granted |

Added for the next run (1.1.1): asks for the microphone first; which physical lens streams at 0.6× / 1× / 2.74× /
5.06×; shutter lag with ZSL on vs. off after the preview has settled; 4K30 HEVC and 1080p60 recording; video with
sound; StoryMax export from the 4K HEVC clip (checks AAC passthrough); self-test timings stored separately.

## Build machine (1.1.1-debug, 2026-11-22; Linux x86-64, OpenJDK 21)

| Check | Command | Result |
|---|---|---|
| JVM unit tests (all Android-free classes) | `./tools/run_unit_tests.sh` | **OK (31 tests)**, ~0.3 s |
| Offline debug APK build | `NONGMAY_TOOLCHAIN=… ./tools/build_apk.sh` | **built** `NongMayCamera-1.1.1-debug.apk`, 60 source files, 205,527 bytes |
| Signature | `apksigner verify --verbose --print-certs` | **v3 verified** (v1/v2 not present; fine for minSdk 29) |
| Package / SDK / permissions | `aapt2 dump badging` | `com.nongmay.camera.v11`, versionCode 12, min 29, target 35, CAMERA + RECORD_AUDIO only |
| Same signer as 1.1.0 (in-place update) | `apksigner verify --print-certs` | certificate SHA-256 b51f1b7b…1fef7d (unchanged) |
| No Java-8 lambdas in dex (dx/min-sdk 26 safety) | `dexdump -d … \| grep -c invoke-custom` | **0** |
| Voice Shutter synthetic evaluation | `tools/voice_eval` (see `VOICE_EVAL.md`) | ran: 142 streams, 20/20 templates |
| On-device self-test, capability inspector | Diagnostics in the app | see the S25 Ultra section above |
| Gradle build (`./gradlew assembleDebug testDebugUnitTest`) | — | **NOT RUN** (AGP unreachable) |

## Unit tests (31)
- **VoiceCoreTest (9):** fftFindsTheToneBin · mfccShapesAndNormalisation · dtwPrefersSameWordEvenWhenStretched ·
  vadFindsSegmentAndRejectsLongTalk · spotterTriggersOnKeywordOnly · spotterIgnoresLongSentencesAndRespectsCooldown ·
  compensationKeepsTheKeywordInSteadyNoise (12 dB SNR: compensated matcher triggers, plain one does not) ·
  templateRoundTripAndSensitivityOrder · thaiPhraseNormalisation
- **StoryMathTest (11):** coverRectFromLandscapeIsCentredAndExact916 · coverRectClampsAndZooms · fitRectLetterboxes ·
  orientationRoundTripsForAllExifValues · frameRatePolicyDropsWithoutJudder · toneMapReferencePoints ·
  profilesAreConsistent · worksheetHidesProfilesUntilKey · quadFullFrameMapsCornersToCorners ·
  quadLandscapeEncoderAndRotationStayInsideTheFrame · avcLevelCoversStoryAndUhdModes
- **ProMathTest (6):** shutterLabelsAndAngles · stopsStayInsideHardwareRange · exposureEquivalenceAndLensUnits ·
  planckianLocus · kelvinGainsBehaveLikeACamera · illuminantInterpolationWeight
- **UtilTest (5):** jsonEscapesAndNests · statsPercentiles · iccIsInsertedAfterJfifAndJpegStillDecodes ·
  iccRejectsNonJpeg · formatting

Test inputs are synthetic (generated tones/formant "words", generated images); no personal media is used.

## Failures found and fixed during the work
- Voice: the first matcher accepted a different synthetic word (base floor too high) → floor lowered; a duplicate
  event per trigger → removed; VAD frame counter not reset → fixed; plain MFCC/DTW scored 0% in every noisy
  condition of the synthetic corpus → noise-compensated matching, virtual-room hypothesis, VAD warm-up and
  relative pause added (`VOICE_EVAL.md`).
- Camera: stale session callbacks after fast switching → `sessionGen` guard; tap/lock state raced with the UI →
  moved into the controller.
- Exif API differences (`TAG_ISO_SPEED_RATINGS`, `getLatLong(float[])`) fixed at compile time.
