package com.prome3us.irprog;

import android.bluetooth.*;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import java.util.*;

/** One serialized GATT session, executed as root. Never retries an ambiguous table write. */
@android.annotation.SuppressLint("MissingPermission") // Root-only process; Fire OS gates these UUIDs on BLUETOOTH_PRIVILEGED.
final class BleTransfer {
    interface Listener { void progress(String s); void finished(boolean success, String message); }
    static final UUID SERVICE = UUID.fromString("fe151500-5e8d-11e6-8b77-86f30ca893d3");
    static final UUID DATA = UUID.fromString("fe151501-5e8d-11e6-8b77-86f30ca893d3");
    static final UUID CONTROL = UUID.fromString("fe151502-5e8d-11e6-8b77-86f30ca893d3");
    private final Context context;
    private final String address;
    private final byte[] table;
    private final Listener listener;
    private final HandlerThread thread = new HandlerThread("RootIrGatt");
    private final Handler h;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic control, data;
    private boolean complete;
    private String phase = "connect";
    private int mtu = 23, offset;
    private byte[] tableId;
    private boolean eraseStarted;
    private final Runnable deadline = () -> finish(false, "Bluetooth operation exceeded two minutes");
    private final Runnable timeout = () -> finish(false, "Timed out during " + phase
            + ". Keep the remote awake and in range. Transfer was not retried.");

