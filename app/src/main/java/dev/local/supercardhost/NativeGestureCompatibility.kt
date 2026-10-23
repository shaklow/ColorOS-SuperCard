package dev.local.supercardhost

import android.view.MotionEvent
import io.github.libxposed.api.XposedModule

/** Prevents the temporary original runtime from claiming a stream owned by the native core. */
internal object NativeGestureCompatibility {
    @JvmStatic fun install(module: XposedModule, loader: ClassLoader) {
        val type = loader.loadClass("com.vivo.card.framework.gesture.CardGestureController")
        val touch = type.getDeclaredMethod("onTouchEvent", MotionEvent::class.java)
        touch.isAccessible = true
        module.hook(touch).intercept { chain ->
            if (NativeCardRuntime.ownsGesture()) false else chain.proceed()
        }
        // Explicit original-card requests hand window ownership back before the old runtime opens.
        val trigger = loader.loadClass("com.vivo.card.service.CardServiceImpl").getDeclaredMethod(
            "onCardTriggerIntent", String::class.java, String::class.java, Int::class.javaPrimitiveType, String::class.java)
        trigger.isAccessible = true
        module.hook(trigger).intercept { chain ->
            NativeCardRuntime.useLegacy()
            chain.proceed()
        }
    }
}
