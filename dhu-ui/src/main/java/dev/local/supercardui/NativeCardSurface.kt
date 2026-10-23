package dev.local.supercardui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import java.util.function.Consumer

/** Original-size card surface. Swipes belong to the host; accessibility retains page actions. */
object NativeCardSurface {
    @JvmStatic fun create(context: Context): View = Page(ContextThemeWrapper(context, R.style.Theme_DhuUi)).root
    @JvmStatic fun bind(root: View, action: Consumer<Bundle>) { (root.tag as Page).action = action }
    @JvmStatic fun render(root: View, state: Bundle) = (root.tag as Page).render(state)
    private class Page(val context: Context) {
        var action: Consumer<Bundle>? = null
        private var index = 0
        private var count = 0
        val root = FrameLayout(context).apply {
            tag = this@Page; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            addView(MaterialCardView(context).apply {
                radius = context.resources.getDimension(R.dimen.card_surface_corner)
                strokeWidth = 0; cardElevation = 0f
                clipToOutline = true
                setCardBackgroundColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceContainerLow, 0))
                addView(FrameLayout(context).apply { tag = "native_content" }, FrameLayout.LayoutParams(-1, -1))
            }, FrameLayout.LayoutParams(-1, -1))
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.isScrollable = count > 1
                    if (index > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
                    if (index + 1 < count) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS)
                }
                override fun performAccessibilityAction(host: View, value: Int, args: Bundle?): Boolean {
                    val name = when (value) {
                        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> if (index > 0) "previous" else null
                        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> if (index + 1 < count) "next" else null
                        AccessibilityNodeInfo.ACTION_DISMISS -> "close"
                        else -> return super.performAccessibilityAction(host, value, args)
                    } ?: return false
                    action?.accept(Bundle().apply { putString("action", name) }) ?: return false
                    return true
                }
            }
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit
                override fun onViewDetachedFromWindow(view: View) { action = null }
            })
        }
        fun render(state: Bundle) {
            index = state.getInt("index"); count = state.getInt("count")
            root.contentDescription = state.getString("title", "卡包") + "，" + (index + 1) + "/" + count
        }
    }
}
