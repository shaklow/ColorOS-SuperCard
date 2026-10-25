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
import android.app.KeyguardManager
import android.os.PowerManager
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics

/** Native core entry point; ColorOS authentication and display backends stay behind adapters. */
internal object NativeCardRuntime {
    private val main = Handler(Looper.getMainLooper())
    private var application: Application? = null
    private var window: NativeCardHost? = null
    private var unlockGeneration = 0L
    private var registered = false
    private var access: ColorOsCardAccess? = null
    private var authentication: AutoCloseable? = null
    private var input: SystemGestureInput? = null
    private var edgeActive = false
    private enum class Mode { AUTO, NATIVE, LEGACY }
    private var mode = Mode.AUTO
    @Volatile private var inputEnabled = false
    private var warmQueued = false
    private fun warmSurface() {
        if (warmQueued) return
        warmQueued = true
        main.looper.queue.addIdleHandler {
            warmQueued = false
            val host = application
            if (registered && host != null && input != null && window == null &&
                host.getSystemService(PowerManager::class.java)?.isInteractive == true) try {
                window = NativeCardHost(host, allowed = ::available).also { it.warm(cards()) }
            } catch (error: Throwable) {
                mode = Mode.LEGACY; inputEnabled = false
                android.util.Log.w("SuperCardNative", "Cannot warm card surface", error)
            }
            false
        }
    }
    @JvmStatic fun ownsGesture() = inputEnabled
    @JvmStatic fun paymentReady() { main.post { if (registered) { syncRouting(); warmSurface() } } }
    private fun policy(): GesturePolicy.Decision {
        val host = checkNotNull(application)
        val metrics = DisplayMetrics()
        checkNotNull(host.getSystemService(DisplayManager::class.java)?.getDisplay(0)).getRealMetrics(metrics)
        return GesturePolicyAdapter.read(host, metrics,
            host.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false,
            host.getSystemService(PowerManager::class.java)?.isInteractive == true)
    }
    private fun syncRouting() {
        val host = application ?: return
        val raw = Settings.Secure.getString(host.contentResolver, "card_setting_data")
        val enabled = if (raw.isNullOrBlank()) emptyList() else CardManagementState.parse(raw).cards.filter { it.enabled }
        val payMigrated = NativePayLauncher.ready && NativePayConfiguration.supported(raw)
        val allMigrated = enabled.isNotEmpty() && enabled.all {
            it.type == "dhu" || it.type == "camera" || (it.type == "pay" && payMigrated)
        }
        val next = input != null && cards().isNotEmpty() && mode != Mode.LEGACY && (mode == Mode.NATIVE || allMigrated) &&
            Settings.Secure.getInt(host.contentResolver, "card_service_total_switch", 1) == 1 &&
            host.getSystemService(PowerManager::class.java)?.isInteractive == true
        val previous = inputEnabled
        inputEnabled = next
        if (next && !previous) PluginRuntime.hide()
        if (!next && previous) hide()
    }
    @JvmStatic fun useLegacy() {
        inputEnabled = false
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { useLegacy() }; return }
        mode = Mode.LEGACY; hide()
    }
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
                syncRouting()
                if (!unlocked() || intent.action == Intent.ACTION_CONFIGURATION_CHANGED) hide()
                if (intent.action == Intent.ACTION_CONFIGURATION_CHANGED) {
                    window?.close(); window = null
                }
                warmSurface()
            }
        }
        val observer = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                unlockGeneration++; authentication?.close(); authentication = null
                try {
                    input?.cancel(); syncRouting()
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
            try {
                input = SystemGestureInput(host, object : SystemGestureInput.Events {
                    override fun enabled() = inputEnabled
                    override fun visible() = isShowing()
                    override fun policy() = NativeCardRuntime.policy()
                    override fun begin() {
                        unlockGeneration++; authentication?.close(); authentication = null
                        edgeActive = true
                        if (unlocked()) {
                            PluginRuntime.hide()
                            val current = window ?: NativeCardHost(host, allowed = ::available).also { window = it }
                            current.beginEdge(cards())
                        }
                    }
                    override fun drag(distance: Float) { if (edgeActive) window?.followEdge(distance) }
                    override fun end(commit: Boolean, velocityX: Float) {
                        if (!edgeActive) return
                        edgeActive = false
                        if (commit && !unlocked()) show() else window?.endEdge(commit, velocityX)
                    }
                })
            } catch (error: Throwable) { android.util.Log.e("SuperCardNativeInput", "Native gesture monitor unavailable", error) }
            syncRouting()
            warmSurface()
        } catch (error: Throwable) {
            inputEnabled = false
            try { input?.close() } catch (cleanup: Throwable) { android.util.Log.w("SuperCardNativeInput", "Cannot roll back monitor", cleanup) }
            input = null; application = null; access = null; registered = false
            host.contentResolver.unregisterContentObserver(observer)
            if (receiverRegistered) host.unregisterReceiver(receiver)
            throw error
        }
    }
    private fun cards(): List<String> {
        val host = checkNotNull(application)
        val raw = Settings.Secure.getString(host.contentResolver, "card_setting_data")
        if (raw.isNullOrBlank()) return listOf("pay", "dhu", "camera")
        return CardManagementState.parse(raw).cards.filter { it.enabled && it.type in listOf("pay", "dhu", "camera") }.map { it.type }
    }
    @JvmStatic fun show() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val host = checkNotNull(application) { "新卡包服务尚未连接" }
        check(Settings.Secure.getInt(host.contentResolver, "card_service_total_switch", 1) == 1) { "请先开启超级卡包" }
        check(cards().isNotEmpty()) { "请在卡片管理启用支付卡、LENS 或校园卡" }
        check(input != null) { "系统滑动手势暂不可用，请重新载入卡包" }
        mode = Mode.NATIVE; syncRouting()
        PluginRuntime.hide()
        val ticket = ++unlockGeneration
        authentication?.close(); authentication = null
        if (unlocked()) { open(); return }
        window?.hide()
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
    @JvmStatic fun hide() {
        edgeActive = false; input?.cancel()
        unlockGeneration++; authentication?.close(); authentication = null; window?.hide()
    }
    @JvmStatic fun isShowing() = window?.showing == true
}
