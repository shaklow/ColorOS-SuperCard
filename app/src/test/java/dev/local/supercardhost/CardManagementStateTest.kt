package dev.local.supercardhost

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 36], manifest = Config.NONE)
class CardManagementStateTest {
    private val raw = """{"card_switch":true,"future":{"v":7},"cards":[
        {"type":"pay","enable":true,"skinName":"custom","functions":[{"type":"wx_pay","enable":false}],"future":9},
        {"type":"camera","enable":false,"functions":[{"type":"video","support":true}]},
        {"type":"memory","enable":true},{"type":"future_card","typeName":"新卡片","enable":false,"custom":[1,2]}]}"""
    private class Storage(var raw: String?) : CardManagementRepository.Storage {
        var writable = true; var writes = 0; var initializes = 0; var accept = true; var replaced: String? = null
        override fun read() = raw
        override fun canWrite() = writable
        override fun write(raw: String): Boolean { writes++; if (accept) this.raw = replaced ?: raw; return accept }
        override fun initialize() { initializes++; raw = """{"cards":[{"type":"pay","enable":true}]}""" }
    }

    @Test fun enableAndReorderPreserveFunctionsSkinsAndUnknownFields() {
        val before = CardManagementState.parse(raw)
        val toggled = CardManagementState.parse(CardManagementState.enabled(before, "camera", true))
        val moved = CardManagementState.move(toggled, "pay", "future_card")
        val root = JSONObject(moved); val original = JSONObject(raw)
        assertTrue(root.getBoolean("card_switch")); assertEquals(original.getJSONObject("future").toString(), root.getJSONObject("future").toString())
        val cards = root.getJSONArray("cards")
        assertEquals(listOf("camera", "memory", "future_card", "pay"), CardManagementState.parse(moved).cards.map { it.type })
        val pay = cards.getJSONObject(3)
        assertEquals(original.getJSONArray("cards").getJSONObject(0).toString(), pay.toString())
        assertTrue(cards.getJSONObject(0).getBoolean("enable"))
        assertEquals("[1,2]", cards.getJSONObject(2).getJSONArray("custom").toString())
        assertEquals("新卡片", before.cards.last().title)
    }

    @Test fun arrowsCanMoveBothWaysAndNoOpDoesNotRewriteJson() {
        val base = CardManagementState.parse(raw)
        val changed = CardManagementState.parse(CardManagementState.move(base, "future_card", "pay"))
        assertEquals(listOf("future_card", "pay", "camera", "memory"), changed.cards.map { it.type })
        assertEquals(raw, CardManagementState.move(base, "pay", "pay"))
        assertEquals(raw, CardManagementState.enabled(base, "pay", true))
    }

    @Test fun lastEnabledCardCannotBeDisabledButAnEmptyLegacySelectionCanRecover() {
        val storage = Storage("""{"cards":[{"type":"pay","enable":true},{"type":"camera","enable":false}]}""")
        val repo = CardManagementRepository(storage); val base = repo.load()
        try { repo.change(base) { CardManagementState.enabled(it, "pay", false) }; fail("last card") } catch (_: IllegalArgumentException) {}
        assertEquals(base.raw, storage.raw); assertEquals(0, storage.writes)
        storage.raw = """{"cards":[{"type":"pay","enable":false}]}"""
        assertTrue(repo.change(repo.load()) { CardManagementState.enabled(it, "pay", true) }.cards.single().enabled)
    }

    @Test fun staleSnapshotCannotOverwriteAnotherEditorsFunctionChanges() {
        val storage = Storage(raw); val repo = CardManagementRepository(storage); val base = repo.load()
        val external = JSONObject(raw).apply { getJSONArray("cards").getJSONObject(0).put("functions", org.json.JSONArray()) }.toString()
        storage.raw = external
        try { repo.change(base) { CardManagementState.move(it, "pay", "memory") }; fail("stale write") } catch (_: IllegalStateException) {}
        assertEquals(0, storage.writes); assertEquals(external, storage.raw)
    }

    @Test fun writeRejectionAndChangedReadbackAreExplicitFailures() {
        val storage = Storage(raw); val repo = CardManagementRepository(storage); val base = repo.load()
        storage.accept = false
        try { repo.change(base) { CardManagementState.enabled(it, "camera", true) }; fail("provider failure") } catch (_: IllegalStateException) {}
        assertEquals(raw, storage.raw)
        storage.accept = true; storage.replaced = """{"cards":[{"type":"memory","enable":true}]}"""
        try { repo.change(base) { CardManagementState.enabled(it, "camera", true) }; fail("readback mismatch") } catch (_: IllegalStateException) {}
        assertEquals(storage.replaced, storage.raw) // Never roll back an external update.
    }

    @Test fun corruptAndDuplicateConfigurationsAreNeverInitializedOrSaved() {
        for (value in listOf("bad", "{}", "{\"cards\":[]}",
            """{"cards":[{"type":"pay","enable":"true"}]}""",
            """{"cards":[{"type":1}]}""", """{"cards":[{"type":"pay"},{"type":"pay"}]}""")) {
            val storage = Storage(value)
            try { CardManagementRepository(storage).load(); fail(value) } catch (_: Exception) {}
            assertEquals(value, storage.raw); assertEquals(0, storage.writes); assertEquals(0, storage.initializes)
        }
    }

    @Test fun permissionIsRequiredForMutationAndFirstInstallOnly() {
        val storage = Storage(raw).apply { writable = false }; val repo = CardManagementRepository(storage)
        val base = repo.load(); assertEquals(4, base.cards.size)
        try { repo.change(base) { CardManagementState.enabled(it, "camera", true) }; fail("permission") } catch (_: IllegalStateException) {}
        storage.raw = null
        try { repo.load(); fail("first install permission") } catch (_: IllegalStateException) {}
        assertEquals(0, storage.initializes)
        storage.writable = true; assertEquals(1, repo.load().cards.size); repo.load()
        assertEquals(1, storage.initializes)
    }
}
