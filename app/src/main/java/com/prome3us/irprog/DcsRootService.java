package com.prome3us.irprog;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import android.util.Log;

import com.topjohnwu.superuser.ipc.RootService;

import java.lang.reflect.Method;

/** Narrow root broker. The UI remains an ordinary /data/app application. */
public final class DcsRootService extends RootService {
    static final String CONTROL = "com.prome3us.irprog.RootControl";
    static final String EVENTS = "com.prome3us.irprog.RootEvents";
    static final int CONNECT = 1001;
    static final int ENSURE_REMOTE_SETTING = 1002;
    static final int LEARNING_DIAGNOSTICS = 1003;
    static final int READY = 1;
    static final int FAILED = 2;

    private volatile IBinder amazon;
    private IBinder events;
    private Object activityManager;
    private IInterface connection;
    private boolean bound;
    private int ownerUid;
    private static final String API = "com.amazon.tv.devicecontrol.api.v1.IDeviceControlApi";
    private static final String LEARN_CALLBACK = "com.amazon.tv.devicecontrol.api.v1.IIrLearningCallback";
    private final Object learningLock = new Object();
    private LearnSession learning;
    private int learningStarts;
    private int learningStops;

    @Override public void onCreate() {
        super.onCreate();
        ownerUid = getApplicationInfo().uid;
        Log.i(MainActivity.TAG, "Root broker started: uid=" + android.os.Process.myUid());
    }

    private final Binder broker = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (Binder.getCallingUid() != ownerUid) throw new SecurityException("Wrong client UID");
            // Binder propagates the UI's identity through nested IPC unless explicitly cleared.
            long identity = Binder.clearCallingIdentity();
            try {
                if (code == CONNECT) {
                    data.enforceInterface(CONTROL);
                    IBinder appThread = data.readStrongBinder();
                    events = data.readStrongBinder();
                    try {
                        bindAmazon(appThread);
                        reply.writeNoException();
                    } catch (Exception e) {
                        Log.e(MainActivity.TAG, "Root bind failed", e);
                        throw new IllegalStateException("Root bind failed: " + e, e);
                    }
                    return true;
                }
                if (code == ENSURE_REMOTE_SETTING) {
                    data.enforceInterface(CONTROL);
                    String value = remoteSetting("get");
                    int old = "1".equals(value) ? 1 : 0;
                    if (old != 1) {
                        remoteSetting("put");
                        if (!"1".equals(remoteSetting("get")))
                            throw new IllegalStateException("Could not enable show_dc_settings");
                    }
                    reply.writeNoException();
                    reply.writeInt(old);
                    return true;
                }
                if (code == LEARNING_DIAGNOSTICS) {
                    data.enforceInterface(CONTROL);
                    reply.writeNoException();
                    synchronized (learningLock) {
                        reply.writeInt(learningStarts);
                        reply.writeInt(learningStops);
                        reply.writeInt(learning != null ? 1 : 0);
                    }
                    return true;
                }
                // Only the programmer's operations and read-only API version are exposed.
                if (code != 48 && code != 50 && code != 51 && code != 70 && code != 83)
                    throw new SecurityException("Unsupported root operation " + code);
                IBinder target = amazon;
                if (target == null) throw new RemoteException("Amazon service is disconnected");
                if (code == 50) return startLearning(target, data, reply);
                if (code == 51) {
                    data.enforceInterface(API);
                    stopLearning(target, data.readString());
                    reply.writeNoException();
                    return true;
                }
                return target.transact(code, data, reply, flags);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    };

    /** Observe terminal callbacks in root before they reach the UI. Amazon's stop operation is
     * NOT idempotent: its driver disables the IRQ again even for a completed session. */
    private final class LearnSession extends Binder {
        final IBinder client;
        String token;
        boolean complete;