    BleTransfer(Context context, String address, byte[] table, Listener listener) {
        this.context = context; this.address = address;
        this.table = table == null ? null : table.clone(); this.listener = listener;
        thread.start(); h = new Handler(thread.getLooper());
    }
    void start() { h.post(() -> guarded(this::connect)); }
    void cancel() { h.post(() -> finish(false, "Bluetooth operation cancelled")); }
    private void guarded(Runnable r) {
        if (complete) return;
        try { r.run(); } catch (Exception e) { finish(false, e.toString()); }
    }
    private void expect(String name) {
        phase = name; h.removeCallbacks(timeout); h.postDelayed(timeout, 15000);
    }
    private void accepted(boolean ok) { if (!ok) finish(false, "Bluetooth refused " + phase); }
    private void connect() {
        if (Process.myUid() != 0) throw new SecurityException("Fire OS IR GATT requires the Magisk root process");
        if (table != null) IrTable.validate(table);
        h.postDelayed(deadline, 120000);
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) throw new IllegalStateException("Bluetooth is off");
        BluetoothDevice target = null;
        List<BluetoothDevice> candidates = new ArrayList<>();
        for (BluetoothDevice d : adapter.getBondedDevices()) {
            if (address != null && d.getAddress().equalsIgnoreCase(address)) target = d;
            String name = d.getName();
            if (name != null && name.toLowerCase(Locale.US).contains("amazon")
                    && name.toLowerCase(Locale.US).contains("remote")) candidates.add(d);
        }
        if (address == null && candidates.size() == 1) target = candidates.get(0);
        if (target == null) throw new IllegalArgumentException(address == null
                ? "Select a paired Amazon remote (found " + candidates.size() + ")"
                : "The selected remote is not paired");
        listener.progress("Connecting to " + target.getName() + " through Magisk root");
        expect("connect");
        gatt = android.os.Build.VERSION.SDK_INT >= 23
                ? target.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                : target.connectGatt(context, false, callback);
        if (gatt == null) finish(false, "Could not create Bluetooth connection");
    }
    private void finish(boolean success, String message) {
        if (complete) return;
        complete = true; h.removeCallbacksAndMessages(null);
        if (!success && eraseStarted) message += ". The old table may have been erased; copy the complete saved set again.";
        if (gatt != null) {
            try { gatt.disconnect(); } catch (RuntimeException ignored) { }
            try { gatt.close(); } catch (RuntimeException ignored) { }
            gatt = null;
        }
        try { listener.finished(success, message); } finally { thread.quitSafely(); }
    }
    private void read(BluetoothGattCharacteristic c, String name) {
        expect(name); accepted(gatt.readCharacteristic(c));
    }
    private void write(BluetoothGattCharacteristic c, byte[] value, String name) {
        expect(name); c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        c.setValue(value); accepted(gatt.writeCharacteristic(c));
    }
    private void sendHeader() {
        byte[] header = new byte[22]; header[0] = 2; header[1] = 1;
        tableId = new byte[16]; new java.security.SecureRandom().nextBytes(tableId);
        System.arraycopy(tableId, 0, header, 2, 16);
        header[20] = (byte)table.length; header[21] = (byte)(table.length >>> 8);
        write(control, header, "header write");
    }
    private void sendChunk() {
        if (offset == table.length) {
            expect("settle after data");
            h.postDelayed(() -> guarded(() -> read(data, "status read")), 500);
            return;
        }
        int end = Math.min(table.length, offset + Math.min(200, mtu - 3));
        byte[] chunk = Arrays.copyOfRange(table, offset, end);
        offset = end; // no retry: a missing callback leaves delivery ambiguous
        write(data, chunk, "data write");
    }
    private boolean check(int status) {
        h.removeCallbacks(timeout);
        if (status == BluetoothGatt.GATT_SUCCESS) return true;
        finish(false, phase + " failed (GATT status " + status + ")"); return false;
    }
    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
            h.post(() -> guarded(() -> {
                if (status != 0 || state == BluetoothProfile.STATE_DISCONNECTED) {
                    finish(false, "Bluetooth disconnected during " + phase + " (status " + status + ")");
                } else if (state == BluetoothProfile.STATE_CONNECTED && phase.equals("connect")) {
                    expect("discovery"); accepted(g.discoverServices());
                }
            }));
        }
        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            h.post(() -> guarded(() -> {
                if (!phase.equals("discovery") || !check(status)) return;
                BluetoothGattService s = g.getService(SERVICE);
                if (s == null) throw new IllegalStateException("Remote has no IR table service");
                control = s.getCharacteristic(CONTROL); data = s.getCharacteristic(DATA);
                if (control == null || data == null) throw new IllegalStateException("IR characteristics missing");
                read(control, "identifier read");
            }));
        }
        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            byte[] v = c.getValue() == null ? new byte[0] : c.getValue().clone();
            UUID uuid = c.getUuid();
            h.post(() -> guarded(() -> {
                if (!(phase.equals("identifier read") && uuid.equals(CONTROL))
                        && !(phase.equals("status read") && uuid.equals(DATA))
                        && !(phase.equals("verification read") && uuid.equals(CONTROL))) return;
                if (!check(status)) return;
                if (phase.equals("identifier read")) {
                    listener.progress("Current table identifier: " + hex(v));
                    if (table == null) { finish(true, "Root Bluetooth read succeeded: " + hex(v)); return; }
                    expect("MTU negotiation");
                    if (!gatt.requestMtu(247)) beginWrite();
                } else if (phase.equals("verification read")) {
                    int count = v.length == 0 ? 0 : v[0] & 127;
                    boolean found = false;
                    if (v.length == 1 + count * 16) {
                        for (int i = 0; i < count; i++)
                            if (Arrays.equals(tableId, Arrays.copyOfRange(v, 1+i*16, 17+i*16))) found = true;
                    }
                    finish(found, found ? "Remote accepted and retained the IR table (status 02, " + table.length + " bytes)"
                            : "Remote reported success but the new table identifier could not be verified");
                } else if (v.length >= 1 && v[0] == 2) {
                    read(control, "verification read");
                } else finish(false, "Remote did not accept the table: status=" + hex(v));
            }));
        }
        @Override public void onMtuChanged(BluetoothGatt g, int value, int status) {
            h.post(() -> guarded(() -> {
                if (!phase.equals("MTU negotiation")) return;
                if (status == 0 && value >= 23) mtu = value;
                beginWrite();
            }));
        }
        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            UUID uuid = c.getUuid();
            h.post(() -> guarded(() -> {
                if (!(phase.equals("start write") && uuid.equals(CONTROL))
                        && !(phase.equals("header write") && uuid.equals(CONTROL))
                        && !(phase.equals("data write") && uuid.equals(DATA))) return;
                if (!check(status)) return;
                if (phase.equals("start write")) {
                    expect("settle after start"); h.postDelayed(() -> guarded(BleTransfer.this::sendHeader), 600);
                } else sendChunk();
            }));
        }
    };
    private void beginWrite() {
        // Header is 22 bytes. Avoid relying on unsupported long/prepared writes at MTU 23.
        if (mtu < 25) { finish(false, "Remote MTU " + mtu + " is too small for the transfer header"); return; }
        listener.progress("Writing " + table.length + " bytes (MTU " + mtu + ")");
        eraseStarted = true;
        write(control, new byte[]{0x10}, "start write");
    }
    static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder();
        for (byte b : bytes) s.append(String.format(Locale.US, "%02x", b & 255));
        return s.toString();
    }
}
