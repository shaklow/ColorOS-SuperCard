package dev.local.supercardhost

import android.app.KeyguardManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 36])
class CouponPackLifecycleTest {
    @Test fun oldLegacyAndNativeWindowsCannotInvalidateTheCurrentNativeWalletOpening() {
        val app = RuntimeEnvironment.getApplication()
        val keyguard = shadowOf(app.getSystemService(KeyguardManager::class.java))
        keyguard.setIsDeviceLocked(true)
        val first = Any(); val current = Any()
        fun field(name: String): Any? = CouponRefreshCoordinator::class.java.getDeclaredField(name).let { it.isAccessible = true; it.get(null) }
        try {
            CouponRefreshCoordinator.onNativeCardPackShown(app, first)
            val generation = field("generation")
            CouponRefreshCoordinator.onNativeCardPackShown(app, first)
            CouponRefreshCoordinator.onCardPackHidden()
            CouponRefreshCoordinator.onCardPackShown(app)
            assertEquals(generation, field("generation")); assertEquals(true, field("visible"))
            CouponRefreshCoordinator.onNativeCardPackShown(app, current)
            val next = field("generation")
            CouponRefreshCoordinator.onNativeCardPackHidden(first)
            assertEquals(next, field("generation")); assertSame(current, field("nativeOwner"))
            CouponRefreshCoordinator.onNativeCardPackHidden(current)
            assertEquals(false, field("visible")); assertNull(field("nativeOwner"))
        } finally {
            CouponRefreshCoordinator.onNativeCardPackHidden(current)
            CouponRefreshCoordinator.onNativeCardPackHidden(first)
            keyguard.setIsDeviceLocked(false)
        }
    }
}
