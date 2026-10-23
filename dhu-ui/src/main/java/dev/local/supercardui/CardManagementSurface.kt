package dev.local.supercardui

import android.content.ClipData
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.*
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.AppCompatImageButton
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textview.MaterialTextView
import java.util.function.Consumer

/** Only framework Bundle and Consumer cross the archive boundary. No runtime card views are created. */
object CardManagementSurface {
    @JvmStatic fun create(context: Context): View = Page(ContextThemeWrapper(context, R.style.Theme_DhuUi)).root
    @JvmStatic fun render(root: View, state: Bundle) = (root.tag as Page).render(state)
    @JvmStatic fun bind(root: View, action: Consumer<Bundle>) { (root.tag as Page).action = action }

    private class Page(val context: Context) {
        val root = FrameLayout(context)
        var action: Consumer<Bundle>? = null
        private val body = object : LinearLayout(context) {
            override fun onMeasure(width: Int, height: Int) {
                super.onMeasure(MeasureSpec.makeMeasureSpec(minOf(MeasureSpec.getSize(width),
                    context.resources.getDimensionPixelSize(R.dimen.settings_page_max_width)), MeasureSpec.getMode(width)), height)
            }
        }.apply { orientation = LinearLayout.VERTICAL; setPadding(gutter(), dp(8), gutter(), dp(28)) }
        private val list = vertical().apply { tag = key("list") }
        private val count = text("正在读取…", 18, strong = true).apply { tag = key("count") }
        private val message = text("正在读取卡片…", 14, secondary = true).apply {
            tag = key("message"); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        private val permission = text("尚未取得设置保存权限，请到运行状态页完成授权。", 14, secondary = true)
        private val progress = ProgressBar(context).apply { tag = key("progress") }
        private val retry = button("刷新", "retry")
        private lateinit var permissionPanel: View
        private val rows = linkedMapOf<String, Row>()
        private var order = emptyList<String>()
        private var writable = false
        private var busy = true
        private data class Drag(val page: Page, val type: String)

        init {
            root.tag = this
            root.setBackgroundColor(color(com.google.android.material.R.attr.colorSurface))
            val scroll = ScrollView(context).apply {
                tag = key("scroll"); isFillViewport = true; clipToPadding = false; isSaveEnabled = false
                addView(body, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            }
            root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
            root.setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                scroll.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
            }
            root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { v.requestApplyInsets() }
                override fun onViewDetachedFromWindow(v: View) { action = null }
            })
            add(MaterialToolbar(context).apply {
                title = "超级卡包"
                setTitleTextAppearance(context, com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                setTitleTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setContentInsetsRelative(0, 0); minimumHeight = dp(48)
                navigationIcon = AppCompatResources.getDrawable(context, R.drawable.settings_ic_back)
                setNavigationIconTint(color(androidx.appcompat.R.attr.colorPrimary)); navigationContentDescription = "返回"
                setNavigationOnClickListener { emit("back") }
                (0 until childCount).map { getChildAt(it) }.filterIsInstance<ImageButton>().first().tag = key("back")
            }, bottom = 12)
            add(text("卡片管理", 32, strong = true), bottom = 8)
            add(text("让常用卡片排在顺手的位置", 14, secondary = true), bottom = 24)
            val hero = horizontal().apply {
                setPadding(dp(18), dp(12), dp(12), dp(12))
                addView(count, LinearLayout.LayoutParams(0, -2, 1f))
                addView(retry, LinearLayout.LayoutParams(dp(76), -2))
            }
            add(card(hero, com.google.android.material.R.attr.colorPrimaryContainer), bottom = 12)
            add(horizontal().apply {
                addView(message, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
                addView(progress, LinearLayout.LayoutParams(dp(24), dp(24)))
            }, bottom = 16)
            permissionPanel = vertical().apply {
                addView(permission, linear(bottom = 8))
                addView(button("运行状态与 PAY", "runtime"), linear(bottom = 16))
            }.also { add(it) }
            add(text("显示顺序", 14, strong = true), bottom = 8)
            add(text("长按手柄或点箭头排序，至少启用一张卡片。", 13, secondary = true), bottom = 16)
            add(list)
            add(button("手势与唤出", "gestures"), top = 16)
            add(button("运行状态与 PAY", "runtime_footer").apply { setOnClickListener { emit("runtime") } })
        }

        fun render(state: Bundle) {
            busy = state.getBoolean("busy"); writable = state.getBoolean("writable")
            message.text = state.getString("message")
            progress.visibility = if (busy) View.VISIBLE else View.INVISIBLE
            retry.isEnabled = !busy
            permissionPanel.visibility = if (!state.getBoolean("authorized", writable) && !busy) View.VISIBLE else View.GONE
            val cards = state.getParcelableArrayList<Bundle>("cards").orEmpty()
            android.util.Log.d("SuperCardManagement", "render busy=$busy writable=$writable cards=${cards.size}")
            val next = cards.map { requireNotNull(it.getString("type")) }
            val enabled = cards.count { it.getBoolean("enabled") }
            count.text = if (cards.isEmpty()) "等待加载" else "$enabled / ${cards.size} 张已启用"
            if (next != order) {
                list.removeAllViews()
                rows.keys.retainAll(next.toSet())
                next.forEach { type -> list.addView(rows.getOrPut(type) { Row(type) }.card, linear(bottom = 12)) }
                order = next
            }
            cards.forEachIndexed { index, value -> rows.getValue(next[index]).render(value, index, enabled) }
        }

        private inner class Row(val type: String) {
            private val title = text("", 19, strong = true)
            private val detail = text(description(type), 13, secondary = true)
            private val state = text("", 12, secondary = true)
            private val toggle = MaterialSwitch(context).apply {
                tag = key("toggle_$type"); isSaveEnabled = false; minimumHeight = dp(48)
            }
            private val grip = iconButton(R.drawable.management_ic_drag, "drag_$type")
            private val up = iconButton(R.drawable.management_ic_up, "up_$type")
            private val down = iconButton(R.drawable.management_ic_down, "down_$type")
            private val settings = button(when (type) {
                "dhu" -> "授权设置"; "staging" -> "管理卡券"; else -> "功能设置"
            }, "settings_$type").apply {
                setPadding(dp(8), paddingTop, dp(8), paddingBottom)
                minWidth = 0; minimumWidth = 0
            }
            val card: MaterialCardView

            init {
                val content = vertical().apply { setPadding(dp(16), dp(16), dp(16), dp(14)) }
                val header = horizontal()
                header.addView(FrameLayout(context).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(16).toFloat(); setColor(color(com.google.android.material.R.attr.colorSecondaryContainer))
                    }
                    addView(ImageView(context).apply {
                        setImageDrawable(AppCompatResources.getDrawable(context, icon(type)))
                        imageTintList = ColorStateList.valueOf(color(com.google.android.material.R.attr.colorOnSecondaryContainer))
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
                header.addView(title, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12); marginEnd = dp(8) })
                header.addView(toggle, LinearLayout.LayoutParams(-2, -2))
                content.addView(header)
                content.addView(detail, linear(top = 12))
                content.addView(state, linear(top = 6, bottom = 8))
                val controls = horizontal()
                controls.addView(grip, LinearLayout.LayoutParams(dp(48), dp(48)))
                controls.addView(up, LinearLayout.LayoutParams(dp(48), dp(48)))
                controls.addView(down, LinearLayout.LayoutParams(dp(48), dp(48)))
                controls.addView(settings, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
                content.addView(controls)
                card = this@Page.card(content).apply { tag = key("card_$type") }
                grip.setOnLongClickListener {
                    val started = writable && !busy && grip.startDragAndDrop(ClipData.newPlainText("卡片排序", ""),
                        View.DragShadowBuilder(card), Drag(this@Page, type), 0)
                    android.util.Log.d("SuperCardManagement", "long press type=$type busy=$busy writable=$writable started=$started")
                    started
                }
                up.setOnClickListener { move(-1) }; down.setOnClickListener { move(1) }
                settings.setOnClickListener { if (!busy) emit("settings", type) }
                card.setOnDragListener { _, event ->
                    val drag = event.localState as? Drag
                    val accepted = writable && !busy && drag != null && drag.page === this@Page && drag.type != type && drag.type in order
                    when (event.action) {
                        DragEvent.ACTION_DRAG_STARTED -> accepted
                        DragEvent.ACTION_DRAG_ENTERED -> { if (accepted) card.strokeWidth = dp(2); accepted }
                        DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> { card.strokeWidth = 0; true }
                        DragEvent.ACTION_DROP -> {
                            card.strokeWidth = 0
                            if (accepted) emit("move", drag!!.type, target = type)
                            accepted
                        }
                        else -> accepted
                    }
                }
            }

            fun render(value: Bundle, index: Int, enabledCount: Int) {
                val label = value.getString("title").orEmpty(); title.text = label
                val checked = value.getBoolean("enabled")
                state.text = "${index + 1} · ${if (checked) "已启用" else "未启用，保留当前位置"}"
                toggle.setOnCheckedChangeListener(null); toggle.isChecked = checked
                toggle.contentDescription = "$label，启用卡片"
                toggle.isEnabled = writable && !busy && (!checked || enabledCount > 1)
                toggle.setOnCheckedChangeListener { _, selected -> if (writable && !busy) emit("toggle", type, selected) }
                grip.isEnabled = writable && !busy && order.size > 1
                grip.contentDescription = "$label，长按拖动排序"
                up.isEnabled = writable && !busy && index > 0; up.contentDescription = "$label，上移"
                down.isEnabled = writable && !busy && index < order.lastIndex; down.contentDescription = "$label，下移"
                settings.visibility = if (type in setOf("pay", "camera", "dhu", "staging")) View.VISIBLE else View.GONE
                settings.isEnabled = !busy
                settings.contentDescription = "$label，${settings.text}"
            }

            private fun move(delta: Int) {
                val target = order.getOrNull(order.indexOf(type) + delta) ?: return
                if (writable && !busy) emit("move", type, target = target)
            }
        }

        private fun emit(kind: String, type: String? = null, value: Boolean = false, target: String? = null) {
            action?.accept(Bundle().apply {
                putString("action", kind); putString("type", type); putBoolean("value", value); putString("target", target)
            })
        }
        private fun icon(type: String) = when (type) {
            "pay" -> R.drawable.pay_ic_wallet; "camera" -> R.drawable.lens_ic_camera
            "dhu" -> R.drawable.dhu_ic_school; "memory" -> R.drawable.management_ic_memory
            "staging" -> R.drawable.management_ic_ticket; else -> R.drawable.settings_ic_cards
        }
        private fun description(type: String) = when (type) {
            "pay" -> "微信与支付宝的扫码、付款及乘车入口"
            "camera" -> "选择并排列常用相机模式"
            "memory" -> "快速记录与保存内容"
            "staging" -> "查看钱包中的卡券"
            "dhu" -> "东华校园卡付款码与授权设置"
            else -> "保留现有卡片配置"
        }
        private fun iconButton(drawable: Int, name: String) = AppCompatImageButton(context, null, 0).apply {
            tag = key(name); setImageDrawable(AppCompatResources.getDrawable(context, drawable))
            isClickable = true; isFocusable = true
            val tint = color(com.google.android.material.R.attr.colorOnSurfaceVariant)
            imageTintList = ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf((tint and 0x00ffffff) or (0x61 shl 24), tint))
            background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf((tint and 0x00ffffff) or (0x1f shl 24)), null,
                android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(android.graphics.Color.WHITE)
                })
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            minimumWidth = dp(48); minimumHeight = dp(48); setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        private fun button(label: String, name: String) = MaterialButton(context, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            tag = key(name); text = label; isAllCaps = false; minimumHeight = dp(48); maxLines = 3
            setOnClickListener { emit(name) }
        }
        private fun card(content: View, surface: Int = com.google.android.material.R.attr.colorSurfaceContainerLow) = MaterialCardView(context).apply {
            setCardBackgroundColor(color(surface)); radius = context.resources.getDimension(R.dimen.settings_card_corner)
            cardElevation = 0f; strokeWidth = 0; setStrokeColor(color(androidx.appcompat.R.attr.colorPrimary))
            addView(content, FrameLayout.LayoutParams(-1, -2))
        }
        private fun text(value: String, size: Int, secondary: Boolean = false, strong: Boolean = false) = MaterialTextView(context).apply {
            text = value; textSize = size.toFloat(); setLineSpacing(dp(3).toFloat(), 1f)
            setTextColor(color(if (secondary) com.google.android.material.R.attr.colorOnSurfaceVariant else com.google.android.material.R.attr.colorOnSurface))
            if (strong) setTypeface(typeface, Typeface.BOLD)
        }
        private fun vertical() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        private fun horizontal() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        private fun linear(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top); bottomMargin = dp(bottom) }
        private fun add(view: View, top: Int = 0, bottom: Int = 0, width: Int = -1, height: Int = -2) {
            body.addView(view, linear(top, bottom).apply { this.width = width; this.height = height })
        }
        private fun color(attr: Int) = MaterialColors.getColor(context, attr, "Card management")
        private fun gutter() = context.resources.getDimensionPixelSize(R.dimen.settings_page_gutter)
        private fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
        private fun key(name: String) = "management_$name"
    }
}
