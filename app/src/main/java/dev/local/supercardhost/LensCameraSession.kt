package dev.local.supercardhost

import android.content.Intent
import android.graphics.Matrix
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface

/** One card owns one camera display. Closing it also cancels any in-flight gesture. */
internal class LensCameraSession(private val backend: Backend) : AutoCloseable {
    interface Display : AutoCloseable { val id: Int }
    interface Backend {
        fun create(surface: Surface, width: Int, height: Int, density: Int): Display
        fun launch(intent: Intent, display: Int)
        fun inject(event: MotionEvent, display: Int): Boolean
    }
    private var display: Display? = null
    private var width = 0
    private var height = 0
    private var gesture: MotionEvent? = null
    private var closed = false

    fun start(surface: Surface, width: Int, height: Int, density: Int, intent: Intent) {
        check(!closed && display == null) { "Camera session already started or closed" }
        require(width > 0 && height > 0 && density > 0)
        val created = backend.create(surface, width, height, density)
        display = created
        try {
            check(created.id > 0) { "Camera must run on a secondary display" }
            this.width = width; this.height = height
            backend.launch(Intent(intent), created.id)
        } catch (error: Throwable) { close(); throw error }
    }

    fun touch(source: MotionEvent, viewWidth: Int, viewHeight: Int): Boolean {
        val target = display ?: return false
        if (closed || viewWidth <= 0 || viewHeight <= 0) return false
        val event = MotionEvent.obtain(source)
        try {
            event.transform(Matrix().apply { setScale(width.toFloat() / viewWidth, height.toFloat() / viewHeight) })
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            if (!backend.inject(event, target.id)) throw IllegalStateException("Camera touch was rejected")
            gesture?.recycle(); gesture = null
            if (event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL)
                gesture = MotionEvent.obtain(event)
            return true
        } finally { event.recycle() }
    }

    override fun close() {
        if (closed) return
        closed = true
        val target = display; display = null
        try {
            gesture?.let { event ->
                try { event.action = MotionEvent.ACTION_CANCEL; if (target != null) backend.inject(event, target.id) }
                finally { event.recycle(); gesture = null }
            }
        } finally { target?.close() }
    }
}
