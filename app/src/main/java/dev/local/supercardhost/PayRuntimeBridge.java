package dev.local.supercardhost;

import android.app.ActivityManager;
import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** Supplies task observations that the original vivo activity observer cannot provide on ColorOS. */
public final class PayRuntimeBridge {
    private static final String TAG = "SuperCardPayBridge";
    private static final long POLL_INTERVAL_MS = 300;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final Map<String, Long> LAST_ERRORS = new HashMap<>();

    private static final PaySessionState SESSION = new PaySessionState();
    private static volatile Method isActiveMethod;
    private static volatile Method displayIdMethod;
    private static Method taskIdMethod;
    private static Method setTaskIdMethod;
    private static Method resumedMethod;
    private static Method topActivityMethod;
    private static Object cardPlugin;
    private static ActivityManager activityManager;
    private static Handler worker;
    private static int previousDisplay = -1;
    private static Application hostApplication;
    private static PayTouchPolicy touchPolicy;

    // These notification markers are accessed only on the main thread.
    private static Object lastManager;
    private static long lastManagerGeneration = -1;
    private static int lastManagerDisplay = -1;
    private static ComponentName lastManagerComponent;
    private static int lastPluginDisplay = -1;
    private static ComponentName lastPluginComponent;

    private PayRuntimeBridge() {}

