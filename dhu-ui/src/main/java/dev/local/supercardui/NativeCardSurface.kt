package dev.local.supercardui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import java.util.function.Consumer

/** Framework-only surface contract for the independent Kotlin card host. */
object NativeCardSurface {
    @JvmStatic fun create(context: Context): View = Page(ContextThemeWrapper(context, R.style.Theme_DhuUi)).root
    @JvmStatic fun bind(root: View, action: Consumer<Bundle>) { (root.tag as Page).action = action }
    @JvmStatic fun render(root: View, state: Bundle) = (root.tag as Page).render(state)
    private class Page(val context: Context) {
        var action: Consumer<Bundle>? = null
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; tag = this@Page
            background = GradientDrawable().apply {
                setColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceContainerLow, 0))
                cornerRadius = context.resources.getDimension(R.dimen.card_surface_corner)
            }
            clipToOutline = true
        }
        private val previous = button("上一张", "previous")
        private val next = button("下一张", "next")
        init {
            root.addView(MaterialCardView(context).apply {
                radius = context.resources.getDimension(R.dimen.card_surface_corner)
                strokeWidth = 0; setCardBackgroundColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceContainerLow, 0))
                addView(FrameLayout(context).apply { tag = "native_content" }, FrameLayout.LayoutParams(-1, -1))
            }, LinearLayout.LayoutParams(-1, 0, 1f))
            root.addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER
                for (button in listOf(previous, next, button("收起", "close")))
                    addView(button, LinearLayout.LayoutParams(0, dp(48), 1f))
            }, LinearLayout.LayoutParams(-1, dp(56)))
            root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit
                override fun onViewDetachedFromWindow(view: View) { action = null }
            })
        }
        fun render(state: Bundle) {
            previous.isEnabled = state.getInt("index") > 0
            next.isEnabled = state.getInt("index") + 1 < state.getInt("count")
            root.contentDescription = "${state.getString("title", "卡包")}，${state.getInt("index") + 1}/${state.getInt("count")}"
        }
        private fun button(label: String, name: String) = MaterialButton(context, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = label; tag = "native_$name"; minimumWidth = 0; minWidth = 0
            setPadding(0, 0, 0, 0); setOnClickListener { action?.accept(Bundle().apply { putString("action", name) }) }
        }
        private fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    }
}
