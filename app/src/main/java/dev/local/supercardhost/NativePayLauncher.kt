package dev.local.supercardhost

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import java.lang.reflect.Method

/** Only the existing SDK/Intent contracts cross the OEM boundary; no card or view manager. */
internal object NativePayLauncher {
    private class Protocol(val sdk: Any, val launch: Method, val builder: Any, val build: Method,
        val defaultDensity: Method)
    @Volatile private var protocol: Protocol? = null
    val ready get() = protocol != null
    @JvmStatic fun install(host: Context, loader: ClassLoader) {
        val sdk = loader.loadClass("com.vivo.card.cards.cardpay.virtual.PayCardSDKLauncher")
        val builder = loader.loadClass("com.vivo.card.cards.cardpay.virtual.PayCardIntentBuilder")
        val utility = loader.loadClass("com.vivo.card.utils.CardUtil")
        protocol = Protocol(sdk.getField("INSTANCE").get(null)!!,
            sdk.getMethod("launchWeChatPay", Context::class.java, String::class.java),
            builder.getField("INSTANCE").get(null)!!,
            builder.getMethod("buildPaymentIntent", Context::class.java, String::class.java),
            utility.getMethod("getDefaultDisplayDensity"))
        NativeCardRuntime.paymentReady()
    }
    fun enlarged(context: Context, function: String): Boolean {
        if (function != "wx_pay" && function != "wx2_pay") return false
        val original = checkNotNull(protocol) { "支付服务尚未连接" }
        val default = (original.defaultDensity.invoke(null) as Number).toInt()
        return default > 0 && android.content.res.Resources.getSystem().displayMetrics.densityDpi > default
    }
    fun launch(context: Context, function: String, user: Int, display: Int, retry: Boolean = false) {
        require(display >= 0)
        val original = checkNotNull(protocol) { "支付服务尚未连接，请重新载入卡包" }
        val app = checkNotNull(PayUserSettings.appForFunction(function))
        check(PayUserSettings.installed(context, PayUserSettings.packageName(app), user)) { "所选支付账号已不可用" }
        if (app == PayUserSettings.WECHAT) {
            check(original.launch.invoke(original.sdk, PayUserRouting.sdkContext(context, user, display), function) == true) {
                "微信页面未能打开，请重试"
            }
        } else {
            val source = original.build.invoke(original.builder, context, function) as? Intent
                ?: error("支付入口暂不可用")
            val intent = Intent(source).setFlags(if (retry) 872415232 else 807403520)
            PayUserRouting.startEmbedded(context, intent, ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle(), app, user, display)
        }
    }
}
