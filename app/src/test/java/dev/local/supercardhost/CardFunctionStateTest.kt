package dev.local.supercardhost

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 36], manifest = Config.NONE)
class CardFunctionStateTest {
    private fun snapshot(type: String = "pay", enabled: Int = 4): CardManagementState.Snapshot {
        val types = if (type == "pay") listOf("wx_scan", "wx_pay", "alipay_scan", "alipay_pay", "wx_ride", "alipay_ride", "wx2_pay")
            else listOf("take_photo", "portrait", "video", "night_view", "mirror", "master", "panoramic")
        val functions = org.json.JSONArray().apply {
            types.forEachIndexed { i, name -> put(JSONObject().put("type", name).put("enable", i < enabled).put("support", true).put("info", "launch:$name").put("future", 17)) }
            put(JSONObject().put("type", "future_native").put("support", false).put("enable", false).put("opaque", "keep"))
        }
        return CardManagementState.parse(JSONObject().put("futureRoot", 9).put("cards", org.json.JSONArray()
            .put(JSONObject().put("type", type).put("enable", false).put("skinName", "custom").put("functions", functions))
            .put(JSONObject().put("type", "memory").put("enable", true).put("payload", "keep"))).toString())
    }

    @Test fun editsPreserveDisabledCardSkinLaunchDataHiddenFunctionsAndOtherCards() {
        for (type in listOf("pay", "camera")) {
            val original = snapshot(type)
            val functions = CardFunctionState.parse(original, type).functions
            val enabled = CardManagementState.parse(CardFunctionState.enabled(original, type, functions[4].type, true))
            val moved = CardManagementState.parse(CardFunctionState.move(enabled, type, functions[0].type, functions[1].type))
            val root = JSONObject(moved.raw); val card = root.getJSONArray("cards").getJSONObject(0)
            assertEquals(9, root.getInt("futureRoot")); assertEquals("custom", card.getString("skinName")); assertFalse(card.getBoolean("enable"))
            assertEquals("keep", root.getJSONArray("cards").getJSONObject(1).getString("payload"))
            val raw = card.getJSONArray("functions")
            assertEquals("keep", raw.getJSONObject(raw.length() - 1).getString("opaque"))
            assertEquals(functions[1].type, raw.getJSONObject(0).getString("type"))
            assertEquals("launch:${functions[0].type}", raw.getJSONObject(1).getString("info"))
            assertEquals(17, raw.getJSONObject(1).getInt("future"))
            assertEquals(5, CardFunctionState.parse(moved, type).functions.count { it.enabled })
        }
    }

    @Test fun originalLimitsAreKeptAndUnderfilledLegacyDataCanRecover() {
        rejects { CardFunctionState.enabled(snapshot(enabled = 1), "pay", "wx_scan", false) }
        rejects { CardFunctionState.enabled(snapshot(enabled = 6), "pay", "wx2_pay", true) }
        rejects { CardFunctionState.enabled(snapshot(enabled = 4), "pay", "wx_ride", true, 4) }
        rejects { CardFunctionState.enabled(snapshot("camera", 4), "camera", "take_photo", false) }
        rejects { CardFunctionState.enabled(snapshot("camera", 6), "camera", "panoramic", true) }
        val recovery = CardManagementState.parse(CardFunctionState.enabled(snapshot("camera", 0), "camera", "take_photo", true))
        assertEquals(1, CardFunctionState.parse(recovery, "camera").functions.count { it.enabled })
    }

    @Test fun backgroundsOnlyChangeAppearanceAndRejectUnrecognizedStrategies() {
        for (type in listOf("pay", "camera")) {
            val before = snapshot(type)
            val changed = CardManagementState.parse(CardFunctionState.background(before, type, "bundled", 1))
            assertEquals("bundled" to 1, CardFunctionState.background(changed, type))
            val original = JSONObject(before.raw); val actual = JSONObject(changed.raw)
            actual.getJSONArray("cards").getJSONObject(0).put("skinName", "custom").remove("skinLoaderStrategy")
            assertEquals(original.toString(), actual.toString())
            rejects { CardFunctionState.background(before, type, "downloaded", 2) }
            rejects { CardFunctionState.background(before, type, "", 1) }
        }
    }

    @Test fun unsupportedAndMalformedFunctionsCannotBeOverwritten() {
        rejects { CardFunctionState.enabled(snapshot(), "pay", "future_native", true) }
        rejects { CardFunctionState.move(snapshot(), "pay", "wx_scan", "future_native") }
        val root = JSONObject(snapshot().raw); val functions = root.getJSONArray("cards").getJSONObject(0).getJSONArray("functions")
        functions.put(JSONObject(functions.getJSONObject(0).toString()))
        rejects { CardFunctionState.parse(CardManagementState.parse(root.toString()), "pay") }
        rejects { CardFunctionState.parse(snapshot(), "memory") }
    }

    private fun rejects(action: () -> Unit) {
        try { action(); fail("Invalid function edit was accepted") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
    }
}
