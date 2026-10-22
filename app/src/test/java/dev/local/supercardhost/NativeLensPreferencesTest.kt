package dev.local.supercardhost

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 36], manifest = Config.NONE)
class NativeLensPreferencesTest {
    @Test fun emptySelectionIsPreservedAndOriginalEngineConfigurationIsUntouched() {
        val context = RuntimeEnvironment.getApplication()
        android.provider.Settings.Secure.putString(context.contentResolver, "card_setting_data", "original settings")
        assertTrue(NativeLensPreferences.save(context, emptySet()))
        assertTrue(NativeLensPreferences.selected(context).isEmpty())
        assertEquals("original settings", android.provider.Settings.Secure.getString(context.contentResolver, "card_setting_data"))
    }
    @Test fun invalidModesCannotBeSaved() {
        val context = RuntimeEnvironment.getApplication()
        val before = NativeLensPreferences.selected(context)
        assertThrows(IllegalArgumentException::class.java) { NativeLensPreferences.save(context, setOf("unknown")) }
        assertEquals(before, NativeLensPreferences.selected(context))
    }
}
