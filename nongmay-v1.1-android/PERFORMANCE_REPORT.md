# PERFORMANCE REPORT — NongMay Camera v1.1

**First real numbers:** Pol's Galaxy S25 Ultra (SM-S938B, Android 17), app 1.1.0-debug, 2026-11-22 14:25
(`device-reports/2026-11-22_SM-S938B/PERFORMANCE_20261122_1425.md`). Sample sizes are tiny (n = 1–11) and most
samples come from the self-test, which drives the camera from code — so these are first measurements, not a
p95 verdict. Nothing here comes from an emulator.

## How the numbers are produced
- `perf/PerfLog` records markers in the app (`SystemClock.elapsedRealtimeNanos`, camera `SENSOR_TIMESTAMP`,
  `AudioRecord` BOOTTIME timestamps), keeps the last 4,000 samples in app storage and reports n / p50 / p95 / min / max.
- Phone: **Settings → Diagnostics → “รายงานความเร็ว (PERFORMANCE)”** → `Download/NongMay/PERFORMANCE_<time>.md/csv`;
  “ล้างสถิติความเร็ว” resets them.
- Jetpack Macrobenchmark / Baseline Profiles: **BLOCKED** (need AGP from Google Maven).

## S25 Ultra results (1.1.0-debug)
| Metric | n | p50 ms | p95 ms | Target | Verdict |
|---|---|---|---|---|---|
| `cold_start_process_to_first_preview_frame` | 1 | 2028.6 | — | ≤ 2.5 s p95 | within target on 1 sample |
| `warm_start_to_first_preview_frame` | 1 | 30.0 | — | ≤ 800 ms p95 | **INVALID** — stale frame, fixed in 1.1.1 |
| `camera_open_request_to_opened` | 7 | 11.5 | 36.8 | — | measured |
| `camera_opened_to_session_configured` | 11 | 271.6 | 570.2 | — | measured; the slowest step of opening |
| `camera_open_request_to_first_preview_frame` | 2 | — | — | — | one valid sample **554.9 ms**; the 36 848 ms sample is **INVALID** (fixed in 1.1.1) |
| `shutter_touch_to_visual_ack_frame` | 0 | — | — | ≤ 80 ms p95 | NOT MEASURED (no finger taps yet) |
| `shutter_touch_to_capture_request` | 8 | 0.9 | 1.0 | — | self-test captures |
| `shutter_touch_to_sensor_exposure_start` | 8 | 160.9 | 195.0 | — | self-test captures right after a session start (ZSL not warmed up) |
| `shutter_touch_to_jpeg_ready` | 8 | 476.5 | 893.1 | — | self-test (4000–4080 px JPEGs, some with RAW) |
| `shutter_touch_to_mediastore_saved` | 8 | 601.4 | 929.6 | — | self-test |
| `manual_setting_request_to_matching_result` | 4 | 181.2 | 201.5 | — | ISO/shutter change visible in results ~6 frames later |
| `record_tap_to_recorder_started` | 1 | 48.2 | — | — | self-test |
| `stop_tap_to_video_saved` | 1 | 175.9 | — | — | self-test |
| `story_photo_export_ms` | 1 | 262.0 | — | — | 12 MP JPEG → 1080×1920 + ICC |
| `story_video_export_ms_per_second_of_video` | 1 | 246.4 | — | — | 1080p30 H.264 → Story: ≈ 4× faster than real time |
| `voice_utterance_end_to_trigger` / `…_capture_request` | 0 | — | — | ≤ 750 ms p95 | NOT MEASURED (mic not granted) |
| camera / mode switch to first frame | 0 | — | — | — | NOT MEASURED |

Self-test per camera: open + session + first result 440–702 ms; request → saved JPEG 652–981 ms.
Battery drain and heat over a 15–20 min session: NOT MEASURED (thermal status 1 = light when the report was made).

## Two measurement bugs the phone exposed (fixed in 1.1.1)
1. **Stale frame on return.** Coming back to the camera, the viewfinder can redraw the last buffer of the previous
   session before the new session exists. 1.1.0 counted that as “first frame”, giving a 30 ms warm start and
   a 36.8 s open→frame sample (measured against the previous open). 1.1.1 only accepts a frame whose sensor
   timestamp is later than the start request (this phone uses REALTIME timestamps), and measures open→frame from
   the screen's own start time.
2. **Self-test samples mixed with real use.** Self-test captures were stored as `shutter_touch_*`. 1.1.1 stores
   them as `selftest_*` in a separate table, so finger/voice timings stay clean. 1.1.1's self-test also measures
   steady-state shutter lag with ZSL on vs. off (after the preview has run), which is what a real tap sees.

## Voice Shutter — synthetic results only (build machine, espeak-ng TTS)
Full tables: `VOICE_EVAL.md`. NORMAL sensitivity, 4 synthetic voices × 5 phrases × 6 tries per condition:
quiet 100% · white noise 20 dB 94% · street-like pink noise 10 dB 48% · other people talking 15 dB 10% ·
room reverb 68%. Other words accepted 0/3600 (incl. `น้องเมย์`, `น้องแม่`); false triggers in 12 min of
conversation 0/h; another voice saying the phrase 15%. Decision latency 200 ms after the end of speech by design;
matcher compute p95 2.71 ms on the build machine's JVM. Not human speech — the phone test is still to come.
