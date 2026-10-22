package dev.local.supercardhost

import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.function.IntConsumer

/** Framework-only facade: MDC, AndroidX and the archive's Kotlin runtime never cross this boundary. */
internal class SettingsUi(val root: View) {
    fun <T : View> view(name: String): T = requireNotNull(root.findViewWithTag("settings_$name"))
    fun on(name: String, action: () -> Unit) { view<View>(name).setOnClickListener { action() } }
    fun render(vararg values: Pair<String, String?>) = render(Bundle().apply {
        values.forEach { (key, value) -> putString(key, value) }
    })
    fun render(values: Bundle) { DhuMaterialUi.renderSettings(root, values) }
    fun choose(title: String, labels: Array<String>, selected: Int, action: (Int) -> Unit) {
        DhuMaterialUi.chooseSettings(root, title, labels, selected, IntConsumer(action))
    }
    fun confirm(title: String, message: String, action: () -> Unit) {
        DhuMaterialUi.confirmSettings(root, title, message, Runnable(action))
    }
}

/** Keeps ordinary Activity lifecycles and OEM manifest entry names while replacing the presentation. */
abstract class M3eSettingsActivity : Activity() {
    internal lateinit var ui: SettingsUi
    protected fun showPage(page: String): Boolean {
        return try {
            ui = SettingsUi(DhuMaterialUi.settings(this, page))
            setContentView(ui.root)
            if (page != "home") ui.on("back") { finish() }
            true
        } catch (error: Exception) { unavailable(error); false }
        catch (error: LinkageError) { unavailable(error); false }
    }

    override fun onCreate(state: Bundle?) {
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        setTheme(if (dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(state)
    }

    override fun onPostCreate(state: Bundle?) {
        super.onPostCreate(state)
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        // PhoneWindow.getInsetsController() dereferences a null DecorView before
        // setContentView. Apply appearance after page creation, including fallback.
        window.decorView.windowInsetsController?.setSystemBarsAppearance(if (dark) 0 else mask, mask)
    }

    protected fun unavailable(error: Throwable) {
        Log.e("SuperCardSettings", "Private settings UI unavailable", error)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(48, 48, 48, 48)
            addView(TextView(context).apply { text = "设置界面暂不可用，请更新或重新打开超级卡包。" })
            addView(Button(context).apply { text = "返回"; setOnClickListener { finish() } })
        })
    }

    protected fun alive() = !isFinishing && !isDestroyed
    protected fun isUiReady() = ::ui.isInitialized
}
