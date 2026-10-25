package dev.local.supercardui

import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.util.function.IntConsumer
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 36])
class SettingsSurfaceTest {
    private fun page(name: String) = SettingsSurface.create(RuntimeEnvironment.getApplication(), name)
    private fun <T : View> View.named(name: String): T = requireNotNull(findViewWithTag("settings_$name"))

    @Test fun hubKeepsEveryExistingSettingsRouteReachable() {
        val root = page("home")
        for (name in listOf("show_cards", "native_cards", "open_cards", "open_runtime", "open_campus", "open_gestures"))
            assertTrue(name, root.named<View>(name).isClickable)
        assertNull(root.findViewWithTag<View>("settings_open_native"))
        assertEquals("新卡包预览：PAY、校园卡与 LENS", root.named<MaterialButton>("native_cards").text.toString())
        SettingsSurface.render(root, Bundle().apply { putString("campus_summary", "已配置授权链接，点击管理") })
        assertEquals("已配置授权链接，点击管理", root.named<TextView>("campus_summary").text)
    }

    @Test fun credentialEditorUsesMaterialAndNeverSavesTextInViewState() {
        val root = page("campus")
        val editor = root.named<EditText>("url")
        assertFalse(editor.isSaveEnabled)
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, editor.importantForAutofill)
        assertEquals("", editor.text.toString())
        for (name in listOf("save", "verify", "clear")) assertTrue(root.named<View>(name) is MaterialButton)
        assertEquals("返回", root.named<View>("back").contentDescription)
        SettingsSurface.render(root, Bundle().apply { putString("url_error", "请输入学校授权链接") })
        assertEquals("请输入学校授权链接", descendants(root).filterIsInstance<TextInputLayout>().single().error)
        SettingsSurface.render(root, Bundle().apply { putString("url_error", null) })
        assertNull(descendants(root).filterIsInstance<TextInputLayout>().single().error)
    }

    @Test fun allNineTouchOverridesStartDisabledAndPermissionDetailsAreCollapsible() {
        val root = page("runtime")
        val switches = descendants(root).filterIsInstance<MaterialSwitch>()
        assertEquals(9, switches.size)
        assertTrue(switches.all { !it.isEnabled && !it.isChecked })
        assertEquals(View.GONE, root.named<View>("setup").visibility)
        root.named<View>("details").performClick()
        assertEquals(View.VISIBLE, root.named<View>("setup").visibility)
        root.named<View>("details").performClick()
        assertEquals(View.GONE, root.named<View>("setup").visibility)
    }

    @Test fun partialStatusUpdatesDoNotOverwriteOtherResults() {
        val root = page("runtime")
        SettingsSurface.render(root, Bundle().apply { putString("root", "已授权"); putString("hook", "已连接") })
        SettingsSurface.render(root, Bundle().apply { putString("root", "检测超时"); putString("root_tone", "error") })
        assertEquals("检测超时", root.named<TextView>("root_value").text)
        assertEquals("已连接", root.named<TextView>("hook_value").text)
    }

    @Test fun accountDialogOnlySendsExplicitChoice() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = SettingsSurface.create(activity, "runtime")
        activity.setContentView(root)
        var chosen = -1
        SettingsSurface.choose(root, "微信启动用户", arrayOf("主用户", "多开用户（999）"), 0, IntConsumer { chosen = it })
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(-1, chosen)
        dialog.listView.performItemClick(null, 1, 1)
        assertEquals(1, chosen)
        assertFalse(dialog.isShowing)
        activity.finish()
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun pagesFitNarrowWindowsWithLargeTextInBothThemes() {
        for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
            val app = RuntimeEnvironment.getApplication()
            val config = Configuration(app.resources.configuration).apply {
                fontScale = 1.3f; uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
            }
            val context = app.createConfigurationContext(config)
            for (name in listOf("home", "runtime", "campus")) {
                val root = SettingsSurface.create(context, name)
                val density = context.resources.displayMetrics.density
                val width = (320 * density).toInt()
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((640 * density).toInt(), View.MeasureSpec.EXACTLY))
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                assertTrue(descendants(root).all { it.width <= width })
                assertTrue(root.named<View>(if (name == "home") "open_cards" else "back").height >= 48 * density)
                if (name != "home") {
                    val back = root.named<ImageButton>("back")
                    assertNotNull("Back navigation must have a visible icon", back.drawable)
                    assertTrue("Icon must fit its touch target", 24 * density + back.paddingStart + back.paddingEnd <= back.width)
                }
                System.getenv("SUPERCARD_PREVIEW_DIR")?.let { directory ->
                    val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                    root.draw(Canvas(image))
                    val target = File(directory, "$name-${if (night == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"}-${android.os.Build.VERSION.SDK_INT}.png")
                    target.parentFile?.mkdirs()
                    target.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    image.recycle()
                    if (name == "runtime") {
                        val scroll = (root as ViewGroup).getChildAt(0) as android.widget.ScrollView
                        scroll.scrollTo(0, (scroll.getChildAt(0).height - scroll.height).coerceAtLeast(0))
                        val lower = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                        root.draw(Canvas(lower))
                        File(directory, "runtime-bottom-${if (night == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"}-${android.os.Build.VERSION.SDK_INT}.png")
                            .outputStream().use { lower.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        lower.recycle()
                    }
                }
            }
        }
    }

    private fun descendants(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { descendants(root.getChildAt(it)) } else emptyList()
}
