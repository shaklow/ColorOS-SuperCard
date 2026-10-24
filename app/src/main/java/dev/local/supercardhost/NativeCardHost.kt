package dev.local.supercardhost

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.*
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import java.util.function.Consumer
import kotlin.math.*

/** Secure live stack: continuous scroll, original snap dynamics, no view recreation on swipes. */
internal class NativeCardHost(private val context: Context, private val type: Int = 2009,
    private val allowed: () -> Boolean) : AutoCloseable {
    interface Content : AutoCloseable {
        val view: View
        fun active(value: Boolean) {}
        fun dispose() = close()
    }
    private val windows = requireNotNull(context.getSystemService(WindowManager::class.java))
    // Configuration changes replace this host; no display queries inside an animation frame.
    private val density = context.resources.displayMetrics.density
    private val layout = android.util.DisplayMetrics().also { windows.defaultDisplay.getRealMetrics(it) }
        .let { NativeCardLayout.measure(it.widthPixels, it.heightPixels, density) }
    private val ownerContext = object : ContextWrapper(context), CardWindowOwner {
        override fun hideCard() = hide()
        override fun secureCard(secure: Boolean) = Unit
    }
    private val surface = MaterialUiArchive.create(ownerContext, MaterialUiArchive.Operation.NATIVE_CARD) as View
    private val body = requireNotNull(surface.findViewWithTag<FrameLayout>("native_content"))
    private val root: NativeGestureViewport = NativeGestureViewport(context, object : NativeGestureViewport.Events {
        override fun busy() = edgeDragging || dismissing || (rootAnimating && abs(root.translationX) > ViewConfiguration.get(context).scaledPagingTouchSlop / 3f)
        override fun begin() = beginPage()
        override fun drag(distance: Float) = dragPage(distance)
        override fun end(distance: Float, velocity: Float, cancel: Boolean) = endPage(velocity, cancel)
        override fun queue(delta: Int) { if (delta != 0) move(delta) }
        override fun inside(x: Int, y: Int) = insideCard(x, y)
        override fun interrupt(): Boolean {
            if (!animating) return false
            if (rootAnimating) {
                stopAnimation(); root.translationX = 0f; entranceOffset = 0f; finishPages(); return false
            }
            val target = order.indexOf(deck.selected) * width()
            if (abs(scroll - target) >= ViewConfiguration.get(context).scaledPagingTouchSlop / 3f) return true
            stopAnimation(); scroll = target; finishPages(); return false
        }
        override fun content() = current?.view
        override fun protectTexture() = (current?.view as? NativePayCard)?.interactive ?: true
    }).apply {
        clipChildren = false; clipToPadding = false
        addView(surface, FrameLayout.LayoutParams(-1, layout.height, Gravity.BOTTOM).apply { bottomMargin = layout.bottom })
    }
    private val touchBounds = Rect()
    private val screenOrigin = IntArray(2)
    private val bodyOrigin = IntArray(2)
    private var outsideDown: MotionEvent? = null
    private var mounted = false
    private var couponSession = false
    private var current: Content? = null
    private val cache = linkedMapOf<String, Content>()
    private var order = emptyList<String>()
    private var pageDragging = false
    private var edgeDragging = false
    private var rootAnimating = false
    private var dismissing = false
    private var scroll = 0f
    private var dragStart = 0f
    private var entranceOffset = 0f
    private var frontOffset = 0f
    private var edgeFollow: CardFollowSpring? = null
    private var edgeTarget = 0f
    private var edgeFrameTime = 0L
    private val edgeFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val follow = edgeFollow ?: return
            if (!edgeDragging || !allowed()) { hide(); return }
            follow.advance(edgeTarget, ((frameTimeNanos - edgeFrameTime) / 1_000_000_000f).coerceAtLeast(0f))
            edgeFrameTime = frameTimeNanos
            root.translationX = follow.position
            entranceOffset = 14.4f * density * root.translationX / travel(); renderStack()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
    private var settling: ValueAnimator? = null
    private var animationGeneration = 0
    private val deck = NativeCardDeck<Content>(::create, ::present, ::clear) {
        android.util.Log.w("SuperCardNative", "Cannot deactivate card", it)
    }
    val showing get() = mounted && deck.selected != null
    val selected get() = deck.selected
    val animating get() = settling != null
    private fun create(card: String): Content = cache.getOrPut(card) {
        check(allowed()) { "请先解锁手机" }
        when (card) {
            "pay" -> object : Content {
                override val view = NativePayCard(ownerContext, ownerContext)
                override fun active(value: Boolean) = view.setActive(value)
                override fun close() { if (current !== this) view.setActive(false) }
                override fun dispose() = view.close()
            }
            "staging" -> object : Content {
                override val view = NativeCouponCard(ownerContext, ownerContext)
                override fun active(value: Boolean) = view.setActive(value)
                override fun close() { if (current !== this) view.setActive(false) }
                override fun dispose() = view.close()
            }
            "dhu" -> object : Content {
                override val view = DhuPayView(ownerContext, false).apply { setActive(false) }
                override fun active(value: Boolean) = view.setActive(value)
                override fun close() { if (current !== this) view.setActive(false) }
                override fun dispose() = view.setActive(false)
            }
            "camera" -> object : Content {
                override val view = NativeLensCard(ownerContext, ownerContext)
                override fun active(value: Boolean) { if (!value) view.suspendCamera() }
                override fun close() { if (current !== this) view.suspendCamera() }
                override fun dispose() = view.close()
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
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    outsideDown?.recycle(); outsideDown = null
                    if (insideCard(event.rawX.toInt(), event.rawY.toInt())) false
                    else { outsideDown = MotionEvent.obtain(event); true }
                }
                MotionEvent.ACTION_UP -> {
                    val down = outsideDown; outsideDown = null
                    down?.let {
                        val slop = ViewConfiguration.get(context).scaledTouchSlop
                        val close = abs(event.rawX - it.rawX) < slop && abs(event.rawY - it.rawY) < slop
                        it.recycle(); if (close) dismiss()
                    }
                    down != null
                }
                MotionEvent.ACTION_CANCEL -> { outsideDown?.recycle(); outsideDown = null; false }
                else -> false
            }
        }
    }
    private fun insideCard(x: Int, y: Int): Boolean {
        root.getLocationOnScreen(screenOrigin)
        body.getLocationOnScreen(bodyOrigin)
        // Accept the stable front slot even when its live view is still sliding into it.
        touchBounds.set(layout.left + bodyOrigin[0], bodyOrigin[1],
            layout.left + layout.width + bodyOrigin[0], layout.height + bodyOrigin[1])
        if (touchBounds.contains(x, y)) return true
        return cache.values.any {
            if (!it.view.getGlobalVisibleRect(touchBounds)) false else {
                touchBounds.offset(screenOrigin[0], screenOrigin[1]); touchBounds.contains(x, y)
            }
        }
    }
    private fun bind() = MaterialUiArchive.update(surface, MaterialUiArchive.Operation.BIND_NATIVE_CARD, Consumer<Bundle> {
        when (it.getString("action")) { "previous" -> move(-1); "next" -> move(1); "close" -> dismiss() }
    })
    fun show(cards: List<String>, selected: String? = null) = mount(cards, selected, false)
    fun beginEdge(cards: List<String>) = mount(cards, null, true)
    fun warm(cards: List<String>) { if (allowed()) cards.forEach(::create) }
    private fun mount(cards: List<String>, selected: String?, edge: Boolean) {
        if (!allowed()) { hide(); error("请先解锁手机") }
        require(cards.isNotEmpty() && cards.distinct().size == cards.size)
        if (order.isNotEmpty() && order != cards) hide()
        stopAnimation(); pageDragging = false; edgeDragging = false
        val opening = !mounted
        order = cards.toList(); bind()
        try {
            for (card in order) {
                val view = create(card).view
                if (view.parent == null) {
                    view.clipToOutline = true
                    view.outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) =
                            outline.setRoundRect(0, 0, view.width, view.height, 28 * density)
                    }
                    view.foreground = ColorDrawable(Color.BLACK).apply { alpha = 0 }
                    body.addView(view, FrameLayout.LayoutParams(layout.width, layout.height, Gravity.CENTER_HORIZONTAL))
                }
            }
            deck.show(cards, selected, true)
            scroll = order.indexOf(deck.selected) * width()
            entranceOffset = if (opening) 14.4f * density else 0f
            renderStack(); root.requestFocus()
            if (opening) {
                root.translationX = travel()
                if (edge) {
                    edgeDragging = true
                    edgeTarget = travel(); edgeFollow = CardFollowSpring(edgeTarget)
                    edgeFrameTime = System.nanoTime()
                    Choreographer.getInstance().postFrameCallback(edgeFrame)
                } else animateRoot(0f, 0f) { finishPages() }
            } else { root.translationX = 0f; finishPages() }
        } catch (error: Throwable) { hide(); throw error }
    }
    private fun present(next: Content, card: String, index: Int, count: Int) {
        check(allowed()) { "请先解锁手机" }
        if (!mounted) {
            root.translationX = travel()
            windows.addView(root, WindowManager.LayoutParams(-1, -1, type,
                WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                setFitInsetsTypes(0); setTitle("SuperCardNativeWindow")
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            })
            mounted = true
        }
        if (!couponSession && "staging" in order) {
            couponSession = true
            try { CouponRefreshCoordinator.onNativeCardPackShown(context.applicationContext as android.app.Application, this) }
            catch (error: Exception) { couponSession = false; CouponRefreshCoordinator.onNativeCardPackHidden(this); throw error }
        }
        MaterialUiArchive.update(surface, MaterialUiArchive.Operation.RENDER_NATIVE_CARD, Bundle().apply {
            putInt("index", index); putInt("count", count)
            putString("title", when (card) { "pay" -> "PAY"; "staging" -> "卡券"; "camera" -> "LENS"; else -> "东华校园卡" })
        })
        current = next
    }
    fun followEdge(distance: Float) {
        if (!edgeDragging) return
        if (!allowed()) { hide(); return }
        edgeTarget = (travel() - distance - 30 * density).coerceIn(0f, travel())
    }
    fun endEdge(commit: Boolean, velocity: Float) {
        if (!edgeDragging) return
        edgeDragging = false; stopEdgeFollow()
        if (!allowed()) { hide(); return }
        animateRoot(if (commit) 0f else travel(), velocity) { if (commit) finishPages() else hide() }
    }
    private fun beginPage(): Boolean {
        if (!allowed()) { hide(); return false }
        if (!showing || edgeDragging || rootAnimating) return false
        stopAnimation()
        pageDragging = true; dragStart = scroll
        return true
    }
    private fun dragPage(distance: Float) {
        if (!pageDragging || !distance.isFinite()) return
        if (!allowed()) { hide(); return }
        val proposed = dragStart - distance
        root.translationX = if (abs(dragStart) < 1f && proposed < 0f) -proposed else 0f
        scroll = CardMotionRules.dragScroll(dragStart, distance, (order.size - 1) * width())
        renderStack()
    }
    private fun endPage(velocity: Float, cancel: Boolean) {
        if (!pageDragging) return
        pageDragging = false
        if (!allowed()) { hide(); return }
        if (root.translationX > 0f) {
            val close = !cancel && CardMotionRules.pageDelta(root.translationX, velocity, width(), density) == -1
            animateRoot(if (close) travel() else 0f, velocity) { if (close) hide() else finishPages() }
            return
        }
        val page = scroll / width()
        val minVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
        val target = if (!cancel && abs(velocity) > minVelocity) {
            if (velocity < 0) floor(page).toInt() + 1 else ceil(page).toInt() - 1
        } else if (cancel) (dragStart / width()).roundToInt() else page.roundToInt()
        settlePage(target.coerceIn(0, order.lastIndex), if (cancel) 0f else -velocity)
    }
    fun move(delta: Int) {
        if (!allowed()) { hide(); return }
        if (!showing || delta == 0 || edgeDragging || rootAnimating) return
        val target = (order.indexOf(deck.selected) + delta.sign).coerceIn(0, order.lastIndex)
        settlePage(target, 0f)
    }
    private fun settlePage(target: Int, velocity: Float) {
        stopAnimation(); pageDragging = false
        deck.show(order, order[target], true)
        val spring = CardSnapSpring(scroll, target * width(), velocity)
        animate(spring.duration, { time -> scroll = spring.position(time); renderStack() }) {
            scroll = target * width(); finishPages()
        }
    }
    private fun renderStack(frontOffset: Float = 0f) {
        this.frontOffset = frontOffset
        val selectedIndex = order.indexOf(deck.selected)
        for ((index, card) in order.withIndex()) {
            val view = cache[card]?.view ?: continue
            val position = index - scroll / width()
            val frame = CardMotionRules.stackFrame(position, width(), density)
            view.translationX = frame.x + if (index == selectedIndex) frontOffset else maxOf(0, index - selectedIndex) * entranceOffset
            view.scaleX = frame.scale; view.scaleY = frame.scale; view.alpha = if (position >= 5) 0f else 1f
            view.elevation = 0f; view.translationZ = frame.z
            ((view as? ViewGroup)?.getChildAt(0) ?: view).elevation = frame.elevation
            (view.foreground as ColorDrawable).alpha = (frame.shade * 255).roundToInt()
            view.importantForAccessibility = if (index == selectedIndex) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
    }
    private fun animateRoot(target: Float, velocity: Float, end: () -> Unit) {
        if (target != 0f) { animateExit(velocity, false, end); return }
        stopAnimation(); rootAnimating = true
        val opening = target == 0f
        val speed = abs(velocity.coerceIn(-60_000f, 60_000f))
        val duration = .35f * if (speed > 1500) .3f else if (speed > 500) .5f else 1f
        val translation = CardSpring(root.translationX, target, velocity, duration)
        val front = CardSpring(0f, 0f, if (opening) velocity else 0f, .35f)
        val rear = CardSpring(entranceOffset, 0f, if (opening) velocity * .9f else 0f, .35f)
        animate(maxOf(translation.durationMillis, front.durationMillis, rear.durationMillis), { time ->
            root.translationX = translation.position(time)
            entranceOffset = rear.position(time); renderStack(front.position(time))
        }) { root.translationX = target; entranceOffset = 0f; rootAnimating = false; end() }
    }
    private fun animate(duration: Long, frame: (Float) -> Unit, end: () -> Unit) {
        val ticket = ++animationGeneration
        val animator = ValueAnimator.ofFloat(0f, duration / 1000f).apply {
            this.duration = duration; interpolator = LinearInterpolator()
            addUpdateListener { if (ticket == animationGeneration) frame(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (ticket != animationGeneration) return
                    settling = null; end()
                }
            })
        }
        settling = animator; frame(0f); animator.start()
    }
    private fun stopAnimation() {
        stopEdgeFollow()
        animationGeneration++; val old = settling; settling = null; old?.cancel(); rootAnimating = false; dismissing = false
    }
    private fun stopEdgeFollow() {
        Choreographer.getInstance().removeFrameCallback(edgeFrame); edgeFollow = null
    }
    private fun finishPages() {
        entranceOffset = 0f
        for (index in body.childCount - 1 downTo 0) {
            if (cache.values.none { it.view === body.getChildAt(index) }) body.removeViewAt(index)
        }
        deck.releaseRetired()
        cache.values.forEach { it.active(it === current) }
        renderStack()
    }
    private fun dismiss() {
        if (!showing || edgeDragging || dismissing) return
        pageDragging = false; animateExit(0f, true) { hide() }
    }
    private fun animateExit(velocity: Float, rollback: Boolean, end: () -> Unit) {
        val from = root.translationX
        val startEntrance = entranceOffset; val startFront = frontOffset
        val motion = CardDismissMotion(from, travel(), if (rollback) scroll else 0f, density, velocity)
        stopAnimation(); rootAnimating = true; dismissing = true
        animate(motion.durationMillis, { time ->
            root.translationX = motion.translation(time)
            if (rollback) scroll = motion.scroll(time)
            val remaining = 1 - motion.progress(time)
            entranceOffset = startEntrance * remaining; renderStack(startFront * remaining)
        }) { rootAnimating = false; dismissing = false; end() }
    }
    fun hide() = deck.hide()
    private fun clear() {
        stopAnimation(); pageDragging = false; edgeDragging = false
        outsideDown?.recycle(); outsideDown = null; current = null
        try { body.removeAllViews() } finally {
            if (couponSession) { couponSession = false; CouponRefreshCoordinator.onNativeCardPackHidden(this) }
            for (content in cache.values) try { content.dispose() }
                catch (error: Exception) { android.util.Log.w("SuperCardNative", "Cannot dispose card", error) }
            cache.clear(); order = emptyList(); scroll = 0f
            if (mounted) try { windows.removeViewImmediate(root); mounted = false }
            catch (error: Exception) { mounted = root.isAttachedToWindow; android.util.Log.w("SuperCardNative", "Cannot detach window", error) }
        }
    }
    override fun close() = deck.close()
    private fun width() = layout.width.toFloat()
    private fun travel() = width() + 2 * layout.left
}
