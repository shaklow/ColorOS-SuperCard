package dev.local.supercardhost

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.ContextWrapper
import android.graphics.PixelFormat
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import java.util.function.Consumer

/** Owns a secure card window, live page transitions and follow-finger entrance. */
internal class NativeCardHost(private val context: Context, private val type: Int = 2009,
    private val allowed: () -> Boolean) : AutoCloseable {
    interface Content : AutoCloseable { val view: View; fun active(value: Boolean) {} }
    private val windows = requireNotNull(context.getSystemService(WindowManager::class.java))
    private val ownerContext = object : ContextWrapper(context), CardWindowOwner {
        override fun hideCard() = hide()
        override fun secureCard(secure: Boolean) = Unit // The whole window stays FLAG_SECURE.
    }
    private val surface = MaterialUiArchive.create(ownerContext, MaterialUiArchive.Operation.NATIVE_CARD) as View
    private val body = requireNotNull(surface.findViewWithTag<FrameLayout>("native_content"))
    private val root = NativeGestureViewport(context, object : NativeGestureViewport.Events {
        override fun busy() = animating || edgeDragging
        override fun begin() = beginPage()
        override fun drag(distance: Float) = dragPage(distance)
        override fun end(distance: Float, velocity: Float, cancel: Boolean) = endPage(distance, velocity, cancel)
        override fun queue(delta: Int) { if (delta != 0) move(delta) }
    }).apply { addView(surface, FrameLayout.LayoutParams(-1, -1)) }
    private var mounted = false
    private var current: Content? = null
    private var preview: Content? = null
    private var previewType: String? = null
    private var state = Bundle()
    private var pageDragging = false
    private var edgeDragging = false
    private var pageDirection = 0
    private var offset = 0f
    private var settling: ValueAnimator? = null
    private var animationGeneration = 0
    private var queued = 0
    private val easing = PathInterpolator(.2f, 0f, .1f, 1f)
    private val deck = NativeCardDeck<Content>(::create, ::present, ::clear) {
        android.util.Log.w("SuperCardNative", "Cannot release card", it)
    }
    val showing get() = mounted && deck.selected != null
    val selected get() = deck.selected
    val animating get() = settling != null
    private fun create(card: String): Content {
        if (previewType == card && preview != null) return preview!!.also { preview = null; previewType = null }
        check(allowed()) { "请先解锁手机" }
        return when (card) {
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
            if (key == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { dismiss(); true } else false
        }
        root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) { dismiss(); true } else false
        }
    }
    private fun bind() = MaterialUiArchive.update(surface, MaterialUiArchive.Operation.BIND_NATIVE_CARD, Consumer<Bundle> {
        when (it.getString("action")) { "previous" -> move(-1); "next" -> move(1); "close" -> dismiss() }
    })
    fun show(cards: List<String>, selected: String? = null) = mount(cards, selected, false)
    fun beginEdge(cards: List<String>) = mount(cards, null, true)
    private fun mount(cards: List<String>, selected: String?, edge: Boolean) {
        if (!allowed()) { hide(); error("请先解锁手机") }
        finishNow()
        val previous = current
        bind()
        try { deck.show(cards, selected, true) } catch (error: Throwable) {
            if (!allowed()) hide()
            throw error
        }
        root.requestFocus()
        if (previous == null) {
            root.translationX = travel()
            if (edge) edgeDragging = true else animateRoot(0f, 0f) { current?.active(true) }
        } else if (current !== previous) animatePage(previous, current!!, pageDirection, 0f, 0f)
    }
    fun followEdge(distance: Float) {
        if (!edgeDragging) return
        if (!allowed()) { hide(); return }
        root.translationX = (travel() - distance - dp(30)).coerceIn(0f, travel())
    }
    fun endEdge(commit: Boolean, velocity: Float) {
        if (!edgeDragging) return
        edgeDragging = false
        if (!allowed()) { hide(); return }
        animateRoot(if (commit) 0f else travel(), velocity) {
            if (commit) current?.active(true) else hide()
        }
    }
    fun move(delta: Int) {
        if (!allowed()) { hide(); return }
        if (delta == 0 || !showing) return
        if (animating || edgeDragging || pageDragging) { queued = if (delta > 0) 1 else -1; return }
        val direction = if (delta > 0) 1 else -1
        if (deck.target(direction) == null) return
        try {
            pageDirection = direction; offset = 0f
            prepare(direction)
            commitPage(direction, 0f)
        } catch (error: Exception) {
            finishNow()
            if (!allowed()) hide()
            android.util.Log.w("SuperCardNative", "Cannot switch card", error)
        }
    }
    private fun present(next: Content, card: String, index: Int, count: Int) {
        check(allowed()) { "请先解锁手机" }
        val previous = current
        val nextState = Bundle().apply {
            putInt("index", index); putInt("count", count)
            putString("title", if (card == "camera") "LENS" else "东华校园卡")
        }
        try {
            if (next.view.parent == null) {
                if (previous != null) next.view.translationX = pageDirection * panelWidth()
                body.addView(next.view, FrameLayout.LayoutParams(-1, -1))
            }
            if (!mounted) {
                root.translationX = travel()
                val metrics = context.resources.displayMetrics
                windows.addView(root, WindowManager.LayoutParams(
                    minOf(dp(280), metrics.widthPixels - dp(24)).coerceAtLeast(1),
                    minOf(dp(450), metrics.heightPixels - dp(64)).coerceAtLeast(1), type,
                    WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, PixelFormat.TRANSLUCENT).apply {
                    gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL; x = dp(12)
                    setTitle("SuperCardNativeWindow"); softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                })
                mounted = true
            }
            check(allowed()) { "手机已锁定" }
            MaterialUiArchive.update(surface, MaterialUiArchive.Operation.RENDER_NATIVE_CARD, nextState)
            current = next; state = nextState; next.active(true)
        } catch (error: Throwable) {
            body.removeView(next.view); current = previous
            if (previous == null) clear()
            else MaterialUiArchive.update(surface, MaterialUiArchive.Operation.RENDER_NATIVE_CARD, state)
            throw error
        }
    }
    private fun beginPage(): Boolean {
        if (!allowed()) { hide(); return false }
        if (!showing || animating || edgeDragging) return false
        pageDragging = true; pageDirection = 0; offset = 0f
        return true
    }
    private fun prepare(direction: Int) {
        if (pageDirection != direction || preview == null) {
            dropPreview()
            if (pageDirection != direction) { current?.view?.let(::reset); root.translationX = 0f }
            pageDirection = direction
            val target = deck.target(direction) ?: return
            previewType = target
            try {
                preview = createFresh(target)
                preview!!.view.translationX = direction * panelWidth()
                body.addView(preview!!.view, if (direction > 0) 0 else body.childCount, FrameLayout.LayoutParams(-1, -1))
            } catch (error: Throwable) { dropPreview(); throw error }
        }
    }
    private fun createFresh(card: String): Content {
        val pending = previewType
        previewType = null
        return try { create(card) } finally { previewType = pending }
    }
    private fun dragPage(distance: Float) {
        if (!pageDragging || !distance.isFinite()) return
        if (!allowed()) { hide(); return }
        val direction = if (distance < 0) 1 else -1
        try { prepare(direction) } catch (error: Exception) {
            pageDragging = false; finishNow()
            android.util.Log.w("SuperCardNative", "Cannot prepare page", error); return
        }
        offset = distance.coerceIn(-panelWidth(), panelWidth())
        if (direction == -1 && deck.target(-1) == null) root.translationX = offset.coerceAtLeast(0f)
        else {
            root.translationX = 0f
            renderSwipe(current!!, preview, if (preview == null) offset * .22f else offset, direction)
        }
    }
    private fun endPage(distance: Float, velocity: Float, cancel: Boolean) {
        if (!pageDragging) return
        pageDragging = false
        if (!allowed()) { hide(); return }
        val delta = if (cancel) 0 else CardMotionRules.pageDelta(distance, velocity, panelWidth(), density())
        if (pageDirection == -1 && deck.target(-1) == null) {
            animateRoot(if (delta == -1) travel() else 0f, velocity) {
                if (delta == -1) hide() else finishPages()
            }
        } else if (preview != null && delta == pageDirection) {
            try { commitPage(delta, velocity) } catch (error: Exception) {
                finishNow(); android.util.Log.w("SuperCardNative", "Cannot commit page", error)
            }
        } else {
            val old = current ?: return
            val candidate = preview; val direction = pageDirection
            val from = if (candidate == null) offset * .22f else offset
            animate(from, 0f, velocity, { renderSwipe(old, candidate, it, direction) }) { dropPreview(); reset(old.view) }
        }
    }
    private fun commitPage(direction: Int, velocity: Float) {
        val old = current ?: return
        val from = offset
        deck.move(direction, true)
        val next = current ?: return
        if (next === old) { dropPreview(); return }
        animatePage(old, next, direction, from, velocity)
    }
    private fun renderSwipe(old: Content, next: Content?, distance: Float, direction: Int) {
        val frame = CardMotionRules.pageFrame(distance, panelWidth(), if (direction == 0) 1 else direction, density())
        old.view.translationX = frame.outgoing.x
        old.view.scaleX = frame.outgoing.scale; old.view.scaleY = old.view.scaleX
        old.view.alpha = 1f
        next?.view?.let {
            it.translationX = frame.incoming.x
            it.scaleX = frame.incoming.scale; it.scaleY = it.scaleX; it.alpha = 1f
        }
    }
    private fun animatePage(old: Content, next: Content, direction: Int, from: Float, velocity: Float) {
        val actualDirection = if (direction == 0) 1 else direction
        animate(from, -actualDirection * panelWidth(), velocity,
            { renderSwipe(old, next, it, actualDirection) }) { finishPages(); next.active(true) }
    }
    private fun animateRoot(target: Float, velocity: Float, end: () -> Unit) =
        animate(root.translationX, target, velocity, { root.translationX = it }, end)
    private fun animate(from: Float, to: Float, velocity: Float, frame: (Float) -> Unit, completed: () -> Unit) {
        val ticket = ++animationGeneration
        val animator = ValueAnimator.ofFloat(from, to).apply {
            duration = CardMotionRules.duration(to - from, velocity, panelWidth()); interpolator = easing
            addUpdateListener { if (ticket == animationGeneration) frame(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (ticket != animationGeneration) return
                    settling = null
                    completed.invoke()
                    val pending = queued; queued = 0
                    if (pending != 0 && showing) move(pending)
                }
            })
        }
        settling = animator; frame(from); animator.start()
    }
    private fun reset(view: View) { view.translationX = 0f; view.scaleX = 1f; view.scaleY = 1f; view.alpha = 1f }
    private fun finishPages() {
        for (index in body.childCount - 1 downTo 0) {
            val child = body.getChildAt(index)
            if (child !== current?.view) body.removeView(child)
        }
        current?.view?.let(::reset)
        deck.releaseRetired()
    }
    private fun dropPreview() {
        val pending = preview; preview = null; previewType = null
        if (pending != null) {
            body.removeView(pending.view)
            try { pending.close() } catch (error: Exception) { android.util.Log.w("SuperCardNative", "Cannot release preview", error) }
        }
    }
    private fun finishNow() {
        animationGeneration++; val animation = settling; settling = null; animation?.cancel()
        queued = 0; pageDragging = false; edgeDragging = false
        dropPreview(); finishPages(); root.translationX = 0f
    }
    private fun dismiss() {
        if (!showing || animating || edgeDragging || pageDragging) return
        animateRoot(travel(), 0f) { hide() }
    }
    fun hide() = deck.hide()
    private fun clear() {
        finishNow()
        try { current?.active(false) } catch (error: Exception) { android.util.Log.w("SuperCardNative", "Cannot deactivate card", error) }
        current = null
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
    private fun panelWidth() = if (body.width > 0) body.width.toFloat()
        else minOf(dp(280), context.resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(1).toFloat()
    private fun travel() = panelWidth() + dp(12)
    private fun density() = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density() + .5f).toInt()
}
