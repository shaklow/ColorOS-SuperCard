package dev.local.supercardhost

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 36], manifest = Config.NONE)
class NativePayConfigurationTest {
    @Test fun usesSavedOrderSupportAndOriginalIconNamesWithoutImportingLaunchData() {
        val raw = """{"cards":[{"type":"pay","enable":true,"skinName":"keep","functions":[
            {"type":"alipay_ride","enable":true,"iconResId":"ic_alipay_ride_normal","info":"untrusted intent"},
            {"type":"wx_pay","enable":false},{"type":"wx2_pay","enable":true},
            {"type":"vivo_pay","enable":true},{"type":"wx_scan","enable":true,"support":false},
            {"type":"unknown","enable":true},{"type":"alipay_ride","enable":true}]}]}"""
        val choices = NativePayConfiguration.parse(raw)
        assertEquals(listOf("alipay_ride", "wx2_pay"), choices.map { it.type })
        assertEquals("ic_alipay_ride_normal", choices[0].icon)
        assertEquals("微信分身付款码", choices[1].title)
        assertFalse(NativePayConfiguration.supported(raw))
        assertTrue(raw.contains("untrusted intent"))
    }
    @Test fun missingSelectionsStayEmptyAndMalformedJsonDoesNotInventFunctions() {
        for (raw in listOf(null, "", "{\"cards\":[{\"type\":\"camera\"}]}")) assertTrue(NativePayConfiguration.parse(raw).isEmpty())
        try { NativePayConfiguration.parse("broken"); fail("Malformed configuration must be reported") } catch (_: org.json.JSONException) {}
    }
    @Test fun onlyUnsupportedEnabledFunctionsKeepAutomaticRoutingOnTheLegacyDeck() {
        assertTrue(NativePayConfiguration.supported("""{"cards":[{"type":"pay","functions":[{"type":"wx_pay","enable":true},{"type":"future","enable":false}]}]}"""))
        assertTrue(NativePayConfiguration.supported("""{"cards":[{"type":"pay","functions":[{"type":"future","enable":true,"support":false}]}]}"""))
        assertFalse(NativePayConfiguration.supported("broken"))
    }
    @Test fun viewportUsesOriginalPhysicalWidthAndDensity() {
        assertEquals(NativePayGeometry.Size(1440, 1931, 640), NativePayGeometry.measure(984, 1320, 1440, 640, false))
        val enlarged = NativePayGeometry.measure(984, 1320, 1440, 640, true)
        assertEquals(1584, enlarged.width)
        assertEquals(1320L * 1584 / 984, enlarged.height.toLong())
        assertEquals(640, enlarged.density)
    }
}
