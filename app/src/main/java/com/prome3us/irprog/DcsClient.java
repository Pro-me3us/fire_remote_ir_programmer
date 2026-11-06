package com.prome3us.irprog;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.IInterface;
import com.topjohnwu.superuser.Shell;
import com.topjohnwu.superuser.ipc.RootService;
import android.os.Parcel;
import android.os.RemoteException;

import com.amazon.tv.devicecontrol.commons.Dtos;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal hand-written client for the AIDL interface com.amazon.tv.devicecontrol.api.v1.IDeviceControlApi
 * exposed by the Fire TV system app com.amazon.tv.devicecontrol.
 *
 * No AIDL build step is needed: the wire format (interface token, "int 1 + Parcel.writeSerializable"
 * for AutoParcelable arguments, transaction codes) was read out of the service APK's
 * IDeviceControlApi$Stub / $Stub$Proxy classes:
 *
 *   startIrLearning(IIrLearningCallback, int) -> String   code 50  (sync)
 *   stopIrLearning(String)                                code 51  (sync)
 *   pushToRemote(AutoParcelable, AutoParcelable)          code 69  (sync)
 *   restoreRemoteConfiguration()                          code 70  (oneway)
 *   pushToRemoteAsync(AP, AP, ICallback)                  code 83  (oneway)
 *
 * Callback interfaces (we host these as Binders):
 *   IIrLearningCallback: 1 onSuccess(AutoParcelable<String pronto>), 2 onError(String), 3 onTimeout()
 *   ICallback:           1 onSuccess(),                               2 onError(String)
 */
final class DcsClient {

    static final String PKG = "com.amazon.tv.devicecontrol";
    static final String SERVICE_CLASS = "com.amazon.tv.devicecontrol.api.DeviceControlApiService";
    static final String BIND_ACTION = "com.amazon.tv.devicecontrol.AIDL_SERVICE";

    private static final String API_DESC = "com.amazon.tv.devicecontrol.api.v1.IDeviceControlApi";
    private static final String IRCB_DESC = "com.amazon.tv.devicecontrol.api.v1.IIrLearningCallback";
    private static final String CB_DESC = "com.amazon.tv.devicecontrol.api.v1.ICallback";

    private static final int TX_START_IR_LEARNING = 50;
    private static final int TX_STOP_IR_LEARNING = 51;
    private static final int TX_RESTORE_REMOTE = 70;
    private static final int TX_PUSH_TO_REMOTE_ASYNC = 83;

    interface LearnListener {
        void onLearned(String prontoCode);
        void onTimeout();
        void onError(String message);
    }

    interface ResultListener {
        void onSuccess();
        void onError(String message);
    }

    interface ConnectionListener {
        void onConnected();
        void onDisconnected();
        void onError(String message);
    }

    private final Context ctx;
    private final ConnectionListener connListener;
    private volatile IBinder service;
    private boolean bound;

    // Callback binders must stay strongly referenced until the service has called them.
    private final List<Binder> liveCallbacks = new ArrayList<Binder>();
    private final Map<String, IrLearnCallback> learnCallbacks = new HashMap<>();

