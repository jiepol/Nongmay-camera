# NongMay Camera v1.1 (กล้องน้องเม 1.1)

A fast Camera2 camera for the Galaxy S25 Ultra with four pillars: **StoryMax** (9:16 Instagram-Story export),
**True Pro** (capability-gated manual controls, RAW, overlays, Pro Video), **Voice Shutter** (say `น้องเม`, or
any phrase you teach it, offline) and **Speed** (opens straight to the viewfinder, instrumented latency).

> **Status (2026-11-22, 1.1.1-debug):** built, unit-tested, and the 1.1.0 on-device self-test passed on Pol's
> Galaxy S25 Ultra (28 PASS · 0 FAIL · 1 SKIP — camera capture on all 4 cameras, manual ISO/shutter/focus/Kelvin,
> RAW, 1080p video, StoryMax photo + video export). Screens, finger/voice paths and Voice Shutter accuracy are still
> unverified — see `FEATURE_MATRIX.md`. Voice numbers so far are synthetic (`VOICE_EVAL.md`).

### New in 1.1.1 (from the S25 Ultra evidence)
- Fixed: first-frame timing could count a stale viewfinder frame after returning to the app (30 ms “warm start”,
  36.8 s open→frame sample in 1.1.0).
- Self-test timings are now stored separately (`selftest_*`) from real finger/voice timings.
- Self-test asks for the microphone, checks which physical lens streams at each zoom preset, measures shutter lag
  with ZSL on/off, records 4K30 HEVC, 1080p60 and video with sound, and exports a Story from the 4K clip.
- Capability report adds the camera's max fps per video size (explains why 4K60 is not offered).

| File | What it is |
|---|---|
| `FEATURE_MATRIX.md` | every feature with its honest state and evidence |
| `DECISIONS.md` | why Camera2, why template-matching voice, colour management, signing |
| `PERFORMANCE_REPORT.md` | how latency is measured; device values NOT MEASURED |
| `VOICE_EVAL.md` | synthetic Voice Shutter evaluation (same code as the APK) |
| `STORY_AB_TEST.md` | blank posted-Story A/B worksheet |
| `DEVICE_CAPABILITIES.md/json` | measured capabilities of Pol's S25 Ultra (raw reports in `device-reports/`) |
| `TEST_RESULTS.md` | what was run and the actual results |

## Install (debug build)
- APK: `NongMayCamera-1.1.1-debug.apk` (delivered separately; never committed). Application id
  `com.nongmay.camera.v11`, so it installs **next to** v1.0 (`app.nongmay.camera`); v1.0 is not replaced.
  1.1.1 is signed with the same key as 1.1.0, so it installs **over** 1.1.0 and keeps phrases, presets and timings.
- Allow "install unknown apps" for the file manager you open it from. Android 10+ (minSdk 29).
- Signed with a personal **debug** key. Future v1.1.x builds must be signed with the same key to update in
  place; a different key means uninstall first (voice phrases and presets would be lost). Keep the `.jks` private.

## Using it
- **Camera:** mode bar `AUTO · PRO · VIDEO · STORY`. Big shutter at the bottom (touch-down shutter and
  volume-key shutter in Settings). Lens chips show the zoom ratio and the physical lens actually used.
- **PRO:** chips for ISO / S / EV / WB (Kelvin) / MF / AF / DRIVE / RAW / ★ presets; each control appears only if
  the current camera supports it, `AUTO` restores automatic. Grids, level, histogram, zebra and peaking come from
  the live preview (approximate).
- **VIDEO:** resolution / fps / H.264-HEVC / bitrate / stabilisation from what the camera+encoder pair supports.
- **STORY / StoryMax:** shoot in 9:16 framing, or open StoryMax (own icon, Gallery → Share → StoryMax, or the
  camera's Story button) → pick photo/video → reframe (pinch/drag, fit/cover, safe zones) → **Export** → **Share**
  (Instagram or any app). Exports are new files in `Pictures/NongMay/Story` / `Movies/NongMay/Story`; originals untouched.
  **Quality Lab** (inside StoryMax) exports A/B variants and writes the worksheet.
- **Voice Shutter:** tap the mic icon on the camera's top bar. First time: teach `น้องเม` 3 times (or
  “คืนค่าเป็น น้องเม”), try it in the test area (shows score vs. threshold), save. Add phrases such as `โฮ่ง ๆ`
  or `ชาลี`; pick sensitivity STRICT / NORMAL / SENSITIVE. The mic runs only while the camera screen is open, never
  during video recording, and audio never leaves the phone (the app has no INTERNET permission).
- **Diagnostics:** Settings → Diagnostics → capabilities report, self-test (~1–2 min), performance report.
  Files go to `Download/NongMay/` and contain no media, audio or location.

## Build
Verified (offline, no Gradle):
```bash
cd nongmay-v1.1-android
./tools/setup_toolchain.sh                 # android.jar API 35 + aapt2 from the pinned Apktool jar (SHA-256 checked) + apt tools
export NONGMAY_TOOLCHAIN=$HOME/nongmay-toolchain
./tools/build_apk.sh                       # -> build/apk/NongMayCamera-1.1.1-debug.apk
./tools/run_unit_tests.sh                  # 31 JUnit tests
```
The signing key is created on first build at `~/.nongmay/nongmay-v11-debug.jks` (override with
`NONGMAY_KEYSTORE` / `NONGMAY_KS_PASS`).

