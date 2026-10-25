package dev.local.supercardhost

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 36])
class WechatCodeIdentityTest {
    @Suppress("DEPRECATION") private fun install(name: String, uid: Int, key: String) {
        val info = PackageInfo().apply {
            packageName = name; applicationInfo = ApplicationInfo().apply { packageName = name; this.uid = uid }
            signatures = arrayOf(Signature(key.toByteArray()))
        }
        shadowOf(RuntimeEnvironment.getApplication().packageManager).installPackage(info)
    }
    @Test fun resolvesTheActualSystemUiUidAndRefusesAnUntrustedSignatureOrOtherUser() {
        val context = RuntimeEnvironment.getApplication()
        install("android", 1000, "platform-fixture")
        install("com.android.systemui", 10242, "platform-fixture")
        assertEquals(10242, WechatCodeIdentity.systemUi(context))
        install("com.android.systemui", 10242, "foreign-fixture")
        try { WechatCodeIdentity.systemUi(context); fail("Untrusted SystemUI signature accepted") } catch (_: IllegalStateException) { }
        install("com.android.systemui", 110242, "platform-fixture")
        try { WechatCodeIdentity.systemUi(context); fail("Different user's SystemUI accepted") } catch (_: IllegalStateException) { }
    }
}
