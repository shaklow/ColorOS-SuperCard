package dev.local.supercardhost

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast

/** Own Activity/window and Kotlin UI; no vivo CardService or plugin classes required to render. */
class NativeCardsActivity : M3eSettingsActivity() {
    private var campus: DhuPayView? = null
    private var cards: View? = null
    private var resumed = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        // Protect codes before a request can start, including screenshots and the recent-task image.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        try {
            val root = DhuMaterialUi.cards(this)
            cards = root
            setContentView(root)
            campus = DhuPayView(this, false).also {
                it.setActive(false)
                root.findViewWithTag<FrameLayout>("native_campus").addView(it, FrameLayout.LayoutParams(-1, -1))
            }
            root.findViewWithTag<View>("native_settings").setOnClickListener { open(HostActivity::class.java) }
            root.findViewWithTag<View>("native_lens_settings").setOnClickListener { open(LensSettingsActivity::class.java) }
            root.findViewWithTag<View>("native_legacy").setOnClickListener {
                CardConfiguration.request(this, "openLegacyCards", null) { _, error ->
                    if (alive()) {
                        if (error == null) finish()
                        else Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                    }
                }
            }
        } catch (error: Exception) { cards = null; campus?.setActive(false); unavailable(error) }
        catch (error: LinkageError) { cards = null; campus?.setActive(false); unavailable(error) }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        campus?.setActive(hasWindowFocus())
        val root = cards ?: return
        try {
            val selected = NativeLensPreferences.selected(this)
            val modes = ColorOsLensModes.query(this).filter { it.type() in selected }
            DhuMaterialUi.renderLens(root, Bundle().apply {
                putStringArray("types", modes.map { it.type() }.toTypedArray())
                putStringArray("titles", modes.map { it.title() }.toTypedArray())
            })
            modes.forEach { mode ->
                root.findViewWithTag<View>("native_lens_${mode.type()}").setOnClickListener {
                    try { ColorOsLensModes.launch(this, mode.type()) }
                    catch (_: ActivityNotFoundException) { Toast.makeText(this, "未找到 ColorOS 相机", Toast.LENGTH_SHORT).show() }
                    catch (_: SecurityException) { Toast.makeText(this, "当前系统不允许打开该相机模式", Toast.LENGTH_SHORT).show() }
                }
            }
        } catch (error: Exception) { Log.e("SuperCardNative", "Camera surface unavailable", error); unavailable(error) }
        catch (error: LinkageError) { cards = null; campus?.setActive(false); unavailable(error) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        campus?.setActive(resumed && hasFocus)
    }
    override fun onPause() { resumed = false; campus?.setActive(false); super.onPause() }
    override fun onDestroy() { campus?.setActive(false); campus = null; cards = null; super.onDestroy() }
    private fun open(type: Class<*>) { startActivity(Intent(this, type)) }
}
