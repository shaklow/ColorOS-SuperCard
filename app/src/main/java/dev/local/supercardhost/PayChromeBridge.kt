package dev.local.supercardhost

import android.content.Context
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/** Decorates existing PAY views after OEM binding; never replaces a display, holder or listener. */
object PayChromeBridge {
    private val installed = Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    private val reported = mutableSetOf<String>()

    @JvmStatic @Synchronized
    fun install(module: XposedModule, host: Context, loader: ClassLoader) {
        if (loader in installed) return
        val card = loader.loadClass("com.vivo.card.cards.cardpay.PayCardView")
        val adapter = loader.loadClass("com.vivo.card.cards.cardpay.adapter.PayCardAdapter")
        val holder = loader.loadClass("com.vivo.card.cards.cardpay.adapter.PayCardAdapter\$ViewHolder")
        val fields = listOf(
            field(card, "cardFrame", ImageView::class.java),
            field(card, "cardLogo", ImageView::class.java),
            field(card, "cardSet", ImageView::class.java),
            field(card, "bigFingerTip", TextView::class.java),
            field(card, "shadowBgView", View::class.java),
            field(card, "linearShadow", LinearLayout::class.java)
        )
        val updates = listOf("initViews", "setSkinDataToCard", "updateCurrentSkin", "onAttachedToWindow")
            .map { method(card, it) }
        val bind = method(adapter, "onBindViewHolder", holder, Int::class.javaPrimitiveType!!)
        val getters = listOf(
            getter(holder, "getRootView", View::class.java),
            getter(holder, "getImgBg", ImageView::class.java),
            getter(holder, "getImgFunc", ImageView::class.java),
            getter(holder, "getTvFunc", TextView::class.java)
        )
        // Resolve every vendor signature and the private appearance API before installing hooks.
        DhuMaterialUi.preparePay(host)
        for (update in updates) module.hook(update).intercept { chain ->
            val result = chain.proceed()
            guarded("card") {
                val views = fields.map { it.get(chain.thisObject) as? View }
                if (views.all { it != null }) decorate("card", views.filterNotNull())
            }
            result
        }
        module.hook(bind).intercept { chain ->
            val result = chain.proceed()
            guarded("function") {
                val views = getters.map { it.invoke(chain.getArg(0)) as View }
                decorate("function", views)
            }
            result
        }
        installed.add(loader)
    }

    private fun decorate(part: String, views: List<View>) {
        DhuMaterialUi.stylePay(views[0].context, part, views.toTypedArray())
    }

    private fun guarded(part: String, action: () -> Unit) {
        try { action() }
        catch (error: Exception) { report(part, error) }
        catch (error: LinkageError) { report(part, error) }
    }

    private fun report(part: String, error: Throwable) {
        synchronized(reported) {
            if (reported.add(part)) Log.w("SuperCardPayUi", "PAY appearance unavailable; retaining original controls: $part", error)
        }
    }

    private fun field(owner: Class<*>, name: String, type: Class<*>): Field =
        owner.getDeclaredField(name).apply {
            require(type.isAssignableFrom(this.type)) { "PAY view field changed: $name" }
            isAccessible = true
        }

    private fun method(owner: Class<*>, name: String, vararg args: Class<*>): Method =
        owner.getDeclaredMethod(name, *args).apply {
            require(returnType == Void.TYPE) { "PAY update signature changed: $name" }
            isAccessible = true
        }

    private fun getter(owner: Class<*>, name: String, type: Class<*>): Method =
        owner.getDeclaredMethod(name).apply {
            require(type.isAssignableFrom(returnType)) { "PAY holder signature changed: $name" }
            isAccessible = true
        }
}
