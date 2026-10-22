package dev.local.supercardhost

import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast

/** Native content inside the original CameraCardView. All clicks use its existing event handler. */
class LensCardView(context: Context, private val owner: View) : FrameLayout(context) {
    private val main = Handler(Looper.getMainLooper())
    private val handler = DhuCardConfiguration.field(owner, "eventHandler")
    private val modelType = owner.javaClass.classLoader!!.loadClass("com.vivo.card.model.CardSetBean\$CardBean\$FunctionBean")
    private val click = handler.javaClass.getMethod("handleFunctionClick", Context::class.java, modelType)
    private val settings = handler.javaClass.getMethod("handleSettingsClick", Context::class.java)
    private val gsonType = owner.javaClass.classLoader!!.loadClass("com.google.gson.Gson")
    private val gson = gsonType.getConstructor().newInstance()
    private val decode = gsonType.getMethod("fromJson", String::class.java, Class::class.java)
    private var surface: View = DhuMaterialUi.lens(context)
    private var observing = false
    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = refresh()
    }

    init { addView(surface, LayoutParams(-1, -1)); refresh() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!observing) {
            try {
                context.contentResolver.registerContentObserver(Settings.Secure.getUriFor("card_setting_data"), false, observer)
                observing = true
            } catch (error: RuntimeException) { Log.w("SuperCardLensUi", "Cannot observe camera configuration", error) }
        }
        refresh()
    }

    override fun onDetachedFromWindow() {
        if (observing) {
            try { context.contentResolver.unregisterContentObserver(observer) }
            catch (error: RuntimeException) { Log.w("SuperCardLensUi", "Cannot remove camera observer", error) }
            observing = false
        }
        main.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    override fun onConfigurationChanged(config: Configuration) {
        super.onConfigurationChanged(config)
        try {
            val next = DhuMaterialUi.lens(context.createConfigurationContext(config))
            removeAllViews(); surface = next; addView(next, LayoutParams(-1, -1)); refresh()
        } catch (error: Exception) { Log.w("SuperCardLensUi", "Cannot update camera appearance", error) }
        catch (error: LinkageError) { Log.w("SuperCardLensUi", "Cannot update camera appearance", error) }
    }

    private fun refresh() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { refresh() }; return }
        val values = Bundle()
        try {
            val raw = Settings.Secure.getString(context.contentResolver, "card_setting_data")
            val choices = LensCardConfiguration.parse(raw)
            // Deserialize the original FunctionBean without reconstructing or changing its payload.
            val functions = choices.map { decode.invoke(gson, it.functionJson(), modelType) }
            values.putStringArray("types", choices.map { it.type() }.toTypedArray())
            values.putStringArray("titles", choices.map { it.title() }.toTypedArray())
            if (raw.isNullOrBlank()) values.putString("message", "正在读取相机快捷方式…")
            DhuMaterialUi.renderLens(surface, values)
            choices.forEachIndexed { index, choice ->
                surface.findViewWithTag<View>("lens_mode_${choice.type()}")?.setOnClickListener {
                    invoke { click.invoke(handler, owner.context, functions[index]) }
                }
            }
        } catch (error: Exception) {
            Log.w("SuperCardLensUi", "Cannot read original camera configuration", error)
            values.clear(); values.putString("message", "相机配置暂不可用，请在右上角设置中检查")
            try { DhuMaterialUi.renderLens(surface, values) }
            catch (unavailable: Exception) { Log.w("SuperCardLensUi", "Cannot render camera status", unavailable) }
            catch (unavailable: LinkageError) { Log.w("SuperCardLensUi", "Cannot render camera status", unavailable) }
        }
        catch (error: LinkageError) { Log.w("SuperCardLensUi", "Camera surface unavailable", error) }
        surface.findViewWithTag<View>("lens_settings")?.setOnClickListener {
            invoke { settings.invoke(handler, owner.context) }
        }
    }

    private fun invoke(action: () -> Unit) {
        try { action() }
        catch (error: Exception) {
            Log.w("SuperCardLensUi", "Original camera action failed", error)
            Toast.makeText(context, "相机功能暂不可用，请重试", Toast.LENGTH_SHORT).show()
        }
    }
}
