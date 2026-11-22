# PERFORMANCE REPORT — NongMay Camera v1.1 (1.1.0-debug)

**Status: no on-device numbers exist yet.** The build machine had no phone and no emulator, so every
camera, encoder, startup, battery and thermal figure below is **NOT MEASURED**. Nothing here was measured
on an emulator either. The only real numbers are build-machine (JVM) measurements, labelled as such.

## How the phone numbers will be produced
- `perf/PerfLog` records markers in the app process (`SystemClock.elapsedRealtimeNanos`, camera
  `SENSOR_TIMESTAMP`, `AudioRecord` BOOTTIME timestamps), appends them to a log in app storage (last 4,000
  samples kept), and reports n / p50 / p95 / min / max per metric.
- On the phone: **Settings → Diagnostics → “รายงานความเร็ว (PERFORMANCE)”** writes
  `Download/NongMay/PERFORMANCE_<time>.md` and `.csv`. “ล้างสถิติความเร็ว” resets them before a test run.
- Cold start is only counted when the first preview frame arrives within 10 s of process start
  (so a process kept alive in the background does not count as “cold”).
- Jetpack Macrobenchmark / Baseline Profiles: **BLOCKED** (need AGP from Google Maven).

## Device metrics (Galaxy S25 Ultra) — all NOT MEASURED
| Metric (PerfLog key) | Spec target (aspirational) | Measured |
|---|---|---|
| `cold_start_process_to_first_preview_frame` | ≤ 2.5 s p95 | NOT MEASURED |
| `warm_start_to_first_preview_frame` | ≤ 800 ms p95 | NOT MEASURED |
| `camera_open_request_to_opened`, `camera_opened_to_session_configured`, `camera_open_request_to_first_preview_frame` | — | NOT MEASURED |
| `shutter_touch_to_visual_ack_frame` | ≤ 80 ms p95 | NOT MEASURED |
| `shutter_touch_to_capture_request` | — | NOT MEASURED |
| `shutter_touch_to_sensor_exposure_start` | — | NOT MEASURED |
| `shutter_touch_to_jpeg_ready`, `shutter_touch_to_mediastore_saved` | — | NOT MEASURED |
| `voice_utterance_end_to_trigger`, `voice_utterance_end_to_capture_request` | ≤ 750 ms p95 | NOT MEASURED |
| `camera_switch_to_first_frame`, `mode_switch_to_first_frame` | — | NOT MEASURED |
| `manual_setting_request_to_matching_result` | — | NOT MEASURED |
| `record_tap_to_recorder_started`, `stop_tap_to_video_saved` | — | NOT MEASURED |
| `story_photo_export_ms` | — | NOT MEASURED |
| `story_video_export_ms_per_second_of_video` (15 / 30 / 60 s clips) | — | NOT MEASURED |
| UI frame timing during export / peaking | 60 fps where feasible | NOT MEASURED |
| Battery drain & heat (15–20 min session, voice on) | — | NOT MEASURED |

## Voice Shutter — synthetic results (build machine, espeak-ng TTS, not human speech)
Full tables: `VOICE_EVAL.md`. Same detector code as the APK, fed 20 ms chunks. NORMAL sensitivity:

| Condition (4 synthetic voices × 5 phrases × 6 tries) | Hits |
|---|---|
| quiet | 120/120 (100%) |
| white noise 20 dB SNR | 113/120 (94%) |
| street-like pink noise 10 dB SNR | 58/120 (48%) |
| other people talking 15 dB SNR | 12/120 (10%) |
| room reverb (RT60 0.4 s, DRR +4 dB) | 82/120 (68%) |

- Default phrase `น้องเม` alone: quiet 24/24, white 24/24, pink 22/24, babble 5/24, reverb 23/24.
- Other words by the same voice accepted: **0/3600** (incl. `น้องเมย์`, `น้องแม่`, `แม่`, `เม`). At SENSITIVE,
  `น้องเมย์` passed the `น้องเม` template 7/40 times.
- False triggers in 12 min of synthetic Thai conversation (quiet room and cafe; 4 template-hours each):
  **0 per hour** at STRICT/NORMAL; cafe 1.0/h at SENSITIVE.
- Someone else saying the same phrase: 54/360 (15%) — it is not a voice lock.
- Decision latency on the audio clock: **200 ms** after the end of speech (p50 = p95; it is the designed
  end-of-speech pause). Matcher compute on the build machine JVM: p50 1.17 ms, p95 2.71 ms, max 4.91 ms per
  segment. AudioRecord buffering and the camera request on the phone: NOT MEASURED.
- The first prototype (plain MFCC/DTW) scored 0% in every noisy/reverberant condition; the shipped matcher was
  tuned on this same corpus, so treat the numbers as optimistic. Spec targets (≥95% quiet close-range,
  ≤1 false trigger/h, p95 ≤750 ms) are **not claimed** until Pol's own test.

## Other build-machine facts
- APK size 201,431 bytes (no libraries, no native code). Unit test suite: 31 tests in ~0.3 s.
- Story export, camera and codec throughput cannot be measured without Android's MediaCodec/Camera2.
