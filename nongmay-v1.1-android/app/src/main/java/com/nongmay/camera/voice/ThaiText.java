package com.nongmay.camera.voice;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Thai-aware normalisation for trigger phrase labels. The acoustic matcher never uses text,
 * but phrase names are compared (duplicates, "restore default") and shown, so variants such as
 * "โฮ่ง ๆ" / "โฮ่งๆ" or a decomposed SARA AM must map to one key — without merging different words.
 */
public final class ThaiText {
    public static final String DEFAULT_PHRASE = "น้องเม";
    private static final char MAI_YAMOK = 'ๆ';

    private ThaiText() {}

    /** Comparison key: NFC, SARA AM composed, no zero-width chars/punctuation, no spaces around ๆ. */
    public static String key(String s) {
        if (s == null) return "";
        String t = Normalizer.normalize(s, Normalizer.Form.NFC);
        t = t.replace("ํา", "ำ");
        StringBuilder sb = new StringBuilder(t.length());
        int prevType = -1;
        char prev = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '​' || c == '‌' || c == '‍' || c == '⁠' || c == '﻿' || c == '­') continue;
            int type = Character.getType(c);
            if (isPunct(c, type)) c = ' ';
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) c = ' ';
            if (type == Character.NON_SPACING_MARK && prevType == Character.NON_SPACING_MARK && c == prev) continue;
            sb.append(c);
            prev = c;
            prevType = type;
        }
        String r = sb.toString().replaceAll(" +", " ").trim();
        r = r.replaceAll(" ?" + MAI_YAMOK + " ?", String.valueOf(MAI_YAMOK)).trim();
        return r.toLowerCase(Locale.ROOT);
    }

    /** Display form: trimmed, single spaces, Royal Institute spacing "คำ ๆ". */
    public static String display(String s) {
        if (s == null) return "";
        String t = Normalizer.normalize(s, Normalizer.Form.NFC).replace("ํา", "ำ");
        t = t.replaceAll("[​‌‍⁠﻿­]", "");
        t = t.replaceAll("\\s+", " ").trim();
        t = t.replaceAll(" ?" + MAI_YAMOK, " " + MAI_YAMOK).trim();
        return t;
    }

    public static boolean sameKey(String a, String b) {
        return key(a).equals(key(b));
    }

    public static boolean isDefault(String s) {
        return sameKey(s, DEFAULT_PHRASE);
    }

    /** Count of spacing (non-combining) letters, a rough proxy for phrase length. */
    public static int baseLetters(String s) {
        String k = key(s);
        int n = 0;
        for (int i = 0; i < k.length(); i++) {
            char c = k.charAt(i);
            if (c == ' ' || c == MAI_YAMOK) continue;
            int type = Character.getType(c);
            if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK) continue;
            n++;
        }
        return n;
    }

    /**
     * True if the phrase is likely too short to be told apart from everyday sounds.
     * Uses the measured sample duration when available (most reliable), otherwise text length.
     */
    public static boolean likelyTooShort(String text, double avgSampleMs) {
        if (avgSampleMs > 0) return avgSampleMs < 380;
        return baseLetters(text) <= 2;
    }

    private static boolean isPunct(char c, int type) {
        if (c == MAI_YAMOK) return false;
        switch (type) {
            case Character.CONNECTOR_PUNCTUATION:
            case Character.DASH_PUNCTUATION:
            case Character.START_PUNCTUATION:
            case Character.END_PUNCTUATION:
            case Character.INITIAL_QUOTE_PUNCTUATION:
            case Character.FINAL_QUOTE_PUNCTUATION:
            case Character.OTHER_PUNCTUATION:
                return true;
            default:
                return false;
        }
    }
}
