# Voice Shutter — synthetic evaluation (espeak-ng TTS, NOT human speech)

Generated on the build machine by `tools/voice_eval/make_corpus.py` + `tools/voice_eval/VoiceEval.java`. The detector is the exact `KwsCore` / `EnergyVad` / `Matcher` / `Mfcc` / `Dtw` / `VoiceTemplate` code that is dexed into the APK, fed in 20 ms chunks like `AudioRecord`. Audio is continuous streams (the app listens continuously): 4 synthetic Thai voices, ±15% speed, ±8 pitch, level −14…−4 dBFS peak; each voice enrolls each phrase with 3 clean takes using the enrollment screen's rules, then says it 6 more times per condition. **These numbers show that the pipeline works and how the settings trade off. They do not predict accuracy with Pol's real voice on the S25 Ultra** — synthetic voices are far more repeatable than people.

Templates built: 20/20. Run time 77 s on 2 CPU cores.

## 1. Shipped matcher vs. the first prototype (NORMAL sensitivity, same threshold)

Shipped = background tracker + noise-compensated mel matching (clamp 1.25×) + a second “virtual room” hypothesis (RT60 0.3 s, DRR +6 dB — deliberately not the corpus room of 0.4 s / +4 dB) + VAD pause at 14 dB below the phrase peak. Prototype = plain MFCC/DTW on the raw segment. The settings were chosen with a parameter sweep on this same corpus, so treat the shipped numbers as optimistic. Decision gates since 1.1.4 (after a real false trigger on a loud “เฮ้ย”): duration ≥ 0.65 × the takes, 2 of 3 takes must agree (median ≤ 1.10 × threshold), sensitivity 1.10 / 1.20 / 1.35 × base. “Not this” samples (taught by the owner) are not part of this corpus.

| Condition | plain MFCC/DTW (first prototype) | noise-compensated (shipped) |
|---|---|---|
| quiet | 119/120 (99%) | 119/120 (99%) |
| white noise 20 dB | 0/120 (0%) | 99/120 (83%) |
| street-like pink noise 10 dB | 0/120 (0%) | 44/120 (37%) |
| other people talking 15 dB | 0/120 (0%) | 10/120 (8%) |
| room reverb | 0/120 (0%) | 64/120 (53%) |
| other words accepted (all conditions) | 0/3600 (0%) | 0/3600 (0%) |
| other synthetic voice accepted (quiet) | 41/360 (11%) | 41/360 (11%) |
| false triggers in 12 min conversation, quiet / cafe (per template-hour) | 0.0 / 0.0 | 0.0 / 0.0 |

## 2. STRICT (threshold = 1.10 × calibrated base)

Same voice says the phrase (6 times × 4 voices per cell):

| Phrase | all | quiet | white noise 20 dB | street-like pink noise 10 dB | other people talking 15 dB | room reverb | other voice (quiet) |
|---|---|---|---|---|---|---|---|
| น้องเม | 88/120 (73%) | 24/24 (100%) | 22/24 (92%) | 20/24 (83%) | 3/24 (13%) | 19/24 (79%) | 8/72 (11%) |
| โฮ่ง ๆ | 42/120 (35%) | 23/24 (96%) | 17/24 (71%) | 2/24 (8%) | 0/24 (0%) | 0/24 (0%) | 0/72 (0%) |
| ชาลี | 42/120 (35%) | 24/24 (100%) | 10/24 (42%) | 0/24 (0%) | 2/24 (8%) | 6/24 (25%) | 2/72 (3%) |
| ถ่ายเลย | 71/120 (59%) | 24/24 (100%) | 22/24 (92%) | 8/24 (33%) | 2/24 (8%) | 15/24 (63%) | 11/72 (15%) |
| cheese | 46/120 (38%) | 18/24 (75%) | 16/24 (67%) | 5/24 (21%) | 1/24 (4%) | 6/24 (25%) | 3/72 (4%) |

Other words by the same voice (18 words × 2 × 5 conditions × 4 voices = 720 per phrase):

| Phrase template | accepted another word |
|---|---|
| น้องเม | 0/720 (0%) |
| โฮ่ง ๆ | 0/720 (0%) |
| ชาลี | 0/720 (0%) |
| ถ่ายเลย | 0/720 (0%) |
| cheese | 0/720 (0%) |

False triggers during 12 min of synthetic Thai conversation (20 templates, 4.0 template-hours per room): **quiet room 0 (0.0/h), cafe 0 (0.0/h)**. Extra triggers outside a spoken phrase in the phrase streams: 0.

## 3. NORMAL (threshold = 1.20 × calibrated base)

Same voice says the phrase (6 times × 4 voices per cell):

