# Voice Shutter — synthetic evaluation (espeak-ng TTS, NOT human speech)

Generated on the build machine by `tools/voice_eval/make_corpus.py` + `tools/voice_eval/VoiceEval.java`. The detector is the exact `KwsCore` / `EnergyVad` / `Matcher` / `Mfcc` / `Dtw` / `VoiceTemplate` code that is dexed into the APK, fed in 20 ms chunks like `AudioRecord`. Audio is continuous streams (the app listens continuously): 4 synthetic Thai voices, ±15% speed, ±8 pitch, level −14…−4 dBFS peak; each voice enrolls each phrase with 3 clean takes using the enrollment screen's rules, then says it 6 more times per condition. **These numbers show that the pipeline works and how the settings trade off. They do not predict accuracy with Pol's real voice on the S25 Ultra** — synthetic voices are far more repeatable than people.

Templates built: 20/20. Run time 82 s on 2 CPU cores.

## 1. Shipped matcher vs. the first prototype (NORMAL sensitivity, same threshold)

Shipped = background tracker + noise-compensated mel matching (clamp 1.25×) + a second “virtual room” hypothesis (RT60 0.3 s, DRR +6 dB — deliberately not the corpus room of 0.4 s / +4 dB) + VAD pause at 14 dB below the phrase peak. Prototype = plain MFCC/DTW on the raw segment. The settings were chosen with a parameter sweep on this same corpus, so treat the shipped numbers as optimistic. Decision gates in 1.1.5: duration ≥ 0.55 × the takes, the two best takes must agree (2nd best ≤ 1.25 × threshold), sensitivity 1.15 / 1.25 / 1.40 × base. On Pol's phone 1.1.3 (1.30) let a loud “เฮ้ย” through and 1.1.4 (1.20 + duration 0.65 + agreement 1.10) rejected his real “น้องเม” — real voices vary far more than these synthetic ones. Owner corrections (“ไม่ใช่คำนี้” / “อันนี้ใช่”) are not part of this corpus.

| Condition | plain MFCC/DTW (first prototype) | noise-compensated (shipped) |
|---|---|---|
| quiet | 120/120 (100%) | 119/120 (99%) |
| white noise 20 dB | 0/120 (0%) | 109/120 (91%) |
| street-like pink noise 10 dB | 0/120 (0%) | 49/120 (41%) |
| other people talking 15 dB | 0/120 (0%) | 11/120 (9%) |
| room reverb | 0/120 (0%) | 74/120 (62%) |
| other words accepted (all conditions) | 0/3600 (0%) | 0/3600 (0%) |
| other synthetic voice accepted (quiet) | 50/360 (14%) | 45/360 (13%) |
| false triggers in 12 min conversation, quiet / cafe (per template-hour) | 0.0 / 0.0 | 0.0 / 0.0 |

## 2. STRICT (threshold = 1.15 × calibrated base)

Same voice says the phrase (6 times × 4 voices per cell):

| Phrase | all | quiet | white noise 20 dB | street-like pink noise 10 dB | other people talking 15 dB | room reverb | other voice (quiet) |
|---|---|---|---|---|---|---|---|
| น้องเม | 92/120 (77%) | 24/24 (100%) | 24/24 (100%) | 22/24 (92%) | 3/24 (13%) | 19/24 (79%) | 11/72 (15%) |
| โฮ่ง ๆ | 45/120 (38%) | 24/24 (100%) | 18/24 (75%) | 3/24 (13%) | 0/24 (0%) | 0/24 (0%) | 0/72 (0%) |
| ชาลี | 49/120 (41%) | 24/24 (100%) | 13/24 (54%) | 1/24 (4%) | 3/24 (13%) | 8/24 (33%) | 9/72 (13%) |
| ถ่ายเลย | 76/120 (63%) | 24/24 (100%) | 22/24 (92%) | 8/24 (33%) | 2/24 (8%) | 20/24 (83%) | 12/72 (17%) |
| cheese | 53/120 (44%) | 21/24 (88%) | 18/24 (75%) | 7/24 (29%) | 1/24 (4%) | 6/24 (25%) | 6/72 (8%) |

Other words by the same voice (18 words × 2 × 5 conditions × 4 voices = 720 per phrase):

| Phrase template | accepted another word |
|---|---|
| น้องเม | 0/720 (0%) |
| โฮ่ง ๆ | 0/720 (0%) |
| ชาลี | 0/720 (0%) |
| ถ่ายเลย | 0/720 (0%) |
| cheese | 0/720 (0%) |

False triggers during 12 min of synthetic Thai conversation (20 templates, 4.0 template-hours per room): **quiet room 0 (0.0/h), cafe 0 (0.0/h)**. Extra triggers outside a spoken phrase in the phrase streams: 0.

## 3. NORMAL (threshold = 1.25 × calibrated base)

Same voice says the phrase (6 times × 4 voices per cell):

