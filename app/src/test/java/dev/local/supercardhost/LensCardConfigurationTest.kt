package dev.local.supercardhost

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class LensCardConfigurationTest {
    @Test fun savedOrderAndOriginalLaunchPayloadArePreserved() {
        val source = """{"cards":[{"type":"paycard"},{"type":"camera","functions":[
            {"type":"video","enable":true,"info":"original-camera-payload","custom":37},
            {"type":"take_photo","enable":true},
            {"type":"video","enable":true,"info":"duplicate"}]}]}"""
        val choices = LensCardConfiguration.parse(source)
        assertEquals(listOf("video", "take_photo"), choices.map { it.type() })
        assertEquals(listOf("录像", "拍照"), choices.map { it.title() })
        val original = JSONObject(choices[0].functionJson())
        assertEquals("original-camera-payload", original.getString("info"))
        assertEquals(37, original.getInt("custom"))
    }

    @Test fun disabledUnsupportedAndUnknownActionsStayHidden() {
        val choices = LensCardConfiguration.parse("""{"cards":[{"type":"camera","functions":[
            {"type":"portrait","enable":false}, {"type":"video","enable":true,"support":false},
            {"type":"unrecognized","enable":true}, {"type":"take_photo","enable":true,"support":true}]}]}""")
        assertEquals(listOf("take_photo"), choices.map { it.type() })
    }

    @Test fun absentAndAllDisabledSelectionsRemainEmpty() {
        for (raw in listOf(null, "", "{\"cards\":[]}", "{\"cards\":[{\"type\":\"camera\",\"functions\":[]}]}",
            "{\"cards\":[{\"type\":\"camera\",\"functions\":[{\"type\":\"take_photo\",\"enable\":false}]}]}"))
            assertTrue(LensCardConfiguration.parse(raw).isEmpty())
    }

    @Test fun damagedConfigurationIsRejectedWithoutInventingActions() {
        for (raw in listOf("broken-json", "{}", "{\"cards\":[false]}")) {
            assertThrows(Exception::class.java) { LensCardConfiguration.parse(raw) }
        }
    }
}
