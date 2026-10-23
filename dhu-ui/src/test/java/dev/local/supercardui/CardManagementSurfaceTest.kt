package dev.local.supercardui

import android.app.Activity
import android.content.ClipData
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.view.DragEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ImageButton
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.function.Consumer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 36])
class CardManagementSurfaceTest {
    private val types = listOf("pay", "memory", "staging", "camera", "dhu")
    private val titles = listOf("Pay 支付卡", "记忆卡片", "卡券", "LENS 相机卡", "东华校园卡")
    private fun page() = CardManagementSurface.create(RuntimeEnvironment.getApplication())
    private fun state(writable: Boolean = true, busy: Boolean = false, onlyOne: Boolean = false) = Bundle().apply {
        putBoolean("writable", writable); putBoolean("busy", busy); putString("message", "开关与顺序已保存")
        putParcelableArrayList("cards", ArrayList(types.mapIndexed { index, type -> Bundle().apply {
            putString("type", type); putString("title", titles[index]); putBoolean("enabled", !onlyOne || index == 0)
        } }))
    }
    private fun <T : View> View.named(name: String): T = requireNotNull(findViewWithTag("management_$name"))

    @Test fun onlyUserChangesEmitEventsAndBusyRenderRestoresConfirmedSwitches() {
        val root = page(); val events = mutableListOf<Bundle>()
        CardManagementSurface.bind(root, Consumer { events.add(it) })
        CardManagementSurface.render(root, state())
        assertTrue(events.isEmpty())
        root.named<MaterialSwitch>("toggle_pay").performClick()
        assertEquals("toggle", events.single().getString("action")); assertFalse(events.single().getBoolean("value"))
        CardManagementSurface.render(root, state(busy = true))
        assertTrue(root.named<MaterialSwitch>("toggle_pay").isChecked)
        assertFalse(root.named<MaterialSwitch>("toggle_pay").isEnabled)
        assertFalse(root.named<View>("up_memory").isEnabled); assertFalse(root.named<View>("settings_camera").isEnabled)
        assertEquals(1, events.size)
    }

    @Test fun orderControlsAndPerCardDestinationsAreAccessibleWithoutDragging() {
        val root = page(); val events = mutableListOf<Bundle>()
        CardManagementSurface.bind(root, Consumer { events.add(it) }); CardManagementSurface.render(root, state())
        assertFalse(root.named<View>("up_pay").isEnabled); assertFalse(root.named<View>("down_dhu").isEnabled)
        root.named<View>("up_camera").performClick()
        assertEquals("move", events.last().getString("action")); assertEquals("staging", events.last().getString("target"))
        root.named<View>("down_camera").performClick()
        assertEquals("dhu", events.last().getString("target"))
        for (type in listOf("pay", "camera", "dhu", "staging")) {
            root.named<View>("settings_$type").performClick()
            assertEquals("settings", events.last().getString("action")); assertEquals(type, events.last().getString("type"))
        }
        assertEquals(View.GONE, root.named<View>("settings_memory").visibility)
        assertEquals("5 / 5 张已启用", root.named<TextView>("count").text)
    }

    @Test fun permissionAndLastCardRulesDisableOnlyInvalidEdits() {
        val root = page(); CardManagementSurface.render(root, state(writable = false))
        assertTrue(types.all { !root.named<View>("toggle_$it").isEnabled && !root.named<View>("drag_$it").isEnabled })
        assertTrue(root.named<View>("runtime").isEnabled); assertTrue(root.named<View>("retry").isEnabled)
        CardManagementSurface.render(root, state(onlyOne = true))
        assertFalse(root.named<View>("toggle_pay").isEnabled)
        assertTrue(root.named<View>("toggle_camera").isEnabled)
        assertTrue(root.named<View>("down_pay").isEnabled)
    }

    @Test fun savingKeepsTheProjectedSwitchAndDoesNotFlashDisabledColors() {
        val root = page(); CardManagementSurface.render(root, state())
        val row = root.named<View>("card_pay")
        val pending = state().apply {
            putBoolean("saving", true)
            getParcelableArrayList<Bundle>("cards")!![0].putBoolean("enabled", false)
        }
        CardManagementSurface.render(root, pending)
        val toggle = root.named<MaterialSwitch>("toggle_pay")
        assertFalse(toggle.isChecked); assertTrue(toggle.isEnabled); assertFalse(toggle.isClickable)
        assertTrue(root.named<View>("down_pay").isEnabled); assertFalse(root.named<View>("down_pay").isClickable)
        pending.putBoolean("saving", false); CardManagementSurface.render(root, pending)
        assertSame(row, root.named<View>("card_pay")); assertFalse(toggle.isChecked); assertTrue(toggle.isClickable)
    }

    @Test fun functionPagesRespectLimitsWithoutOfferingUnrelatedCardEditors() {
        for (type in listOf("pay", "camera")) {
            val root = CardManagementSurface.createFunctions(RuntimeEnvironment.getApplication(), type)
            CardManagementSurface.render(root, state(onlyOne = true).apply { putInt("minimum", 1); putInt("maximum", 1) })
            assertFalse(root.findViewWithTag<MaterialSwitch>("function_toggle_pay").isEnabled)
            assertFalse(root.findViewWithTag<MaterialSwitch>("function_toggle_camera").isEnabled)
            assertEquals(View.GONE, root.findViewWithTag<View>("function_settings_pay").visibility)
        }
    }

