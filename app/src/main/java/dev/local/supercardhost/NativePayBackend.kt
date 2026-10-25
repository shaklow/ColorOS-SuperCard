package dev.local.supercardhost

import android.app.ActivityManager
import android.content.Context
import android.view.InputEvent
import android.view.MotionEvent
import android.view.Surface

/** SystemUI owns launching/input; the authenticated root backend creates the fixed PAY display. */
internal class NativePayBackend @JvmOverloads constructor(private val context: Context, private val codes: WechatCodeChannel? = null) : NativePaySession.Backend {
    private val input = checkNotNull(context.getSystemService(Context.INPUT_SERVICE))
    private val inject = input.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    private val displayId = InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    override fun create(surface: Surface, size: NativePayGeometry.Size): LensCameraSession.Display {
        PayRuntimeBridge.prepareNative()
        val display = RootDisplayClient.create("SuperCard_Virtual", size.width, size.height, size.density,
            surface, RootDisplayProtocol.DISPLAY_FLAGS)
        return object : LensCameraSession.Display {
            override val id get() = display.display.let { if (it.isValid) it.displayId else -1 }
            override fun close() = UiCompat.releaseVirtualDisplay(display)
        }
    }
    override fun bind(owner: NativePayDisplayOwner, function: String, user: Int) {
        PayRuntimeBridge.beginNative(owner, function, user)
        try { codes?.bind(owner.displayId) } catch (error: Exception) {
            android.util.Log.i("SuperCardNativePay", "Native code binding unavailable: ${error.javaClass.simpleName}: ${error.message}")
            codes?.close()
        }
    }
    override fun unbind(owner: NativePayDisplayOwner) {
        try { codes?.close() } finally { PayRuntimeBridge.endNative(owner) }
    }
    override fun launch(function: String, user: Int, display: Int) = NativePayLauncher.launch(context, function, user, display)
    override fun retry(function: String, user: Int, display: Int) = NativePayLauncher.launch(context, function, user, display, true)
    override fun inject(event: MotionEvent, display: Int): Boolean {
        require(display > 0)
        displayId.invoke(event, display)
        return inject.invoke(input, event, 0) == true
    }
    override fun promote(task: Int, display: Int, user: Int) {
        require(task > 0 && display > 0 && user >= 0)
        val tasks = checkNotNull(context.getSystemService(ActivityManager::class.java)).getRunningTasks(30)
        val current = tasks.firstOrNull { it.taskId == task } ?: error("支付页面已关闭，请重新打开")
        check(current.javaClass.getField("displayId").getInt(current) == display &&
            current.javaClass.getField("userId").getInt(current) == user) { "支付页面已变化，请重新打开" }
        val type = Class.forName("android.app.ActivityTaskManager")
        val service = type.getMethod("getService").invoke(null)
        service.javaClass.getMethod("moveRootTaskToDisplay", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(service, task, 0)
    }
}
