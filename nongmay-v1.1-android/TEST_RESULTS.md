# TEST RESULTS — 2026-11-22, build machine (Linux x86-64, OpenJDK 21, no Android device)

| Check | Command | Result |
|---|---|---|
| JVM unit tests (all Android-free classes) | `./tools/run_unit_tests.sh` | **OK (31 tests)**, ~0.3 s |
| Offline debug APK build | `NONGMAY_TOOLCHAIN=… ./tools/build_apk.sh` | **built**, 60 source files, 269 dex classes |
| Signature | `apksigner verify --verbose --print-certs` | **v3 verified** (v1/v2 not present; fine for minSdk 29) |
| Package / SDK / permissions | `aapt2 dump badging` | `com.nongmay.camera.v11`, versionCode 11, min 29, target 35, CAMERA + RECORD_AUDIO only |
| No Java-8 lambdas in dex (dx/min-sdk 26 safety) | `dexdump -d … \| grep -c invoke-custom` | **0** |
| Voice Shutter synthetic evaluation | `tools/voice_eval` (see `VOICE_EVAL.md`) | ran: 142 streams, 20/20 templates |
| On-device self-test, capability inspector, camera/codec/IG checks | Diagnostics in the app | **NOT RUN** (no device) |
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
