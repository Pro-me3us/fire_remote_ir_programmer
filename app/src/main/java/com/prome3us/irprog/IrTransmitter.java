package com.prome3us.irprog;

import android.content.Context;
import android.hardware.ConsumerIrManager;

/**
 * Transmits a learned Pronto code from the Fire TV Cube's OWN built-in IR blaster (not the remote's),
 * so a code can be verified before it's ever pushed to the remote.
 *
 * Uses the plain public android.hardware.ConsumerIrManager API (requires android.permission.TRANSMIT_IR,
 * a NORMAL/install-time permission - confirmed via the AOSP IConsumerIr.hal and consumerir.h headers,
 * no runtime grant dialog needed) - NOT a raw sysfs write. This was a deliberate choice over sysfs: the
 * service APK (com.amazon.tv.devicecontrol) itself calls this exact same API
 * (ConsumerIrTransmitterImpl.transmit -> android.hardware.ConsumerIrManager.transmit), obtained via
 * context.getSystemService("consumer_ir") - confirmed by decompiling it, not assumed. That means this
 * is the sanctioned, HAL-backed path on this hardware, with no sysfs permission/SELinux uncertainty.
 *
 * The Pronto-to-(frequencyHz, int[] pattern) conversion is ALSO taken from the service's own
 * IrCodeConverter.convertToRaw/calculateFrequency/getRawCode (decompiled, not reimplemented from
 * scratch), so the result matches exactly what the service itself would compute from the same string:
 *   frequencyHz = round(1,000,000 / (freqWordHex * 0.241246))
 *   pattern[i]  = floor(dataWordHex[i] * 100,000 / frequencyHz)   (clamped to >=1, matching the
 *                 service's correctZeroCodes - ConsumerIrManager would likely reject a literal 0)
 *
 * ConsumerIrManager.transmit()'s pattern is alternating ON/OFF microsecond durations, first entry ON;
 * an odd-length array is explicitly fine per the HAL docs ("carrier turned off at the end... even if
 * an odd number of entries") - which matters here since this service's learned captures are commonly
 * odd-length (see Pronto.java's header comment on IrCodeConverter.convertToPronto).
 */
final class IrTransmitter {
    private IrTransmitter() {}

    static final class Result {
        final boolean success;
        final String message;
        Result(boolean success, String message) { this.success = success; this.message = message; }
    }

    static Result transmit(Context ctx, String prontoCode) {
        ConsumerIrManager cir;
        try {
            cir = (ConsumerIrManager) ctx.getSystemService(Context.CONSUMER_IR_SERVICE);
        } catch (Exception e) {
            return new Result(false, "Could not get ConsumerIrManager: " + e);
        }
        if (cir == null) {
            return new Result(false, "No ConsumerIrManager on this device (getSystemService returned null).");
        }
        if (!cir.hasIrEmitter()) {
            return new Result(false, "ConsumerIrManager reports this device has no IR emitter.");
        }

        int[] parsed;
        try {
            parsed = prontoToFrequencyAndPattern(prontoCode);
        } catch (RuntimeException e) {
            return new Result(false, "Could not convert code: " + e.getMessage());
        }
        int frequencyHz = parsed[0];
        int[] pattern = new int[parsed.length - 1];
        System.arraycopy(parsed, 1, pattern, 0, pattern.length);

        try {
            cir.transmit(frequencyHz, pattern);
        } catch (SecurityException e) {
            return new Result(false, "Missing android.permission.TRANSMIT_IR: " + e);
        } catch (Exception e) {
            return new Result(false, "transmit() failed: " + e);
        }
        return new Result(true, "Transmitted at " + frequencyHz + "Hz, " + pattern.length + " pattern entries.");
    }

    /** Returns [frequencyHz, pattern[0], pattern[1], ...] - packed into one array to avoid an extra
     *  small allocation/object for the common case; unpacked by the caller above. */
    private static int[] prontoToFrequencyAndPattern(String prontoCode) {
        String err = Pronto.check(prontoCode);
        if (err != null) throw new IllegalArgumentException("invalid Pronto code: " + err);
        String[] w = Pronto.normalize(prontoCode).split(" ");
        int freqWord = Integer.parseInt(w[1], 16);
        if (freqWord == 0) throw new IllegalArgumentException("zero frequency word");
        int frequencyHz = (int) (1_000_000.0 / (freqWord * 0.241246));
        if (frequencyHz <= 0) throw new IllegalArgumentException("computed frequency <= 0 Hz");

        int[] out = new int[w.length - 4 + 1]; // data words + 1 slot for frequency
        out[0] = frequencyHz;
        for (int i = 4; i < w.length; i++) {
            int hex = Integer.parseInt(w[i], 16);
            int us = (int) Math.floor(hex * 100000.0 / frequencyHz);
            out[i - 4 + 1] = Math.max(1, us); // matches the service's correctZeroCodes
        }
        return out;
    }
}