    @Test fun dragDropOnlyAcceptsThisPagesCurrentLocalCard() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = CardManagementSurface.create(activity); val events = mutableListOf<Bundle>()
        activity.setContentView(root)
        CardManagementSurface.bind(root, Consumer { events.add(it) }); CardManagementSurface.render(root, state())
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val dragType = Class.forName("dev.local.supercardui.CardManagementSurface\$Page\$Drag")
        fun local(page: View) = dragType.getDeclaredConstructor(page.tag.javaClass, String::class.java)
            .apply { isAccessible = true }.newInstance(page.tag, "pay")
        fun event(action: Int, local: Any): DragEvent = ReflectionHelpers.callConstructor(DragEvent::class.java).apply {
            ReflectionHelpers.setField(this, "mAction", action); ReflectionHelpers.setField(this, "mLocalState", local)
            val clip = ClipData.newPlainText("cards", "")
            ReflectionHelpers.setField(this, "mClipData", clip); ReflectionHelpers.setField(this, "mClipDescription", clip.description)
        }
        val target = root.named<View>("card_camera")
        assertTrue("Drag dispatch requires an attached ViewRoot", target.isAttachedToWindow)
        assertFalse(target.dispatchDragEvent(event(DragEvent.ACTION_DRAG_STARTED, Any())))
        assertFalse(target.dispatchDragEvent(event(DragEvent.ACTION_DRAG_STARTED, local(page()))))
        val own = local(root)
        assertTrue(target.dispatchDragEvent(event(DragEvent.ACTION_DRAG_STARTED, own)))
        assertTrue(target.dispatchDragEvent(event(DragEvent.ACTION_DROP, own)))
        assertEquals("pay", events.single().getString("type")); assertEquals("camera", events.single().getString("target"))
        CardManagementSurface.render(root, state(busy = true))
        assertFalse(target.dispatchDragEvent(event(DragEvent.ACTION_DROP, own)))
        assertEquals(1, events.size)
        activity.finish()
    }

    @Test fun backgroundPickerPreservesSelectionWhileSavingAndReusesTiles() {
        val root = CardManagementSurface.createFunctions(RuntimeEnvironment.getApplication(), "pay")
        val events = mutableListOf<Bundle>()
        CardManagementSurface.bind(root, Consumer { events.add(it) })
        val values = state().apply {
            putString("backgroundName", "second"); putInt("backgroundStrategy", 1)
            putParcelableArrayList("backgrounds", arrayListOf("first", "second").mapIndexed { i, name -> Bundle().apply {
                putString("name", name); putInt("strategy", 1); putString("title", "背景 ${i + 1}")
            } }.let { ArrayList(it) })
        }
        CardManagementSurface.render(root, values)
        val first = root.findViewWithTag<View>("function_background_0")
        assertFalse(first.isSelected)
        assertTrue(root.findViewWithTag<View>("function_background_1").isSelected)
        first.performClick(); assertEquals("background", events.single().getString("action"))
        assertEquals("first", events.single().getString("name")); assertEquals(1, events.single().getInt("strategy"))
        values.putBoolean("saving", true); values.putString("backgroundName", "first")
        CardManagementSurface.render(root, values)
        assertSame(first, root.findViewWithTag<View>("function_background_0")); assertTrue(first.isSelected)
        assertTrue(first.isEnabled); assertFalse(first.isClickable)
        first.performClick(); assertEquals(1, events.size)
        values.putBoolean("saving", false); CardManagementSurface.render(root, values)
        assertTrue(first.isClickable); assertTrue(first.isSelected)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun layoutFitsNarrowAndWideWindowsWithLargeTextInBothThemes() {
        for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
            val app = RuntimeEnvironment.getApplication()
            val config = Configuration(app.resources.configuration).apply {
                fontScale = 1.3f; uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
            }
            val context = app.createConfigurationContext(config)
            for (widthDp in listOf(320, 600)) {
                val root = CardManagementSurface.create(context)
                CardManagementSurface.render(root, state())
                val density = context.resources.displayMetrics.density
                val width = (widthDp * density).toInt(); val height = (740 * density).toInt()
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
                assertTrue(descendants(root).all { it.width <= width })
                for (type in types) for (name in listOf("drag_$type", "up_$type", "down_$type")) {
                    val button = root.named<ImageButton>(name)
                    assertNotNull(button.drawable); assertTrue(button.width >= 48 * density)
                    assertTrue(button.contentDescription.isNotBlank())
                    assertTrue(button.drawable.bounds.width() > 0)
                    assertTrue("Reorder icon must produce visible pixels: $name", hasInk(button))
                }
                for (type in listOf("pay", "camera", "dhu", "staging")) {
                    val button = root.named<MaterialButton>("settings_$type")
                    assertTrue(button.width > 0 && button.height >= 48 * density)
                    assertEquals(1, button.layout.lineCount)
                    assertTrue(button.paint.measureText(button.text.toString()) <= button.width - button.paddingLeft - button.paddingRight)
                    assertTrue(button.layout?.getEllipsisCount(0) == 0)
                    assertTrue("Editor action must produce visible text: $type", hasInk(button))
                }
                System.getenv("SUPERCARD_PREVIEW_DIR")?.let { directory ->
                    val suffix = "$widthDp-${if (night == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"}-${android.os.Build.VERSION.SDK_INT}"
                    save(root, File(directory, "management-$suffix.png"))
                    val scroll = root.named<ScrollView>("scroll")
                    scroll.scrollTo(0, (scroll.getChildAt(0).height - scroll.height).coerceAtLeast(0))
                    save(root, File(directory, "management-bottom-$suffix.png"))
                }
            }
        }
    }

    private fun hasInk(view: View): Boolean {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val visible = (0 until bitmap.width).any { x ->
            (0 until bitmap.height).any { y -> android.graphics.Color.alpha(bitmap.getPixel(x, y)) > 0 }
        }
        bitmap.recycle()
        return visible
    }

    private fun save(root: View, file: File) {
        val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(image)); file.parentFile?.mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
