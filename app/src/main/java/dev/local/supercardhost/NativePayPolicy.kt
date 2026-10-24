package dev.local.supercardhost

import android.content.ComponentName

/** OEM page rules, scoped to the selected app; authentication never accepts embedded input. */
internal object NativePayPolicy {
    private val authentication = setOf(
        "com.tencent.mm.plugin.walletlock.gesture.ui.GestureGuardLogicUI",
        "com.tencent.mm.plugin.walletlock.fingerprint.ui.FingerprintWalletLockUI",
        "com.alipay.mobile.security.gesture.ui.GestureLiteActivity_"
    )
    private val permissions = setOf("com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.android.packageinstaller")
    fun target(function: String): String = when (PayUserSettings.appForFunction(function)) {
        PayUserSettings.WECHAT -> "com.tencent.mm"
        PayUserSettings.ALIPAY -> "com.eg.android.AlipayGphone"
        else -> error("不支持的支付功能")
    }
    fun authentication(component: ComponentName) = component.className in authentication ||
        (component.packageName in permissions && component.className.endsWith(".GrantPermissionsActivity"))
    fun accepts(function: String, component: ComponentName) = component.packageName == target(function) ||
        (component.packageName in permissions && authentication(component))
    fun touch(function: String, component: ComponentName?, enabled: Boolean): Boolean {
        if (component == null || component.packageName != target(function) || authentication(component)) return false
        if (component.className == "com.tencent.mm.framework.app.UIPageFragmentActivity") return false
        val original = component.className.contains("com.tencent.mm.plugin.appbrand.ui.AppBrandUI") ||
            component.className.contains("com.alipay.android.phone.xriver.bundlex.CSGAPushActivity")
        return original || enabled
    }
    fun scan(function: String) = function.endsWith("_scan")
    fun scanPage(component: ComponentName) = component.className in setOf(
        "com.tencent.mm.plugin.scanner.ui.BaseScanUI", "com.alipay.mobile.scan.as.main.MainCaptureActivity")
    fun reached(component: ComponentName?) = component != null && (authentication(component) || scanPage(component) ||
        component.className in setOf("com.tencent.mm.plugin.offline.ui.WalletOfflineCoinPurseUI",
            "com.tencent.mm.framework.app.UIPageFragmentActivity",
            "com.alipay.mobile.onsitepay.merge.OnsitepayActivity", "com.alipay.mobile.onsitepay.merge.OnsitepayActivityStandard",
            "com.alipay.android.phone.wallet.aptrip.ui.ApTripActivity") ||
        component.className.startsWith("com.tencent.mm.plugin.appbrand.ui.AppBrandUI") ||
        component.className.startsWith("com.alipay.mobile.nebulax.xriver.activity.XRiverActivity"))
    fun fullscreenDelay(component: ComponentName) = if (component.className.startsWith("com.tencent.mm.plugin.appbrand.ui.AppBrandUI") ||
        component.className.startsWith("com.alipay.mobile.nebulax.xriver.activity.XRiverActivity")) 1000L else 300L
}

/** CONTAINER mode renders at the physical screen width, keeping the viewport's aspect ratio. */
internal object NativePayGeometry {
    data class Size(val width: Int, val height: Int, val density: Int)
    fun measure(viewWidth: Int, viewHeight: Int, screenWidth: Int, density: Int, enlarged: Boolean): Size {
        require(viewWidth > 0 && viewHeight > 0 && screenWidth > 0 && density > 0)
        val width = if (enlarged) (screenWidth * 1.1f).toInt() else screenWidth
        return Size(width, (viewHeight.toLong() * width / viewWidth).toInt().coerceAtLeast(1), density)
    }
}
