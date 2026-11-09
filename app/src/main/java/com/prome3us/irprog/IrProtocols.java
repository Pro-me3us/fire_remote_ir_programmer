package com.prome3us.irprog;

/** Encoders for the 10 most common consumer IR protocols. Each returns raw pulse/space
 *  microseconds (ready for Pronto.encode) plus the carrier frequency to use.
 *
 *  Confidence note: NEC/NECx/Samsung32/LG32, Sony SIRC, JVC and Sharp timings below are the
 *  well-established textbook values. RC5 and RC6's bit-sense convention (which level means "1")
 *  varies across references; this uses the convention common learning-remote libraries use, which
 *  guarantees a pulse-first frame (required by this table format), but hasn't been verified against
 *  real RC5/RC6 hardware in this project - test before relying on it. Panasonic/Kaseikyo's vendor-id
 *  and parity fields vary by manufacturer, so the full 48-bit frame is taken as-given rather than
 *  computed; look up the exact value for your device.
 */
final class IrProtocols {
    private IrProtocols() {}

    static final class Result { final int[] us; final int freqHz; Result(int[] u, int f) { us = u; freqHz = f; } }

    enum Protocol {
        NEC, NEC_EXTENDED, SAMSUNG32, LG32, SONY_SIRC, RC5, RC6, PANASONIC_KASEIKYO, JVC, SHARP
    }

    static Result encode(Protocol p, long code, int bits, int sonyVariant) {
        switch (p) {
            case NEC: {
                int addr = (int) ((code >> 8) & 0xff), cmd = (int) (code & 0xff);
                return necFamily(new int[]{addr, (~addr) & 0xff, cmd, (~cmd) & 0xff});
            }
            case NEC_EXTENDED: {
                int addrLo = (int) (code & 0xff), addrHi = (int) ((code >> 8) & 0xff), cmd = (int) ((code >> 16) & 0xff);
                return necFamily(new int[]{addrLo, addrHi, cmd, (~cmd) & 0xff});
            }
            case SAMSUNG32: case LG32: {
                int b0 = (int) (code & 0xff), b1 = (int) ((code >> 8) & 0xff),
                        b2 = (int) ((code >> 16) & 0xff), b3 = (int) ((code >> 24) & 0xff);
                return necFamily(new int[]{b0, b1, b2, b3});
            }
            case SONY_SIRC: return sirc(code, sonyVariant);
            case RC5: return rc5(code);
            case RC6: return rc6(code);
            case PANASONIC_KASEIKYO: return kaseikyo(code, bits);
            case JVC: return jvc(code);
            case SHARP: return sharp(code);
        }
        throw new IllegalArgumentException("unsupported protocol");
    }

    // ---- NEC family: 38kHz, 9000/4500 leader, 560us pulses, 560/1690us spaces, 560us trailer ----
    private static Result necFamily(int[] bytesMsbFirst) {
        Waveform w = new Waveform();
        w.pulse(9000); w.space(4500);
        for (int b : bytesMsbFirst) {
            for (int bit = 0; bit < 8; bit++) {
                w.pulse(560);
                w.space(((b >> bit) & 1) != 0 ? 1690 : 560);
            }
        }
        w.pulse(560);
        // Every real captured code in this app includes a trailing NEC repeat frame after a ~40ms
        // gap (leader + 2250us space + trailer pulse, no data bits). The remote's own playback logic
        // appears to expect this structure - a bare single frame transmits fine standalone (e.g. via
        // the Cube's own IR emitter) but doesn't work once pushed to the remote's table.
        w.space(40000);
        w.pulse(9000); w.space(2250); w.pulse(560);
        return new Result(w.toMicros(), 38000);
    }

    // ---- Sony SIRC: 40kHz, 2400/600 leader, 600/1200us pulses, 600us spaces, LSB-first, no trailer ----
    private static Result sirc(long code, int variant) {
        int cmdBits = 7;
        int addrBits = variant == 15 ? 8 : 5;
        int extBits = variant == 20 ? 8 : 0;
        int totalBits = cmdBits + addrBits + extBits;
        Waveform w = new Waveform();
        w.pulse(2400); w.space(600);
        for (int bit = 0; bit < totalBits; bit++) {
            boolean one = ((code >> bit) & 1) != 0;
            w.pulse(one ? 1200 : 600);
            w.space(600);
        }
        return new Result(w.toMicros(), 40000);
    }

    // ---- RC5: 36kHz, Manchester, 889us half-bit. bit=1 -> pulse,space; bit=0 -> space,pulse. ----
    private static Result rc5(long code14) {
        Waveform w = new Waveform();
        for (int bit = 13; bit >= 0; bit--) {
            boolean one = ((code14 >> bit) & 1) != 0;
            if (one) { w.pulse(889); w.space(889); } else { w.space(889); w.pulse(889); }
        }
        return new Result(w.toMicros(), 36000);
    }

    // ---- RC6 mode 0: 36kHz, cell=444.44us. leader 6c pulse/2c space, start bit + toggle bit are
    // double-width, mode/address/command at normal width. bit=1 -> pulse,space; bit=0 -> space,pulse. ----
    private static Result rc6(long code) {
        double c = 444.44;
        Waveform w = new Waveform();
        w.pulse(6 * c); w.space(2 * c);
        rc6Bit(w, true, 2 * c);                 // start bit, always 1, double width
        for (int bit = 2; bit >= 0; bit--) rc6Bit(w, ((code >> (17 + bit)) & 1) != 0, c); // mode (3 bits)
        rc6Bit(w, ((code >> 16) & 1) != 0, 2 * c);  // toggle bit, double width
        for (int bit = 15; bit >= 0; bit--) rc6Bit(w, ((code >> bit) & 1) != 0, c);        // address+command
        return new Result(w.toMicros(), 36000);
    }
    private static void rc6Bit(Waveform w, boolean one, double halfWidth) {
        if (one) { w.pulse(halfWidth); w.space(halfWidth); } else { w.space(halfWidth); w.pulse(halfWidth); }
    }

    // ---- Panasonic/Kaseikyo: 37kHz, 3456/1728 leader, 432us pulses, 432/1296us spaces, 432us trailer.
    // Full frame (vendor id + data + parity) taken as given - look up the exact value for your device. ----
    private static Result kaseikyo(long code, int bits) {
        Waveform w = new Waveform();
        w.pulse(3456); w.space(1728);
        for (int bit = 0; bit < bits; bit++) {
            w.pulse(432);
            w.space(((code >> bit) & 1) != 0 ? 1296 : 432);
        }
        w.pulse(432);
        return new Result(w.toMicros(), 37000);
    }

    // ---- JVC: 38kHz, 8400/4200 leader, 527us pulses, 527/1707us spaces, 527us trailer, MSB-first ----
    private static Result jvc(long code16) {
        Waveform w = new Waveform();
        w.pulse(8400); w.space(4200);
        for (int bit = 15; bit >= 0; bit--) {
            w.pulse(527);
            w.space(((code16 >> bit) & 1) != 0 ? 1707 : 527);
        }
        w.pulse(527);
        return new Result(w.toMicros(), 38000);
    }

    // ---- Sharp: 38kHz, no leader, 320us pulses, 680/1680us spaces, 320us trailer. Single frame only -
    // some Sharp devices expect a second, data-inverted frame; add if yours needs it. ----
    private static Result sharp(long code15) {
        Waveform w = new Waveform();
        for (int bit = 14; bit >= 0; bit--) {
            w.pulse(320);
            w.space(((code15 >> bit) & 1) != 0 ? 1680 : 680);
        }
        w.pulse(320);
        return new Result(w.toMicros(), 38000);
    }
}
