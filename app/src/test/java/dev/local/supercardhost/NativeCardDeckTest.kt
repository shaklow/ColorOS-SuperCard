package dev.local.supercardhost

import org.junit.Assert.*
import org.junit.Test

class NativeCardDeckTest {
    private class Card(val type: String) : AutoCloseable { var closes = 0; override fun close() { closes++ } }
    @Test fun switchAndHideOwnExactlyOneContentAndInvalidateOldWork() {
        val cards = mutableListOf<Card>(); var visible: Card? = null
        val deck = NativeCardDeck({ type -> Card(type).also(cards::add) }, { value, _, _, _ -> visible = value }, { visible = null }, { throw it })
        deck.show(listOf("dhu", "camera")); val first = deck.generation
        deck.show(listOf("dhu", "camera")); assertEquals(1, cards.size)
        deck.move(1); assertEquals("camera", visible!!.type); assertEquals(1, cards[0].closes)
        assertFalse(deck.current(first)); assertTrue(deck.current(deck.generation))
        deck.move(1); assertEquals(2, cards.size)
        deck.hide(); deck.hide(); assertNull(visible); assertEquals(listOf(1, 1), cards.map { it.closes })
        assertFalse(deck.current(deck.generation))
        deck.show(listOf("camera")); deck.close(); deck.close(); assertEquals(1, cards.last().closes)
    }
    @Test fun rejectedReplacementClosesOnlyNewContentAndPreservesTheOldSession() {
        val cards = mutableListOf<Card>(); var reject = false; var visible: Card? = null
        val deck = NativeCardDeck({ type -> Card(type).also(cards::add) }, { value, _, _, _ ->
            if (reject) throw IllegalStateException("Window unavailable"); visible = value
        }, { visible = null }, { throw it })
        deck.show(listOf("dhu", "camera")); val token = deck.generation
        reject = true
        try { deck.move(1); fail("Rejected window succeeded") } catch (_: IllegalStateException) { }
        assertEquals("dhu", visible!!.type); assertEquals("dhu", deck.selected)
        assertEquals(listOf(0, 1), cards.map { it.closes }); assertTrue(deck.current(token))
        deck.close(); assertEquals(listOf(1, 1), cards.map { it.closes })
    }
    @Test fun cardRemovalClosesThePreviousSelectionAndNoUnknownCardIsConstructed() {
        val seen = mutableListOf<String>(); var selected = ""
        val deck = NativeCardDeck({ type -> seen.add(type); Card(type) }, { _, type, _, _ -> selected = type }, {}, { throw it })
        deck.show(listOf("camera", "dhu"), "unknown"); assertEquals("camera", selected)
        deck.show(listOf("dhu")); assertEquals("dhu", selected)
        try { deck.show(listOf("dhu", "dhu")); fail("Duplicate cards accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(listOf("camera", "dhu"), seen); deck.close()
    }
}
