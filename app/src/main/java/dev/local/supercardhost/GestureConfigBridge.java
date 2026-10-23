package dev.local.supercardhost;

import android.app.Application;
import android.app.KeyguardManager;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** Applies the saved vivo gesture settings without relying on vivo's interaction service. */
public final class GestureConfigBridge {
    private static final String TAG = "SuperCardGestureConfig";
    private static final String TOTAL = "card_service_total_switch";
    private static final String SIDE = "navigation_gesture_right_side";
    private static final String RANGE = GestureSettingsRules.RANGE_KEY;
    private static final String LOCK = "bbk_lock_disable_card_slide_setting";
    private static final String LOCK_STYLE = "bbk_lock_disable_card_slide_open_style";
    private static final String REST = "bbk_screen_disable_card_slide_setting";
    private static final String REST_STYLE = "bbk_screen_disable_card_slide_open_style";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, Field> FIELDS = new HashMap<>();
    private static Application application;
    private static Object originalPlugin;
    private static Object currentHelper;
    private static Object appliedHelper;
    private static Field helperInstance;
    private static Method notifyRegion;
    private static Policy appliedPolicy;
    private static volatile Policy policy;
    private static boolean installed;
    private static boolean applying;
    private static long lastErrorTime;

    private GestureConfigBridge() {}

