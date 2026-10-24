package dev.local.supercardhost

import android.content.Intent
import android.graphics.Bitmap
import android.util.Base64
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 36])
class NativeCouponTest {
    private fun film(id: String = "one") = JSONObject().put("card_id", id).put("movieTitle", "电影票 $id").put("orderStatus", 1).put("qrCode", "wallet-payload-$id")
    private fun snapshot(account: String = "a".repeat(64)) = JSONObject().put("_walletAccountKey", account)
        .put("films", JSONArray().put(film())).put("performances", JSONArray()).put("coupons", JSONArray())
    private class Store(var data: JSONObject?) : NativeCouponSession.Store {
        var valid = true; var writable = true
        val hidden = mutableSetOf<String>()
        override fun snapshot() = data
        override fun current(account: String) = valid && data?.optString("_walletAccountKey") == account
        override fun hidden(key: String) = key in hidden
        override fun remove(key: String): Boolean { if (!writable) return false; hidden.add(key); return true }
    }
    @Test fun projectionKeepsOriginalCategoryOrderAndFiltersOnlyInvalidOrLocallyHiddenRows() {
        val data = snapshot().apply {
            getJSONArray("films").put(film()).put(film("hidden")).put(film("unknown").put("orderStatus", 0))
            getJSONArray("performances").put(JSONObject().put("id", "show").put("projectName", "演出").put("venue", "测试剧院").put("orderStatus", 2))
            getJSONArray("coupons").put(JSONObject().put("entityId", "shop").put("couponName", "团购券").put("orderStatus", 1)
                .put("_codeMode", "douyin").put("_verificationCode", "NOT-A-QR"))
        }
        val rows = NativeCouponData.parse(data) { it == "film___hidden" }
        assertEquals(listOf("film___one", "performance___show", "coupon___shop"), rows.map { it.key })
        assertFalse(rows[1].usable)
        assertEquals("测试剧院", rows[1].merchant)
        assertTrue(rows[2].hint.contains("抖音未提供券码"))
        assertEquals("", rows[2].qr)
        assertNull(NativeCouponCodes.decode(rows[2]))
        val pending = rows[0].copy(qr = "", pending = true)
        assertTrue(pending.hint("updating_codes").contains("补全"))
        assertTrue(pending.hint("codes_unavailable").contains("暂未读取成功"))
    }
    @Test fun sameAccountUpdatesKeepSelectionButAccountInvalidationClearsIt() {
        val store = Store(snapshot()); val session = NativeCouponSession(store)
        session.refresh(); session.setActive(true); assertTrue(session.select("film___one"))
        store.data!!.getJSONArray("films").getJSONObject(0).put("qrCode", "refreshed-qr")
        session.refresh(); assertEquals("film___one", session.selected); assertEquals("refreshed-qr", session.current()!!.qr)
        store.valid = false
        assertNull(session.current()); assertFalse(session.removeSelected()); assertTrue(store.hidden.isEmpty())
        session.refresh(); assertTrue(session.items.isEmpty()); assertNull(session.selected)
        store.valid = true; store.data = snapshot("b".repeat(64)); session.refresh(); assertNull(session.selected)
    }
    @Test fun removingIsLocalAndWriteFailureOrInactiveCardCannotRemoveAnything() {
        val store = Store(snapshot()); val session = NativeCouponSession(store)
        session.refresh(); assertFalse(session.select("film___one"))
        session.setActive(true); session.select("film___one")
        store.writable = false; assertFalse(session.removeSelected()); assertEquals(1, session.items.size)
        store.writable = true; assertTrue(session.removeSelected()); assertTrue(session.items.isEmpty()); assertNull(session.selected)
        assertEquals(setOf("film___one"), store.hidden)
        assertEquals(1, store.data!!.getJSONArray("films").length())
        session.close(); session.setActive(true); session.refresh(); assertFalse(session.active); assertTrue(session.items.isEmpty())
    }
    @Test fun switchAwayDisablesActionsButRestoresTheSameSelectionWithinAnOpening() {
        val session = NativeCouponSession(Store(snapshot()))
        session.refresh(); session.setActive(true); session.select("film___one")
        session.setActive(false); assertNull(session.current()); assertFalse(session.removeSelected())
        session.setActive(true); assertEquals("film___one", session.current()!!.key)
        session.invalidate(); assertNull(session.current()); assertNull(session.account)
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun actualWalletQrPayloadAndImageRoundTripWithoutUsingVerificationText() {
        val row = NativeCouponData.parse(snapshot()) { false }.single().copy(qr = "https://example.invalid/票券?a=1")
        val bitmap = checkNotNull(NativeCouponCodes.decode(row))
        assertEquals(row.qr, decode(bitmap))
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val actualImage = checkNotNull(NativeCouponCodes.decode(row.copy(image = Base64.encodeToString(bytes, Base64.NO_WRAP), qr = "other")))
        assertEquals(row.qr, decode(actualImage))
        assertNull(NativeCouponCodes.decode(row.copy(usable = false)))
        assertThrows(IllegalArgumentException::class.java) { NativeCouponCodes.decode(row.copy(image = "broken")) }
        bitmap.recycle(); actualImage.recycle()
    }
    @Test fun arbitraryDestinationsNeverBecomeWalletOrOriginalImageActions() {
        val row = NativeCouponData.parse(snapshot()) { false }.single()
        val app = RuntimeEnvironment.getApplication()
        assertNull(NativeCouponActions.source(app, row.copy(source = Intent(Intent.ACTION_VIEW).setData(android.net.Uri.parse("https://example.invalid/")).toUri(Intent.URI_INTENT_SCHEME))))
        assertNull(NativeCouponActions.original(row.copy(originalImage = "file:///private/photo.png")))
        assertNull(NativeCouponActions.original(row.copy(originalImage = "https://example.invalid/image.png")))
        val image = NativeCouponActions.original(row.copy(originalImage = "content://test.wallet/image/one"))!!
        assertEquals("content", image.data!!.scheme)
        assertTrue(image.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }
    private fun decode(bitmap: Bitmap): String {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))).text
    }
}
