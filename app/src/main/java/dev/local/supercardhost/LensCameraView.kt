package dev.local.supercardhost

import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.util.Log

/** Real system camera UI composed into the existing floating card, including its native controls. */
internal class LensCameraView(context: Context, private val type: String, back: Runnable,
    private val secure: (Boolean) -> Unit = { PluginRuntime.setLensSecure(it) }) : FrameLayout(context) {
    private val main = Handler(Looper.getMainLooper())
    private val texture = TextureView(context)
    private val status = TextView(context)
    private var session: LensCameraSession? = null
    private var output: Surface? = null
    private var closed = false
    private var frames = false
    private val timeout = Runnable { if (!frames) fail(IllegalStateException("Camera did not produce a frame")) }

    init {
        setBackgroundColor(Color.BLACK)
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val exit = TextView(context).apply {
            text = "‹ 返回"; contentDescription = "返回相机模式"; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); textSize = 16f; setOnClickListener { back.run() }
        }
        val title = TextView(context).apply {
            text = ColorOsLensModes.query(context).first { it.type() == type }.title()
            setTextColor(Color.WHITE); textSize = 16f
        }
        header.addView(exit, LinearLayout.LayoutParams(dp(88), -1))
        header.addView(title, LinearLayout.LayoutParams(-1, -2))
        addView(texture, LayoutParams(-1, -1).apply { topMargin = dp(48) })
        addView(header, LayoutParams(-1, dp(48)))
        status.text = "正在打开相机…"; status.setTextColor(Color.WHITE)
        status.gravity = Gravity.CENTER; status.textSize = 15f
        addView(status, LayoutParams(-1, -1).apply { topMargin = dp(48) })
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                if (closed) return
                try {
                    // Render at the card's density and aspect ratio. TextureView scales the
                    // existing buffer during layout changes; touch uses the same transform.
                    surface.setDefaultBufferSize(width, height)
                    output = Surface(surface)
                    secure(true)
                    RootDisplayClient.requestBackend()
                    main.postDelayed(timeout, 10_000)
                    val start = object : Runnable {
                        override fun run() {
                            if (closed) return
                            if (!RootDisplayClient.isReady()) { main.postDelayed(this, 200); return }
                            try {
                                session = LensCameraSession(LensDisplayBackend(context)).also {
                                    it.start(output!!, width, height, resources.displayMetrics.densityDpi,
                                        ColorOsLensModes.embeddedIntent(context, type))
                                }
                            } catch (error: Exception) { fail(error) }
                            catch (error: LinkageError) { fail(error) }
                        }
                    }
                    start.run()
                } catch (error: Exception) { fail(error) }
                catch (error: LinkageError) { fail(error) }
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { close(); return true }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                if (!closed && session != null) { frames = true; status.visibility = View.GONE; main.removeCallbacks(timeout) }
            }
        }
        texture.setOnTouchListener { view, event ->
            if (closed) false else try {
                val handled = session?.touch(event, view.width, view.height) == true
                if (handled) view.parent?.requestDisallowInterceptTouchEvent(
                    event.actionMasked != android.view.MotionEvent.ACTION_UP && event.actionMasked != android.view.MotionEvent.ACTION_CANCEL)
                handled
            } catch (error: Exception) { fail(error); true }
        }
    }

    fun close() {
        if (closed) return
        closed = true; main.removeCallbacksAndMessages(null)
        try { session?.close() }
        catch (error: Exception) { Log.w("SuperCardLens", "Cannot release camera display", error) }
        finally {
            session = null; output?.release(); output = null
            secure(false)
        }
    }

    private fun fail(error: Throwable) {
        Log.w("SuperCardLens", "Embedded camera failed", error)
        close(); status.visibility = View.VISIBLE
        status.text = "卡片内相机暂不可用\n请返回后重试，并检查虚拟屏后端"
    }
    override fun onDetachedFromWindow() { try { close() } finally { super.onDetachedFromWindow() } }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
}
