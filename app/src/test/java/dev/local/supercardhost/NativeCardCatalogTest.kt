package dev.local.supercardhost

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 36])
class NativeCardCatalogTest {
    @Test fun couponKeepsItsConfiguredPositionAndSupportsAutomaticNativeRouting() {
        val raw = """{"cards":[{"type":"camera","enable":true},{"type":"staging","enable":true},{"type":"dhu","enable":true}]}"""
        assertEquals(listOf("camera", "staging", "dhu"), NativeCardCatalog.cards(raw))
        assertTrue(NativeCardCatalog.complete(raw, false))
        assertFalse(NativeCardCatalog.complete(raw.replace("camera", "memory"), true))
    }
    @Test fun payStillRequiresItsProtocolAndUnknownCardsKeepTheFullLegacyDeck() {
        val raw = """{"cards":[{"type":"staging","enable":true},{"type":"pay","enable":true}]}"""
        assertFalse(NativeCardCatalog.complete(raw, false)); assertTrue(NativeCardCatalog.complete(raw, true))
        assertFalse(NativeCardCatalog.complete(raw.replace("staging", "future"), true))
        assertEquals(listOf("pay", "staging", "camera", "dhu"), NativeCardCatalog.cards(null))
    }
}
