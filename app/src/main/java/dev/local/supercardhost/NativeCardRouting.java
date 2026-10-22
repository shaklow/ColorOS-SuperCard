package dev.local.supercardhost;

import android.app.Application;
import android.app.KeyguardManager;
import android.content.Intent;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import io.github.libxposed.api.XposedModule;

/** Reuses the validated OEM gesture boundary; new UI itself stays in the module process. */
final class NativeCardRouting {
    private static boolean ready;
    private static boolean legacyRequest;
    static boolean isReady() { return ready; }

    static void install(XposedModule module, Application host, Object plugin) {
        try {
            Object impl = DhuCardConfiguration.field(plugin, "cardServiceImpl");
            java.lang.reflect.Method trigger = impl.getClass().getDeclaredMethod("onCardTriggerIntent",
                    String.class, String.class, int.class, String.class);
            trigger.setAccessible(true);
            module.hook(trigger).intercept(chain -> {
                // Targeted payment/lockscreen launches keep their original route.
                String target = (String) chain.getArg(1), openType = (String) chain.getArg(3);
                if (!legacyRequest && (target == null || "paycard".equals(target))
                        && (openType == null || openType.isEmpty()) && shouldRoute(host)
                        && launch(host)) return null;
                return chain.proceed();
            });
            // A swipe does not pass through onCardTriggerIntent. Keep its own validated
            // threshold/cancel behavior, and transfer only after a committed opening.
            Class<?> callback = plugin.getClass().getClassLoader().loadClass(
                    "com.vivo.card.framework.coordinator.CardCoordinator$setupGestureCallbacks$1");
            java.lang.reflect.Method end = callback.getDeclaredMethod("onGestureEnd", float.class, float.class, float.class);
            end.setAccessible(true);
            module.hook(end).intercept(chain -> {
                if (!shouldRoute(host)) return chain.proceed();
                boolean committed;
                try {
                Object coordinator = DhuCardConfiguration.field(impl, "cardCoordinator");
                boolean shown = Boolean.TRUE.equals(coordinator.getClass().getMethod("isCardWindowShown").invoke(coordinator));
                Object controller = coordinator.getClass().getMethod("getGestureController").invoke(coordinator);
                committed = Boolean.TRUE.equals(controller.getClass().getMethod("shouldTriggerShow",
                        float.class, float.class, float.class, boolean.class).invoke(controller,
                        chain.getArg(0), chain.getArg(1), chain.getArg(2), shown));
                } catch (Exception | LinkageError error) {
                    ready = false;
                    Log.w("SuperCardNative", "Gesture decision unavailable; retaining existing route", error);
                    return chain.proceed();
                }
                Object result = chain.proceed();
                // A cancelled swipe can keep its window attached while animating out.
                // The original threshold result, rather than attachment, decides routing.
                if (committed && shouldRoute(host) && launch(host)) PluginRuntime.hide();
                return result;
            });
            ready = true;
        } catch (Exception | LinkageError error) { Log.w("SuperCardNative", "Native gesture route unavailable", error); }
    }

    private static boolean launch(Application host) {
        try {
            host.startActivity(new Intent().setClassName("dev.local.supercardhost",
                    "dev.local.supercardhost.NativeCardsActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            return true;
        } catch (RuntimeException error) {
            Log.w("SuperCardNative", "Native card launch failed; retaining existing route", error);
            return false;
        }
    }

    static boolean shouldRoute(android.content.Context context) {
        try {
            if (!ready || Settings.Secure.getInt(context.getContentResolver(), "supercard_native_cards", 0) != 1) return false;
            KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
            PowerManager power = context.getSystemService(PowerManager.class);
            return keyguard != null && !keyguard.isKeyguardLocked() && power != null && power.isInteractive();
        } catch (RuntimeException unavailable) { return false; }
    }

    static void showLegacy(Runnable action) {
        boolean previous = legacyRequest;
        legacyRequest = true;
        try { action.run(); } finally { legacyRequest = previous; }
    }
}
