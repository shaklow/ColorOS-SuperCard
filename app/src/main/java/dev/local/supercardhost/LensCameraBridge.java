package dev.local.supercardhost;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.SystemClock;
import android.view.ViewGroup;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;
import io.github.libxposed.api.XposedModule;

/** Keeps a Lens mode click inside its card, before the OEM handler hides the window. */
final class LensCameraBridge {
    private record Pending(WeakReference<LensCardView> view, String type, long at) {}

    static void install(XposedModule module, ClassLoader loader) throws Exception {
        Class<?> handler = loader.loadClass("com.vivo.card.cards.cardcamera.handler.CameraCardEventHandler");
        Class<?> function = loader.loadClass("com.vivo.card.model.CardSetBean$CardBean$FunctionBean");
        Class<?> base = loader.loadClass("com.vivo.card.cards.base.view.BaseCardView");
        Field callback = handler.getDeclaredField("cardCallback"); callback.setAccessible(true);
        Method expanded = method(handler, "isCardExpanded");
        Method type = method(function, "getType");
        Method unlock = method(handler, "requestUnlock", Context.class);
        Map<Object, Pending> pending = new WeakHashMap<>(); // Original callbacks run on the UI thread.

        module.hook(method(handler, "handleFunctionClick", Context.class, function)).intercept(chain -> {
            Object owner = chain.getThisObject();
            LensCardView view = view(callback.get(owner));
            if (view == null) return chain.proceed();
            pending.remove(owner);
            if (!Boolean.TRUE.equals(expanded.invoke(owner))) return null;
            Context context = (Context) chain.getArg(0);
            String selected = (String) type.invoke(chain.getArg(1));
            // Validate the mode before touching the display or requesting authentication.
            ColorOsLensModes.embeddedIntent(context, selected);
            KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
            if (keyguard != null && (keyguard.isKeyguardLocked() || keyguard.isDeviceLocked())) {
                pending.put(owner, new Pending(new WeakReference<>(view), selected, SystemClock.uptimeMillis()));
                unlock.invoke(owner, context);
            } else view.openCamera(selected);
            return null;
        });
        module.hook(method(handler, "handleSettingsClick", Context.class)).intercept(chain -> {
            pending.remove(chain.getThisObject());
            LensCardView view = view(callback.get(chain.getThisObject()));
            if (view != null) view.closeCamera();
            return chain.proceed();
        });
        module.hook(method(handler, "resetPendingKeyguardRequest")).intercept(chain -> {
            pending.remove(chain.getThisObject()); return chain.proceed();
        });
        module.hook(method(handler, "onKeyguardSuccess")).intercept(chain -> {
            Pending selected = pending.remove(chain.getThisObject());
            Object result = chain.proceed();
            if (selected != null && SystemClock.uptimeMillis() - selected.at() < 60_000) {
                LensCardView view = selected.view().get();
                if (view != null) view.resumeCameraAfterUnlock(selected.type());
            }
            return result;
        });
        // A card often stays attached while the pack hides or scrolls to another page.
        for (String event : new String[]{"onCardPackHiding", "onCardPackHidden", "onCardPackDestroyed"}) {
            module.hook(method(base, event)).intercept(chain -> {
                LensCardView view = view(chain.getThisObject());
                if (view != null) view.closeCamera();
                return chain.proceed();
            });
        }
        module.hook(method(base, "notifyCardForegroundState", boolean.class)).intercept(chain -> {
            if (!Boolean.TRUE.equals(chain.getArg(0))) {
                LensCardView view = view(chain.getThisObject());
                if (view != null) view.closeCamera();
            }
            return chain.proceed();
        });
        module.hook(method(base, "onOuterPageScrollStateChanged", int.class, String.class)).intercept(chain -> {
            if (!"camera".equals(chain.getArg(1))) {
                LensCardView view = view(chain.getThisObject());
                if (view != null) view.closeCamera();
            }
            return chain.proceed();
        });
    }

    private static LensCardView view(Object owner) {
        if (owner instanceof ViewGroup card)
            for (int i = 0; i < card.getChildCount(); i++)
                if (card.getChildAt(i) instanceof LensCardView view) return view;
        return null;
    }
    private static Method method(Class<?> type, String name, Class<?>... parameters) throws Exception {
        Method result = type.getDeclaredMethod(name, parameters); result.setAccessible(true); return result;
    }
}
