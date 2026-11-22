# STORY A/B TEST — posted-Story worksheet (blank)

**Status: NOT RUN.** No Story has been posted and no local export comparison has been measured (no phone was
available to the build machine). Nothing in this file is a result. The only claim the app makes before evidence
is “Story-optimized export for Galaxy S25 Ultra”.

The app generates a filled-in copy of this worksheet with blind codes: **StoryMax → Quality Lab →** pick a
source → choose profiles → export → `Download/NongMay/STORY_AB_TEST_<experiment>.md` (plus
`STORY_EXPORTS_<time>.csv` with export time, size, bitrate, fps, colour tags, ICC for every file).

## Question
Does any StoryMax profile survive Instagram's Story recompression better than (a) sharing the camera original
directly and (b) the stable profile? Measured only by Pol, on an account he controls, posted by hand.

## Protocol
1. Same source per experiment; one scene type per experiment (skin tones indoors, high-contrast outdoors,
   foliage/hair, small text, fast motion, low light, HDR video if any).
2. Export each variant in Quality Lab. Keep the key section hidden until grading is done.
3. Post every variant as a Story from the same account, same network (Wi-Fi), same day, no IG filters/stickers,
   IG “upload at highest quality” setting the same for all.
4. View on the S25 Ultra and, if possible, an iPhone, at full brightness, after IG finished processing.
   Screen-record or screenshot each Story; do not download others' media.
5. Grade blind 1–5 per column; then open the key and note winners/losers per scene. Do not generalise beyond the
   scenes tested.

## Variants
| Code | Profile | What it tests |
|---|---|---|
| A | DIRECT (camera original via Android share) | IG's own processing of the untouched file |
| B | P1080_SDR / V1080_SDR (stable) | 1080×1920, sRGB JPEG q95 · H.264 High 10 Mbps BT.709 |
| C | P1440_TEST / V1440_TEST | larger upload, same SDR pipeline |
| D | V1080_BR8 / V1080_BR12 / V1080_BR16 | bitrate sensitivity |
| E | DIRECT_ORIGINAL | stream copy / JPEG copy, metadata & GPS removed |
| — | V_HDR_TEST | NOT IMPLEMENTED in v1.1 |

## Grading sheet (copy one block per experiment)
Experiment: ____  Source: ____  Scene: ____  Date/network: ____

| Blind | Viewer phone | Colour/skin | Shadows | Highlights | Text/hair detail | Motion | Artifacts (ringing/blocks/banding) | Overall | Notes |
|---|---|---|---|---|---|---|---|---|---|
|  | Galaxy S25 Ultra |  |  |  |  |  |  |  |  |
|  | iPhone |  |  |  |  |  |  |  |  |
|  | Galaxy S25 Ultra |  |  |  |  |  |  |  |  |
|  | iPhone |  |  |  |  |  |  |  |  |

## Key (fill only after grading)
| Experiment | Blind | Profile | File | Measured before upload (size, bitrate, colour tags) |
|---|---|---|---|---|
|  |  |  |  |  |

## Preliminary local export comparison
NOT MEASURED (requires MediaCodec on the phone). Quality Lab's CSV is the place to record it.
