package dev.local.supercardhost

import android.graphics.Rect
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 36], manifest = Config.NONE)
class GestureSettingsRulesTest {
    private fun metrics() = DisplayMetrics().apply { widthPixels = 1000; heightPixels = 2000; density = 1f }
    private fun policy(raw: String?): Rect {
        val type = Class.forName("dev.local.supercardhost.GestureConfigBridge\$Policy")
        val constructor = type.declaredConstructors.single().apply { isAccessible = true }
        val value = constructor.newInstance(true, "side_back;side_card", raw, false, 0, false, 0, false, true, metrics())
        return type.getDeclaredField("navigationRegion").apply { isAccessible = true }.get(value) as Rect
    }
    private fun handle(raw: String?): Rect = GestureHandle::class.java.getDeclaredMethod("navigationRegion", String::class.java, DisplayMetrics::class.java)
        .apply { isAccessible = true }.invoke(null, raw, metrics()) as Rect

    @Test fun defaultsHintAndInputRegionAgreeOnSavedGeometry() {
        val cases = listOf(
            "76;10;100;30" to Rect(976, 100, 1000, 300),
            " 76 ; 10.125 ; 100 ; 30.125 " to Rect(976, 101, 1000, 301),
            "0;0;1000;1" to Rect(976, 0, 1000, 1),
            "0;0;1000;2000" to Rect(976, 0, 1000, 2000),
            "0;0;1000;2001" to Rect(), "-1;10;100;30" to Rect(),
            "100;10;100;30" to Rect(), "0;1;1000;1.01" to Rect(),
            "0;NaN;100;30" to Rect(), "0;1;Infinity;30" to Rect(),
            "0;1;1e40;30" to Rect(), "0;1;100;30;" to Rect(), null to Rect()
        )
        for ((raw, expected) in cases) {
            assertEquals(raw, expected, policy(raw)); assertEquals(raw, expected, handle(raw))
            assertEquals(raw, !expected.isEmpty, HostSettingsValues.validRange(raw, 1000, 2000))
        }
    }

    @Test fun readUsesPhysicalPixelsAndAcceptsShortExistingRangesWithoutWriting() {
        val context = RuntimeEnvironment.getApplication()
        val metrics = DisplayMetrics().also { context.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(it) }
        val raw = "0;1;${metrics.widthPixels};2"
        Settings.Secure.putString(context.contentResolver, GestureSettingsRules.RANGE_KEY, raw)
        Settings.Secure.putString(context.contentResolver, "navigation_gesture_right_side", "side_back;not_side_card")
        val snapshot = CardConfiguration.read(context)
        assertFalse(snapshot.getBoolean("unlocked"))
        val state = FloatSettingsState.read(snapshot)!!
        assertEquals(1f / metrics.heightPixels, state.top, 0f)
        assertEquals(2f / metrics.heightPixels, state.bottom, 0f)
        assertEquals(raw, Settings.Secure.getString(context.contentResolver, GestureSettingsRules.RANGE_KEY))
        val edit = state.range(1, 0)
        assertTrue(GestureSettingsRules.validEditRange(edit.top, edit.bottom))
        assertEquals(GestureSettingsRules.EDIT_MIN_LENGTH, edit.snapshot().getFloat("minimumLength"), 0f)
        assertEquals(state.top, state.visible(false).top, 0f)
        assertEquals(state.bottom, state.opacity(.6f).bottom, 0f)
    }

    @Test fun persistedEditsRoundTripAndRepairOnlyInvalidGeometry() {
        val context = RuntimeEnvironment.getApplication()
        for (height in listOf(1, 320, 2340, 3200)) for (width in listOf(1, 100, 1080)) {
            for ((top, bottom) in listOf(0f to 1f, 0f to .0401f, .9599f to 1f, .76f to .98f)) {
                val raw = GestureSettingsRules.encode(top, bottom, width, height, 3f)
                val pixels = GestureSettingsRules.project(raw, width, height)!!
                assertTrue(pixels.top >= 0 && pixels.bottom <= height && pixels.top < pixels.bottom)
                assertEquals(raw, GestureSettingsRules.encode(pixels.top.toFloat() / height, pixels.bottom.toFloat() / height, width, height, 3f))
            }
        }
        val raw = "0;1;100;2"
        assertEquals(raw, HostSettingsValues.ensureRange(context, raw))
        val repaired = HostSettingsValues.ensureRange(context, "0;NaN;100;30")
        assertNotNull(GestureSettingsRules.parseRange(repaired))
        assertEquals(repaired, Settings.Secure.getString(context.contentResolver, GestureSettingsRules.RANGE_KEY))
    }

    @Test fun invalidFractionsAndOpacityShareTheWriteAndPreviewRules() {
        for (top in listOf(Float.NaN, Float.NEGATIVE_INFINITY, -.01f, 1f))
            assertFalse(GestureSettingsRules.validEditRange(top, 1f))
        for (bottom in listOf(Float.NaN, Float.POSITIVE_INFINITY, -.01f, 1.01f))
            assertFalse(GestureSettingsRules.validEditRange(0f, bottom))
        assertTrue(GestureSettingsRules.validEditRange(.5f, .54f))
        assertFalse(GestureSettingsRules.validEditRange(.5f, .539f))
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, -.01f, 1.01f)) {
            assertFalse(GestureSettingsRules.validOpacity(value))
            try { GestureHandle.setPreview(0f, 1f, value, true); fail("Invalid preview accepted") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(.4f, GestureSettingsRules.opacity(Float.NaN), 0f)
        assertEquals(0f, GestureSettingsRules.opacity(-1), 0f)
        assertEquals(1f, GestureSettingsRules.opacity(101), 0f)
        assertFalse(GestureSettingsRules.sideEnabled("side_back;side_card_extra"))
        assertTrue(GestureSettingsRules.sideEnabled("side_none; side_card "))
        assertTrue(GestureSettingsRules.sideEnabled(""))
        assertEquals("side_music;side_back", GestureSettingsRules.unlocked("side_music;side_card", false))
        assertEquals("side_back;side_card", GestureSettingsRules.unlocked("broken;side_none", true))
    }
}