| Phrase | all | quiet | white noise 20 dB | street-like pink noise 10 dB | other people talking 15 dB | room reverb | other voice (quiet) |
|---|---|---|---|---|---|---|---|
| น้องเม | 98/120 (82%) | 24/24 (100%) | 24/24 (100%) | 22/24 (92%) | 5/24 (21%) | 23/24 (96%) | 12/72 (17%) |
| โฮ่ง ๆ | 54/120 (45%) | 24/24 (100%) | 20/24 (83%) | 4/24 (17%) | 0/24 (0%) | 6/24 (25%) | 5/72 (7%) |
| ชาลี | 68/120 (57%) | 24/24 (100%) | 22/24 (92%) | 4/24 (17%) | 3/24 (13%) | 15/24 (63%) | 10/72 (14%) |
| ถ่ายเลย | 79/120 (66%) | 24/24 (100%) | 23/24 (96%) | 8/24 (33%) | 2/24 (8%) | 22/24 (92%) | 12/72 (17%) |
| cheese | 63/120 (53%) | 23/24 (96%) | 20/24 (83%) | 11/24 (46%) | 1/24 (4%) | 8/24 (33%) | 6/72 (8%) |

Other words by the same voice (18 words × 2 × 5 conditions × 4 voices = 720 per phrase):

| Phrase template | accepted another word |
|---|---|
| น้องเม | 0/720 (0%) |
| โฮ่ง ๆ | 0/720 (0%) |
| ชาลี | 0/720 (0%) |
| ถ่ายเลย | 0/720 (0%) |
| cheese | 0/720 (0%) |

False triggers during 12 min of synthetic Thai conversation (20 templates, 4.0 template-hours per room): **quiet room 0 (0.0/h), cafe 0 (0.0/h)**. Extra triggers outside a spoken phrase in the phrase streams: 0.

## 4. SENSITIVE (threshold = 1.40 × calibrated base)

Same voice says the phrase (6 times × 4 voices per cell):

| Phrase | all | quiet | white noise 20 dB | street-like pink noise 10 dB | other people talking 15 dB | room reverb | other voice (quiet) |
|---|---|---|---|---|---|---|---|
| น้องเม | 102/120 (85%) | 24/24 (100%) | 24/24 (100%) | 23/24 (96%) | 7/24 (29%) | 24/24 (100%) | 12/72 (17%) |
| โฮ่ง ๆ | 67/120 (56%) | 24/24 (100%) | 24/24 (100%) | 9/24 (38%) | 0/24 (0%) | 10/24 (42%) | 12/72 (17%) |
| ชาลี | 80/120 (67%) | 24/24 (100%) | 23/24 (96%) | 10/24 (42%) | 3/24 (13%) | 20/24 (83%) | 12/72 (17%) |
| ถ่ายเลย | 82/120 (68%) | 24/24 (100%) | 23/24 (96%) | 8/24 (33%) | 3/24 (13%) | 24/24 (100%) | 12/72 (17%) |
| cheese | 80/120 (67%) | 24/24 (100%) | 24/24 (100%) | 17/24 (71%) | 1/24 (4%) | 14/24 (58%) | 9/72 (13%) |

Other words by the same voice (18 words × 2 × 5 conditions × 4 voices = 720 per phrase):

| Phrase template | accepted another word |
|---|---|
| น้องเม | 6/720 (1%) |
| โฮ่ง ๆ | 0/720 (0%) |
| ชาลี | 0/720 (0%) |
| ถ่ายเลย | 0/720 (0%) |
| cheese | 0/720 (0%) |

Which words got through: น้องเม ← น้องเมย์ ×5, น้องเม ← แม่ ×1

False triggers during 12 min of synthetic Thai conversation (20 templates, 4.0 template-hours per room): **quiet room 0 (0.0/h), cafe 0 (0.0/h)**. Extra triggers outside a spoken phrase in the phrase streams: 0.

## 5. Latency (NORMAL)

- End of speech → trigger decision on the audio clock: p50 200 ms, p95 200 ms (n=119). This is the 200 ms end-of-speech hangover by design (so “น้องเม…” isn't cut mid-word).
- Compute for the 20 ms chunk that ends a segment (VAD + mel + 6 compensated DTW matches: 3 takes × dry/virtual room), build machine JVM: p50 1.16 ms, p95 2.69 ms, max 5.02 ms. Not measured on the phone.
- On the phone add AudioRecord buffering (~20–40 ms, NOT MEASURED) and the capture request; the app logs real `voice_end_to_capture_ms` in Diagnostics.

## How to read this

- “same voice” = the voice that enrolled says the phrase again (the real use case).
- “other voice” = someone else says the same phrase. The matcher is speaker-dependent on purpose; it is not a lock, a similar voice can pass.
- The conversation script deliberately contains sentences with “น้องเม”, “ชาลี”, “ถ่ายเลย” and “ชีสเค้ก” inside them; the app is meant to fire only on the phrase said on its own.
- Next step is Pol's own voice on the S25 Ultra: Voice screen → test mode shows score vs. threshold for every attempt.
