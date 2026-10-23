package dev.local.supercardui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textview.MaterialTextView
import java.util.function.Consumer
import kotlin.math.abs
import kotlin.math.roundToInt

/** App-owned settings. The archive only renders values and emits framework Bundles. */
object GestureSettingsSurface {
    @JvmStatic fun create(context: Context): View = Page(ContextThemeWrapper(context, R.style.Theme_DhuUi)).root
    @JvmStatic fun render(root: View, state: Bundle) = (root.tag as Page).render(state)
    @JvmStatic fun bind(root: View, action: Consumer<Bundle>) { (root.tag as Page).action = action }

    private class Page(val context: Context) {
        val root = FrameLayout(context)
        var action: Consumer<Bundle>? = null
        private var rendering = false
        private var loaded = false
        private var values = Bundle()
        private var dialog: AlertDialog? = null
        private val lockLabels = arrayOf("关闭", "整条右侧", "跟随唤出范围", "右下部分")
        private val body = object : LinearLayout(context) {
            override fun onMeasure(width: Int, height: Int) {
                super.onMeasure(MeasureSpec.makeMeasureSpec(minOf(MeasureSpec.getSize(width),
                    resources.getDimensionPixelSize(R.dimen.settings_page_max_width)), MeasureSpec.getMode(width)), height)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            val gutter = context.resources.getDimensionPixelSize(R.dimen.settings_page_gutter)
            setPadding(gutter, dp(8), gutter, dp(28))
        }
        private val message = text("正在连接卡包服务…", 14, true).apply {
            tag = key("message"); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        private val preview = PhonePreview().apply { tag = key("preview") }
        private val range = text("", 14, true)
        private val sliders = linkedMapOf<String, Pair<Slider, TextView>>()
        private val toggles = linkedMapOf<String, MaterialSwitch>()
        private val lock = button("锁屏呼出方式", "lock")
        private val reset = button("恢复浮标默认外观", "reset")

        init {
            root.tag = this; root.setBackgroundColor(color(com.google.android.material.R.attr.colorSurface))
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
                override fun onViewDetachedFromWindow(v: View) { action = null; dialog?.dismiss(); dialog = null }
            })
            add(MaterialToolbar(context).apply {
                title = "超级卡包"; minimumHeight = dp(48); setContentInsetsRelative(0, 0)
                setTitleTextAppearance(context, com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                setTitleTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
                navigationIcon = AppCompatResources.getDrawable(context, R.drawable.settings_ic_back)
                setNavigationIconTint(color(androidx.appcompat.R.attr.colorPrimary)); navigationContentDescription = "返回"
                setNavigationOnClickListener { emit("back") }
                (0 until childCount).map { getChildAt(it) }.filterIsInstance<ImageButton>().first().tag = key("back")
            }, bottom = 12)
            add(text("手势与唤出", 32, strong = true), bottom = 8)
            add(text("调整右侧唤出范围，让浮标更顺手", 14, true), bottom = 20)
            add(card(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(16))
                addView(preview, LinearLayout.LayoutParams(-1, dp(240)))
                addView(range.apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
                addView(text("拖动标记移动范围，拖动端点调整长度", 12, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }), bottom = 16)
            add(message, bottom = 8); add(button("刷新连接", "retry"), bottom = 20)
            val controls = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(12)) }
            for ((name, label) in listOf("position" to "浮标位置", "length" to "唤出范围长度", "opacity" to "浮标不透明度")) {
                val title = text(label, 16, strong = true)
                val slider = Slider(context).apply {
                    tag = key(name); valueFrom = 0f; valueTo = 1000f; stepSize = if (name == "opacity") 10f else 1f
                    contentDescription = label; isSaveEnabled = false; minimumHeight = dp(48)
                    setLabelFormatter { value ->
                        val fraction = value / 1000
                        val percentage = when (name) {
                            "length" -> (.0401f + fraction * .9599f) * 100
                            "position" -> fraction * (1 - this@Page.values.getFloat("bottom", .98f) + this@Page.values.getFloat("top", .76f)) * 100
                            else -> fraction * 100
                        }
                        "${percentage.roundToInt()}%"
                    }
                    addOnChangeListener { _, value, fromUser -> if (fromUser && !rendering && loaded) emit(name) { putInt("value", value.roundToInt()) } }
                    addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                        override fun onStartTrackingTouch(slider: Slider) { emit("tracking") { putInt("kind", if (name == "position") 0 else if (name == "length") 1 else 2) } }
                        override fun onStopTrackingTouch(slider: Slider) { emit("commit") }
                    })
                }
                sliders[name] = slider to title
                controls.addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                controls.addView(slider, LinearLayout.LayoutParams(-1, dp(56)))
            }
            add(card(controls), bottom = 24)
            add(text("唤出方式", 18, strong = true), bottom = 12)
            toggle("unlocked", "非锁屏侧滑")
            add(lock.apply { setOnClickListener { chooseLock() } }, bottom = 20)
            add(text("浮标显示", 18, strong = true), bottom = 12)
            toggle("handleVisible", "显示唤出浮标"); toggle("handleAutoHide", "自动隐藏浮标")
            add(text("浮标只提示唤出范围。锁屏呼出方式单独生效。", 13, true), bottom = 16)
            add(reset)
            render(Bundle())
        }

        fun render(state: Bundle) {
            values = Bundle(state); loaded = state.getBoolean("loaded")
            rendering = true
            try {
                val enabled = state.getBoolean("enabled", true); val visible = state.getBoolean("handleVisible", true)
                val top = state.getFloat("top", .76f); val bottom = state.getFloat("bottom", .98f)
                setText(message, state.getString("message") ?: if (loaded) "调整后自动保存" else "正在连接卡包服务…")
                setText(range, "屏幕 ${(top * 100).roundToInt()}% – ${(bottom * 100).roundToInt()}%")
                preview.isEnabled = loaded
                preview.contentDescription = "唤出范围，屏幕 ${(top * 100).roundToInt()}% 到 ${(bottom * 100).roundToInt()}%，上下滑动调整位置"
                preview.invalidate()
                for ((name, pair) in sliders) {
                    val value = state.getInt(name, if (name == "opacity") 400 else 0).coerceIn(0, 1000).toFloat()
                    if (pair.first.value != value) pair.first.value = value
                    pair.first.isEnabled = loaded && (name != "opacity" || enabled && visible)
                    setText(pair.second, when (name) {
                        "position" -> "浮标位置 · 屏幕 ${(top * 100).roundToInt()}%"
                        "length" -> "唤出范围长度 · ${((bottom - top) * 100).roundToInt()}%"
                        else -> "浮标不透明度 · ${(value / 10).roundToInt()}%"
                    })
                }
                toggles.forEach { (name, toggle) ->
                    val checked = state.getBoolean(name, true)
                    if (toggle.isChecked != checked) toggle.isChecked = checked
                    toggle.isEnabled = loaded && (name == "unlocked" || enabled && (name != "handleAutoHide" || visible))
                }
                lock.isEnabled = loaded; reset.isEnabled = loaded
                lock.text = "锁屏呼出方式 · ${lockLabels[state.getInt("lockStyle").coerceIn(0, 3)]}"
            } finally { rendering = false }
        }
        private fun toggle(name: String, label: String) {
            val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(56) }
            row.addView(text(label, 16), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
            val control = MaterialSwitch(context).apply {
                tag = key(name); minimumHeight = dp(48); isSaveEnabled = false; contentDescription = label
                setOnCheckedChangeListener { _, checked -> if (!rendering && loaded) emit(name) { putBoolean("value", checked) } }
            }
            toggles[name] = control; row.addView(control); add(row, bottom = 8)
        }
        private fun chooseLock() {
            if (!loaded) return
            dialog?.dismiss()
            dialog = MaterialAlertDialogBuilder(context).setTitle("锁屏呼出方式")
                .setSingleChoiceItems(lockLabels, values.getInt("lockStyle").coerceIn(0, 3)) { view, index ->
                    view.dismiss(); if (loaded) emit("lockStyle") { putInt("value", index) }
                }.setNegativeButton("取消", null).create().also { it.show() }
        }
        private fun emit(name: String, data: Bundle.() -> Unit = {}) { action?.accept(Bundle().apply { putString("action", name); data() }) }

        private inner class PhonePreview : View(context) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            private var mode = -1
            private var offset = 0f
            init { isFocusable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
            private fun bounds() = floatArrayOf(width / 2f - dp(58), dp(10).toFloat(), width / 2f + dp(58), height - dp(10).toFloat())
            override fun onDraw(canvas: Canvas) {
                val b = bounds(); val h = b[3] - b[1]
                paint.color = color(com.google.android.material.R.attr.colorSurfaceContainerHighest)
                canvas.drawRoundRect(b[0], b[1], b[2], b[3], dp(22).toFloat(), dp(22).toFloat(), paint)
                paint.color = color(com.google.android.material.R.attr.colorOnSurfaceVariant); paint.alpha = 45
                canvas.drawRoundRect(b[0] + dp(18), b[1] + dp(36), b[2] - dp(18), b[1] + dp(42), dp(3).toFloat(), dp(3).toFloat(), paint)
                val start = b[1] + h * values.getFloat("top", .76f); val end = b[1] + h * values.getFloat("bottom", .98f)
                paint.color = color(androidx.appcompat.R.attr.colorPrimary); paint.alpha = 255
                canvas.drawRoundRect(b[2] - dp(4), start, b[2] + dp(2), end, dp(3).toFloat(), dp(3).toFloat(), paint)
                canvas.drawCircle(b[2], start, dp(6).toFloat(), paint); canvas.drawCircle(b[2], end, dp(6).toFloat(), paint)
                paint.alpha = 255
            }
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (!isEnabled) return false
                val b = bounds(); val h = b[3] - b[1]; val y = ((event.y - b[1]) / h).coerceIn(0f, 1f)
                val top = values.getFloat("top", .76f); val bottom = values.getFloat("bottom", .98f)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (abs(event.x - b[2]) > dp(28)) return false
                        val startDistance = abs(y - top) * h; val endDistance = abs(y - bottom) * h
                        mode = if (minOf(startDistance, endDistance) < dp(14)) { if (startDistance <= endDistance) 1 else 2 } else 0
                        offset = y - top; parent.requestDisallowInterceptTouchEvent(true)
                        emit("tracking") { putInt("kind", if (mode == 0) 0 else 1) }; return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (mode < 0) return false
                        val length = bottom - top
                        val start = when (mode) { 1 -> y.coerceIn(0f, (bottom - .0401f).coerceAtLeast(0f)); 2 -> top; else -> (y - offset).coerceIn(0f, (1 - length).coerceAtLeast(0f)) }
                        val end = if (mode == 2) y.coerceIn((top + .0401f).coerceAtMost(1f), 1f) else if (mode == 1) bottom else start + length
                        emit("range") { putFloat("top", start); putFloat("bottom", end) }; return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (mode < 0) return false
                        mode = -1; parent.requestDisallowInterceptTouchEvent(false); emit("commit"); performClick(); return true
                    }
                }
                return mode >= 0
            }
            override fun performClick(): Boolean { super.performClick(); return true }
            override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(info)
                if (isEnabled) { info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD); info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD) }
            }
            override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean = when (action) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> nudge(.02f)
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> nudge(-.02f)
                else -> super.performAccessibilityAction(action, arguments)
            }
            override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> nudge(-.02f); KeyEvent.KEYCODE_DPAD_DOWN -> nudge(.02f)
                else -> super.onKeyDown(keyCode, event)
            }
            private fun nudge(delta: Float): Boolean {
                if (!isEnabled) return false
                val length = values.getFloat("bottom", .98f) - values.getFloat("top", .76f)
                val start = (values.getFloat("top", .76f) + delta).coerceIn(0f, (1 - length).coerceAtLeast(0f))
                emit("range") { putFloat("top", start); putFloat("bottom", start + length) }; emit("commit"); return true
            }
        }
        private fun button(label: String, name: String) = MaterialButton(context, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = label; tag = key(name); minimumHeight = dp(48); isAllCaps = false; maxLines = 3; setOnClickListener { emit(name) }
        }
        private fun card(content: View) = MaterialCardView(context).apply {
            radius = resources.getDimension(R.dimen.settings_card_corner); strokeWidth = 0; cardElevation = 0f
            setCardBackgroundColor(color(com.google.android.material.R.attr.colorSurfaceContainerLow)); addView(content, FrameLayout.LayoutParams(-1, -2))
        }
        private fun text(value: String, size: Int, secondary: Boolean = false, strong: Boolean = false) = MaterialTextView(context).apply {
            text = value; textSize = size.toFloat(); setLineSpacing(dp(3).toFloat(), 1f)
            setTextColor(color(if (secondary) com.google.android.material.R.attr.colorOnSurfaceVariant else com.google.android.material.R.attr.colorOnSurface))
            if (strong) setTypeface(typeface, Typeface.BOLD)
        }
        private fun setText(view: TextView, value: String) { if (view.text.toString() != value) view.text = value }
        private fun add(view: View, bottom: Int = 0) { body.addView(view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(bottom) }) }
        private fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
        private fun color(attribute: Int) = MaterialColors.getColor(context, attribute, "Gesture settings")
        private fun key(name: String) = "gesture_$name"
    }
}