    public static void install(XposedModule module, Application host,
            ClassLoader pluginLoader, Object plugin) {
        if (!INSTALLED.compareAndSet(false, true)) return;
        try {
            Class<?> managerType = pluginLoader.loadClass(
                    "com.vivo.card.cards.cardpay.virtual.PayVirtualViewManager");
            // JADX labels this Kotlin getter getIsVirtualDisplayActive, but the
            // original DEX/smali method is named isVirtualDisplayActive.
            try {
                isActiveMethod = findMethod(managerType, "isVirtualDisplayActive");
            } catch (NoSuchMethodException absentOriginalName) {
                isActiveMethod = findMethod(managerType, "getIsVirtualDisplayActive");
            }
            displayIdMethod = findMethod(managerType, "getVirtualDisplayId");
            taskIdMethod = findMethod(managerType, "getTaskId");
            setTaskIdMethod = findMethod(managerType, "setTaskId", int.class);
            resumedMethod = findMethod(managerType, "handleActivityResumed", ComponentName.class);
            topActivityMethod = findMethod(plugin.getClass(), "setTopActivity",
                    String.class, String.class);
            activityManager = host.getSystemService(ActivityManager.class);
            if (activityManager == null) throw new IllegalStateException("ActivityManager unavailable");
            cardPlugin = plugin;
            hostApplication = host;
            PayUserRouting.install(module, host, pluginLoader);
            PayDisplayReadiness.install(module, host, pluginLoader);

            Method launch = findMethod(managerType, "launchPayment",
                    String.class, int[].class, Intent.class);
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

            HandlerThread thread = new HandlerThread("SuperCardPayTasks");
            thread.start();
            worker = new Handler(thread.getLooper());
            worker.post(POLL);
            Log.i(TAG, "Original payment task observer installed");
            try {
                touchPolicy = new PayTouchPolicy(host, () -> SESSION.getSnapshot().getFunction(),
                        PayRuntimeBridge::getActiveDisplayId, PayRuntimeBridge::failure);
                touchPolicy.install(module, pluginLoader);
            }
            catch (Throwable error) { failure("install payment touch policy", error); }
            try { PayDiagnostics.install(module, host, pluginLoader); }
            catch (Throwable error) { failure("install optional payment diagnostics", error); }
        } catch (Throwable error) {
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
        if (manager != null) try { findMethod(manager.getClass(), "release").invoke(manager); }
        catch (Exception error) { failure("release previous PAY user", error); }
    }

    public static int getActiveDisplayId() {
        return displayId(SESSION.getSnapshot());
    }

    private static int displayId(PaySessionState.Snapshot session) {
        Object manager = session.getManager();
        if (manager == null || !SESSION.isCurrent(session)) return -1;
        try {
            if (!Boolean.TRUE.equals(isActiveMethod.invoke(manager))) return -1;
            int id = ((Number) displayIdMethod.invoke(manager)).intValue();
            return id > 0 && SESSION.isCurrent(session) ? id : -1;
        } catch (Throwable error) {
            failure("read payment display", error);
            return -1;
        }
    }

    /** One snapshot on an actual OEM timeout, never a log in the polling loop. */
    static void reportWeChatFallback(String function) {
        Handler handler = worker;
        if (handler == null) return;
        int display = getActiveDisplayId();
        boolean ready = RootDisplayClient.isReady();
        handler.post(() -> {
            try {
                var info = hostApplication.getPackageManager().getPackageInfo("com.tencent.mm", 0);
                StringBuilder pages = new StringBuilder();
                int count = 0;
                for (ActivityManager.RunningTaskInfo task : activityManager.getRunningTasks(30)) {
                    ComponentName top = task.topActivity;
                    if (top == null || !"com.tencent.mm".equals(top.getPackageName())) continue;
                    if (count++ >= 6) break;
                    if (pages.length() > 0) pages.append(", ");
                    pages.append(top.getClassName()).append("@display=").append(taskDisplay(task));
                }
                Log.w(TAG, "WeChat launch timeout: function=" + function + " version=" + info.versionName
                        + "/" + info.getLongVersionCode() + " backendReady=" + ready
                        + " requestedDisplay=" + display + " pages=[" + pages + "]");
            } catch (Throwable error) { failure("report WeChat launch timeout", error); }
        });
    }

    static void refreshTouchPolicy() {
        // Configuration requests and original view binding are delivered on the main thread.
        if (touchPolicy != null) touchPolicy.refresh();
    }

    private static final Runnable POLL = new Runnable() {
        @Override
        public void run() {
            try {
                PaySessionState.Snapshot session = SESSION.getSnapshot();
                int user = session.getUser();
                int display = displayId(session);
                if (previousDisplay > 0 && display < 0) {
                    MAIN.post(() -> {
                        if (getActiveDisplayId() < 0) PluginRuntime.setPaymentSecure(false);
                    });
                }
                previousDisplay = display;
                // SystemUI already holds the task permission. The public API returns the
                // same RunningTaskInfo objects without depending on changing ATMS signatures.
                List<ActivityManager.RunningTaskInfo> tasks = activityManager.getRunningTasks(30);
                ActivityManager.RunningTaskInfo main = null;
                ActivityManager.RunningTaskInfo payment = null;
                for (ActivityManager.RunningTaskInfo task : tasks) {
                    if (task.topActivity == null) continue;
                    int taskDisplay = taskDisplay(task);
                    if (main == null && taskDisplay == 0) main = task;
                    if (payment == null && display > 0 && taskDisplay == display
                            && isPaymentPackage(task.topActivity.getPackageName())
                            && (user < 0 || task.getClass().getField("userId").getInt(task) == user)) {
                        payment = task;
                    }
                }
                // Keep the virtual top while its launch is pending, including the short
                // interval where no payment task has appeared on the display yet.
                ActivityManager.RunningTaskInfo selected = display > 0 ? payment : main;
                if (selected != null) {
                    ComponentName component = selected.topActivity;
                    int selectedDisplay = taskDisplay(selected);
                    int taskId = selected.taskId;
                    MAIN.post(() -> synchronizeTask(session, display,
                            selectedDisplay, taskId, component));
                }
            } catch (Throwable error) {
                failure("observe tasks", error);
            } finally {
                Handler handler = worker;
                if (handler != null) handler.postDelayed(this, POLL_INTERVAL_MS);
            }
        }
    };

    private static void synchronizeTask(PaySessionState.Snapshot session, int display,
            int selectedDisplay, int taskId, ComponentName component) {
        // A snapshot queued before release/relaunch must not restore a stale virtual top.
        if (!SESSION.isCurrent(session) || displayId(session) != display) return;
        Object manager = session.getManager();
        long generation = session.getGeneration();
        if (touchPolicy != null) touchPolicy.observe(display > 0 && selectedDisplay == display ? component : null);

        if (display > 0 && selectedDisplay == display && manager != null) {
            boolean changed = lastManager != manager || lastManagerGeneration != generation
                    || lastManagerDisplay != display || !component.equals(lastManagerComponent);
            try {
                if (changed) {
                    try {
                        resumedMethod.invoke(manager, component);
                        lastManager = manager;
                        lastManagerGeneration = generation;
                        lastManagerDisplay = display;
                        lastManagerComponent = component;
                    } finally {
                        // The vivo callback queries getRunningTasks(1), which can select
                        // display 0. Correct its task ID with our matching-display snapshot.
                        correctTaskId(manager, taskId);
                    }
                } else {
                    correctTaskId(manager, taskId);
                }
            } catch (Throwable error) {
                failure("notify payment resume", error);
            }
        }

        if (selectedDisplay != lastPluginDisplay || !component.equals(lastPluginComponent)) {
            if (selectedDisplay == 0) GestureHandle.onTopActivityChanged();
            try {
                topActivityMethod.invoke(cardPlugin,
                        component.getClassName(), component.getPackageName());
                lastPluginDisplay = selectedDisplay;
                lastPluginComponent = component;
            } catch (Throwable error) {
                failure("notify original top activity", error);
            }
        }
        // Reusing a display and the same page for another function still changes its
        // per-function policy, even when the original top-activity notification is deduplicated.
        if (display > 0 && selectedDisplay == display && touchPolicy != null)
            touchPolicy.applyGeneration(generation, component);
    }

    private static void correctTaskId(Object manager, int taskId) throws Exception {
        if (((Number) taskIdMethod.invoke(manager)).intValue() != taskId) {
            setTaskIdMethod.invoke(manager, taskId);
        }
    }

    private static int taskDisplay(ActivityManager.RunningTaskInfo task) throws Exception {
        return task.getClass().getField("displayId").getInt(task);
    }

    private static boolean isPaymentPackage(String name) {
        return "com.tencent.mm".equals(name) || "com.eg.android.AlipayGphone".equals(name)
                || PayDiagnostics.isTestPackage(name);
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameters)
            throws NoSuchMethodException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // Protected original methods live in VirtualViewManager.
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
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