        LearnSession(IBinder client) { this.client = client; }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(LEARN_CALLBACK);
                return true;
            }
            if (code < 1 || code > 3) return super.onTransact(code, data, reply, flags);
            int position = data.dataPosition();
            data.enforceInterface(LEARN_CALLBACK);
            data.setDataPosition(position);
            synchronized (learningLock) {
                // Cancellation already completed locally. Ignore a late callback from that session.
                if (complete) return true;
                complete = true;
                if (learning == this) learning = null;
            }
            return client.transact(code, data, reply, flags);
        }
    }

    private boolean startLearning(IBinder target, Parcel data, Parcel reply) throws RemoteException {
        data.enforceInterface(API);
        IBinder callback = data.readStrongBinder();
        int timeout = data.readInt();
        if (callback == null) throw new IllegalArgumentException("Missing learning callback");
        LearnSession session = new LearnSession(callback);
        synchronized (learningLock) {
            if (learning != null) throw new IllegalStateException("Learning already in progress");
            learning = session;
        }
        Parcel request = Parcel.obtain();
        try {
            request.writeInterfaceToken(API);
            request.writeStrongBinder(session);
            request.writeInt(timeout);
            if (!target.transact(50, request, reply, 0))
                throw new RemoteException("startIrLearning is not supported");
            int position = reply.dataPosition();
            try {
                reply.setDataPosition(0);
                reply.readException();
                synchronized (learningLock) {
                    // A terminal callback can arrive before this synchronous reply.
                    session.token = reply.readString();
                    learningStarts++;
                }
            } finally { reply.setDataPosition(position); }
            return true;
        } catch (RemoteException | RuntimeException e) {
            synchronized (learningLock) {
                session.complete = true;
                if (learning == session) learning = null;
            }
            throw e;
        } finally { request.recycle(); }
    }

    private void stopLearning(IBinder target, String token) throws RemoteException {
        synchronized (learningLock) {
            if (learning == null || learning.complete || token == null
                    || !token.equals(learning.token)) {
                Log.i(MainActivity.TAG, "Ignoring stop for an inactive learning session");
                return;
            }
            learning.complete = true;
            learning = null;
        }
        Parcel request = Parcel.obtain();
        Parcel response = Parcel.obtain();
        try {
            request.writeInterfaceToken(API);
            request.writeString(token);
            if (!target.transact(51, request, response, 0))
                throw new RemoteException("stopIrLearning is not supported");
            response.readException();
            synchronized (learningLock) { learningStops++; }
        } finally { request.recycle(); response.recycle(); }
    }

    @Override public IBinder onBind(Intent intent) { return broker; }

    private void bindAmazon(IBinder appThread) throws Exception {
        if (bound) throw new IllegalStateException("Already connected");
        if (appThread == null || events == null) throw new IllegalArgumentException("Missing client");
        Class<?> atStub = Class.forName("android.app.IApplicationThread$Stub");
        Object caller = atStub.getMethod("asInterface", IBinder.class).invoke(null, appThread);
        Class<?> amClass = Class.forName("android.app.IActivityManager");
        if (Build.VERSION.SDK_INT >= 26) {
            activityManager = Class.forName("android.app.ActivityManager")
                    .getMethod("getService").invoke(null);
        } else {
            activityManager = Class.forName("android.app.ActivityManagerNative")
                    .getMethod("getDefault").invoke(null);
        }
        Binder callback = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                if (code == INTERFACE_TRANSACTION) {
                    if (reply != null) reply.writeString("android.app.IServiceConnection");
                    return true;
                }
                if (code != 1) return super.onTransact(code, data, reply, flags);
                data.enforceInterface("android.app.IServiceConnection");
                if (data.readInt() != 0) ComponentName.CREATOR.createFromParcel(data);
                amazon = data.readStrongBinder();
                boolean dead = Build.VERSION.SDK_INT >= 26 && data.readInt() != 0;
                if (amazon != null && !dead) {
                    amazon.linkToDeath(() -> { amazon = null; notifyClient(FAILED, "Amazon service died"); }, 0);
                    notifyClient(READY, "Amazon service connected through Magisk root");
                } else {
                    amazon = null;
                    notifyClient(FAILED, "Amazon service disconnected");
                }
                return true;
            }
        };
        connection = (IInterface) Class.forName("android.app.IServiceConnection$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, callback);
        Intent intent = new Intent(DcsClient.BIND_ACTION)
                .setComponent(new ComponentName(DcsClient.PKG, DcsClient.SERVICE_CLASS));
        // Android requires a registered IApplicationThread even when UID 0 makes the call.
        // Use our UI's thread token for lifecycle accounting; the Binder caller is still root.
        Method bind = amClass.getMethod("bindService", Class.forName("android.app.IApplicationThread"),
                IBinder.class, Intent.class, String.class, Class.forName("android.app.IServiceConnection"),
                int.class, String.class, int.class);
        int result = (Integer) bind.invoke(activityManager, caller, null, intent, null, connection,
                Context.BIND_AUTO_CREATE, getPackageName(), ownerUid / 100000);
        if (result != 1) throw new IllegalStateException("bindService returned " + result);
        bound = true;
    }

    /** RootService has no AMS-registered application thread for ContentResolver either.
     * Android's settings tool acquires the provider as an external root caller. All arguments
     * are fixed here; this is not a shell-command interface exposed to the app. */
    private String remoteSetting(String operation) {
        Process process = null;
        try {
            String user = Integer.toString(ownerUid / 100000);
            ProcessBuilder builder = "put".equals(operation)
                    ? new ProcessBuilder("/system/bin/settings", "--user", user,
                            "put", "global", "show_dc_settings", "1")
                    : new ProcessBuilder("/system/bin/settings", "--user", user,
                            "get", "global", "show_dc_settings");
            process = builder.redirectErrorStream(true).start();
            long deadline = SystemClock.elapsedRealtime() + 10000;
            int exit;
            while (true) {
                try { exit = process.exitValue(); break; }
                catch (IllegalThreadStateException running) {
                    if (SystemClock.elapsedRealtime() > deadline)
                        throw new IllegalStateException("settings command timed out");
                    Thread.sleep(20);
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = input.read(buffer)) != -1 && output.size() < 4096)
                    output.write(buffer, 0, count);
            }
            String text = new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
            if (exit != 0) throw new IllegalStateException("settings failed: " + text);
            return text;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Root settings operation failed", e);
        } finally { if (process != null) process.destroy(); }
    }

    private void notifyClient(int code, String message) {
        IBinder target = events;
        if (target == null) return;
        Parcel p = Parcel.obtain();
        try {
            p.writeInterfaceToken(EVENTS);
            p.writeString(message);
            target.transact(code, p, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException e) {
            Log.w(MainActivity.TAG, "UI disconnected", e);
        } finally { p.recycle(); }
    }

    @Override public void onDestroy() {
        IBinder target = amazon;
        String token;
        synchronized (learningLock) { token = learning == null ? null : learning.token; }
        if (target != null && token != null) {
            try { stopLearning(target, token); }
            catch (Exception e) { Log.w(MainActivity.TAG, "Stop learning during shutdown failed", e); }
        }
        if (bound) {
            try {
                Class.forName("android.app.IActivityManager")
                        .getMethod("unbindService", Class.forName("android.app.IServiceConnection"))
                        .invoke(activityManager, connection);
            } catch (Exception e) { Log.w(MainActivity.TAG, "Unbind failed", e); }
        }
        amazon = null;
        super.onDestroy();
    }
}
