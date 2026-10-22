package dev.local.supercardhost;

import android.util.Log;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import io.github.libxposed.api.XposedModule;

/** Replaces only CameraCardView's inner Compose surface, preserving the original card/window ABI. */
final class LensSurfaceBridge {
    static void install(XposedModule module, ClassLoader loader) throws Exception {
        Class<?> camera = loader.loadClass("com.vivo.card.cards.cardcamera.CameraCardView");
        java.lang.reflect.Method compose = camera.getDeclaredMethod("initComposeView");
        compose.setAccessible(true);
        module.hook(compose).intercept(chain -> {
            ViewGroup card = (ViewGroup) chain.getThisObject();
            for (int index = 0; index < card.getChildCount(); index++)
                if (card.getChildAt(index) instanceof LensCardView) return null;
            LensCardView content = null;
            try {
                content = new LensCardView(card.getContext(), card);
                card.addView(content, new FrameLayout.LayoutParams(-1, -1));
                return null;
            } catch (Exception | LinkageError error) {
                if (content != null && content.getParent() == card) {
                    try { card.removeView(content); }
                    catch (RuntimeException cleanup) { Log.w("SuperCardLensUi", "Cannot remove failed Lens surface", cleanup); }
                }
                Log.w("SuperCardLensUi", "M3E surface unavailable; retaining original Lens view", error);
                return chain.proceed();
            }
        });
    }
}