    public static synchronized void install(XposedModule module, Application host,
                                     ClassLoader loader, Object plugin) {
        if (installed) return;
        try {
            application = host;
            originalPlugin = plugin;
            Class<?> type = loader.loadClass("com.vivo.card.framework.gesture.CardGestureRegionHelper");
            String[] names = {"currentRegion", "navigationCardSlideArea", "navigationRightSide",
                    "isCardTotalSwitchOpen", "lockScreenCardSlideEnable", "lockScreenCardSlideOpenStyle",
                    "lockScreenCardSlideSwitchOpen", "restScreenCardSlideEnable",
                    "restScreenCardSlideOpenStyle", "restScreenCardSlideSwitchOpen",
                    "isKeyguardShowing", "screenWidth", "screenHeight", "slideEdge",
                    "lastRangeStr", "lastNavigationCardRange"};
            for (String name : names) {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                FIELDS.put(name, field);
            }
            helperInstance = type.getDeclaredField("instance");
            helperInstance.setAccessible(true);
            notifyRegion = type.getDeclaredMethod("notifyGestureRegionChanged");
            notifyRegion.setAccessible(true);
            Method update = type.getDeclaredMethod("updateTouchRegion");
            update.setAccessible(true);
            module.hook(update).intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) {
                    try {
                        currentHelper = chain.getThisObject();
                        if (Looper.myLooper() == Looper.getMainLooper()) {
                            apply(currentHelper, readPolicy(), true);
                        } else {
                            Object helper = currentHelper;
                            MAIN.post(() -> {
                                try { apply(helper, readPolicy(), true); }
                                catch (Throwable error) {
                                    disableHelper(helper);
                                    report("update region on main", error);
                                }
                            });
                        }
                    } catch (Throwable error) {
                        disableHelper(chain.getThisObject());
                        report("update region", error);
                    }
                    return null;
                }
            });
            installGate(module, type.getDeclaredMethod("isInValidRegion", float.class, float.class), 0, 1);
            installGate(module, type.getDeclaredMethod("canTriggerOnKeyguard",
                    boolean.class, float.class, float.class), 1, 2);
            // The original observer indexes [1]/[3] even for malformed external settings.
            // Our own policy already makes an invalid range inactive; keep that failure
            // from taking down the SystemUI process hosting the original plugin.
            Class<?> switchType = loader.loadClass("com.vivo.card.cards.commondata.local.CardSwitchConfiguration");
            Method changedRange = switchType.getDeclaredMethod("onNavigationSlideCardRangeChanged");
            changedRange.setAccessible(true);
            module.hook(changedRange).intercept(chain -> {
                String range = Settings.Secure.getString(host.getContentResolver(), RANGE);
                if (GestureSettingsRules.parseRange(range) == null) {
                    refresh();
                    return null;
                }
                return chain.proceed();
            });
            ContentObserver observer = new ContentObserver(MAIN) {
                @Override public void onChange(boolean selfChange) { refresh(); }
                @Override public void onChange(boolean selfChange, Uri uri) { refresh(); }
            };
            ContentResolver resolver = host.getContentResolver();
            for (String key : new String[]{TOTAL, SIDE, RANGE, LOCK, LOCK_STYLE, REST, REST_STYLE}) {
                resolver.registerContentObserver(Settings.Secure.getUriFor(key), false, observer);
            }
            installed = true;
            refresh();
            Log.i(TAG, "Installed separate unlocked, keyguard and screen-off gesture settings");
        } catch (Throwable error) {
            report("install", error);
        }
    }

    private static void installGate(XposedModule module, Method method, int xIndex, int yIndex) {
        method.setAccessible(true);
        module.hook(method).intercept(new XposedInterface.Hooker() {
            @Override public Object intercept(XposedInterface.Chain chain) {
                try {
                    currentHelper = chain.getThisObject();
                    // Input dispatch normally runs on main; refresh here also closes observer races.
                    if (Looper.myLooper() == Looper.getMainLooper()) refresh();
                    Policy active = policy;
                    float x = ((Number) chain.getArg(xIndex)).floatValue();
                    float y = ((Number) chain.getArg(yIndex)).floatValue();
                    if (active == null || !Float.isFinite(x) || !Float.isFinite(y)
                            || !active.region.contains((int) x, (int) y)) return false;
                    return chain.proceed();
                } catch (Throwable error) {
                    report("gesture gate", error);
                    return false;
                }
            }
        });
    }

    /** Called by settings observers and the existing native keyguard/screen state updates. */
    public static void refresh() {
        if (application == null || helperInstance == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(GestureConfigBridge::refresh);
            return;
        }
        if (applying) return;
        try {
            Policy next = readPolicy();
            Object helper = currentHelper;
            if (helper == null) helper = helperInstance.get(null);
            policy = next;
            if (helper == null) return;
            currentHelper = helper;
            if (helper != appliedHelper || !next.sameSettings(appliedPolicy)) apply(helper, next, false);
        } catch (Throwable error) {
            policy = null;
            disableHelper(currentHelper);
            report("refresh", error);
        }
    }

    private static Policy readPolicy() {
        ContentResolver resolver = application.getContentResolver();
        KeyguardManager keyguard = application.getSystemService(KeyguardManager.class);
        PowerManager power = application.getSystemService(PowerManager.class);
        if (keyguard == null || power == null) throw new IllegalStateException("Missing system state service");
        DisplayManager displays = application.getSystemService(DisplayManager.class);
        Display display = displays == null ? null : displays.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) throw new IllegalStateException("Missing default display");
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        return new Policy(GesturePolicyAdapter.read(application, metrics, keyguard.isKeyguardLocked(), power.isInteractive()));
    }

    private static void apply(Object helper, Policy next, boolean force) throws Exception {
        if (helper == null || applying) return;
        if (!force && helper == appliedHelper && next.sameSettings(appliedPolicy)) return;
        applying = true;
        try {
            Rect previous = (Rect) FIELDS.get("currentRegion").get(helper);
            boolean changed = previous == null || !previous.equals(next.region);
            boolean stateChanged = appliedPolicy != null && !next.sameSettings(appliedPolicy);
            FIELDS.get("screenWidth").setInt(helper, next.width);
            FIELDS.get("screenHeight").setInt(helper, next.height);
            FIELDS.get("slideEdge").setInt(helper, next.edge);
            FIELDS.get("navigationCardSlideArea").set(helper, new RectF(next.navigationRegion));
            FIELDS.get("navigationRightSide").set(helper, next.side);
            FIELDS.get("lastRangeStr").set(helper, next.range);
            FIELDS.get("lastNavigationCardRange").set(helper, next.rangeValues);
            FIELDS.get("isCardTotalSwitchOpen").setBoolean(helper, next.total);
            FIELDS.get("isKeyguardShowing").setBoolean(helper, next.locked || !next.interactive);
            FIELDS.get("lockScreenCardSlideOpenStyle").setInt(helper, next.lockStyle);
            FIELDS.get("restScreenCardSlideOpenStyle").setInt(helper, next.restStyle);
            FIELDS.get("lockScreenCardSlideSwitchOpen").setBoolean(helper, next.lockSwitch);
            FIELDS.get("restScreenCardSlideSwitchOpen").setBoolean(helper, next.restSwitch);
            // Only the current screen state may contribute; the original implementation unions both.
            FIELDS.get("lockScreenCardSlideEnable").setBoolean(helper, next.lockEnabled);
            FIELDS.get("restScreenCardSlideEnable").setBoolean(helper, next.restEnabled);
            FIELDS.get("currentRegion").set(helper, new Rect(next.region));
            Policy previousPolicy = appliedPolicy;
            appliedPolicy = next;
            appliedHelper = helper;
            policy = next;
            if (changed || stateChanged) resetGesture();
            if (changed) notifyRegion.invoke(helper);
            if (!next.total && (previousPolicy == null || previousPolicy.total)) PluginRuntime.hide();
            if (stateChanged || previousPolicy == null) {
                Log.i(TAG, "Gesture settings: total=" + next.total + ", state=" + next.branch
                        + ", navigation=" + next.navigationEnabled + ", region=" + next.region);
            }
        } finally {
            applying = false;
        }
    }

    private static void resetGesture() {
        try {
            Object implementation = value(originalPlugin, "cardServiceImpl");
            Object coordinator = value(implementation, "cardCoordinator");
            Object controller = value(coordinator, "gestureController");
            if (controller == null) return; // Heavy resources can legitimately be released.
            Method reset = controller.getClass().getDeclaredMethod("resetGestureState");
            reset.setAccessible(true);
            reset.invoke(controller);
        } catch (Throwable error) {
            report("reset gesture", error);
        }
    }

    private static Object value(Object target, String name) throws Exception {
        if (target == null) return null;
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void disableHelper(Object helper) {
        policy = null;
        appliedPolicy = null;
        appliedHelper = null;
        if (helper == null) return;
        try {
            Field field = FIELDS.get("currentRegion");
            if (field != null) field.set(helper, new Rect());
            if (notifyRegion != null) notifyRegion.invoke(helper);
        } catch (Throwable ignored) { }
    }

    private static void report(String phase, Throwable error) {
        long now = SystemClock.uptimeMillis();
        if (lastErrorTime != 0 && now - lastErrorTime < 10_000) return;
        lastErrorTime = now;
        Log.e(TAG, "Unable to " + phase, error);
    }

    private static final class Policy {
        final boolean total, lockSwitch, restSwitch, locked, interactive;
        final boolean navigationEnabled, lockEnabled, restEnabled;
        final int lockStyle, restStyle, width, height, edge, branch;
        final String side, range;
        final Float[] rangeValues;
        final Rect navigationRegion = new Rect();
        final Rect region = new Rect();

        // Android and original-ABI projection of an immutable Kotlin decision.
        Policy(GesturePolicy.Decision next) {
            GesturePolicy.Settings settings = next.settings;
            total = settings.getTotal(); side = settings.getSide(); range = settings.getRange();
            lockSwitch = settings.getLockSwitch(); lockStyle = settings.getLockStyle();
            restSwitch = settings.getRestSwitch(); restStyle = settings.getRestStyle();
            locked = next.screen.getLocked(); interactive = next.screen.getInteractive();
            width = next.display.getWidth(); height = next.display.getHeight(); edge = next.edge; branch = next.branch;
            rangeValues = GestureSettingsRules.parseRange(range);
            navigationEnabled = next.navigationEnabled; lockEnabled = next.lockEnabled; restEnabled = next.restEnabled;
            navigationRegion.set(GesturePolicyAdapter.rect(next.navigationRegion));
            region.set(GesturePolicyAdapter.rect(next.region));
        }

        boolean sameSettings(Policy other) {
            return other != null && total == other.total && lockSwitch == other.lockSwitch
                    && restSwitch == other.restSwitch && lockStyle == other.lockStyle
                    && restStyle == other.restStyle && locked == other.locked && interactive == other.interactive
                    && width == other.width && height == other.height && edge == other.edge
                    && side.equals(other.side) && Objects.equals(range, other.range);
        }
    }
}