| Phrase | all | quiet | white noise 20 dB | street-like pink noise 10 dB | other people talking 15 dB | room reverb | other voice (quiet) |
|---|---|---|---|---|---|---|---|
| น้องเม | 94/120 (78%) | 24/24 (100%) | 24/24 (100%) | 22/24 (92%) | 4/24 (17%) | 20/24 (83%) | 12/72 (17%) |
| โฮ่ง ๆ | 49/120 (41%) | 24/24 (100%) | 19/24 (79%) | 3/24 (13%) | 0/24 (0%) | 3/24 (13%) | 2/72 (3%) |
| ชาลี | 56/120 (47%) | 24/24 (100%) | 15/24 (63%) | 1/24 (4%) | 3/24 (13%) | 13/24 (54%) | 10/72 (14%) |
| ถ่ายเลย | 78/120 (65%) | 24/24 (100%) | 23/24 (96%) | 8/24 (33%) | 2/24 (8%) | 21/24 (88%) | 12/72 (17%) |
| cheese | 59/120 (49%) | 23/24 (96%) | 18/24 (75%) | 10/24 (42%) | 1/24 (4%) | 7/24 (29%) | 5/72 (7%) |

Other words by the same voice (18 words × 2 × 5 conditions × 4 voices = 720 per phrase):

| Phrase template | accepted another word |
|---|---|
| น้องเม | 0/720 (0%) |
| โฮ่ง ๆ | 0/720 (0%) |
| ชาลี | 0/720 (0%) |
| ถ่ายเลย | 0/720 (0%) |
| cheese | 0/720 (0%) |

False triggers during 12 min of synthetic Thai conversation (20 templates, 4.0 template-hours per room): **quiet room 0 (0.0/h), cafe 0 (0.0/h)**. Extra triggers outside a spoken phrase in the phrase streams: 0.

## 4. SENSITIVE (threshold = 1.35 × calibrated base)

Same voice says the phrase (6 times × 4 voices per cell):

| Phrase | all | quiet | white noise 20 dB | street-like pink noise 10 dB | other people talking 15 dB | room reverb | other voice (quiet) |
|---|---|---|---|---|---|---|---|
| น้องเม | 102/120 (85%) | 24/24 (100%) | 24/24 (100%) | 23/24 (96%) | 7/24 (29%) | 24/24 (100%) | 12/72 (17%) |
| โฮ่ง ๆ | 65/120 (54%) | 24/24 (100%) | 23/24 (96%) | 9/24 (38%) | 0/24 (0%) | 9/24 (38%) | 12/72 (17%) |
| ชาลี | 74/120 (62%) | 24/24 (100%) | 20/24 (83%) | 8/24 (33%) | 3/24 (13%) | 19/24 (79%) | 12/72 (17%) |
| ถ่ายเลย | 82/120 (68%) | 24/24 (100%) | 23/24 (96%) | 8/24 (33%) | 3/24 (13%) | 24/24 (100%) | 12/72 (17%) |
| cheese | 73/120 (61%) | 24/24 (100%) | 22/24 (92%) | 14/24 (58%) | 1/24 (4%) | 12/24 (50%) | 9/72 (13%) |

Other words by the same voice (18 words × 2 × 5 conditions × 4 voices = 720 per phrase):

| Phrase template | accepted another word |
|---|---|
| น้องเม | 1/720 (0%) |
| โฮ่ง ๆ | 0/720 (0%) |
| ชาลี | 0/720 (0%) |
| ถ่ายเลย | 0/720 (0%) |
| cheese | 0/720 (0%) |

Which words got through: น้องเม ← น้องเมย์ ×1

False triggers during 12 min of synthetic Thai conversation (20 templates, 4.0 template-hours per room): **quiet room 0 (0.0/h), cafe 0 (0.0/h)**. Extra triggers outside a spoken phrase in the phrase streams: 0.

## 5. Latency (NORMAL)

- End of speech → trigger decision on the audio clock: p50 200 ms, p95 200 ms (n=119). This is the 200 ms end-of-speech hangover by design (so “น้องเม…” isn't cut mid-word).
- Compute for the 20 ms chunk that ends a segment (VAD + mel + 6 compensated DTW matches: 3 takes × dry/virtual room), build machine JVM: p50 1.20 ms, p95 2.71 ms, max 5.42 ms. Not measured on the phone.
- On the phone add AudioRecord buffering (~20–40 ms, NOT MEASURED) and the capture request; the app logs real `voice_end_to_capture_ms` in Diagnostics.

## How to read this

- “same voice” = the voice that enrolled says the phrase again (the real use case).
- “other voice” = someone else says the same phrase. The matcher is speaker-dependent on purpose; it is not a lock, a similar voice can pass.
- The conversation script deliberately contains sentences with “น้องเม”, “ชาลี”, “ถ่ายเลย” and “ชีสเค้ก” inside them; the app is meant to fire only on the phrase said on its own.
- Next step is Pol's own voice on the S25 Ultra: Voice screen → test mode shows score vs. threshold for every attempt.
