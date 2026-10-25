package dev.local.supercardhost

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/** SystemUI-side, launch-scoped receiver. The peer proves SystemUI identity by a reverse Binder call. */
internal class WechatCodeChannel(private val context: Context, private val function: String, private val user: Int,
    private val listener: Listener) : Binder(), AutoCloseable {
    interface Listener {
        fun connected()
        fun code(value: Code)
        fun cleared()
    }
    class Code(val value: String, val page: ComponentName, val task: Int, val sequence: Long, val timestamp: Long)
    private val main = Handler(Looper.getMainLooper())
    private val nonce = WechatCodeWire.nonce()
    private val order = WechatCodeOrder()
    private var display = -1
    private var uid = -1
    private var control: IBinder? = null
    private var hello = false
    private val death = IBinder.DeathRecipient { main.post { close(); listener.cleared() } }
    val connected get() = synchronized(this) { hello && !order.closed }
    fun bind(display: Int) {
        require(display > 0 && WechatCodeWire.function(function) && user >= 0)
        check(Process.myUid() == Process.SYSTEM_UID) { "Native code receiver requires SystemUI" }
        val resolved = PayUserSettings.forUser(context, user).packageManager.getApplicationInfo("com.tencent.mm", 0).uid
        check(resolved / 100000 == user && resolved % 100000 >= 10000)
        synchronized(this) { check(!order.closed && this.display < 0); this.display = display; uid = resolved }
        Registry.add(display, this)
    }
    private fun locked() = context.getSystemService(KeyguardManager::class.java)?.let { it.isKeyguardLocked || it.isDeviceLocked } != false
    private fun packet() = Bundle().apply { putBinder("port", this@WechatCodeChannel); putString("nonce", nonce); putInt("display", display) }
    @Synchronized override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == INTERFACE_TRANSACTION) { reply?.writeString(WechatCodeWire.DESCRIPTOR); return true }
        if (code !in WechatCodeWire.HELLO..WechatCodeWire.CLEAR) return super.onTransact(code, data, reply, flags)
        var accepted = false
        try {
            if (order.closed || display <= 0 || Binder.getCallingUid() != uid || locked() || !WechatCodeWire.check(data, nonce, display)) return answer(reply, false)
            if (code == WechatCodeWire.HELLO) {
                val challenge = data.readString()
                val peer = data.readStrongBinder()
                if (!hello && WechatCodeWire.token(challenge) && peer != null && data.dataAvail() == 0) {
                    val auth = Parcel.obtain(); val result = Parcel.obtain()
                    try {
                        WechatCodeWire.header(auth, nonce, display); auth.writeString(challenge)
                        if (peer.transact(WechatCodeWire.AUTHORIZE, auth, result, 0)) {
                            result.readException()
                            if (result.readInt() == 1) {
                                peer.linkToDeath(death, 0); control = peer; hello = true; accepted = true
                                main.post { if (connected) listener.connected() }
                            }
                        }
                    } finally { auth.recycle(); result.recycle() }
                }
            } else if (hello) {
                val sequence = data.readLong(); val timestamp = data.readLong()
                val task = data.readInt(); val name = data.readString()
                val value = if (code == WechatCodeWire.CODE) data.readString() else null
                val page = name?.let { ComponentName("com.tencent.mm", it) }
                if (data.dataAvail() == 0 && task > 0 && WechatCodeWire.page(page) &&
                    (code == WechatCodeWire.CLEAR || WechatCodeWire.payment(value)) && order.accept(sequence, timestamp, SystemClock.elapsedRealtime())) {
                    accepted = true
                    main.post {
                        val current = synchronized(this) { !order.closed && order.sequence == sequence }
                        if (current && !locked()) {
                            if (value != null) listener.code(Code(value, checkNotNull(page), task, sequence, timestamp)) else listener.cleared()
                        }
                    }
                }
            }
        } catch (_: Exception) { /* A malformed or dead peer cannot affect the original payment UI. */ }
        return answer(reply, accepted)
    }
    private fun answer(reply: Parcel?, accepted: Boolean): Boolean { reply?.writeNoException(); reply?.writeInt(if (accepted) 1 else 0); return true }
    fun current(value: Code) = synchronized(this) { !order.closed && order.sequence == value.sequence && !locked() }
    override fun close() {
        val peer: IBinder?
        synchronized(this) { if (order.closed) return; order.close(); hello = false; peer = control; control = null }
        Registry.remove(display, this)
        main.removeCallbacksAndMessages(null)
        if (peer != null) {
            try { peer.unlinkToDeath(death, 0) } catch (_: Exception) { }
            val data = Parcel.obtain()
            try { WechatCodeWire.header(data, nonce, display); peer.transact(WechatCodeWire.STOP, data, null, IBinder.FLAG_ONEWAY) }
            catch (_: Exception) { } finally { data.recycle() }
        }
    }
    private object Registry {
        private val channels = ConcurrentHashMap<Int, WechatCodeChannel>()
        fun add(display: Int, channel: WechatCodeChannel) {
            PayUserRouting.setLaunchDecorator { intent, app, user, id ->
                val owner = channels[id]
                if (owner != null && app == PayUserSettings.WECHAT && owner.user == user && !owner.order.closed)
                    intent.putExtra(WechatCodeWire.EXTRA, owner.packet())
            }
            channels.put(display, channel)?.close()
        }
        fun remove(display: Int, channel: WechatCodeChannel) { channels.remove(display, channel) }
    }
}
