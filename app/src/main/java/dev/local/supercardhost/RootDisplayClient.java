package dev.local.supercardhost;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

/** Narrow root backend for fixed PAY/LENS displays; no payment or camera data is handled here. */
public final class RootDisplayClient {
    private static final String TAG = "SuperCardRootDisplay";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final RootDisplayConnection CONNECTION = new RootDisplayConnection(MAIN, () -> {
        Log.w(TAG, "Secure display backend disconnected");
        PluginRuntime.setPaymentSecure(false);
        requestBackend();
    });
    private static Application host;
    private static boolean starterBound;
    private static long lastPaymentDemand = -3_000;
    private static final ServiceConnection STARTER = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            Log.i(TAG, "APK starter service connected");
        }
        @Override public void onServiceDisconnected(ComponentName name) { }
        @Override public void onBindingDied(ComponentName name) { releaseStarter(); requestBackend(); }
        @Override public void onNullBinding(ComponentName name) { releaseStarter(); }
    };
    private static final Runnable RELEASE_STARTER = RootDisplayClient::releaseStarter;

    public static void install(Application host) {
        if (RootDisplayClient.host != null) return;
        host.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (getSentFromUid() != 0) {
                    Log.w(TAG, "Rejected display backend announcement from uid=" + getSentFromUid());
                    return;
                }
                Bundle extras = intent.getExtras();
                IBinder candidate = extras == null ? null : extras.getBinder("service");
                String session = intent.getStringExtra("nonce");
                if (candidate == null || session == null || !session.matches("[0-9a-f]{32}")) return;
                try {
                    if (!CONNECTION.connect(candidate, session)) return;
                    Log.i(TAG, "Secure display backend connected, sender uid=0");
                    releaseStarter();
                } catch (Throwable error) { Log.e(TAG, "Display backend handshake failed", error); }
            }
        }, new IntentFilter(RootDisplayProtocol.ACTION), "android.permission.DUMP", null, Context.RECEIVER_EXPORTED);
        RootDisplayClient.host = host;
        requestBackend();
        MAIN.postDelayed(new Runnable() {
            @Override public void run() {
                if (!isReady()) requestBackend();
                MAIN.postDelayed(this, 15_000);
            }
        }, 15_000);
    }

    public static boolean isReady() {
        return CONNECTION.snapshot() != null;
    }

    public static void requestBackend() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(RootDisplayClient::requestBackend);
            return;
        }
        if (host == null) return;
        if (isReady() || starterBound) return;
        try {
            starterBound = host.bindService(new Intent().setClassName(
                    AutoVirtualDisplayManager.PACKAGE,
                    AutoVirtualDisplayManager.PACKAGE + ".RootDisplayStartService"),
                    STARTER, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
            Log.i(TAG, "Requested APK starter binding, accepted=" + starterBound);
            if (starterBound) MAIN.postDelayed(RELEASE_STARTER, 12_000);
        } catch (Throwable error) { Log.w(TAG, "Cannot bind APK root starter", error); }
    }

    static void requestBackendForPayment() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(RootDisplayClient::requestBackendForPayment);
            return;
        }
        if (host == null || isReady()) return;
        requestBackend();
        long now = SystemClock.elapsedRealtime();
        if (now - lastPaymentDemand < 3_000) return;
        lastPaymentDemand = now;
        try {
            // A binding may already be pending from routine startup. The existing
            // privileged receiver also delivers this demand to that running app.
            host.sendBroadcast(new Intent(AutoVirtualDisplayManager.START).setClassName(
                    AutoVirtualDisplayManager.PACKAGE,
                    AutoVirtualDisplayManager.PACKAGE + ".RootDisplayStartReceiver")
                    .putExtra(AutoVirtualDisplayManager.PAYMENT_DEMAND, true));
        } catch (Throwable error) { Log.w(TAG, "Cannot request payment backend startup", error); }
    }

    private static void releaseStarter() {
        MAIN.removeCallbacks(RELEASE_STARTER);
        if (!starterBound) return;
        starterBound = false;
        try { host.unbindService(STARTER); }
        catch (IllegalArgumentException ignored) { }
    }

    public static synchronized VirtualDisplay create(String name, int width, int height, int density,
            Surface surface, int flags) {
        if (!"SuperCard_Virtual".equals(name)) throw new IllegalArgumentException("Unsupported display name");
        return createNamed(name, width, height, density, surface, flags, true);
    }

    static synchronized VirtualDisplay createCamera(int width, int height, int density, Surface surface) {
        // Same trusted, own-content, destroy-on-removal display policy as the original card.
        // The daemon permits one display per fixed card name and only the authenticated SystemUI client.
        return createNamed("SuperCard_Lens", width, height, density, surface, RootDisplayProtocol.DISPLAY_FLAGS, false);
    }

    private static VirtualDisplay createNamed(String name, int width, int height, int density,
            Surface surface, int flags, boolean payment) {
        RootDisplayConnection.Session session = CONNECTION.snapshot();
        if (session == null) {
            requestBackend();
            throw new IllegalStateException("Secure card display backend is not connected");
        }
        flags |= DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE;
        if (payment) PluginRuntime.setPaymentSecure(true);
        int displayId = -1;
        try {
            RootDisplayTransactions.DisplayToken created = RootDisplayTransactions.create(session,
                    name, width, height, density, surface, flags);
            displayId = created.id;
            VirtualDisplay result = RootDisplayWrapper.wrap(name, width, height, density, surface, flags, created);
            Log.i(TAG, (payment ? "Original secure payment display ready id=" : "Lens display ready id=") + displayId + ", size=" + width + "x" + height + ", flags=" + flags);
            return result;
        } catch (Throwable error) {
            if (displayId > 0) releaseFailedDisplay(session, displayId);
            if (payment) PluginRuntime.setPaymentSecure(false);
            throw new IllegalStateException("Cannot create secure card display", error);
        }
    }

    public static synchronized void released(int id) {
        RootDisplayConnection.Session session = CONNECTION.snapshot();
        if (session != null) releaseFailedDisplay(session, id);
    }

    private static void releaseFailedDisplay(RootDisplayConnection.Session session, int id) {
        try { RootDisplayTransactions.release(session, id); }
        catch (Throwable error) { Log.w(TAG, "Cleanup failed for display " + id, error); }
    }
}