    DcsClient(Context ctx, ConnectionListener l) {
        this.ctx = ctx.getApplicationContext();
        this.connListener = l;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean closed;
    private IBinder root;
    private final Runnable connectionTimeout = () -> fail("Root connection timed out. Check Magisk and reopen the app.");

    private void fail(String message) {
        main.post(() -> {
            if (closed) return;
            unbind();
            connListener.onError(message);
        });
    }

    private final Binder events = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            data.enforceInterface(DcsRootService.EVENTS);
            String message = data.readString();
            main.post(() -> {
                if (closed) return;
                main.removeCallbacks(connectionTimeout);
                if (code == DcsRootService.READY) {
                    service = root;
                    connListener.onConnected();
                } else {
                    service = null;
                    fail(message);
                }
            });
            return true;
        }
    };

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (closed) return;
            root = binder;
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                Class<?> activityThread = Class.forName("android.app.ActivityThread");
                Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
                IInterface appThread = (IInterface) activityThread.getMethod("getApplicationThread").invoke(thread);
                data.writeInterfaceToken(DcsRootService.CONTROL);
                data.writeStrongBinder(appThread.asBinder());
                data.writeStrongBinder(events);
                if (!binder.transact(DcsRootService.CONNECT, data, reply, 0))
                    throw new RemoteException("Root helper rejected initialization");
                reply.readException();
            } catch (Exception e) { fail("Cannot initialize root helper: " + e); }
            finally { data.recycle(); reply.recycle(); }
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            root = null;
            if (!closed) fail("Root helper disconnected. Reopen the app to reconnect.");
        }
    };

    /** Starts Magisk authorization asynchronously, never blocks the UI for a root prompt. */
    boolean bind() {
        closed = false;
        main.postDelayed(connectionTimeout, 45000);
        Shell.getShell(shell -> main.post(() -> {
            if (closed) return;
            if (!shell.isRoot()) {
                fail("Root access denied. Grant this app Superuser access in Magisk, then reopen it.");
                return;
            }
            try {
                bound = true;
                RootService.bind(new Intent(ctx, DcsRootService.class), conn);
            } catch (Exception e) { fail("Could not start root helper: " + e); }
        }));
        return true;
    }

    void unbind() {
        closed = true;
        main.removeCallbacks(connectionTimeout);
        if (bound) {
            try { RootService.unbind(conn); } catch (IllegalArgumentException ignored) {}
            bound = false;
        }
        service = null;
        root = null;
        synchronized (this) { liveCallbacks.clear(); learnCallbacks.clear(); }
    }

    int ensureRemoteSetting() throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DcsRootService.CONTROL);
            if (!requireService().transact(DcsRootService.ENSURE_REMOTE_SETTING, data, reply, 0))
                throw new RemoteException("Root helper rejected settings operation");
            reply.readException();
            return reply.readInt();
        } finally { data.recycle(); reply.recycle(); }
    }

    boolean isConnected() { return service != null; }

    private IBinder requireService() throws RemoteException {
        IBinder s = service;
        if (s == null) throw new RemoteException("Not connected to " + PKG);
        return s;
    }

    int[] learningDiagnostics() throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DcsRootService.CONTROL);
            if (!requireService().transact(DcsRootService.LEARNING_DIAGNOSTICS, data, reply, 0))
                throw new RemoteException("Root diagnostics unavailable");
            reply.readException();
            return new int[] { reply.readInt(), reply.readInt(), reply.readInt() };
        } finally { data.recycle(); reply.recycle(); }
    }

    int getApiVersion() throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(API_DESC);
            if (!requireService().transact(48, data, reply, 0))
                throw new RemoteException("getApiVersion is not supported");
            reply.readException();
            return reply.readInt();
        } finally { data.recycle(); reply.recycle(); }
    }

    // ---------------------------------------------------------------- learning

    /** Starts IR learning. Result arrives via the listener (on a binder thread). Returns the token. */
    String startIrLearning(int timeout, LearnListener l) throws RemoteException {
        IBinder s = requireService();
        IrLearnCallback cb = new IrLearnCallback(l);
        keep(cb);
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(API_DESC);
            data.writeStrongBinder(cb);
            data.writeInt(timeout);
            if (!s.transact(TX_START_IR_LEARNING, data, reply, 0))
                throw new RemoteException("startIrLearning is not supported");
            reply.readException();
            String token = reply.readString();
            synchronized (this) { if (!cb.completed) learnCallbacks.put(token, cb); }
            return token;
        } catch (RemoteException | RuntimeException e) {
            release(cb);
            throw e;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    void stopIrLearning(String token) throws RemoteException {
        IBinder s = requireService();
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(API_DESC);
            data.writeString(token);
            if (!s.transact(TX_STOP_IR_LEARNING, data, reply, 0))
                throw new RemoteException("stopIrLearning is not supported");
            reply.readException();
            synchronized (this) {
                IrLearnCallback cb = learnCallbacks.remove(token);
                if (cb != null) release(cb);
            }
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    // ---------------------------------------------------------------- programming the remote

    /** pushToRemoteAsync(IrCodeSet, List<RemoteButtonFunction>, ICallback) - replaces the custom IR table. */
    void pushToRemote(Dtos.IrCodeSet codeSet, ArrayList<Dtos.RemoteButtonFunction> mapping,
                      ResultListener l) throws RemoteException {
        IBinder s = requireService();
        Binder cb = new ResultCallback(l);
        keep(cb);
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(API_DESC);
            writeAutoParcelable(data, codeSet);
            writeAutoParcelable(data, mapping);
            data.writeStrongBinder(cb);
            if (!s.transact(TX_PUSH_TO_REMOTE_ASYNC, data, null, IBinder.FLAG_ONEWAY))
                throw new RemoteException("pushToRemoteAsync is not supported");
        } catch (RemoteException | RuntimeException e) {
            release(cb);
            throw e;
        } finally {
            data.recycle();
        }
    }

    /** restoreRemoteConfiguration() - resets the remote's IR table to stock. Fire-and-forget. */
    void restoreRemote() throws RemoteException {
        IBinder s = requireService();
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(API_DESC);
            if (!s.transact(TX_RESTORE_REMOTE, data, null, IBinder.FLAG_ONEWAY))
                throw new RemoteException("restoreRemoteConfiguration is not supported");
        } finally {
            data.recycle();
        }
    }

    /** Same bytes AutoParcelable.writeToParcel produces: int flag + Parcel.writeSerializable. */
    private static void writeAutoParcelable(Parcel p, Serializable value) {
        if (value == null) {
            p.writeInt(0);
        } else {
            p.writeInt(1);
            p.writeSerializable(value);
        }
    }

    // ---------------------------------------------------------------- callback binders

    private synchronized void keep(Binder b) { liveCallbacks.add(b); }
    private synchronized void release(Binder b) {
        liveCallbacks.remove(b);
        if (b instanceof IrLearnCallback) {
            ((IrLearnCallback) b).completed = true;
            learnCallbacks.values().remove(b);
        }
    }

    private final class IrLearnCallback extends Binder {
        private final LearnListener l;
        private boolean completed;

        IrLearnCallback(LearnListener l) {
            this.l = l;
            attachInterface(null, IRCB_DESC);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            switch (code) {
                case 1: { // onSuccess(AutoParcelable)
                    data.enforceInterface(IRCB_DESC);
                    String pronto = null;
                    if (data.readInt() != 0) {
                        Object o = data.readSerializable();
                        pronto = (o == null) ? null : String.valueOf(o);
                    }
                    release(this);
                    l.onLearned(pronto);
                    return true;
                }
                case 2: // onError(String)
                    data.enforceInterface(IRCB_DESC);
                    release(this);
                    l.onError(data.readString());
                    return true;
                case 3: // onTimeout()
                    data.enforceInterface(IRCB_DESC);
                    release(this);
                    l.onTimeout();
                    return true;
                case INTERFACE_TRANSACTION:
                    reply.writeString(IRCB_DESC);
                    return true;
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }
    }

    private final class ResultCallback extends Binder {
        private final ResultListener l;

        ResultCallback(ResultListener l) {
            this.l = l;
            attachInterface(null, CB_DESC);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            switch (code) {
                case 1: // onSuccess()
                    data.enforceInterface(CB_DESC);
                    release(this);
                    l.onSuccess();
                    return true;
                case 2: // onError(String)
                    data.enforceInterface(CB_DESC);
                    release(this);
                    l.onError(data.readString());
                    return true;
                case INTERFACE_TRANSACTION:
                    reply.writeString(CB_DESC);
                    return true;
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }
    }
}
