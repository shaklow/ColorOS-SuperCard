package dev.local.supercardhost

import android.content.Context
import android.content.res.Configuration
import android.app.KeyguardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import java.util.function.BiConsumer

/** Native content inside the original CameraCardView, with an owned interactive camera session. */
class LensCardView(context: Context, private val owner: View) : FrameLayout(context) {
    private val lifecycle = FloatingCardScope(BiConsumer { stage, error ->
        Log.w("SuperCardLensUi", "Camera observation failed: $stage", error)
    })
    private val handler = DhuCardConfiguration.field(owner, "eventHandler")
    private val modelType = owner.javaClass.classLoader!!.loadClass("com.vivo.card.model.CardSetBean\$CardBean\$FunctionBean")
    private val click = handler.javaClass.getMethod("handleFunctionClick", Context::class.java, modelType)
    private val settings = handler.javaClass.getMethod("handleSettingsClick", Context::class.java)
    private val gsonType = owner.javaClass.classLoader!!.loadClass("com.google.gson.Gson")
    private val gson = gsonType.getConstructor().newInstance()
    private val decode = gsonType.getMethod("fromJson", String::class.java, Class::class.java)
    private var surface: View = DhuMaterialUi.lens(context)
    private var appearance = context
    private var camera: LensCameraView? = null
    private val resume = Handler(Looper.getMainLooper())
    private val refresh = Runnable { replaceSurface(appearance) }

    init { bind(surface); addView(surface, LayoutParams(-1, -1)) }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (lifecycle.attach(context.contentResolver, Settings.Secure.getUriFor("card_setting_data"),
                Runnable { lifecycle.post(refresh) })) lifecycle.post(refresh)
    }

    override fun onDetachedFromWindow() {
        try { closeCamera(); lifecycle.detach() }
        finally { super.onDetachedFromWindow() }
    }

    override fun onConfigurationChanged(config: Configuration) {
        super.onConfigurationChanged(config)
        try { replaceSurface(context.createConfigurationContext(config)) }
        catch (error: RuntimeException) { Log.w("SuperCardLensUi", "Cannot configure camera appearance", error) }
        catch (error: LinkageError) { Log.w("SuperCardLensUi", "Cannot configure camera appearance", error) }
    }

    private fun replaceSurface(context: Context) {
        var next: View? = null
        try {
            next = DhuMaterialUi.lens(context)
            bind(next)
            addView(next, LayoutParams(-1, -1))
            val previous = surface
            surface = next; appearance = context
            removeView(previous)
            if (camera != null) { next.visibility = View.GONE; camera?.bringToFront() }
        } catch (error: Exception) { cleanup(next); Log.w("SuperCardLensUi", "Cannot update camera appearance", error) }
        catch (error: LinkageError) { cleanup(next); Log.w("SuperCardLensUi", "Cannot update camera appearance", error) }
    }

    fun openCamera(type: String) {
        if (!isAttachedToWindow || !owner.isShown) return
        closeCamera()
        val next = LensCameraView(context, type, Runnable { closeCamera() })
        camera = next
        surface.visibility = View.GONE
        addView(next, LayoutParams(-1, -1))
    }

    fun closeCamera() {
        resume.removeCallbacksAndMessages(null)
        val previous = camera; camera = null
        try { previous?.close() }
        finally {
            if (previous != null) removeView(previous)
            surface.visibility = View.VISIBLE
        }
    }

    fun resumeCameraAfterUnlock(type: String) {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard == null || keyguard.isKeyguardLocked || keyguard.isDeviceLocked) return
        resume.removeCallbacksAndMessages(null)
        PluginRuntime.showCamera()
        val reference = java.lang.ref.WeakReference(this)
        resume.post(object : Runnable {
            var attempts = 0
            override fun run() {
                val view = reference.get() ?: return
                if (keyguard.isKeyguardLocked || keyguard.isDeviceLocked) return
                view.invoke {
                    if (view.isAttachedToWindow && view.owner.isShown &&
                        view.owner.javaClass.getMethod("isCardExpanded").invoke(view.owner) == true) {
                        view.openCamera(type)
                    } else if (++attempts < 20) view.resume.postDelayed(this, 100)
                }
            }
        })
    }

    private fun cleanup(next: View?) {
        if (next != null && next !== surface && next.parent === this) {
            try { removeView(next) }
            catch (error: RuntimeException) { Log.w("SuperCardLensUi", "Cannot remove failed camera surface", error) }
        }
    }

    private fun bind(target: View) {
        val values = Bundle()
        val actions = try {
            val raw = Settings.Secure.getString(context.contentResolver, "card_setting_data")
            val choices = LensCardConfiguration.parse(raw)
            // Deserialize the original FunctionBean without reconstructing or changing its payload.
            val functions = choices.map { decode.invoke(gson, it.functionJson(), modelType) }
            values.putStringArray("types", choices.map { it.type() }.toTypedArray())
            values.putStringArray("titles", choices.map { it.title() }.toTypedArray())
            if (raw.isNullOrBlank()) values.putString("message", "正在读取相机快捷方式…")
            choices.mapIndexed { index, choice -> choice.type() to functions[index] }
        } catch (error: Exception) {
            Log.w("SuperCardLensUi", "Cannot read original camera configuration", error)
            values.clear(); values.putString("message", "相机配置暂不可用，请在右上角设置中检查")
            emptyList()
        }
        // A failed private render propagates before a replacement is attached.
        DhuMaterialUi.renderLens(target, values)
        actions.forEach { (type, function) ->
            target.findViewWithTag<View>("lens_mode_$type")?.setOnClickListener {
                invoke { click.invoke(handler, owner.context, function) }
            }
        }
        target.findViewWithTag<View>("lens_settings")?.setOnClickListener {
            invoke { settings.invoke(handler, owner.context) }
        }
    }

    private fun invoke(action: () -> Unit) {
        try { action() }
        catch (error: Exception) {
            Log.w("SuperCardLensUi", "Original camera action failed", error)
            Toast.makeText(context, "相机功能暂不可用，请重试", Toast.LENGTH_SHORT).show()
        }
        catch (error: LinkageError) {
            Log.w("SuperCardLensUi", "Original camera action unavailable", error)
            Toast.makeText(context, "相机功能暂不可用，请重试", Toast.LENGTH_SHORT).show()
        }
    }
}
