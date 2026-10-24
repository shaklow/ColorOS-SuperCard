package dev.local.supercardui

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.view.ContextThemeWrapper
import java.util.function.Consumer

/** PAY chrome owns no account, app launch, payment content or display. */
object PayCardSurface {
    @JvmStatic fun create(resources: Context): View = Page(ContextThemeWrapper(resources, R.style.Theme_DhuUi)).root
    @JvmStatic fun render(root: View, state: Bundle) = (root.tag as Page).render(state)
    @JvmStatic fun bind(root: View, action: Consumer<Bundle>) { (root.tag as Page).action = action }

    private class Page(private val context: Context) {
        private val d = CardDesign(context)
        var action: Consumer<Bundle>? = null
        private val idle = FrameLayout(context).apply { tag = "pay_idle" }
        private val opened = FrameLayout(context).apply { tag = "pay_opened"; visibility = View.GONE }
        private val viewport = FrameLayout(context).apply {
            tag = "pay_viewport"; background = d.shape(ColorStateList.valueOf(android.graphics.Color.BLACK), R.dimen.card_inner_corner)
            clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        private val title = d.text("PAY", 15, bold = true).apply { tag = "pay_title"; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        private val message = d.text("请选择支付功能", 14, secondary = true).apply { tag = "pay_message"; gravity = Gravity.CENTER }
        private val loading = d.text("正在打开…", 14).apply {
            tag = "pay_loading"; gravity = Gravity.CENTER; isClickable = true
            background = d.shape(ColorStateList.valueOf(d.color(com.google.android.material.R.attr.colorSurfaceContainerLow)), R.dimen.card_inner_corner)
        }
        private val tiles = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        private val tabs = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; tag = "pay_tabs" }
        private val tabScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; isFillViewport = true; addView(tabs) }
        private val shortcuts = ScrollView(context).apply { isFillViewport = false; addView(tiles) }
        private var types = emptyList<String>()
        private var labels = emptyList<String>()
        private var selected: String? = null
        val root = FrameLayout(context).apply {
            tag = this@Page
            background = d.shape(ColorStateList.valueOf(d.color(com.google.android.material.R.attr.colorSurfaceContainerLow)), R.dimen.card_surface_corner)
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        init {
            val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            heading.addView(ImageView(context).apply {
                setImageResource(R.drawable.pay_ic_wallet)
                imageTintList = ColorStateList.valueOf(d.color(androidx.appcompat.R.attr.colorPrimary))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(d.dp(28), d.dp(28)))
            heading.addView(d.text("PAY", 18, bold = true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = d.dp(12) })
            heading.addView(d.icon("pay_settings", "PAY 设置", R.drawable.dhu_ic_settings).apply {
                tag = "pay_settings"; setOnClickListener { emit("settings") }
            })
            idle.addView(heading, FrameLayout.LayoutParams(-1, d.dp(48)).apply { leftMargin = d.dp(20); rightMargin = d.dp(10); topMargin = d.dp(10) })
            val artwork = ImageView(context).apply {
                setImageResource(R.drawable.pay_ic_wallet)
                imageTintList = ColorStateList.valueOf(d.color(androidx.appcompat.R.attr.colorPrimary))
                alpha = .22f; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                if (context.resources.configuration.fontScale > 1.3f) visibility = View.GONE
            }
            idle.addView(artwork, FrameLayout.LayoutParams(d.dp(88), d.dp(88), Gravity.CENTER).apply { bottomMargin = d.dp(120) })
            idle.addView(message, FrameLayout.LayoutParams(-1, d.dp(64), if (context.resources.configuration.fontScale > 1.3f) Gravity.TOP else Gravity.CENTER).apply {
                if (context.resources.configuration.fontScale > 1.3f) topMargin = d.dp(74) else bottomMargin = d.dp(12)
                leftMargin = d.dp(20); rightMargin = d.dp(20)
            })
            idle.addView(shortcuts, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                leftMargin = d.dp(20); rightMargin = d.dp(20); bottomMargin = d.dp(24)
            })
            root.addView(idle, FrameLayout.LayoutParams(-1, -1))

            val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            header.addView(d.icon("pay_back", "返回支付功能", R.drawable.pay_ic_back).apply {
                tag = "pay_back"; setOnClickListener { emit("back") }
            })
            header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(d.icon("pay_fullscreen", "全屏打开当前支付页面", R.drawable.pay_ic_fullscreen).apply {
                tag = "pay_fullscreen"; setOnClickListener { emit("fullscreen") }
            })
            opened.addView(header, FrameLayout.LayoutParams(-1, d.dp(48)).apply { leftMargin = d.dp(6); rightMargin = d.dp(6) })
            opened.addView(viewport, FrameLayout.LayoutParams(-1, -1).apply { leftMargin = d.dp(21); rightMargin = d.dp(21); topMargin = d.dp(51); bottomMargin = d.dp(85) })
            opened.addView(loading, FrameLayout.LayoutParams(-1, -1).apply { leftMargin = d.dp(21); rightMargin = d.dp(21); topMargin = d.dp(51); bottomMargin = d.dp(85) })
            opened.addView(tabScroll, FrameLayout.LayoutParams(-1, d.dp(64), Gravity.BOTTOM).apply { leftMargin = d.dp(6); rightMargin = d.dp(6); bottomMargin = d.dp(6) })
            root.addView(opened, FrameLayout.LayoutParams(-1, -1))
        }
        private fun emit(action: String, function: String? = null) = this.action?.accept(Bundle().apply {
            putString("action", action); if (function != null) putString("function", function)
        })
        private fun tile(type: String, label: String, compact: Boolean): View {
            val tight = compact && context.resources.configuration.fontScale >= 1.3f
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                tag = if (compact) "pay_tab_$type" else "pay_function_$type"
                contentDescription = label; isClickable = true; isFocusable = true
                setPadding(d.dp(3), d.dp(if (tight) 2 else 4), d.dp(3), d.dp(if (tight) 2 else 4))
                setOnClickListener { emit("function", type) }
            }
            val icon = ImageView(context).apply {
                tag = if (compact) "pay_tab_icon_$type" else "pay_icon_$type"
                setImageDrawable(AppCompatResources.getDrawable(context, R.drawable.pay_ic_wallet))
                imageTintList = ColorStateList.valueOf(d.color(com.google.android.material.R.attr.colorOnSurface))
            }
            val large = context.resources.configuration.fontScale > 1.3f
            cell.addView(icon, LinearLayout.LayoutParams(d.dp(if (tight) 20 else 24), d.dp(if (tight) 20 else 24)))
            val shortLabel = if (label.startsWith("微信分身")) "Ⅱ" + label.removePrefix("微信分身")
                else label.removePrefix("微信").removePrefix("支付宝")
            val text = d.text(if (compact) shortLabel else label, if (compact && large) 9 else if (compact) 10 else 12).apply {
                gravity = Gravity.CENTER; maxLines = if (!compact && large) 3 else 2
                if (compact) { includeFontPadding = false; setLineSpacing(0f, 1f) }
            }
            cell.addView(text, LinearLayout.LayoutParams(-1, -2))
            val selection = ImageView(context)
            // Reuse the existing PAY tonal treatment without importing OEM holders or models.
            PayCardAppearance.style(context, "function", arrayOf(cell, selection, icon, text))
            return cell
        }
        private fun rebuild() {
            tiles.removeAllViews(); tabs.removeAllViews()
            // OEM shortcut ordering fills a column from top to bottom before the next column.
            val columns = (types.size + 1) / 2
            for (column in 0 until columns) {
                val group = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                for (row in 0..1) {
                    val index = column * 2 + row
                    if (index >= types.size) break
                    group.addView(tile(types[index], labels[index], false), LinearLayout.LayoutParams(-1, d.dp(if (context.resources.configuration.fontScale > 1.3f) 124 else 84)).apply {
                        if (row > 0) topMargin = d.dp(10)
                    })
                }
                tiles.addView(group, LinearLayout.LayoutParams(0, -2, 1f).apply { if (column > 0) marginStart = d.dp(10) })
            }
            types.forEachIndexed { index, type ->
                tabs.addView(tile(type, labels[index], true), LinearLayout.LayoutParams(
                    if (context.resources.configuration.fontScale > 1.3f) d.dp(72) else 0, -1,
                    if (context.resources.configuration.fontScale > 1.3f) 0f else 1f))
            }
        }
        fun render(state: Bundle) {
            val nextTypes = state.getStringArray("types")?.toList() ?: emptyList()
            val nextLabels = state.getStringArray("titles")?.toList() ?: emptyList()
            require(nextTypes.size == nextLabels.size && nextTypes.distinct().size == nextTypes.size)
            if (nextTypes != types || nextLabels != labels) { types = nextTypes; labels = nextLabels; rebuild() }
            selected = state.getString("selected")?.takeIf { it in types }
            idle.visibility = if (selected == null) View.VISIBLE else View.GONE
            opened.visibility = if (selected == null) View.GONE else View.VISIBLE
            message.text = state.getString("message", if (types.isEmpty()) "请在右上角设置中选择支付功能" else "请选择支付功能")
            title.text = selected?.let { labels[types.indexOf(it)] } ?: "PAY"
            loading.text = state.getString("message", "正在打开…")
            loading.visibility = if (state.getBoolean("loading") && selected != null) View.VISIBLE else View.GONE
            tabs.visibility = if (types.size > 1) View.VISIBLE else View.GONE
            tabScroll.visibility = tabs.visibility
            val bottom = if (types.size > 1) 85 else 21
            for (view in listOf(viewport, loading)) view.layoutParams = (view.layoutParams as FrameLayout.LayoutParams).apply { bottomMargin = d.dp(bottom) }
            types.forEach { type -> root.findViewWithTag<View>("pay_tab_$type")?.isSelected = type == selected }
        }
    }
}
