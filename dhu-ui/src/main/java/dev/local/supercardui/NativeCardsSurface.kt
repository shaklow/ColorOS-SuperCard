package dev.local.supercardui

import android.animation.ValueAnimator
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.view.ContextThemeWrapper

/** UI only. Feature data and callbacks cross the archive as framework types. */
object NativeCardsSurface {
    @JvmStatic fun create(resources: Context): View = Page(ContextThemeWrapper(resources, R.style.Theme_DhuUi)).root
    @JvmStatic fun renderLens(root: View, values: Bundle) = (root.tag as Page).renderLens(values)

    private class Page(context: Context) {
        private val d = CardDesign(context)
        val root = FrameLayout(context)
        private val modes = object : GridLayout(context) {
            override fun onMeasure(width: Int, height: Int) {
                val columns = if (MeasureSpec.getSize(width) >= d.dp(300) && resources.configuration.fontScale <= 1.2f) 2 else 1
                if (columnCount != columns) {
                    val children = (0 until childCount).map { getChildAt(it) }
                    removeAllViews()
                    columnCount = columns
                    children.forEach { addView(it, GridLayout.LayoutParams()) }
                }
                for (index in 0 until childCount) {
                    getChildAt(index).layoutParams = GridLayout.LayoutParams().apply {
                        this.width = 0; this.height = -2
                        columnSpec = GridLayout.spec(index % columns, 1f); rowSpec = GridLayout.spec(index / columns)
                        setMargins(if (index % columns == 0) 0 else d.dp(8), 0, 0, d.dp(6))
                    }
                }
                super.onMeasure(width, height)
            }
        }
        init {
            root.tag = this
            root.setBackgroundColor(d.color(com.google.android.material.R.attr.colorSurface))
            val body = object : LinearLayout(context) {
                override fun onMeasure(width: Int, height: Int) {
                    super.onMeasure(MeasureSpec.makeMeasureSpec(minOf(MeasureSpec.getSize(width), d.dp(600)), MeasureSpec.getMode(width)), height)
                }
            }.apply { orientation = LinearLayout.VERTICAL; setPadding(d.dp(20), d.dp(12), d.dp(20), d.dp(28)) }
            val scroll = ScrollView(context).apply {
                isFillViewport = true; clipToPadding = false
                addView(body, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            }
            root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
            root.setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                scroll.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
            }
            val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            header.addView(d.text("超级卡包", 30, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(d.icon("settings", "卡包设置", R.drawable.dhu_ic_settings))
            body.addView(header, LinearLayout.LayoutParams(-1, -2))
            body.addView(d.text("常用卡片，触手可及", 14, secondary = true), spacing(0, 24))
            body.addView(FrameLayout(context).apply { tag = "native_campus" }, LinearLayout.LayoutParams(-1, d.dp(460)))
            val lens = d.column().apply { setPadding(d.dp(20), d.dp(16), d.dp(20), d.dp(20)) }
            val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            val copy = d.column().apply {
                addView(d.text("LENS", 24, bold = true))
                addView(d.text("捕捉眼前的灵感", 13, secondary = true))
            }
            heading.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
            heading.addView(d.icon("lens_settings", "相机快捷方式设置", R.drawable.dhu_ic_settings))
            lens.addView(heading, LinearLayout.LayoutParams(-1, -2))
            lens.addView(modes, spacing(12, 0))
            body.addView(d.card(lens), spacing(16, 16))
            val legacy = d.column().apply {
                setPadding(d.dp(20), d.dp(20), d.dp(20), d.dp(16))
                addView(d.text("更多卡片", 20, bold = true))
                addView(d.text("PAY · 记忆 · 卡券", 14, secondary = true), spacing(6, 12))
                addView(d.button("legacy", "打开卡包"), LinearLayout.LayoutParams(-1, -2))
            }
            body.addView(d.card(legacy, primary = true), spacing(0, 0))
            root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    v.requestApplyInsets()
                    if (ValueAnimator.areAnimatorsEnabled()) {
                        body.alpha = 0f; body.translationY = d.dp(16).toFloat()
                        body.animate().alpha(1f).translationY(0f).setDuration(350)
                            .setInterpolator(PathInterpolator(.2f, 0f, 0f, 1f)).start()
                    }
                }
                override fun onViewDetachedFromWindow(v: View) { body.animate().cancel() }
            })
        }
        fun renderLens(values: Bundle) {
            modes.removeAllViews()
            val types = values.getStringArray("types") ?: emptyArray()
            val titles = values.getStringArray("titles") ?: emptyArray()
            require(types.size == titles.size)
            if (types.isEmpty()) {
                modes.addView(d.text("还没有快捷方式，点右上角设置添加", 14, secondary = true))
                return
            }
            types.forEachIndexed { index, type ->
                modes.addView(d.button("lens_$type", titles[index]).apply { minHeight = d.dp(64) })
            }
        }
        private fun spacing(top: Int, bottom: Int) = LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = d.dp(top); bottomMargin = d.dp(bottom)
        }
    }
}
