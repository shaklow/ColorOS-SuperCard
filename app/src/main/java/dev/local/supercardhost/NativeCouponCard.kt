package dev.local.supercardhost

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import java.lang.ref.WeakReference
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/** One native card owns selection, image work and subscriptions, while wallet refresh owns its data. */
internal class NativeCouponCard(context: Context, private val owner: CardWindowOwner,
    private val feed: NativeCouponFeed = WalletNativeCouponFeed(context.applicationContext as Application)) : FrameLayout(context), AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val session = NativeCouponSession(feed)
    private val surface = MaterialUiArchive.create(context, MaterialUiArchive.Operation.COUPON) as View
    private val image = checkNotNull(surface.findViewWithTag<ImageView>("coupon_qr"))
    private val hint = checkNotNull(surface.findViewWithTag<TextView>("coupon_hint"))
    private val progress = checkNotNull(surface.findViewWithTag<View>("coupon_code_loading"))
    private var subscribed = false
    private var closed = false
    private var ticket = 0L
    private var work: Future<*>? = null
    private var bound: Pair<String?, NativeCouponData.Item>? = null
    private val changed = Runnable { if (!closed && isAttachedToWindow) refresh() }
    init {
        addView(surface, LayoutParams(-1, -1))
        MaterialUiArchive.update(surface, MaterialUiArchive.Operation.BIND_COUPON, Consumer<Bundle> { event ->
            if (!session.active || closed || locked()) return@Consumer
            when (event.getString("action")) {
                "select" -> if (session.select(event.getString("key", ""))) render()
                "back" -> { session.back(); render() }
                "manage" -> open(WalletCouponClient.managementIntent(context))
                "open" -> session.current(event.getString("key"))?.let { open(NativeCouponActions.source(context, it)) }
                "original" -> session.current(event.getString("key"))?.let { open(NativeCouponActions.original(it)) }
                "remove" -> if (event.getString("key") == session.selected) {
                    if (session.removeSelected()) { render(); toast("已从卡包移除，钱包原券仍保留") }
                    else { refresh(); toast("未能移除此券，请稍后重试") }
                }
            }
        })
        render()
    }
    private fun locked() = context.getSystemService(KeyguardManager::class.java)?.let { it.isKeyguardLocked || it.isDeviceLocked } != false
    private fun refresh() {
        try { session.refresh(); render() }
        catch (error: Exception) {
            clearCode(); session.invalidate()
            Log.w("SuperCardNativeCoupon", "Coupon projection unavailable: ${error.javaClass.simpleName}")
            render()
        }
    }
    private fun render() {
        val state = feed.state()
        val reason = feed.failure()
        val loading = state in setOf("idle", "loading", "updating_codes")
        val title = when (state) {
            "locked" -> "解锁后查看券码"
            "ready" -> "暂无可显示的券码"
            "partial" -> "部分券暂不支持"
            "unavailable", "codes_unavailable" -> WalletConnectionStatus.title(reason)
            else -> "正在更新券码"
        }
        val description = when (state) {
            "locked" -> "解锁后会从钱包更新当前账号的券码"
            "ready" -> "券码来自当前账号的钱包；从卡包移除后，钱包原券仍保留"
            "partial" -> "暂未接入的券可在钱包券管理中查看"
            "unavailable", "codes_unavailable" -> WalletConnectionStatus.description(reason)
            else -> "正在从钱包读取当前账号的券码"
        }
        MaterialUiArchive.update(surface, MaterialUiArchive.Operation.RENDER_COUPON, Bundle().apply {
            putParcelableArrayList("items", ArrayList(session.items.map { row -> Bundle().apply {
                putString("key", row.key); putString("title", row.title); putString("category", row.category)
                putString("merchant", row.merchant); putString("time", row.time); putString("status", row.status)
                putBoolean("usable", row.usable); putString("hint", row.hint(state))
                putBoolean("code", row.usable && !row.douyin && (row.qr.isNotEmpty() || row.image.isNotEmpty() || row.imageUrl.isNotEmpty()))
                putBoolean("original", NativeCouponActions.original(row) != null)
            } }))
            putString("selected", session.selected); putString("title", title); putString("description", description)
            putBoolean("loading", loading)
            putString("notice", if (session.items.isEmpty() || state == "ready") "" else if (state == "updating_codes")
                "正在补全券码，已读取的券仍可查看" else if (state == "codes_unavailable") "部分二维码暂不可用，可在钱包查看" else if (state == "partial") "部分券暂未接入，可在钱包查看" else "")
        })
        bindCode()
    }
    private fun bindCode() {
        val row = session.current()
        val next = row?.let { session.account to it }
        if (next == bound) return
        clearCode()
        bound = next
        if (row == null || !row.usable || row.douyin || !isAttachedToWindow) return
        if (row.image.isEmpty() && row.qr.isEmpty() && row.imageUrl.isEmpty()) return
        val token = ticket
        progress.visibility = View.VISIBLE
        if (row.image.isEmpty() && row.imageUrl.isNotEmpty()) {
            val weak = WeakReference(this)
            CouponImageLoader.bind(image, "${session.account}:${row.key}", row.imageUrl, hint, Runnable {
                weak.get()?.let { if (it.live(token, row)) it.progress.visibility = View.GONE }
            })
        } else try {
            work = WORK.submit {
                try {
                    val bitmap = NativeCouponCodes.decode(row)
                    main.post {
                        if (!live(token, row)) { bitmap?.recycle(); return@post }
                        progress.visibility = View.GONE
                        image.setImageBitmap(bitmap)
                        hint.text = if (bitmap != null) row.hint else "券码暂不可用，可打开钱包查看"
                    }
                } catch (_: Exception) {
                    main.post { if (live(token, row)) { progress.visibility = View.GONE; hint.text = "券码暂不可用，可打开钱包查看" } }
                }
            }
        } catch (_: RuntimeException) { progress.visibility = View.GONE; hint.text = "券码暂不可用，可打开钱包查看" }
    }
    private fun live(token: Long, row: NativeCouponData.Item) = !closed && token == ticket && isAttachedToWindow && session.current() == row
    private fun clearCode() {
        ticket++; work?.cancel(true); work = null; WORK.purge()
        CouponImageLoader.clear(image); progress.visibility = View.GONE; bound = null
    }
    private fun open(intent: Intent?) {
        if (!session.active || locked()) return
        if (intent == null) { toast("钱包入口暂不可用，请打开钱包查看"); return }
        try { context.startActivity(intent); owner.hideCard() }
        catch (_: Exception) { toast("钱包入口暂不可用，请打开钱包查看") }
    }
    private fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    fun setActive(value: Boolean) {
        if (closed || session.active == value) return
        session.setActive(value)
        if (value) refresh() else clearCode()
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!subscribed && !closed) { feed.subscribe(this, changed); subscribed = true; refresh() }
    }
    override fun onDetachedFromWindow() { try { close() } finally { super.onDetachedFromWindow() } }
    override fun close() {
        if (closed) return
        closed = true
        try { if (subscribed) feed.unsubscribe(this) }
        finally { subscribed = false; session.close(); clearCode(); main.removeCallbacksAndMessages(null) }
    }
    companion object {
        private val WORK = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(2),
            { action -> Thread(action, "SuperCardCouponCode").apply { isDaemon = true } })
    }
}
