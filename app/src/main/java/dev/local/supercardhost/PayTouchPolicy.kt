package dev.local.supercardhost

import android.content.ComponentName
import android.content.Context
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.function.BiConsumer
import java.util.function.IntSupplier
import java.util.function.Supplier

/** Owns the optional touch allowance and its original PAY view binding, not display/session creation. */
internal class PayTouchPolicy(
    private val host: Context,
    private val function: Supplier<String?>,
    private val display: IntSupplier,
    private val failure: BiConsumer<String, Throwable>
) {
    private var observed: ComponentName? = null
    private var view: Any? = null
    private var activity: String? = null
    private var update: Method? = null
    private var appliedGeneration = -1L

    fun install(module: XposedModule, loader: ClassLoader) {
        val constants = loader.loadClass("com.vivo.card.utils.CardTypeConstant")
        val card = loader.loadClass("com.vivo.card.cards.cardpay.PayCardView")
        val noIntercept = method(constants, "isNoInterceptActivity", String::class.java)
        val update = method(card, "updatePayClickListener", String::class.java)
        val cleanup = listOf("onCardPackHiding", "onDetachedFromWindow", "resetCard").map { method(card, it) }
        this.update = update
        module.hook(noIntercept).intercept { chain ->
            val original = chain.proceed() == true
            if (original) true else {
                val selected = function.get()
                PayTouchAccess.allows(false, display.asInt, observed, chain.getArg(0) as String?,
                    PayTouchSettings.targetPackage(selected), PayTouchSettings.enabled(host, selected))
            }
        }
        module.hook(update).intercept { chain ->
            view = chain.thisObject; activity = chain.getArg(0) as String?
            chain.proceed()
        }
        cleanup.forEach { event -> module.hook(event).intercept { chain ->
            if (view === chain.thisObject) { view = null; activity = null; observed = null }
            chain.proceed()
        } }
    }

    fun observe(component: ComponentName?) { observed = component }

    fun refresh() {
        val owner = view ?: return
        val page = activity ?: return
        if (display.asInt <= 0) return
        try { update?.invoke(owner, page) }
        catch (error: Exception) { failure.accept("refresh payment touch policy", error) }
    }

    fun applyGeneration(generation: Long, component: ComponentName) {
        // A reused page/display still needs the newly selected function's opt-in.
        if (appliedGeneration == generation || view == null) return
        activity = component.className
        refresh(); appliedGeneration = generation
    }

    private fun method(type: Class<*>, name: String, vararg parameters: Class<*>): Method {
        var owner: Class<*>? = type
        while (owner != null) {
            try { return owner.getDeclaredMethod(name, *parameters).apply { isAccessible = true } }
            catch (_: NoSuchMethodException) { owner = owner.superclass }
        }
        throw NoSuchMethodException("${type.name}.$name")
    }
}

/** Framework-only decision used by the hook; the original whitelist always takes precedence. */
internal object PayTouchAccess {
    fun allows(original: Boolean, display: Int, observed: ComponentName?, activity: String?, expected: String?, enabled: Boolean): Boolean {
        if (original) return true
        if (display <= 0 || observed == null || expected == null || expected != observed.packageName ||
            observed.className != activity) return false
        if (activity in authentication) return false
        return enabled
    }
    private val authentication = setOf(
        "com.tencent.mm.plugin.walletlock.gesture.ui.GestureGuardLogicUI",
        "com.tencent.mm.plugin.walletlock.fingerprint.ui.FingerprintWalletLockUI",
        "com.tencent.mm.framework.app.UIPageFragmentActivity",
        "com.alipay.mobile.security.gesture.ui.GestureLiteActivity_"
    )
}
