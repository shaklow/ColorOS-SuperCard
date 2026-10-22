package dev.local.supercardhost

import android.app.Application
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 36])
class LensCameraIntentTest {
    @Test fun everyModeKeepsItsNativeExtrasWithoutClearingMainCameraTask() {
        val context = RuntimeEnvironment.getApplication<Application>()
        for (mode in ColorOsLensModes.query(context)) {
            val intent = ColorOsLensModes.embeddedIntent(context, mode.type())
            assertEquals("com.oplus.camera/com.oplus.camera.Camera", intent.component!!.flattenToString())
            assertEquals(mode.cameraMode(), intent.getIntExtra("mode", -1))
            assertEquals(mode.front(), intent.getBooleanExtra("front", false))
            assertEquals(!mode.front(), intent.getBooleanExtra("rear", false))
            assertEquals(0, intent.flags and (Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_TASK_ON_HOME))
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK != 0)
            assertEquals(if (mode.type() == "high_piexl") "android.media.action.STILL_IMAGE_CAMERA"
                else "com.oplus.camera.action.SHORTCUT_TYPE_MENU", intent.action)
        }
    }

    @Test fun unknownModeCannotBecomeAnArbitraryActivity() {
        try { ColorOsLensModes.embeddedIntent(RuntimeEnvironment.getApplication(), "arbitrary"); fail("unknown mode") }
        catch (_: IllegalArgumentException) {}
    }
}
