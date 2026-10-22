package dev.local.supercardui

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.view.ContextThemeWrapper

/** One card surface. Its parent owns the floating window, gestures and lifecycle. */
object LensCardSurface {
    @JvmStatic fun create(resources: Context): View = Page(ContextThemeWrapper(resources, R.style.Theme_DhuUi)).root
    @JvmStatic fun render(root: View, values: Bundle) = (root.tag as Page).render(values)

    private class Page(context: Context) {
        private val d = CardDesign(context)
        private val modes = object : GridLayout(context) {
            override fun onMeasure(width: Int, height: Int) {
                val columns = if (MeasureSpec.getSize(width) >= d.dp(190) && resources.configuration.fontScale <= 1.3f) 2 else 1
                if (columns != columnCount) {
                    val children = (0 until childCount).map { getChildAt(it) }
                    removeAllViews(); columnCount = columns
                    children.forEach { addView(it, GridLayout.LayoutParams()) }
                }
                for (index in 0 until childCount) getChildAt(index).layoutParams = GridLayout.LayoutParams().apply {
                    this.width = 0; this.height = -2
                    columnSpec = GridLayout.spec(index % columns, 1f); rowSpec = GridLayout.spec(index / columns)
                    setMargins(if (index % columns == 0) 0 else d.dp(8), 0, 0, d.dp(8))
                }
                super.onMeasure(width, height)
            }
        }.apply { tag = "lens_modes" }
        private val empty = d.text("正在读取相机快捷方式…", 14, secondary = true).apply {
            tag = "lens_empty"; setPadding(0, d.dp(24), 0, d.dp(24)); gravity = Gravity.CENTER
        }
        val root: View

        init {
            val body = d.column().apply { setPadding(d.dp(16), d.dp(16), d.dp(16), d.dp(16)) }
            val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; isBaselineAligned = false }
            heading.addView(ImageView(context).apply {
                setImageDrawable(AppCompatResources.getDrawable(context, R.drawable.lens_ic_camera))
                imageTintList = ColorStateList.valueOf(d.color(androidx.appcompat.R.attr.colorPrimary))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(d.dp(36), d.dp(36)))
            heading.addView(d.column().apply {
                addView(d.text("LENS", 18, bold = true))
                addView(d.text("选择拍摄模式", 12, secondary = true))
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = d.dp(10) })
            heading.addView(d.icon("lens_settings", "LENS 设置", R.drawable.dhu_ic_settings).apply { tag = "lens_settings" })
            body.addView(heading, LinearLayout.LayoutParams(-1, -2))
            body.addView(modes, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.dp(16) })
            body.addView(empty, LinearLayout.LayoutParams(-1, -2))
            val scroll = ScrollView(context).apply {
                isFillViewport = false; isSaveEnabled = false
                addView(body, FrameLayout.LayoutParams(-1, -2))
            }
            root = d.card(scroll).apply { tag = this@Page }
        }

        fun render(values: Bundle) {
            val types = values.getStringArray("types") ?: emptyArray()
            val titles = values.getStringArray("titles") ?: emptyArray()
            require(types.size == titles.size)
            modes.removeAllViews()
            empty.visibility = if (types.isEmpty()) View.VISIBLE else View.GONE
            empty.text = values.getString("message", "请在右上角设置中选择拍摄模式")
            types.forEachIndexed { index, type ->
                modes.addView(d.button(type, titles[index]).apply {
                    tag = "lens_mode_$type"; textSize = 14f; minHeight = d.dp(72)
                    minWidth = 0; minimumWidth = 0; maxLines = 2
                    setPaddingRelative(d.dp(8), d.dp(12), d.dp(8), d.dp(12))
                    contentDescription = "打开${titles[index]}"
                })
            }
        }
    }
}
