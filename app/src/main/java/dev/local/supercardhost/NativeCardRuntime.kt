package dev.local.supercardhost

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/** Native core entry point; ColorOS authentication and display backends stay behind adapters. */
internal object NativeCardRuntime {
    private val main = Handler(Looper.getMainLooper())
    private var application: Application? = null
    private var window: NativeCardHost? = null
    private var unlockGeneration = 0L
    private var registered = false
    private var access: ColorOsCardAccess? = null
    private var authentication: AutoCloseable? = null
    private fun unlocked() = access?.unlocked() == true
    private fun available() = unlocked() && application?.let {
        Settings.Secure.getInt(it.contentResolver, "card_service_total_switch", 1) == 1
    } == true
    @JvmStatic fun install(host: Application) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (registered) return
        require(host.packageName == "com.android.systemui")
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!unlocked() || intent.action == Intent.ACTION_CONFIGURATION_CHANGED) hide()
            }
        }
        val observer = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                unlockGeneration++; authentication?.close(); authentication = null
                try {
                    if (Settings.Secure.getInt(host.contentResolver, "card_service_total_switch", 1) != 1 || !unlocked()) hide()
                    else if (window?.showing == true) {
                        val cards = cards()
                        if (cards.isEmpty()) hide() else window?.show(cards)
                    }
                } catch (error: Exception) { hide(); android.util.Log.w("SuperCardNative", "Cannot refresh cards", error) }
            }
        }
        var receiverRegistered = false
        try {
            host.registerReceiver(receiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_CONFIGURATION_CHANGED)
            }, null, main, Context.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
            for (key in listOf("card_setting_data", "card_service_total_switch"))
                host.contentResolver.registerContentObserver(Settings.Secure.getUriFor(key), false, observer)
            RootDisplayClient.install(host)
            application = host; access = ColorOsCardAccess(host); registered = true
        } catch (error: Throwable) {
            host.contentResolver.unregisterContentObserver(observer)
            if (receiverRegistered) host.unregisterReceiver(receiver)
            throw error
        }
    }
    private fun cards(): List<String> {
        val host = checkNotNull(application)
        val raw = Settings.Secure.getString(host.contentResolver, "card_setting_data")
        if (raw.isNullOrBlank()) return listOf("dhu", "camera")
        return CardManagementState.parse(raw).cards.filter { it.enabled && it.type in listOf("dhu", "camera") }.map { it.type }
    }
    @JvmStatic fun show() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val host = checkNotNull(application) { "新卡包服务尚未连接" }
        check(Settings.Secure.getInt(host.contentResolver, "card_service_total_switch", 1) == 1) { "请先开启超级卡包" }
        check(cards().isNotEmpty()) { "请在卡片管理启用 LENS 或校园卡" }
        PluginRuntime.hide()
        val ticket = ++unlockGeneration
        authentication?.close(); authentication = null
        if (unlocked()) { open(); return }
        window?.hide()
        authentication?.close()
        authentication = checkNotNull(access).requestUnlock(Runnable {
            if (ticket == unlockGeneration) {
                authentication = null
                try { open() } catch (error: Exception) { hide(); android.util.Log.w("SuperCardNative", "Cannot resume cards", error) }
            }
        })
    }
    private fun open() {
        if (!available()) return
        val host = checkNotNull(application)
        val current = window ?: NativeCardHost(host, allowed = ::available).also { window = it }
        current.show(cards())
    }
    @JvmStatic fun hide() { unlockGeneration++; authentication?.close(); authentication = null; window?.hide() }
    @JvmStatic fun isShowing() = window?.showing == true
}
