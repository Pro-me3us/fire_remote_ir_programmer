package com.prome3us.irprog;

/** Best-effort protocol identification for a learned Pronto code, by matching the leading
 *  pulse/space signature and carrier frequency against each known protocol's timing, then
 *  decoding bits if a match is found. Several protocols (NEC/NEC-Extended/Samsung32/LG32) are
 *  electrically identical - they differ only in how the bits are labeled, not in the waveform -
 *  so those can't be told apart from the signal alone; this reports them as one family. */
final class IrDetector {
    private IrDetector() {}

    private static boolean near(double v, double target, double pct) {
        return Math.abs(v - target) <= target * pct;
    }

    static String describe(String pronto) {
        try {
            int[] t = Pronto.toTicks10us(pronto);
            int freq = Pronto.carrierHz(pronto);
            int[] us = new int[t.length];
            for (int i = 0; i < t.length; i++) us[i] = t[i] * 10;
            if (us.length < 4) return "Detected protocol: too short to classify.";

            if (near(freq, 38000, 0.08) && near(us[0], 9000, 0.1) && near(us[1], 4500, 0.15)) {
                Long v = decodePulseWidth(us, 2, 32, 560, 560, 1690);
                if (v != null) {
                    int addr = (int) (v & 0xff), addrInv = (int) ((v >> 8) & 0xff);
                    int cmd = (int) ((v >> 16) & 0xff), cmdInv = (int) ((v >> 24) & 0xff);
                    boolean stdNec = (addrInv == ((~addr) & 0xff));
                    return String.format(java.util.Locale.US,
                            "Detected protocol: NEC-family (32-bit), raw=0x%08X%s", v,
                            stdNec ? String.format(java.util.Locale.US,
                                    " (standard NEC: address=0x%02X command=0x%02X - to re-apply, pick protocol "
                                    + "NEC and enter 0x%02X%02X, OR pick NEC_EXTENDED/SAMSUNG32/LG32 and enter "
                                    + "the full raw value above)", addr, cmd, addr, cmd)
                                   : String.format(java.util.Locale.US,
                                    " (extended/Samsung/LG-style: address=0x%04X command=0x%02X - to re-apply, "
                                    + "pick protocol NEC_EXTENDED, SAMSUNG32, or LG32 and enter the full raw "
                                    + "value above)", ((addrInv << 8) | addr), cmd));
                }
            }
            if (near(freq, 38000, 0.08) && near(us[0], 8400, 0.1) && near(us[1], 4200, 0.15)) {
                Long v = decodePulseWidth(us, 2, 16, 527, 527, 1707);
                if (v != null) return String.format("Detected protocol: JVC (16-bit), raw=0x%04X", v);
            }
            if (near(freq, 37000, 0.08) && near(us[0], 3456, 0.1) && near(us[1], 1728, 0.15)) {
                int bits = (us.length - 3) / 2;
                Long v = decodePulseWidth(us, 2, bits, 432, 432, 1296);
                if (v != null) return String.format("Detected protocol: Panasonic/Kaseikyo (%d-bit), raw=0x%X", bits, v);
            }
            if (near(freq, 36000, 0.08) && near(us[0], 2666, 0.12) && near(us[1], 889, 0.2)) {
                return "Detected protocol: RC6 (leader matched; bit decode not implemented - raw code stored as learned).";
            }
            if (near(freq, 40000, 0.08) && near(us[0], 2400, 0.1) && near(us[1], 600, 0.2)) {
                int bits = (us.length - 2) / 2;
                String variant = bits >= 20 ? "20-bit" : bits >= 15 ? "15-bit" : "12-bit";
                Long v = decodeSony(us, bits);
                if (v != null) return String.format("Detected protocol: Sony SIRC (%s), raw=0x%X", variant, v);
            }
            if (near(freq, 36000, 0.08) && !near(us[0], 2666, 0.12)) {
                return "Detected protocol: RC5 (Manchester signature matched; bit decode not implemented - raw code stored as learned).";
            }
            if (near(freq, 38000, 0.08) && us[0] < 500) {
                return "Detected protocol: Sharp (no-leader signature matched; bit decode not implemented - raw code stored as learned).";
            }
        } catch (Exception ignored) { }
        return "Detected protocol: unrecognized (stored as a raw learned code either way).";
    }

    /** NEC-style: fixed pulse width, space length distinguishes 0/1. Decodes byte-by-byte,
     *  LSB-first within each byte (actual transmission order), then reverses byte order for
     *  the printed value - matching how NEC/extended-NEC codes are conventionally published
     *  (confirmed against a real captured code: last-transmitted byte = most-significant printed byte). */
    private static Long decodePulseWidth(int[] us, int startIdx, int bits, int pulseUs, int zeroSpace, int oneSpace) {
        if (bits % 8 != 0) return null;
        int idx = startIdx;
        int nBytes = bits / 8;
        int[] bytes = new int[nBytes];
        for (int bi = 0; bi < nBytes; bi++) {
            int val = 0;
            for (int i = 0; i < 8; i++) {
                if (idx + 1 >= us.length) return null;
                if (!near(us[idx], pulseUs, 0.3)) return null;
                int space = us[idx + 1];
                boolean one = Math.abs(space - oneSpace) < Math.abs(space - zeroSpace);
                if (one) val |= (1 << i);
                idx += 2;
            }
            bytes[bi] = val;
        }
        long v = 0;
        for (int bi = nBytes - 1; bi >= 0; bi--) v = (v << 8) | bytes[bi];
        return v;
    }

    /** Sony SIRC: pulse width distinguishes 0/1, fixed space. LSB-first. */
    private static Long decodeSony(int[] us, int bits) {
        int idx = 2;
        long v = 0;
        for (int i = 0; i < bits; i++) {
            if (idx >= us.length) return null;
            boolean one = Math.abs(us[idx] - 1200) < Math.abs(us[idx] - 600);
            v |= (one ? 1L : 0L) << i;
            idx += 2;
        }
        return v;
    }
}
