package com.prome3us.irprog;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
final class ProbeTable {
private static final byte[] TAIL = {5,0,1,0,0};
    private static byte[] entry(int id) {
        int cmd = id & 0xff;
        int[] sent = {0xA5, 0x5A, cmd, (~cmd) & 0xff};   // NEC bytes, LSB first
        int[] w = new int[71];
        int n = 0, sum = 0;
        w[n++] = 899; w[n++] = 449; sum += 899 + 449;
        for (int b : sent) {
            for (int j = 0; j < 8; j++) {
                int space = ((b >> j) & 1) != 0 ? 165 : 55;
                w[n++] = 60; w[n++] = space; sum += 60 + space;
            }
        }
        w[n++] = 60; sum += 60;
        w[n++] = 10800 - sum;
        w[n++] = 899; w[n++] = 226; w[n++] = 60;
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        byte[] asc = "f[38028]c[33]l[71][0]r[1]d[0]t[0]".getBytes();
        p.write(asc, 0, asc.length);
        p.write(0);
        for (int i = 0; i < n; i++) { p.write(w[i] & 0xff); p.write((w[i] >> 8) & 0xff); }
        byte[] pay = p.toByteArray();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(3); body.write(0);
        body.write(pay.length & 0xff); body.write((pay.length >> 8) & 0xff);
        body.write(pay, 0, pay.length);
        body.write(TAIL, 0, TAIL.length);
        byte[] bb = body.toByteArray();
        ByteArrayOutputStream rec = new ByteArrayOutputStream();
        rec.write(id); rec.write(2);
        rec.write(bb.length & 0xff); rec.write((bb.length >> 8) & 0xff);
        rec.write(bb, 0, bb.length);
        return rec.toByteArray();
    }
    
    static byte[] buildTable(int[] ids) {
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        t.write(ids.length + 1);
        t.write(0x02); t.write(0x01); t.write(5); t.write(0);   // power placeholder record, as in the stock push
        t.write(TAIL, 0, TAIL.length);
        for (int id : ids) { byte[] e = entry(id); t.write(e, 0, e.length); }
        byte[] body = t.toByteArray();
        try {
            byte[] sha = MessageDigest.getInstance("SHA-256").digest(body);
            t.write(sha, 0, sha.length);
        } catch (Exception e) { throw new RuntimeException(e); }
        return t.toByteArray();
    }

}
