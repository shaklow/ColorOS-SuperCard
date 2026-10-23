package dev.local.supercardhost

import android.content.Context
import android.content.ContextWrapper
import android.graphics.PixelFormat
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import java.util.function.Consumer

/** Independent secure window and content lifetime. It never loads a vivo service, card or model. */
internal class NativeCardHost(private val context: Context, private val type: Int = 2009,
    private val allowed: () -> Boolean) : AutoCloseable {
    interface Content : AutoCloseable { val view: View; fun active(value: Boolean) {} }
    private val windows = requireNotNull(context.getSystemService(WindowManager::class.java))
    private val ownerContext = object : ContextWrapper(context), CardWindowOwner {
        override fun hideCard() = hide()
        override fun secureCard(secure: Boolean) = Unit // The whole window is always FLAG_SECURE.
    }
    private val root = MaterialUiArchive.create(ownerContext, MaterialUiArchive.Operation.NATIVE_CARD) as View
    private val body = requireNotNull(root.findViewWithTag<FrameLayout>("native_content"))
    private var mounted = false
    private var current: Content? = null
    private var downX = 0f
    private var state = Bundle()
    private val deck = NativeCardDeck<Content>(::create, ::present, ::clear) {
        android.util.Log.w("SuperCardNative", "Cannot release card", it)
    }
    val showing get() = mounted && deck.selected != null
    private fun create(type: String): Content {
        check(allowed()) { "请先解锁手机" }
        return when (type) {
            "dhu" -> object : Content {
                override val view = DhuPayView(ownerContext, false).apply { setActive(false) }
                override fun active(value: Boolean) = view.setActive(value)
                override fun close() = view.setActive(false)
            }
            "camera" -> object : Content {
                override val view = NativeLensCard(ownerContext, ownerContext)
                override fun close() = view.close()
            }
            else -> error("此卡片尚未迁移")
        }
    }
    init {
        bind()
        root.isFocusableInTouchMode = true
        root.setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { hide(); true } else false
        }
        root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) { hide(); true } else false
        }
        // Footer-only swipes preserve camera controls, nested scrolling and payment gestures.
        listOf("previous", "next", "close").forEach { action ->
            root.findViewWithTag<View>("native_$action").setOnTouchListener { button, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> downX = event.rawX
                    MotionEvent.ACTION_UP -> if (kotlin.math.abs(event.rawX - downX) > dp(64)) {
                        val cancel = MotionEvent.obtain(event).apply { this.action = MotionEvent.ACTION_CANCEL }
                        try { button.onTouchEvent(cancel) } finally { cancel.recycle() }
                        move(if (event.rawX < downX) 1 else -1); return@setOnTouchListener true
                    }
                }
                false
            }
        }
    }
    fun show(cards: List<String>, selected: String? = null) {
        if (!allowed()) { hide(); error("请先解锁手机") }
        bind()
        try { deck.show(cards, selected) } catch (error: Throwable) {
            if (!allowed()) hide()
            throw error
        }
        root.requestFocus()
    }
    fun move(delta: Int) {
        if (!allowed()) { hide(); return }
        try { deck.move(delta) } catch (error: Exception) {
            if (!allowed()) hide()
            android.util.Log.w("SuperCardNative", "Cannot switch card", error)
        }
    }
    private fun bind() = MaterialUiArchive.update(root, MaterialUiArchive.Operation.BIND_NATIVE_CARD, Consumer<Bundle> {
        when (it.getString("action")) { "previous" -> move(-1); "next" -> move(1); "close" -> hide() }
    })
    private fun present(next: Content, card: String, index: Int, count: Int) {
        check(allowed()) { "请先解锁手机" }
        val previous = current
        val nextState = Bundle().apply {
            putInt("index", index); putInt("count", count)
            putString("title", if (card == "camera") "LENS" else "东华校园卡")
        }
        previous?.active(false)
        try {
            body.addView(next.view, FrameLayout.LayoutParams(-1, -1))
            if (!mounted) {
                val metrics = context.resources.displayMetrics
                val width = minOf(dp(280), metrics.widthPixels - dp(24)).coerceAtLeast(1)
                val height = minOf(dp(506), metrics.heightPixels - dp(64)).coerceAtLeast(1)
                windows.addView(root, WindowManager.LayoutParams(width, height, type,
                    WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT).apply {
                    gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL; x = dp(12)
                    setTitle("SuperCardNativeWindow"); softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                })
                mounted = true
            }
            check(allowed()) { "手机已锁定" }
            MaterialUiArchive.update(root, MaterialUiArchive.Operation.RENDER_NATIVE_CARD, nextState)
            previous?.view?.let(body::removeView)
            current = next; state = nextState; next.active(true)
            next.view.alpha = 0f
            next.view.animate().alpha(1f).setDuration(180).start()
        } catch (error: Throwable) {
            body.removeView(next.view)
            previous?.let { if (it.view.parent == null) body.addView(it.view, FrameLayout.LayoutParams(-1, -1)); it.active(true) }
            current = previous
            if (previous == null) clear() else MaterialUiArchive.update(root, MaterialUiArchive.Operation.RENDER_NATIVE_CARD, state)
            throw error
        }
    }
    fun hide() = deck.hide()
    private fun clear() {
        try { current?.active(false) } catch (error: Exception) { android.util.Log.w("SuperCardNative", "Cannot deactivate card", error) }
        current = null
        for (index in 0 until body.childCount) body.getChildAt(index).animate().cancel()
        try { body.removeAllViews() } finally {
            if (mounted) {
                try { windows.removeViewImmediate(root); mounted = false }
                catch (error: Exception) {
                    mounted = root.isAttachedToWindow
                    android.util.Log.w("SuperCardNative", "Cannot detach window", error)
                }
            }
        }
    }
    override fun close() = deck.close()
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
}
