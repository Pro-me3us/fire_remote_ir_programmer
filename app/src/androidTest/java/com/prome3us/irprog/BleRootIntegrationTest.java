package com.prome3us.irprog;

import android.app.Instrumentation;
import android.os.Bundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Headless test of the real app -> libsu -> root GATT route. Never writes an IR table. */
public final class BleRootIntegrationTest extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle b) { args=b; start(); }
    private void report(String s) { Bundle b=new Bundle();b.putString("stream",s+"\n");sendStatus(0,b); }
    @Override public void onStart() {
        Bundle result=new Bundle(); DcsClient client=null;
        try {
            android.content.Context context = getTargetContext();
            if ((context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
                throw new AssertionError("Test target must be an ordinary app");
            if (context.checkPermission("android.permission.BLUETOOTH_PRIVILEGED", android.os.Process.myPid(), android.os.Process.myUid())
                    != android.content.pm.PackageManager.PERMISSION_DENIED)
                throw new AssertionError("UI unexpectedly has privileged Bluetooth permission");
            report("PASS: installed under /data/app without BLUETOOTH_PRIVILEGED");
            CountDownLatch connected=new CountDownLatch(1), read=new CountDownLatch(1);
            AtomicReference<String> error=new AtomicReference<>();
            client=new DcsClient(getTargetContext(),new DcsClient.ConnectionListener() {
                public void onConnected(){connected.countDown();}
                public void onDisconnected(){error.set("Disconnected");connected.countDown();}
                public void onError(String s){error.set(s);connected.countDown();}
            });
            client.bind();
            if(!connected.await(35,TimeUnit.SECONDS)||error.get()!=null)throw new AssertionError("Root bind: "+error.get());
            report("PASS: ordinary installed app bound to Magisk root broker; devicecontrol API="+client.getApiVersion());
            client.bluetooth(args.getString("addr"),null,new BleTransfer.Listener(){
                public void progress(String s){report(s);}
                public void finished(boolean ok,String s){if(!ok)error.set(s);report(s);read.countDown();}
            });
            if(!read.await(45,TimeUnit.SECONDS)||error.get()!=null)throw new AssertionError("Root read: "+error.get());
            report("PASS: app -> Binder -> Magisk root -> Bluetooth read completed");
            try {
                client.bluetooth(args.getString("addr"),new byte[42],new BleTransfer.Listener(){
                    public void progress(String s){} public void finished(boolean ok,String s){}
                });
                throw new AssertionError("Malformed table accepted");
            } catch(IllegalArgumentException expected) {report("PASS: root broker rejected malformed table before Bluetooth access");}
            result.putString("stream","PASS: root integration complete; no IR table writes\n");
        } catch(Throwable e){result.putString("stream","FAIL: "+e+"\n");}
        finally {if(client!=null) { DcsClient cleanup=client; runOnMainSync(cleanup::unbind); }}
        finish(-1,result);
    }
}
