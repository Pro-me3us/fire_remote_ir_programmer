package com.prome3us.irprog;

import java.util.Locale;

/** Helpers for the "learned" Pronto hex format the Fire TV service uses for IrCode.code1/code2. */
final class Pronto {
    private Pronto() {}

    static String normalize(String s) {
        return s.trim().replaceAll("\\s+", " ").toUpperCase(Locale.US);
    }

    /**
     * Learned/raw Pronto as THIS service actually generates and parses it - confirmed from decompiled
     * source, not the textbook Philips Pronto spec:
     *
     *   IrCodeConverter.convertToPronto(raw[], freq):
     *     "0000 " + freq + " " + toHex(raw.length / 2) + " 0000 " + <one word per element of raw[]>
     *
     *   - word2 ("n1") is raw.length/2 using Java int (floor) division - for an ODD-length raw capture
     *     this silently loses the low bit, so n1 does NOT reliably reconstruct the true data length.
     *   - word3 ("n2") is a HARDCODED "0000" literal, never an actual second-sequence count.
     *
     *   IrCodeConverter.convertToRaw(pronto) - the PUSH-side parser - confirms this is safe to ignore:
     *   it removes exactly 4 header words (word0, freq, n1, n2) WITHOUT using n1/n2's numeric value for
     *   anything, then treats every remaining word as raw data, whatever that count is. So odd-length
     *   captures round-trip correctly on push; there's no transmission-fidelity risk from this.
     *
     * An earlier version of this check assumed the classic total=4+2*(n1+n2) invariant and rejected
     * every learned code with an odd-length raw capture (a header-says-N/found-N+1 mismatch on
     * essentially every real capture) - a bug in this validator, not a real learning failure. Fixed by
     * dropping the length cross-check entirely and validating structure only.
     */
    /**
     * Compares two learned captures allowing for raw-timing measurement jitter, instead of exact
     * string equality. Real IR receivers don't reproduce byte-identical microsecond counts across
     * separate presses of the same physical button/protocol state - only the structure (word count,
     * format marker) should be exact; individual timing values are compared within a percentage
     * tolerance instead.
     *
     * Word-by-word treatment:
     *   word0 ("0000")     - exact (format marker, not a measurement)
     *   word1 (frequency)  - tolerance (hardware-measured, but should barely vary - carrier is fixed)
     *   word2, word3       - exact (both are DERIVED from word count, not raw measurements: word2 is
     *                         floor(dataLength/2), word3 is always a hardcoded "0000" - see
     *                         IrCodeConverter.convertToPronto. If word counts match, as required below,
     *                         these are guaranteed to already match too.)
     *   remaining words    - tolerance (actual raw timing samples - this is where real jitter lives)
     *
     * Word COUNT must match exactly - that's a structural property of the capture (how many edges were
     * detected), not a timing measurement, so no tolerance is applied there. A length mismatch is
     * treated as "not equivalent" rather than silently truncating/padding.
     *
     * toleranceFraction is e.g. 0.20 for 20%. This default hasn't been calibrated against real hardware
     * jitter measurements - it's a reasonable starting guess, not a verified figure. If codes still fail
     * to match reliably, try loosening it (e.g. 0.30-0.40); if clearly-different captures are being
     * accepted as equivalent, tighten it.
     */
    static boolean approxEqual(String a, String b, double toleranceFraction) {
        String[] wa = normalize(a).split(" ");
        String[] wb = normalize(b).split(" ");
        if (wa.length != wb.length) return false;
        for (int i = 0; i < wa.length; i++) {
            if (i == 1) {
                if (!withinTolerance(wa[i], wb[i], toleranceFraction)) return false;
            } else if (i == 0 || i == 2 || i == 3) {
                if (!wa[i].equalsIgnoreCase(wb[i])) return false;
            } else {
                if (!withinTolerance(wa[i], wb[i], toleranceFraction)) return false;
            }
        }
        return true;
    }

    private static boolean withinTolerance(String hexA, String hexB, double toleranceFraction) {
        int a, b;
        try {
            a = Integer.parseInt(hexA, 16);
            b = Integer.parseInt(hexB, 16);
        } catch (NumberFormatException e) {
            return false;
        }
        int diff = Math.abs(a - b);
        int denom = Math.max(Math.max(a, b), 1); // avoid /0; also avoids inflating tiny-value percentages
        return (diff / (double) denom) <= toleranceFraction;
    }

    static String check(String code) {
        if (code == null) return "empty";
        String[] w = normalize(code).split(" ");
        if (w.length < 6) return "too short";
        try {
            for (String x : w) {
                if (x.length() != 4) return "every word must be 4 hex digits (got '" + x + "')";
                Integer.parseInt(x, 16);
            }
            if (!w[0].equals("0000")) return "first word must be 0000 (raw/learned Pronto format)";
            if (Integer.parseInt(w[1], 16) == 0) return "carrier frequency word is zero";
        } catch (NumberFormatException e) {
            return "non-hex word";
        }
        return null;
    }
}
