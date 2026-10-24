package dev.local.supercardhost;

import android.app.Application;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** Supplies task observations that the original vivo activity observer cannot provide on ColorOS. */
public final class PayRuntimeBridge {
    private static final String TAG = "SuperCardPayBridge";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final Map<String, Long> LAST_ERRORS = new HashMap<>();

    private static final PaySessionState SESSION = new PaySessionState();
    private static volatile PayDisplayAccess displayAccess;
    private static PayTaskObserver observer;
    private static PayTouchPolicy touchPolicy;

    private PayRuntimeBridge() {}

    public static void install(XposedModule module, Application host,
            ClassLoader pluginLoader, Object plugin) {
        if (!INSTALLED.compareAndSet(false, true)) return;
        try {
            Class<?> managerType = pluginLoader.loadClass(
                    "com.vivo.card.cards.cardpay.virtual.PayVirtualViewManager");
            PayDisplayAccess access = new PayDisplayAccess(managerType, SESSION, PayRuntimeBridge::failure);
            PayTaskObserver prepared = new PayTaskObserver(host, plugin, SESSION, access,
                    () -> touchPolicy, PayRuntimeBridge::failure);
            displayAccess = access;
            observer = prepared;
            PayUserRouting.install(module, host, pluginLoader);
            PayDisplayReadiness.install(module, host, pluginLoader);
            try { NativePayLauncher.install(host, pluginLoader); }
            catch (Throwable error) { failure("install native payment protocol", error); }

            Method launch = managerType.getDeclaredMethod("launchPayment", String.class, int[].class, Intent.class);
            launch.setAccessible(true);
            module.hook(launch).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    // Store the manager before the original launch flow starts its activity.
                    try {
                        String function = (String) chain.getArg(0);
                        int user = PayUserSettings.resolve(host, function);
                        SESSION.begin(chain.getThisObject(), function, user);
                        if (touchPolicy != null) touchPolicy.observe(null);
                    } catch (Throwable error) {
                        failure("capture payment manager", error);
                        PayUserRouting.failed(host, error);
                        return null;
                    }
                    return chain.proceed();
                }
            });

            try {
                touchPolicy = new PayTouchPolicy(host, () -> SESSION.getSnapshot().getFunction(),
                        PayRuntimeBridge::getActiveDisplayId, PayRuntimeBridge::failure);
                touchPolicy.install(module, pluginLoader);
            }
            catch (Throwable error) { failure("install payment touch policy", error); }
            observer.start();
            Log.i(TAG, "Original payment task observer installed");
            try { PayDiagnostics.install(module, host, pluginLoader); }
            catch (Throwable error) { failure("install optional payment diagnostics", error); }
        } catch (Throwable error) {
            if (observer != null) observer.stop();
            INSTALLED.set(false);
            failure("install", error);
        }
    }

    /** Returns only the original manager's current, active virtual display. */
    public static int getWeChatRideDisplayId() {
        PaySessionState.Snapshot session = SESSION.getSnapshot();
        String function = session.getFunction();
        return "wx_ride".equals(function) || "wx2_ride".equals(function)
                ? displayId(session) : -1;
    }

    static long getLaunchGeneration() { return SESSION.getSnapshot().getGeneration(); }

    static int userForFunction(android.content.Context host, String function) {
        PaySessionState.Snapshot session = SESSION.getSnapshot();
        if (function.equals(session.getFunction()) && session.getUser() >= 0) return session.getUser();
        return PayUserSettings.resolve(host, function);
    }

    static int userForApp(android.content.Context host, String app) {
        PaySessionState.Snapshot session = SESSION.getSnapshot();
        if (app.equals(PayUserSettings.appForFunction(session.getFunction())) && session.getUser() >= 0) return session.getUser();
        return PayUserSettings.resolve(host, PayUserSettings.WECHAT.equals(app) ? "wx_pay" : "alipay_pay");
    }

    static void userChanged(String app) {
        PaySessionState.Snapshot session = SESSION.getSnapshot();
        if (!app.equals(PayUserSettings.appForFunction(session.getFunction())) || !SESSION.clear(session)) return;
        // Invalidate before releasing so a queued task/SDK callback cannot restore the old account.
        Object manager = session.getManager();
        if (touchPolicy != null) touchPolicy.observe(null);
        if (manager != null && displayAccess != null) displayAccess.release(manager);
    }

    public static int getActiveDisplayId() {
        return displayId(SESSION.getSnapshot());
    }

    static void prepareNative() {
        PaySessionState.Snapshot old = SESSION.getSnapshot();
        if (!SESSION.clear(old)) return;
        if (touchPolicy != null) touchPolicy.observe(null);
        if (old.getManager() != null && displayAccess != null) displayAccess.release(old.getManager());
    }

    static void beginNative(NativePayDisplayOwner owner, String function, int user) {
        SESSION.begin(owner, function, user);
        if (touchPolicy != null) touchPolicy.observe(null);
    }

    static void endNative(NativePayDisplayOwner owner) {
        PaySessionState.Snapshot current = SESSION.getSnapshot();
        if (current.getManager() == owner && SESSION.clear(current) && touchPolicy != null) touchPolicy.observe(null);
    }

    private static int displayId(PaySessionState.Snapshot session) {
        PayDisplayAccess access = displayAccess;
        return access == null ? -1 : access.id(session);
    }

    static void reportWeChatFallback(String function) {
        if (observer != null) observer.reportWeChatFallback(function);
    }

    static void refreshTouchPolicy() {
        // Configuration requests and original view binding are delivered on the main thread.
        if (touchPolicy != null) touchPolicy.refresh();
    }

    private static void failure(String stage, Throwable error) {
        while (error instanceof InvocationTargetException && error.getCause() != null) {
            error = error.getCause();
        }
        synchronized (LAST_ERRORS) {
            long now = SystemClock.elapsedRealtime();
            Long last = LAST_ERRORS.get(stage);
            if (last != null && now - last < 30_000) return;
            LAST_ERRORS.put(stage, now);
        }
        Log.w(TAG, stage + " failed", error);
    }
}
