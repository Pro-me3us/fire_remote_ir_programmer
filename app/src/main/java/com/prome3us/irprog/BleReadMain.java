package com.prome3us.irprog;

import android.content.Context;
import android.os.Looper;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** ADB/root read-only diagnostic. Runs the same transport as the app, with no Activity. */
public final class BleReadMain {
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        Context context = (Context) at.getMethod("getSystemContext").invoke(thread);
        CountDownLatch done = new CountDownLatch(1);
        BleTransfer transfer = new BleTransfer(context, args.length == 0 ? null : args[0], null,
                new BleTransfer.Listener() {
                    public void progress(String s) { System.out.println(s); }
                    public void finished(boolean ok, String s) { System.out.println((ok ? "PASS: " : "FAIL: ") + s); done.countDown(); }
                });
        transfer.start();
        if (!done.await(45, TimeUnit.SECONDS)) { transfer.cancel(); throw new IllegalStateException("Diagnostic deadline"); }
        System.exit(0);
    }
}
