package dev.local.supercardhost

import android.content.ComponentName
import android.graphics.Matrix
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface

/** Display ownership shared with the existing account/task bridge, without an OEM view manager. */
internal interface NativePayDisplayOwner : AutoCloseable {
    val displayId: Int
    fun resumed(component: ComponentName, task: Int)
}

/** One selected function/account owns its display, task observations and in-flight touch. */
internal class NativePaySession(
    private val backend: Backend, val function: String, val user: Int, private val events: Events
) : NativePayDisplayOwner {
    interface Backend {
        fun create(surface: Surface, size: NativePayGeometry.Size): LensCameraSession.Display
        fun bind(owner: NativePayDisplayOwner, function: String, user: Int)
        fun unbind(owner: NativePayDisplayOwner)
        fun launch(function: String, user: Int, display: Int)
        fun retry(function: String, user: Int, display: Int)
        fun inject(event: MotionEvent, display: Int): Boolean
        fun promote(task: Int, display: Int, user: Int)
    }
    interface Events {
        fun changed()
        fun fullscreen(delay: Long)
        fun closed()
    }
    private var display: LensCameraSession.Display? = null
    private var size: NativePayGeometry.Size? = null
    private var gesture: MotionEvent? = null
    private var closed = false
    private var page: ComponentName? = null
    private var task = -1
    private var sawScan = false
    private var handoff = false
    private var retried = false
    var pageGeneration = 0L
        private set
    override val displayId get() = if (closed) -1 else display?.id ?: -1
    val hasPage get() = !closed && page != null
    val reachedPage get() = !closed && NativePayPolicy.reached(page)
    val needsRetry get() = !closed && !retried && page?.className == "com.alipay.mobile.quinox.SchemeLauncherActivity"
    fun interactive(enabled: Boolean) = !closed && !handoff && displayId > 0 && NativePayPolicy.touch(function, page, enabled)

    fun start(surface: Surface, size: NativePayGeometry.Size) {
        check(!closed && display == null)
        require(user >= 0)
        NativePayPolicy.target(function)
        try {
            val next = backend.create(surface, size); display = next; this.size = size
            check(next.id > 0) { "支付卡必须使用独立安全显示" }
            backend.bind(this, function, user)
            backend.launch(function, user, next.id)
        } catch (error: Throwable) { close(); throw error }
    }

    override fun resumed(component: ComponentName, task: Int) {
        if (closed || displayId <= 0 || task <= 0 || !NativePayPolicy.accepts(function, component)) return
        val changed = page != component || this.task != task
        if (changed) cancelTouch()
        page = component; this.task = task
        val scanResult = NativePayPolicy.scan(function) && sawScan && !NativePayPolicy.scanPage(component)
        if (NativePayPolicy.scanPage(component)) sawScan = true
        if (changed) { pageGeneration++; events.changed() }
        if (!handoff && (NativePayPolicy.authentication(component) || scanResult)) {
            handoff = true; events.fullscreen(NativePayPolicy.fullscreenDelay(component))
        }
    }

    fun touch(source: MotionEvent, width: Int, height: Int, enabled: Boolean): Boolean {
        val target = display ?: return false
        val dimensions = size ?: return false
        if (!interactive(enabled) || handoff || width <= 0 || height <= 0) { cancelTouch(); return false }
        val event = MotionEvent.obtain(source)
        try {
            event.transform(Matrix().apply { setScale(dimensions.width.toFloat() / width, dimensions.height.toFloat() / height) })
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            check(backend.inject(event, target.id)) { "支付页面触摸失败" }
            gesture?.recycle(); gesture = null
            if (event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL)
                gesture = MotionEvent.obtain(event)
            return true
        } finally { event.recycle() }
    }

    fun fullscreen() {
        check(!closed && displayId > 0)
        cancelTouch()
        if (task > 0) backend.promote(task, displayId, user)
        val restart = task <= 0
        close()
        if (restart) backend.launch(function, user, 0)
    }
    fun retry() {
        if (!needsRetry || displayId <= 0) return
        retried = true; backend.retry(function, user, displayId)
    }

    private fun cancelTouch() {
        val old = gesture ?: return
        gesture = null
        try { old.action = MotionEvent.ACTION_CANCEL; if (displayId > 0) backend.inject(old, displayId) }
        finally { old.recycle() }
    }

    override fun close() {
        if (closed) return
        try { cancelTouch() }
        finally {
            closed = true
            try { backend.unbind(this) }
            finally {
                val old = display; display = null; page = null; task = -1
                try { old?.close() } finally { events.closed() }
            }
        }
    }
}