Android Studio (UNVERIFIED — AGP could not be downloaded on the build machine): open `nongmay-v1.1-android/`,
let Studio create the Gradle wrapper (AGP 8.7.3 needs Gradle 8.9+ and JDK 17), then
`./gradlew assembleDebug testDebugUnitTest`.

Voice evaluation (needs `espeak-ng`, Python 3 with numpy + scipy):
```bash
./tools/run_unit_tests.sh                  # compiles the Android-free classes into build/unit/classes
python3 tools/voice_eval/make_corpus.py build/voice_corpus
javac -cp build/unit/classes -d build/voice_eval tools/voice_eval/VoiceEval.java
java -cp build/unit/classes:build/voice_eval VoiceEval build/voice_corpus VOICE_EVAL.md
```

## Layout
`app/src/main/java/com/nongmay/camera/` — `ui/` camera screen · `camera/` Camera2 controller, capture,
video, overlays, inspector · `camera/pro/` exposure & WB math · `voice/` VAD, mel/MFCC, matcher, DTW, engine,
screens · `storymax/` import, canvas, photo/video export, GL, profiles, Quality Lab · `perf/` timing,
diagnostics, self-test · `settings/` · `util/`.

## Permissions & privacy
CAMERA, RECORD_AUDIO only. No INTERNET, no location, no storage permission, no telemetry, `allowBackup=false`.
Voice phrases are stored as 26-band mel energy envelopes (no audio) in app-private storage; deleting a phrase
deletes them. No Instagram login/API; you post manually.

## Known limitations
- Nothing verified on a device yet (see `FEATURE_MATRIX.md`).
- Voice Shutter is a speaker-dependent sound matcher, not speech recognition: re-teach if it misses; other
  people with a similar voice can trigger it; weak among other people talking (10% in the synthetic test).
- Not implemented: 10-bit HLG video and 120/240 fps slow motion (both available on the S25 Ultra), time-lapse
  video, voice start/stop recording, location tags, non-AAC audio re-encode in StoryMax, HDR retention profile.
- Not possible for a third-party app on the S25 Ultra (camera HAL): 4K60, 8K, 200 MP, LOG, OIS control.
- Blocked here: Macrobenchmark / Baseline Profiles, CameraX / Media3 / Kotlin (no Maven access), Gradle build check.

## Pol's 10-minute test (S25 Ultra)
Before starting: ลง 1.1.1 ทับตัวเดิม → Settings → Diagnostics → “ล้างสถิติความเร็ว” → **Self-test อีกรอบ (อนุญาตไมค์ด้วย)**.
ทำตามนี้แล้วส่งไฟล์ใน `Download/NongMay/` กลับมา
1. **เปิดแอปแบบ cold 10 ครั้ง** (ปัดแอปทิ้งก่อนทุกครั้ง) → กดถ่าย 1 รูปทุกครั้ง; จดว่าเห็นภาพสดเร็วไหม รูปเข้าแกลเลอรีไหม
2. **Voice:** แตะไอคอนไมค์ → สอน `น้องเม` 3 ครั้ง → พูด `น้องเม` 20 ครั้งในห้องเงียบ + 20 ครั้งตอนเปิดทีวี/พัดลม;
   นับรูปที่ได้จริงเทียบ 40 ครั้ง และพูด `น้องเมย์`, `น้องแม่`, คุยปกติ 2 นาที → นับรูปที่ถ่ายเอง.
   แล้วเพิ่ม `โฮ่ง ๆ` → ทดสอบ 10 ครั้ง → กด “คืนค่าเป็น น้องเม”
3. **PRO:** ทุกเลนส์ที่มี ลองปรับ ISO / S / WB(K) / MF → ถ่าย → ดูว่าภาพเปลี่ยนจริงไหม และข้อความ “ไม่รองรับ” ขึ้นตรงไหน; ลอง RAW+JPEG ถ้ามี
4. ถ่ายของเคลื่อนไหวเร็ว, ที่มืด, ย้อนแสงกลางแดด, ผม/ใบไม้, ตัวหนังสือ — AUTO เทียบ PRO, เปิด/ปิด stabilisation
5. **VIDEO** 4K30 / 4K60 (ถ้ามีให้เลือก) 15–60 วิ → เข้า StoryMax → export มาตรฐาน + Quality Lab → เปิดดูในแกลเลอรีว่าภาพ/เสียงตรง ไม่หมุนผิด
6. (ถ้าสะดวก) โพสต์ variant จาก Quality Lab ลงสตอรี่บัญชีตัวเอง แล้วให้คะแนนแบบปิดชื่อใน `STORY_AB_TEST_*.md`
7. ใช้ต่อ 15–20 นาที: เครื่องร้อนไหม แบตลดเท่าไร มีเด้ง/ค้างไหม ถ่ายเองโดยไม่ได้พูดกี่ครั้ง ถือมือเดียวสบายไหม
8. สุดท้าย Diagnostics → **DEVICE_CAPABILITIES**, **Self-test**, **PERFORMANCE** → ส่ง 3–4 ไฟล์ใน `Download/NongMay/` กลับมา
