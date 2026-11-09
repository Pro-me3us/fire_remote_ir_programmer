package com.prome3us.irprog;

import android.app.Instrumentation;
import android.bluetooth.*;
import android.os.Bundle;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Headless, read-only diagnostic. Does not write a remote table or launch an Activity. */
public final class BleReadTest extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle b) { args = b; start(); }
    private void report(String s) {
        android.util.Log.i("IrBleRead", s);
        Bundle b = new Bundle(); b.putString("stream", s + "\n"); sendStatus(0, b);
    }
    @Override public void onStart() {
        CountDownLatch done = new CountDownLatch(1);
        BluetoothGatt g = null;
        Bundle result = new Bundle();
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            for (BluetoothDevice d : adapter.getBondedDevices()) report("Bonded: " + d.getName() + " " + d.getAddress());
            BluetoothDevice remote = adapter.getRemoteDevice(args.getString("addr"));
            g = remote.connectGatt(getTargetContext(), false, new BluetoothGattCallback() {
                @Override public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
                    report("Connection status=" + status + " state=" + state);
                    if (state == BluetoothProfile.STATE_CONNECTED) report("Discover accepted=" + g.discoverServices());
                    else if (state == BluetoothProfile.STATE_DISCONNECTED) done.countDown();
                }
                @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
                    report("Discovery status=" + status);
                    BluetoothGattCharacteristic target = null;
                    for (BluetoothGattService s : g.getServices()) {
                        for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                            if (s.getUuid().toString().startsWith("fe1515")) report("Characteristic=" + c.getUuid() + " handle=" + c.getInstanceId());
                            if (c.getUuid().equals(UUID.fromString("fe151502-5e8d-11e6-8b77-86f30ca893d3"))) target = c;
                        }
                    }
                    if (target == null) { report("No control characteristic"); done.countDown(); }
                    else report("Read accepted=" + g.readCharacteristic(target));
                }
                @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
                    report("READ CALLBACK status=" + status + " value=" + java.util.Arrays.toString(c.getValue()));
                    done.countDown();
                }
            }, BluetoothDevice.TRANSPORT_LE);
            result.putString("stream", done.await(25, TimeUnit.SECONDS) ? "Diagnostic completed\n" : "Timed out waiting for callback\n");
        } catch (Exception e) { result.putString("stream", "ERROR " + e + "\n"); }
        finally { if (g != null) { g.disconnect(); g.close(); } }
        finish(-1, result);
    }
}
