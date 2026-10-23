package dev.local.supercardhost

import org.junit.Assert.*
import org.junit.Test

class GesturePolicyTest {
    private val display = GesturePolicy.Display(1000, 2000, 1f)
    private val navigation = GesturePolicy.Bounds(976, 100, 1000, 300)
    private val full = GesturePolicy.Bounds(976, 0, 1000, 2000)
    private val lower = GesturePolicy.Bounds(976, 1333, 1000, 2000)
    private val none = GesturePolicy.EMPTY

    @Test fun everyScreenStateUsesOnlyItsOwnSwitchAndStyle() {
        val styles = mapOf(-1 to none, 0 to none, 1 to full, 2 to navigation, 3 to lower, 99 to none)
        for (total in listOf(false, true)) for (side in listOf(false, true))
            for (valid in listOf(false, true)) for (lock in listOf(false, true)) for (rest in listOf(false, true))
                for ((lockStyle, lockRegion) in styles) for ((restStyle, restRegion) in styles) {
                    val settings = GesturePolicy.Settings(total, if (side) "side_back;side_card" else "side_back;side_none",
                        if (valid) "0;10;100;30" else "broken", lock, lockStyle, rest, restStyle)
                    val available = side && valid
                    val expected = listOf(
                        if (total && available) navigation else none,
                        if (total && lock && (lockStyle != 2 || available)) lockRegion else none,
                        if (total && rest && (restStyle != 2 || available)) restRegion else none
                    )
                    val screens = listOf(GesturePolicy.Screen(false, true), GesturePolicy.Screen(true, true),
                        GesturePolicy.Screen(true, false), GesturePolicy.Screen(false, false))
                    screens.forEachIndexed { index, screen ->
                        val branch = index.coerceAtMost(2)
                        val decision = GesturePolicy.evaluate(settings, screen, display)
                        assertEquals(branch, decision.branch)
                        assertEquals(expected[branch], decision.region)
                        assertEquals(available, decision.navigationEnabled)
                        assertEquals(branch == 1 && !expected[1].empty(), decision.lockEnabled)
                        assertEquals(branch == 2 && !expected[2].empty(), decision.restEnabled)
                        assertEquals(if (screen.interactive) expected[branch] else none, decision.hintRegion(true, false))
                        assertEquals(none, decision.hintRegion(false, false))
                        assertEquals(none, decision.hintRegion(true, true))
                    }
                }
    }
    @Test fun fullAndLowerRegionsRemainAvailableWithoutNavigation() {
        for (style in listOf(1, 3)) {
            val settings = GesturePolicy.Settings(true, "side_none;side_back", "broken", true, style, true, style)
            val result = GesturePolicy.evaluate(settings, GesturePolicy.Screen(true, true), display)
            assertFalse(result.navigationEnabled); assertFalse(result.region.empty())
            assertEquals(if (style == 1) full else lower, result.region)
        }
    }
    @Test fun geometryAndInputChecksAreIndependentOfAndroid() {
        val settings = GesturePolicy.Settings(true, null, "0;0;1000;1", false, 0, false, 0)
        val decision = GesturePolicy.evaluate(settings, GesturePolicy.Screen(false, true), display)
        assertEquals(GesturePolicy.Bounds(976, 0, 1000, 1), decision.region)
        assertTrue(decision.region.contains(976f, 0f)); assertFalse(decision.region.contains(1000f, 0f))
        assertFalse(decision.region.contains(976f, 1f)); assertFalse(decision.region.contains(Float.NaN, 0f))
        assertEquals(null, settings.side); assertEquals(GestureSettingsRules.DEFAULT_SIDE, decision.settings.side)
        for (invalid in listOf(GesturePolicy.Display(0, 100, 1f), GesturePolicy.Display(100, 0, 1f),
            GesturePolicy.Display(100, 100, Float.NaN), GesturePolicy.Display(100, 100, 0f))) {
            try { GesturePolicy.evaluate(settings, GesturePolicy.Screen(false, true), invalid); fail("Invalid display accepted") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(1, GesturePolicy.evaluate(settings.copy(range = "0;0;1;1"),
            GesturePolicy.Screen(false, true), GesturePolicy.Display(1, 1, 5f)).edge)
    }
}
