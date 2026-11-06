package com.prome3us.irprog;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;

import java.io.FileInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.util.ArrayList;
import java.util.List;
import dalvik.system.DexClassLoader;
import com.amazon.tv.devicecontrol.commons.Dtos;
import com.amazon.tv.devicecontrol.commons.Definitions;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Device tests. No push, restore, transmit, or changes to stored IR codes. */
public final class RootIntegrationTest extends Instrumentation {
    private DcsClient client;
    private String token;
    private Bundle arguments;

    @Override public void onCreate(Bundle args) { arguments = args; start(); }

    private void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        Bundle result = new Bundle();
        result.putString("stream", "PASS: " + message + "\n");
        sendStatus(0, result);
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int resultCode = -1;
        try {
            Context context = getTargetContext();
            check((context.getApplicationInfo().flags & ApplicationInfo.FLAG_SYSTEM) == 0,
                    "installed as a normal data app");
            check(context.checkSelfPermission("com.amazon.tv.devicecontrol.WRITE")
                    == PackageManager.PERMISSION_DENIED, "UI lacks Amazon privileged permission");
            check(context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                    == PackageManager.PERMISSION_DENIED, "UI lacks secure-settings permission");
            ServiceConnection direct = new ServiceConnection() {
                public void onServiceConnected(ComponentName n, IBinder b) { }
                public void onServiceDisconnected(ComponentName n) { }
            };
            boolean denied = false;
            try {
                boolean bound = context.bindService(new Intent(DcsClient.BIND_ACTION).setComponent(
                        new ComponentName(DcsClient.PKG, DcsClient.SERVICE_CLASS)), direct,
                        Context.BIND_AUTO_CREATE);
                if (bound) context.unbindService(direct);
                denied = !bound;
            } catch (SecurityException expected) { denied = true; }
            check(denied, "direct unprivileged service bind is rejected");

            checkServiceSerialization(context);

            CountDownLatch connected = new CountDownLatch(1);
            AtomicReference<String> error = new AtomicReference<>();
            runOnMainSync(() -> {
                client = new DcsClient(context, new DcsClient.ConnectionListener() {
                    public void onConnected() { connected.countDown(); }
                    public void onDisconnected() { error.set("disconnected"); connected.countDown(); }
                    public void onError(String message) { error.set(message); connected.countDown(); }
                });
                client.bind();
            });
            check(connected.await(50, TimeUnit.SECONDS) && error.get() == null && client.isConnected(),
                    "Magisk root broker connects: " + error.get());
            check(client.getApiVersion() > 0, "read-only service transaction returns API version");

            // Exercise the write only when explicitly requested. Always restore original value.
            if (arguments != null && "true".equals(arguments.getString("settingsWrite"))) {
                String original = Settings.Global.getString(context.getContentResolver(), "show_dc_settings");
                try {
                    shell("settings put global show_dc_settings 0");
                    check(Settings.Global.getInt(context.getContentResolver(), "show_dc_settings", -1) == 0,
                            "prepared reversible settings-write test");
                    check(client.ensureRemoteSetting() == 0, "root broker performs protected settings write");
                    check(Settings.Global.getInt(context.getContentResolver(), "show_dc_settings", -1) == 1,
                            "protected settings write took effect");
                } finally {
                    shell(original == null ? "settings delete global show_dc_settings"
                            : "settings put global show_dc_settings " + Integer.parseInt(original));
                }
            }

            CountDownLatch timeout = new CountDownLatch(1);
            token = client.startIrLearning(1500, new DcsClient.LearnListener() {
                public void onLearned(String code) { error.set("Unexpected physical IR signal"); timeout.countDown(); }
                public void onTimeout() { timeout.countDown(); }
                public void onError(String message) { error.set(message); timeout.countDown(); }
            });
            check(token != null && !token.isEmpty(), "root learning call returns a session token");
            check(timeout.await(8, TimeUnit.SECONDS) && error.get() == null,
                    "real IR receiver timeout callback reaches the app: " + error.get());
            int stopsAfterTimeout = client.learningDiagnostics()[1];
            check(client.learningDiagnostics()[2] == 0,
                    "root observes terminal callback and clears the active session");
            client.stopIrLearning(token);
            client.stopIrLearning(token);
            check(client.learningDiagnostics()[1] == stopsAfterTimeout,
                    "stopping a timed-out session does not call Amazon stop again");
            token = null;
            DcsClient.LearnListener unused = new DcsClient.LearnListener() {
                public void onLearned(String code) { }
                public void onTimeout() { }
                public void onError(String message) { }
            };
            token = client.startIrLearning(10000, unused);
            client.stopIrLearning(token);
            client.stopIrLearning(token);
            check(client.learningDiagnostics()[1] == stopsAfterTimeout + 1,
                    "repeated cancellation forwards exactly one stop");
            token = client.startIrLearning(10000, unused);
            check(token != null, "cancel releases the receiver for another learning session");
            client.stopIrLearning(token);
            token = null;
            result.putString("stream", "All root integration checks passed. Remote programming unchanged.\n");
        } catch (Throwable e) {
            resultCode = 0;
            result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(e));
        } finally {
            if (client != null) {
                try { if (token != null) client.stopIrLearning(token); } catch (Exception ignored) { }
                runOnMainSync(() -> client.unbind());
            }
        }
        finish(resultCode, result);
    }

    private void checkServiceSerialization(Context context) throws Exception {
        String apk = context.getPackageManager().getApplicationInfo(DcsClient.PKG, 0).sourceDir;
        ClassLoader serviceLoader = new DexClassLoader(apk, context.getCodeCacheDir().getPath(),
                null, String.class.getClassLoader());
        ArrayList<Dtos.IrCode> codes = new ArrayList<>();
        ArrayList<Dtos.RemoteButtonFunction> buttons = new ArrayList<>();
        String pronto = "0000 006D 0001 0000 0010 0020";
        for (Definitions.RemoteButton button : Definitions.RemoteButton.values()) {
            Definitions.DeviceFunction function = Definitions.defaultFunctionFor(button);
            codes.add(new Dtos.IrCode(function, pronto, null, false));
            buttons.add(new Dtos.RemoteButtonFunction(button, function));
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(new Dtos.IrCodeSet("Wire test only", 0, 2, 33, codes));
            out.writeObject(buttons);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray())) {
            @Override protected Class<?> resolveClass(ObjectStreamClass desc)
                    throws java.io.IOException, ClassNotFoundException {
                return Class.forName(desc.getName(), false, serviceLoader);
            }
        }) {
            Object decoded = in.readObject();
            check(decoded.getClass().getClassLoader() == serviceLoader,
                    "push DTOs deserialize using the installed Amazon APK's own classes");
            List<?> actualCodes = (List<?>) decoded.getClass().getMethod("getIrCodes").invoke(decoded);
            List<?> actualButtons = (List<?>) in.readObject();
            check(actualCodes.size() == 20 && actualButtons.size() == 20,
                    "all 20 remote mappings deserialize against the installed service");
            for (int i = 0; i < actualCodes.size(); i++) {
                Object code = actualCodes.get(i);
                Object button = actualButtons.get(i);
                if (!pronto.equals(code.getClass().getMethod("getCode1").invoke(code)))
                    throw new AssertionError("IR code changed on deserialization");
                Object codeFunction = code.getClass().getMethod("getDeviceFunction").invoke(code);
                Object buttonFunction = button.getClass().getMethod("getFunction").invoke(button);
                if (codeFunction != buttonFunction) throw new AssertionError("Mapping mismatch");
            }
            check(true, "Pronto strings and function mappings survive service deserialization");
        }
    }

    private void shell(String command) throws Exception {
        try (ParcelFileDescriptor pfd = getUiAutomation().executeShellCommand(command);
             FileInputStream in = new FileInputStream(pfd.getFileDescriptor())) {
            byte[] buffer = new byte[1024];
            while (in.read(buffer) != -1) { /* Wait for command completion. */ }
        }
    }
}
