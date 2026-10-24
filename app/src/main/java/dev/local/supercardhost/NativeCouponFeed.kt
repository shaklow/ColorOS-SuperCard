package dev.local.supercardhost

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import java.lang.ref.WeakReference

internal interface NativeCouponFeed : NativeCouponSession.Store {
    fun state(): String
    fun failure(): String
    fun subscribe(owner: Any, changed: Runnable)
    fun unsubscribe(owner: Any)
}

/** Shares the existing wallet IPC, refresh generations and local-only hidden preferences. */
internal class WalletNativeCouponFeed(private val app: Application) : NativeCouponFeed {
    private val hidden = app.getSharedPreferences("supercard_wallet_hidden_coupons", Context.MODE_PRIVATE)
    private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    override fun snapshot() = CouponRefreshCoordinator.snapshot(app)
    override fun current(account: String) = CouponRefreshCoordinator.isAccountCurrent(app, account)
    override fun hidden(key: String) = hidden.getBoolean(key, false)
    override fun remove(key: String) = hidden.edit().putBoolean(key, true).commit()
    override fun state() = CouponRefreshCoordinator.state(app)
    override fun failure() = CouponRefreshCoordinator.failureReason(app)
    override fun subscribe(owner: Any, changed: Runnable) {
        val weak = WeakReference(changed)
        CouponRefreshCoordinator.subscribe(owner, Runnable { weak.get()?.run() })
        preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> weak.get()?.run() }
            .also(hidden::registerOnSharedPreferenceChangeListener)
    }
    override fun unsubscribe(owner: Any) {
        CouponRefreshCoordinator.unsubscribe(owner)
        preferenceListener?.let(hidden::unregisterOnSharedPreferenceChangeListener); preferenceListener = null
    }
}
