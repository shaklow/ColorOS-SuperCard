package dev.local.supercardhost

import android.content.ComponentName
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 36])
class PayTouchAccessTest {
    @Test fun optInOnlyAppliesToTheObservedTargetOnTheActiveDisplay() {
        val page = ComponentName("com.tencent.mm", "PaymentPage")
        fun allowed(display: Int = 7, observed: ComponentName? = page, activity: String = page.className,
                    expected: String? = page.packageName, enabled: Boolean = true) =
            PayTouchAccess.allows(false, display, observed, activity, expected, enabled)
        assertTrue(allowed())
        assertFalse(allowed(display = 0)); assertFalse(allowed(display = -1))
        assertFalse(allowed(observed = null)); assertFalse(allowed(activity = "OtherPage"))
        assertFalse(allowed(expected = "com.eg.android.AlipayGphone")); assertFalse(allowed(expected = null))
        assertFalse(allowed(enabled = false))
    }

    @Test fun originalWhitelistAndAuthenticationExceptionsKeepTheirPrecedence() {
        val authentication = listOf(
            "com.tencent.mm.plugin.walletlock.gesture.ui.GestureGuardLogicUI",
            "com.tencent.mm.plugin.walletlock.fingerprint.ui.FingerprintWalletLockUI",
            "com.tencent.mm.framework.app.UIPageFragmentActivity",
            "com.alipay.mobile.security.gesture.ui.GestureLiteActivity_"
        )
        for (activity in authentication) {
            val page = ComponentName(if (activity.startsWith("com.alipay")) "com.eg.android.AlipayGphone" else "com.tencent.mm", activity)
            assertFalse(PayTouchAccess.allows(false, 7, page, activity, page.packageName, true))
            assertTrue(PayTouchAccess.allows(true, 7, page, activity, page.packageName, true))
        }
        assertTrue(PayTouchAccess.allows(true, -1, null, null, null, false))
    }
}
