package dev.local.supercardhost

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Scoped to WeChat's main process. Observes only an authenticated, current virtual-display session. */
internal object WechatCodeHook {
    private val installed = AtomicBoolean()
    private val main = Handler(Looper.getMainLooper())
    private val ports = ConcurrentHashMap<Int, Port>()
    private lateinit var contextField: Field
    private lateinit var imageField: Field
    private lateinit var qrClass: Class<*>
    private lateinit var barClass: Class<*>
    @JvmStatic fun install(module: XposedModule, loader: ClassLoader) {
        if (Application.getProcessName() != "com.tencent.mm" || !installed.compareAndSet(false, true)) return
        try {
            val setter = resolve(loader)
            val base = setter.declaringClass
            contextField = base.declaredFields.single { Context::class.java.isAssignableFrom(it.type) }.apply { isAccessible = true }
            imageField = base.declaredFields.single { ImageView::class.java.isAssignableFrom(it.type) }.apply { isAccessible = true }
            module.hook(Instrumentation::class.java.getDeclaredMethod("callActivityOnCreate", Activity::class.java, Bundle::class.java)).intercept { chain ->
                safely { connect(chain.getArg(0) as Activity) }
                chain.proceed()
            }
            for (name in listOf("callActivityOnResume", "callActivityOnPause", "callActivityOnDestroy")) {
                module.hook(Instrumentation::class.java.getDeclaredMethod(name, Activity::class.java)).intercept { chain ->
                    val activity = chain.getArg(0) as Activity
                    if (name != "callActivityOnResume") safely { ports[activity.display?.displayId ?: -1]?.leave(activity) }
                    val result = chain.proceed()
                    if (name == "callActivityOnResume") safely { ports[activity.display?.displayId ?: -1]?.resume(activity) }
                    result
                }
            }
            module.hook(Activity::class.java.getDeclaredMethod("onWindowFocusChanged", Boolean::class.javaPrimitiveType)).intercept { chain ->
                val result = chain.proceed()
                safely {
                    val activity = chain.thisObject as Activity
                    if (chain.getArg(0) == false) ports[activity.display?.displayId ?: -1]?.leave(activity)
                    else ports[activity.display?.displayId ?: -1]?.resume(activity)
                }
                result
            }
            module.hook(setter).intercept { chain ->
                val result = chain.proceed()
                if (ports.isNotEmpty()) safely {
                    val widget = chain.thisObject
                    val value = chain.getArg(0) as? String
                    if (widget != null && (qrClass.isInstance(widget) || barClass.isInstance(widget))) {
                        val activity = activity(contextField.get(widget) as? Context)
                        val image = imageField.get(widget) as? ImageView
                        if (activity != null && image != null) {
                            val port = ports[activity.display?.displayId ?: -1]
                            val weakImage = WeakReference(image)
                            val weakActivity = WeakReference(activity)
                            val qr = qrClass.isInstance(widget)
                            main.post { if (port != null && ports[port.display] === port) port.capture(weakActivity, weakImage, value, qr) }
                        }
                    }
                }
                result
            }
            android.util.Log.i("SuperCardWechatCode", "Scoped code observer installed")
        } catch (error: Throwable) {
            installed.set(false)
            android.util.Log.w("SuperCardWechatCode", "Code observer unavailable: ${error.javaClass.simpleName}")
        }
    }
    private fun resolve(loader: ClassLoader): Method {
        try {
            qrClass = loader.loadClass("com.tencent.kinda.framework.widget.base.KindaQRCodeViewImpl")
            barClass = loader.loadClass("com.tencent.kinda.framework.widget.base.KindaBarCodeViewImpl")
            return qrClass.superclass.getDeclaredMethod("setCode", String::class.java)
        } catch (_: ReflectiveOperationException) {
            ObfuscationResolver.open(loader).use { dex ->
                fun render(text: String) = dex.uniqueMethod("WeChat code renderer",
                    org.luckypray.dexkit.query.matchers.MethodMatcher.create().usingStrings(text)
                        .returnType("android.graphics.Bitmap").paramTypes("java.lang.String")) { method ->
                    method.returnType == android.graphics.Bitmap::class.java && method.parameterTypes.contentEquals(arrayOf(String::class.java))
                }
                qrClass = render("kinda getBitmap mQRcode == null").declaringClass
                barClass = render("getBarcodeBitmap mBarcode == null").declaringClass
                check(qrClass.superclass == barClass.superclass)
                return qrClass.superclass.declaredMethods.single { it.returnType == Void.TYPE && it.parameterTypes.contentEquals(arrayOf(String::class.java)) }
            }
        }
    }
    private fun activity(value: Context?): Activity? {
        var context = value
        repeat(12) {
            if (context is Activity) return context as Activity
            val next = (context as? ContextWrapper)?.baseContext
            if (next == null || next === context) return null
            context = next
        }
        return null
    }
    private inline fun safely(action: () -> Unit) { try { action() } catch (_: Throwable) { } }
    private fun connect(activity: Activity) {
        val data = activity.intent?.getBundleExtra(WechatCodeWire.EXTRA) ?: return
        android.util.Log.i("SuperCardWechatCode", "Native code port received on display=${activity.display?.displayId}")
        activity.intent.removeExtra(WechatCodeWire.EXTRA)
        val receiver = data.getBinder("port") ?: return
        val nonce = data.getString("nonce") ?: return
        val display = data.getInt("display", -1)
        if (!WechatCodeWire.token(nonce) || display <= 0 || activity.display?.displayId != display) return
        val port = Port(receiver, nonce, display, WechatCodeIdentity.systemUi(activity))
        if (port.connect()) {
            ports.put(display, port)?.close()
            android.util.Log.i("SuperCardWechatCode", "Native code peer authorized display=$display")
        } else android.util.Log.i("SuperCardWechatCode", "Native code peer unavailable display=$display")
    }
    private class Slot(val activity: WeakReference<Activity>, val image: WeakReference<ImageView>, val value: String)
    private class Port(private val receiver: IBinder, private val nonce: String, val display: Int, systemUiUid: Int) {
        private val challenge = WechatCodeWire.nonce()
        @Volatile private var closed = false
        private var current: WeakReference<Activity>? = null
        private var qr: Slot? = null
        private var bar: Slot? = null
        private var sequence = 0L
        private var sent = false
        private var last: String? = null
        private var lastAt = 0L
        private val tick = object : Runnable {
            override fun run() { if (!closed) { safely { publish() }; main.postDelayed(this, 250) } }
        }
        private val death = IBinder.DeathRecipient { main.post { close() } }
        private val control = WechatCodeControl(nonce, display, challenge, systemUiUid) { main.post { close() } }
        fun connect(): Boolean {
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                receiver.linkToDeath(death, 0)
                WechatCodeWire.header(data, nonce, display); data.writeString(challenge); data.writeStrongBinder(control)
                if (!receiver.transact(WechatCodeWire.HELLO, data, reply, 0)) { close(); return false }
                reply.readException()
                if (reply.readInt() != 1 || !control.authorized) { close(); return false }
                main.post(tick); return true
            } catch (_: Exception) { close(); return false }
            finally { data.recycle(); reply.recycle() }
        }
        fun resume(activity: Activity) { if (WechatCodeWire.page(activity.componentName)) current = WeakReference(activity) }
        fun leave(activity: Activity) {
            if (current?.get() === activity) {
                clear(activity); current = null; qr = null; bar = null; last = null
            }
        }
        fun capture(activity: WeakReference<Activity>, image: WeakReference<ImageView>, value: String?, isQr: Boolean) {
            if (closed || !control.authorized) return
            val owner = activity.get() ?: return
            if (!WechatCodeWire.page(owner.componentName) || owner.display?.displayId != display) return
            val slot = if (WechatCodeWire.payment(value)) Slot(activity, image, checkNotNull(value)) else null
            if (isQr) qr = slot else bar = slot
        }
        private fun live(slot: Slot?, owner: Activity): Boolean = slot != null && slot.activity.get() === owner && slot.image.get()?.let {
            it.isAttachedToWindow && it.isShown && it.alpha > 0f && it.drawable != null
        } == true
        private fun publish() {
            val owner = current?.get() ?: return
            val locked = owner.getSystemService(KeyguardManager::class.java)?.let { it.isKeyguardLocked || it.isDeviceLocked } != false
            if (locked || owner.isFinishing || owner.isDestroyed || owner.display?.displayId != display || !owner.hasWindowFocus() || !live(qr, owner) || !live(bar, owner)) {
                clear(owner); return
            }
            val value = qr?.value ?: return
            if (value != bar?.value) { clear(owner); return }
            val now = SystemClock.elapsedRealtime()
            if (sent && value == last && now - lastAt < 5000) return
            if (send(WechatCodeWire.CODE, owner, value)) { sent = true; last = value; lastAt = now }
        }
        private fun clear(owner: Activity) { if (sent) { send(WechatCodeWire.CLEAR, owner, null); sent = false; last = null } }
        private fun send(kind: Int, owner: Activity, value: String?): Boolean {
            if (!control.authorized || closed) return false
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                WechatCodeWire.header(data, nonce, display); data.writeLong(++sequence); data.writeLong(SystemClock.elapsedRealtime())
                data.writeInt(owner.taskId); data.writeString(owner.componentName.className)
                if (kind == WechatCodeWire.CODE) data.writeString(value)
                if (!receiver.transact(kind, data, reply, 0)) { close(); return false }
                reply.readException()
                if (reply.readInt() != 1) { close(); return false }
                return true
            } catch (_: Exception) { close(); return false } finally { data.recycle(); reply.recycle() }
        }
        fun close() {
            if (closed) return
            closed = true; control.close(); current = null; qr = null; bar = null; last = null
            main.removeCallbacks(tick); ports.remove(display, this)
            safely { receiver.unlinkToDeath(death, 0) }
        }
    }
}
