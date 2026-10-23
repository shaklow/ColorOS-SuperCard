package dev.local.supercardhost

import android.app.Activity
import android.content.Intent
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
    @Test fun cardManagementCompletesItsFirstLifecycle() = launch(CardManagementActivity::class.java)

    @Test fun functionEditorsCompleteTheirLifecycleWithMissingArchive() {
        for (type in listOf("pay", "camera")) {
            val controller = Robolectric.buildActivity(CardFunctionSettingsActivity::class.java, Intent().putExtra("mCardType", type)).setup()
            assertNotNull(controller.get().window.decorView)
            controller.pause().stop().destroy()
        }
    }

    @Test fun cachedShortcutRedirectCompletesItsFirstLifecycle() = launch(NativeCardsActivity::class.java)

    private fun <T : Activity> launch(type: Class<T>) {
        val controller = Robolectric.buildActivity(type).setup()
        assertNotNull(controller.get().window.decorView)
        controller.pause().stop().destroy()
    }
}
