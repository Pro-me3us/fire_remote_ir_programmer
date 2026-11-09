package com.prome3us.irprog;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Raw remote table format established by the author's working CoreELEC probe. */
final class IrTable {
    private static final byte[] TAIL = {5,0,1,0,0};
    static byte[] build(Map<ProgramButton, String[]> codes) {
        if (codes.isEmpty()) throw new IllegalArgumentException("No codes stored");
        TreeMap<Integer, byte[]> records = new TreeMap<>();
        for (Map.Entry<ProgramButton, String[]> e : codes.entrySet()) {
            Integer id = ButtonIds.ID.get(e.getKey());
            if (id == null) throw new IllegalArgumentException("No confirmed Bluetooth mapping for " + e.getKey()
                    + ". Clear its stored code before copying; it will not be silently discarded.");
            String[] pair = e.getValue();
            if (pair == null || pair.length != 2) throw new IllegalArgumentException("Invalid code pair");
            records.put(id, entry(id, pair[0], pair[1]));
        }
        // Stock convention: power must exist. Preserve a real learned POWER code when supplied.
        if (!records.containsKey(2)) records.put(2, new byte[]{2,1,5,0,5,0,1,0,0});
        ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write(records.size());
        // Keep the stock power-first ordering, then deterministic ID order.
        append(out, records.remove(2));
        for (byte[] record : records.values()) append(out, record);
        append(out, sha(out.toByteArray()));
        byte[] result = out.toByteArray(); validate(result); return result;
    }
    /** Minimal table (power placeholder only, no real buttons) - used to clear the remote. */
    static byte[] buildEmpty() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(1);
        append(out, new byte[]{2, 1, 5, 0, 5, 0, 1, 0, 0});
        append(out, sha(out.toByteArray()));
        byte[] result = out.toByteArray();
        validate(result);
        return result;
    }    
    private static byte[] entry(int id, String first, String second) {
        validateCode(first);
        if (second != null) validateCode(second);
        int freq = Pronto.carrierHz(first);
        if (second != null && Math.abs(Pronto.carrierHz(second) - freq) > freq * 0.02)
            throw new IllegalArgumentException("Toggle codes must use the same carrier frequency");
        int[] a = Pronto.toTicks10us(first);
        int[] b = second == null ? new int[0] : Pronto.toTicks10us(second);
        String text = String.format(Locale.US,"f[%d]c[33]l[%d][%d]r[1]d[0]t[%d]",freq,a.length,b.length,b.length == 0 ? 0 : 1);
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        append(payload, text.getBytes(StandardCharsets.US_ASCII)); payload.write(0);
        for (int[] sequence : new int[][]{a,b}) for (int tick : sequence) {
            if (tick < 1 || tick > 65535) throw new IllegalArgumentException("IR timing does not fit a 16-bit 10us word");
            word(payload,tick);
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(3); body.write(0); word(body,payload.size()); append(body,payload.toByteArray()); append(body,TAIL);
        ByteArrayOutputStream record = new ByteArrayOutputStream();
        record.write(id); record.write(2); word(record,body.size()); append(record,body.toByteArray());
        return record.toByteArray();
    }
    private static void validateCode(String code) {
        String error = Pronto.check(code);
        if (error != null) throw new IllegalArgumentException("Invalid IR code: " + error);
    }
    private static void word(ByteArrayOutputStream out, int value) {
        if (value > 65535) throw new IllegalArgumentException("IR entry exceeds 65535 bytes");
        out.write(value & 255); out.write(value >>> 8);
    }
    private static void append(ByteArrayOutputStream out, byte[] b) { out.write(b,0,b.length); }
    private static byte[] sha(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    /** Validate framing before the privileged helper can write anything. */
    static void validate(byte[] table) {
        if (table == null || table.length < 42 || table.length > 65535)
            throw new IllegalArgumentException("Invalid table size");
        int limit = table.length - 32;
        if (!MessageDigest.isEqual(sha(Arrays.copyOf(table,limit)), Arrays.copyOfRange(table,limit,table.length)))
            throw new IllegalArgumentException("Table checksum mismatch");
        boolean[] seen = new boolean[256]; int pos = 1, count = table[0] & 255;
        if (count == 0) throw new IllegalArgumentException("Empty remote table");
        for (int i=0;i<count;i++) {
            if (pos + 4 > limit) throw new IllegalArgumentException("Truncated table entry");
            int id = table[pos] & 255, tag = table[pos+1] & 255;
            if (seen[id]) throw new IllegalArgumentException("Duplicate button ID"); seen[id] = true;
            int size = (table[pos+2]&255) | ((table[pos+3]&255)<<8); pos += 4;
            if (size < 5 || pos + size > limit || (tag != 1 && tag != 2))
                throw new IllegalArgumentException("Invalid entry length or tag");
            if (!Arrays.equals(TAIL, Arrays.copyOfRange(table,pos+size-5,pos+size)))
                throw new IllegalArgumentException("Invalid Bluetooth fallback command");
            if (tag == 1 && size != 5) throw new IllegalArgumentException("Invalid placeholder");
            if (tag == 2) {
                if (size < 10 || table[pos] != 3 || table[pos+1] != 0
                        || ((table[pos+2]&255) | ((table[pos+3]&255)<<8)) != size-9)
                    throw new IllegalArgumentException("Invalid IR command framing");
            }
            pos += size;
        }
        if (pos != limit || !seen[2]) throw new IllegalArgumentException("Invalid table records");
    }
}
