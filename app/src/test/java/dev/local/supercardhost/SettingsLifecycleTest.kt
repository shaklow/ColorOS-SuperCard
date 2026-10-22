package dev.local.supercardhost

import android.app.Activity
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The host lifecycle must also work when the private archive is unavailable. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 36], manifest = Config.NONE)
class SettingsLifecycleTest {
    @Test fun launcherCompletesItsFirstLifecycle() = launch(HostActivity::class.java)
    @Test fun runtimeSettingsCompletesItsFirstLifecycle() = launch(HostToolsActivity::class.java)
    @Test fun campusSettingsCompletesItsFirstLifecycle() = launch(DhuPaySettingsActivity::class.java)

    @Test fun cachedShortcutRedirectCompletesItsFirstLifecycle() = launch(NativeCardsActivity::class.java)

    private fun <T : Activity> launch(type: Class<T>) {
        val controller = Robolectric.buildActivity(type).setup()
        assertNotNull(controller.get().window.decorView)
        controller.pause().stop().destroy()
    }
}
