package com.nongmay.camera.storymax.quality;

import java.util.List;
import java.util.Locale;

/**
 * Builds the manual "posted Story" A/B worksheet (Markdown). The app never logs in, posts or
 * downloads anything from Instagram: Pol uploads each variant himself and grades what viewers see.
 * Pure Java.
 */
public final class AbWorksheet {

    public static final class Row {
        public String experimentId;
        public String blindCode;
        public String profileCode;
        public String sourceName;
        public String actual;     // measured output summary (pre-upload)
        public String fileName;
    }

    private AbWorksheet() {}

    public static String build(String appVersion, String device, String createdAt, List<Row> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("# NongMay StoryMax — posted-Story A/B worksheet\n\n");
        sb.append("- App: ").append(appVersion).append("  \n- Phone: ").append(device)
                .append("  \n- Generated: ").append(createdAt).append("\n\n");
        sb.append("Measurements below are **pre-upload** (what the app wrote). They say nothing yet about what ")
                .append("Instagram serves after its own transcode. Fill the grading tables from the **posted** Story.\n\n");
        sb.append("## Protocol\n\n");
        sb.append("1. Post each blind code as a separate Story from the same account, same Instagram version, ")
                .append("same network (note Wi-Fi/5G), same in-app edits (none).\n");
        sb.append("2. If Instagram shows a high-quality upload option, record whether it was on — do not assume it exists.\n");
        sb.append("3. Grade on the Galaxy S25 Ultra and, if available, an iPhone. Grade by blind code only ")
                .append("(open the key at the bottom after grading).\n");
        sb.append("4. Scores 1–5 (5 = best). Repeat promising variants on another day/connection.\n\n");
        sb.append("Upload log: date ______  network ______  IG version ______  HQ upload setting ______\n\n");
        String current = null;
        for (Row r : rows) {
            if (!r.experimentId.equals(current)) {
                current = r.experimentId;
                sb.append("## Experiment ").append(current).append(" — source: ").append(nz(r.sourceName)).append("\n\n");
                sb.append("| Blind | Viewer phone | Colour/skin | Shadows | Highlights | Text/hair detail | Motion | Artifacts (ringing/blocks/banding) | Overall | Notes |\n");
                sb.append("|---|---|---|---|---|---|---|---|---|---|\n");
                for (Row x : rows) {
                    if (!x.experimentId.equals(current)) continue;
                    sb.append("| ").append(x.blindCode).append(" | Galaxy |  |  |  |  |  |  |  |  |\n");
                    sb.append("| ").append(x.blindCode).append(" | iPhone |  |  |  |  |  |  |  |  |\n");
                }
                sb.append('\n');
            }
        }
        sb.append("## Key (open after grading)\n\n");
        sb.append("| Experiment | Blind | Profile | File | Measured before upload |\n|---|---|---|---|---|\n");
        for (Row r : rows) {
            sb.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s |\n",
                    r.experimentId, r.blindCode, r.profileCode, nz(r.fileName), nz(r.actual).replace("|", "/")));
        }
        sb.append("\nWinner/loser per scene: ______\n\nNo conclusion should be drawn beyond the scenes actually tested.\n");
        return sb.toString();
    }

    /** Three-letter blind codes that do not reveal order (e.g. "KTR"). */
    public static String blindCode(long seed, int index) {
        String letters = "BCDFGHJKLMNPQRSTVWXZ";
        long x = seed * 6364136223846793005L + 1442695040888963407L + index * 0x9E3779B97F4A7C15L;
        StringBuilder sb = new StringBuilder(3);
        for (int i = 0; i < 3; i++) {
            x ^= (x >>> 29);
            x *= 0xBF58476D1CE4E5B9L;
            int k = (int) ((x >>> 33) % letters.length());
            if (k < 0) k += letters.length();
            sb.append(letters.charAt(k));
        }
        return sb.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
