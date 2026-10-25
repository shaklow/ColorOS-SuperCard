package dev.local.supercardhost

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import java.util.function.Consumer

/** Kotlin PAY view/session owner. The private UI only renders framework state and callbacks. */
internal class NativePayCard(context: Context, private val owner: CardWindowOwner) : FrameLayout(context), AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val scope = FloatingCardScope { stage, error -> Log.w("SuperCardNativePay", stage, error) }
    private val surface = MaterialUiArchive.create(context, MaterialUiArchive.Operation.PAY) as View
    private val viewport = requireNotNull(surface.findViewWithTag<FrameLayout>("pay_viewport"))
    private var texture: TextureView? = null
    private var choices = emptyList<NativePayConfiguration.Choice>()
    private var selected: String? = null
    private var session: NativePaySession? = null
    private var output: Surface? = null
    private var active = false
    private var revealGate = NativePayRevealGate(false)
    private var touchEnabled = false
    private var request = 0L
    private var preparing = false
    private var message: String? = null
    private val refresh = Runnable { stopPayment(); readConfiguration() }
    private val revealPage = Runnable { if (session != null && active && revealGate.reveal(SystemClock.elapsedRealtime())) render() }
    val interactive get() = revealGate.revealed && session?.interactive(touchEnabled) == true
    init {
        addView(surface, LayoutParams(-1, -1))
        MaterialUiArchive.update(surface, MaterialUiArchive.Operation.BIND_PAY, Consumer<Bundle> { event ->
            if (!active) return@Consumer
            when (event.getString("action")) {
                "function" -> event.getString("function")?.let(::choose)
                "back" -> stopPayment()
                "fullscreen" -> fullscreen()
                "settings" -> {
                    owner.hideCard()
                    context.startActivity(Intent().setClassName("dev.local.supercardhost", CardFunctionSettingsActivity::class.java.name)
                        .putExtra("mCardType", "pay").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        })
        readConfiguration()
    }
    private fun createTexture() {
        val target = TextureView(context).apply { tag = "native_pay_texture" }
        texture = target
        target.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(value: SurfaceTexture, width: Int, height: Int) { if (texture === target) prepare() }
            override fun onSurfaceTextureSizeChanged(value: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(value: SurfaceTexture): Boolean {
                if (texture === target) main.post { if (texture === target) stopPayment() }
                return true
            }
            override fun onSurfaceTextureUpdated(value: SurfaceTexture) {
                if (texture === target && session != null) {
                    revealGate.frame(SystemClock.elapsedRealtime())
                    scheduleReveal()
                }
            }
        }
        target.setOnClickListener { if (texture === target && !interactive) fullscreen() }
        target.setOnTouchListener { view, event ->
            if (texture !== target || !interactive) false else try {
                val handled = session?.touch(event, view.width, view.height, touchEnabled) == true
                if (handled) view.parent?.requestDisallowInterceptTouchEvent(
                    event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL)
                handled
            } catch (error: Exception) { fail(error); true }
        }
        viewport.addView(target, LayoutParams(-1, -1))
        prepare()
    }
    private fun readConfiguration() {
        try {
            choices = NativePayConfiguration.parse(Settings.Secure.getString(context.contentResolver, "card_setting_data"))
            message = null
        } catch (error: Exception) { choices = emptyList(); message = "支付功能配置暂不可用，请在设置中检查"; Log.w("SuperCardNativePay", "Read configuration", error) }
        render()
        bindIcons()
    }
    private fun render() {
        MaterialUiArchive.update(surface, MaterialUiArchive.Operation.RENDER_PAY, Bundle().apply {
            putStringArray("types", choices.map { it.type }.toTypedArray())
            putStringArray("titles", choices.map { it.title }.toTypedArray())
            putString("selected", selected)
            putBoolean("loading", selected != null && !revealGate.revealed)
            message?.let { putString("message", it) }
        })
    }
    private fun bindIcons() {
        // The original icons remain resources only; no vendor View/model crosses this boundary.
        try {
            val module = context.createPackageContext("dev.local.supercardhost", Context.CONTEXT_IGNORE_SECURITY)
            for (choice in choices) {
                if (!choice.icon.matches(Regex("[a-z0-9_]+"))) continue
                val id = module.resources.getIdentifier(choice.icon, "drawable", SuperCardArchiveResources.PACKAGE)
                if (id == 0) continue
                for (tag in listOf("pay_icon_${choice.type}", "pay_tab_icon_${choice.type}"))
                    surface.findViewWithTag<ImageView>(tag)?.setImageDrawable(module.getDrawable(id))
            }
        } catch (error: Exception) { Log.w("SuperCardNativePay", "Original shortcut icons unavailable", error) }
    }
    private fun choose(function: String) {
        if (!active || choices.none { it.type == function }) return
        stopPayment(false)
        selected = function; message = null
        revealGate = NativePayRevealGate(NativePayPolicy.scan(function))
        touchEnabled = PayTouchSettings.enabled(context, function)
        render(); createTexture()
    }
    private fun prepare() {
        val target = texture ?: return
        if (!active || selected == null || session != null || output != null || preparing || !target.isAvailable || !isAttachedToWindow) return
        preparing = true
        val function = selected!!
        val ticket = request
        val deadline = SystemClock.elapsedRealtime() + 10_000
        val ready = object : Runnable {
            override fun run() {
                if (ticket != request || texture !== target || !active || selected != function || !isAttachedToWindow) return
                try {
                    check(context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true) { "请先解锁手机" }
                    check(NativePayLauncher.ready) { "支付服务尚未连接，请重新载入卡包" }
                    if (!RootDisplayClient.isReady()) {
                        check(SystemClock.elapsedRealtime() < deadline) { "支付卡暂未就绪，请重试" }
                        RootDisplayClient.requestBackendForPayment(); main.postDelayed(this, 100); return
                    }
                    val user = PayUserSettings.resolve(context, function)
                    val metrics = DisplayMetrics()
                    context.getSystemService(android.view.WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
                    val size = NativePayGeometry.measure(target.width, target.height, metrics.widthPixels, metrics.densityDpi,
                        NativePayLauncher.enlarged(context, function))
                    val buffer = checkNotNull(target.surfaceTexture)
                    buffer.setDefaultBufferSize(size.width, size.height)
                    output = Surface(buffer)
                    lateinit var next: NativePaySession
                    next = NativePaySession(NativePayBackend(context), function, user, object : NativePaySession.Events {
                        override fun changed() {
                            if (session !== next) return
                            if (!revealGate.revealed) {
                                revealGate.page(SystemClock.elapsedRealtime(), next.reachedPage, next.needsRetry)
                                main.removeCallbacks(revealPage)
                            }
                            val page = next.pageGeneration
                            if (next.needsRetry) main.postDelayed({
                                if (session === next && next.pageGeneration == page) try { next.retry() } catch (error: Exception) { fail(error) }
                            }, 1500)
                            render()
                        }
                        override fun fullscreen(delay: Long) { main.postDelayed({ if (session === next) this@NativePayCard.fullscreen() }, delay) }
                        override fun closed() {
                            if (session === next) {
                                session = null; stopPayment()
                            }
                        }
                    })
                    session = next
                    preparing = false
                    next.start(checkNotNull(output), size)
                    main.postDelayed(object : Runnable {
                        override fun run() {
                            if (session !== next) return
                            try {
                                check(RootDisplayClient.isReady() && next.displayId > 0) { "支付显示已关闭" }
                                main.postDelayed(this, 500)
                            } catch (error: Exception) { fail(error) }
                        }
                    }, 500)
                    main.postDelayed({
                        if (session === next && !revealGate.revealed) fail(IllegalStateException("支付页面尚未就绪，请重试"))
                    }, 10_000)
                } catch (error: Exception) { fail(error) }
                catch (error: LinkageError) { fail(error) }
            }
        }
        ready.run()
    }
    private fun scheduleReveal() {
        if (revealGate.revealed) return
        main.removeCallbacks(revealPage)
        revealGate.remaining(SystemClock.elapsedRealtime())?.let { main.postDelayed(revealPage, it) }
    }
    private fun fullscreen() {
        val current = session ?: return
        if (!active) return
        try { current.fullscreen(); owner.hideCard() }
        catch (error: Exception) { fail(error) }
    }
    private fun fail(error: Throwable) {
        Log.w("SuperCardNativePay", "Payment page unavailable", error)
        stopPayment(); message = "支付页面暂不可用，请点击功能重试"; render()
    }
    private fun stopPayment(reset: Boolean = true) {
        request++; main.removeCallbacksAndMessages(null)
        val old = session; session = null
        val oldTexture = texture; texture = null; preparing = false
        try { old?.close() }
        catch (error: Exception) { Log.w("SuperCardNativePay", "Release payment display", error) }
        finally {
            output?.release(); output = null
            if (oldTexture?.parent === viewport) viewport.removeView(oldTexture)
            revealGate = NativePayRevealGate(selected?.let(NativePayPolicy::scan) == true)
            if (reset) { selected = null; render() }
        }
    }
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (!value) stopPayment(false)
        else if (selected != null && texture == null) { render(); createTexture() }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val keys = listOf("card_setting_data", "supercard_pay_user_wechat", "supercard_pay_user_alipay") +
            PayTouchSettings.FUNCTIONS.map { "supercard_pay_touch_$it" }
        scope.attachObservation(object : FloatingCardScope.Observation {
            override fun register(observer: ContentObserver) {
                try { keys.forEach { context.contentResolver.registerContentObserver(Settings.Secure.getUriFor(it), false, observer) } }
                catch (error: RuntimeException) { context.contentResolver.unregisterContentObserver(observer); throw error }
            }
            override fun unregister(observer: ContentObserver) = context.contentResolver.unregisterContentObserver(observer)
        }, Runnable { scope.post(refresh) })
        scope.post(refresh)
    }
    override fun onDetachedFromWindow() { try { close() } finally { super.onDetachedFromWindow() } }
    override fun close() { scope.detach(); active = false; stopPayment() }
}
