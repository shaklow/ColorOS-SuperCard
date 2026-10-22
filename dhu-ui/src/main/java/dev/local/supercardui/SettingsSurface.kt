package dev.local.supercardui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.button.MaterialButton
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import java.util.function.IntConsumer

/** All MDC/Kotlin objects stay inside the private archive. The host sees framework views only. */
object SettingsSurface {
    @JvmStatic fun create(resources: Context, page: String): View = Page(
        ContextThemeWrapper(resources, R.style.Theme_DhuUi), page
    ).root

    @JvmStatic fun render(root: View, values: Bundle) = (root.tag as Page).render(values)
    @JvmStatic fun choose(root: View, title: String, labels: Array<String>, selected: Int, action: IntConsumer) {
        val page = root.tag as Page
        page.show(MaterialAlertDialogBuilder(root.context).setTitle(title)
            .setSingleChoiceItems(labels, selected) { dialog, which -> dialog.dismiss(); action.accept(which) }
            .setNegativeButton("取消", null).create())
    }
    @JvmStatic fun confirm(root: View, title: String, message: String, action: Runnable) {
        val page = root.tag as Page
        page.show(MaterialAlertDialogBuilder(root.context).setTitle(title).setMessage(message)
            .setPositiveButton("清除") { _, _ -> action.run() }.setNegativeButton("取消", null).create())
    }

    private class Page(val context: Context, val page: String) {
        val root = FrameLayout(context)
        private val body = object : LinearLayout(context) {
            override fun onMeasure(width: Int, height: Int) {
                val limited = MeasureSpec.makeMeasureSpec(minOf(MeasureSpec.getSize(width), context.resources.getDimensionPixelSize(R.dimen.settings_page_max_width)), MeasureSpec.getMode(width))
                super.onMeasure(limited, height)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            val gutter = context.resources.getDimensionPixelSize(R.dimen.settings_page_gutter)
            setPadding(gutter, dp(8), gutter, dp(28))
        }
        private val statuses = mutableMapOf<String, Pair<TextView, ImageView>>()
        private var dialog: AlertDialog? = null
        private var details: View? = null
        private var input: TextInputLayout? = null

        init {
            root.tag = this
            root.setBackgroundColor(color(com.google.android.material.R.attr.colorSurface))
            val scroll = ScrollView(context).apply {
                isFillViewport = true; clipToPadding = false; isSaveEnabled = false
                addView(body, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            }
            root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
            root.setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val keyboard = insets.getInsets(WindowInsets.Type.ime())
                scroll.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
                insets
            }
            root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { v.requestApplyInsets() }
                override fun onViewDetachedFromWindow(v: View) { dialog?.dismiss(); dialog = null }
            })
            toolbar()
            when (page) {
                "home" -> home()
                "runtime" -> runtime()
                "campus" -> campus()
                "lens" -> lens()
                else -> error("Unknown settings page")
            }
        }

        private fun toolbar() {
            val bar = MaterialToolbar(context).apply {
                title = if (page == "home") "设置" else "超级卡包"
                setTitleTextAppearance(context, com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                setTitleTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setContentInsetsRelative(0, 0); minimumHeight = dp(48)
                if (page != "home") {
                    navigationIcon = AppCompatResources.getDrawable(context, R.drawable.settings_ic_back)
                    setNavigationIconTint(color(androidx.appcompat.R.attr.colorPrimary))
                    navigationContentDescription = "返回"
                    setNavigationOnClickListener { }
                    (0 until childCount).map { getChildAt(it) }.filterIsInstance<ImageButton>().first().tag = key("back")
                }
            }
            add(bar, bottom = 12)
        }

        private fun home() {
            title("超级卡包", "管理卡片、连接与使用偏好")
            val hero = vertical().apply { setPadding(dp(22), dp(22), dp(22), dp(22)) }
            hero.addView(icon(R.drawable.settings_ic_cards, 44, tonal = true))
            hero.addView(text("卡片与功能", 24, strong = true).space(top = 16))
            hero.addView(text("启用卡片，调整显示顺序", 14, secondary = true).space(top = 6))
            hero.addView(button("管理卡片", "open_cards").space(top = 18))
            add(card(hero, com.google.android.material.R.attr.colorPrimaryContainer), bottom = 28)
            section("连接与授权")
            navigation("运行状态与 PAY", "检查系统连接、启动用户与触摸设置", R.drawable.settings_ic_status, "open_runtime")
            navigation("东华校园卡", "管理付款码授权链接", R.drawable.dhu_ic_school, "open_campus", "campus_summary")
            navigation("新版卡片", "校园卡与相机快捷方式", R.drawable.settings_ic_cards, "open_native")
            toggleGroup("卡片界面", listOf("native_cards" to "解锁后手势打开新版卡片"))
            add(text("关闭时使用原卡包；锁屏仍沿用原卡包。", 12, secondary = true), top = 8)
            section("使用偏好", top = 24)
            navigation("手势与唤出", "调整唤出区域与操作方式", R.drawable.settings_ic_gesture, "open_gestures")
        }

        private fun runtime() {
            title("运行状态与 PAY", "连接状态一目了然，常用设置集中管理")
            val hero = vertical().apply { setPadding(dp(22), dp(22), dp(22), dp(22)) }
            hero.addView(text("卡包连接", 13, secondary = true))
            hero.addView(text("正在读取状态…", 24, strong = true, tag = "connection").space(top = 6))
            hero.addView(text("检查框架与 SystemUI 的实际响应", 14, secondary = true).space(top = 8))
            add(card(hero, com.google.android.material.R.attr.colorPrimaryContainer), bottom = 16)
            val rows = vertical()
            statusRow(rows, "root", "Root 授权", "正在检测…", R.drawable.settings_ic_key)
            statusRow(rows, "framework", "框架", "正在连接…", R.drawable.settings_ic_status)
            statusRow(rows, "hook", "SystemUI", "正在连接…", R.drawable.settings_ic_cards)
            statusRow(rows, "display", "安全虚拟屏", "正在连接…", R.drawable.settings_ic_display)
            add(card(rows), bottom = 10)
            add(button("刷新运行状态", "refresh", textOnly = true), bottom = 4)
            add(text("Root 检测最长等待 12 秒。未连接不代表未安装框架。", 12, secondary = true), bottom = 14)
            add(button("权限与后台详情", "details", textOnly = true).apply {
                setOnClickListener {
                    details?.let { panel ->
                        panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                        text = if (panel.visibility == View.VISIBLE) "收起权限与后台详情" else "权限与后台详情"
                    }
                }
            })
            details = text("正在检查设置保存与后台权限…", 14, secondary = true, tag = "setup").apply {
                setPadding(dp(12), dp(8), dp(12), dp(12)); visibility = View.GONE
            }.also { add(it) }
            add(text("正在读取设置…", 14, tag = "message").apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }, top = 10, bottom = 24)
            section("启动用户")
            add(text("微信与支付宝分别选择。保存后，下次打开卡片生效。", 14, secondary = true), bottom = 14)
            navigation("微信", "尚未读取", R.drawable.settings_ic_person, "user_wechat", "user_wechat_value")
            navigation("支付宝", "尚未读取", R.drawable.settings_ic_person, "user_alipay", "user_alipay_value")
            add(text("原有微信分身专用项始终使用分身。创建分身后可刷新此页。", 12, secondary = true, tag = "user_hint"), top = 8)
            section("页面触摸", top = 28)
            add(text("开启后允许对应功能的应用页面接收触摸；关闭时沿用原版名单。", 14, secondary = true), bottom = 14)
            toggleGroup("微信", listOf("wx_scan" to "扫一扫", "wx_pay" to "付款码", "wx_ride" to "乘车码"))
            toggleGroup("支付宝", listOf("alipay_scan" to "扫一扫", "alipay_pay" to "付款码", "alipay_ride" to "乘车码", "alipay_health_code" to "医保码"))
            toggleGroup("微信分身专用", listOf("wx2_scan" to "扫一扫", "wx2_pay" to "付款码"))
        }

        private fun lens() {
            title("相机快捷方式", "选择在 LENS 卡片上显示的拍摄模式")
            toggleGroup("拍摄模式", listOf(
                "take_photo" to "拍照", "portrait" to "人像", "video" to "录像", "night_view" to "夜景",
                "master" to "大师模式", "mirror" to "镜子", "panoramic" to "全景", "high_piexl" to "高像素",
                "mini_distance" to "微距", "star_sky" to "星空", "document_correction" to "文档矫正"))
        }

        private fun campus() {
            title("东华校园卡", "更新授权，随时查看付款码")
            val intro = horizontal().apply { setPadding(dp(18), dp(18), dp(18), dp(18)) }
            intro.addView(icon(R.drawable.dhu_ic_school, 44, tonal = true))
            intro.addView(vertical().apply {
                addView(text("校园卡支付", 18, strong = true))
                addView(text("从校园卡支付页面复制完整链接", 13, secondary = true).space(top = 4))
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
            add(card(intro, com.google.android.material.R.attr.colorPrimaryContainer), bottom = 24)
            section("授权链接")
            input = TextInputLayout(context, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
                hint = "完整授权链接"; helperText = "链接过期时，在这里替换并保存。"; isSaveEnabled = false
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                addView(TextInputEditText(this.context).apply {
                    tag = key("url"); minLines = 3; maxLines = 7; gravity = Gravity.TOP
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                    isSaveEnabled = false
                }, LinearLayout.LayoutParams(-1, -2))
            }.also { add(it, bottom = 20) }
            add(button("保存并验证", "save"), bottom = 10)
            add(button("验证已保存的链接", "verify", tonal = true), bottom = 10)
            add(button("清除授权链接", "clear", textOnly = true).apply {
                setTextColor(color(androidx.appcompat.R.attr.colorError))
            }, bottom = 20)
            val state = vertical().apply {
                setPadding(dp(18), dp(16), dp(18), dp(16))
                addView(text("授权状态", 13, secondary = true))
                addView(text("正在读取…", 16, tag = "message").apply {
                    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                }.space(top = 6))
            }
            add(card(state))
        }

        private fun toggleGroup(label: String, functions: List<Pair<String, String>>) {
            val group = vertical().apply { setPadding(dp(18), dp(8), dp(18), dp(8)) }
            group.addView(text(label, 13, secondary = true).space(top = 8, bottom = 4))
            functions.forEach { (id, name) ->
                group.addView(MaterialSwitch(context).apply {
                    tag = key("toggle_$id"); text = name; textSize = 16f; minHeight = dp(56)
                    isEnabled = false; isSaveEnabled = false
                    contentDescription = "$label$name，页面触摸"
                    setTextColor(color(com.google.android.material.R.attr.colorOnSurface))
                }, LinearLayout.LayoutParams(-1, -2))
            }
            add(card(group), bottom = 12)
        }

        private fun statusRow(parent: LinearLayout, id: String, label: String, value: String, drawable: Int) {
            val row = horizontal().apply { setPadding(dp(18), dp(14), dp(18), dp(14)) }
            val leading = icon(drawable, 24)
            row.addView(leading)
            val copy = vertical().apply {
                addView(text(label, 13, secondary = true))
                addView(text(value, 16, tag = "${id}_value").space(top = 3))
            }
            row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) })
            statuses[id] = (copy.getChildAt(1) as TextView) to leading
            parent.addView(row, LinearLayout.LayoutParams(-1, -2))
        }

        private fun navigation(title: String, subtitle: String, drawable: Int, action: String, valueTag: String? = null) {
            val row = horizontal().apply { setPadding(dp(18), dp(18), dp(18), dp(18)); minimumHeight = dp(88) }
            row.addView(icon(drawable, 28))
            row.addView(vertical().apply {
                addView(text(title, 17, strong = true))
                addView(text(subtitle, 13, secondary = true, tag = valueTag).space(top = 5))
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16); marginEnd = dp(12) })
            row.addView(icon(R.drawable.settings_ic_next, 20))
            add(card(row).apply {
                tag = key(action); isClickable = true; isFocusable = true
                val background = android.util.TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, background, true)
                foreground = AppCompatResources.getDrawable(context, background.resourceId)
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info); info.className = Button::class.java.name
                    }
                }
            }, bottom = 10)
        }

        fun render(values: Bundle) {
            for (id in statuses.keys) if (values.containsKey(id)) {
                val (label, image) = statuses.getValue(id)
                label.text = values.getString(id)
                val attr = when (values.getString("${id}_tone")) {
                    "error" -> androidx.appcompat.R.attr.colorError
                    "ready" -> androidx.appcompat.R.attr.colorPrimary
                    else -> com.google.android.material.R.attr.colorOnSurfaceVariant
                }
                image.imageTintList = ColorStateList.valueOf(color(attr))
            }
            for (name in listOf("connection", "message", "setup", "campus_summary", "user_wechat_value", "user_alipay_value", "user_hint")) {
                if (values.containsKey(name)) root.findViewWithTag<TextView>(key(name))?.text = values.getString(name)
            }
            if (values.containsKey("url_error")) input?.error = values.getString("url_error")
        }

        fun show(value: AlertDialog) { dialog?.dismiss(); dialog = value; value.show() }
        private fun title(value: String, subtitle: String) {
            add(text(value, 32, strong = true), bottom = 8)
            add(text(subtitle, 14, secondary = true), bottom = 24)
        }
        private fun section(value: String, top: Int = 0) { add(text(value, 14, strong = true), top = top, bottom = 12) }
        private fun key(value: String) = "settings_$value"
        private fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
        private fun color(attr: Int) = MaterialColors.getColor(context, attr, "SuperCard settings")
        private fun vertical() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        private fun horizontal() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        private fun text(value: String, size: Int, secondary: Boolean = false, strong: Boolean = false, tag: String? = null) = MaterialTextView(context).apply {
            text = value; textSize = size.toFloat(); setLineSpacing(dp(3).toFloat(), 1f)
            setTextColor(color(if (secondary) com.google.android.material.R.attr.colorOnSurfaceVariant else com.google.android.material.R.attr.colorOnSurface))
            if (strong) setTypeface(typeface, Typeface.BOLD)
            if (tag != null) this.tag = key(tag)
        }
        private fun icon(drawable: Int, size: Int, tonal: Boolean = false) = ImageView(context).apply {
            setImageDrawable(AppCompatResources.getDrawable(context, drawable)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            imageTintList = ColorStateList.valueOf(color(androidx.appcompat.R.attr.colorPrimary))
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
            if (tonal) setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        private fun button(value: String, action: String, textOnly: Boolean = false, tonal: Boolean = false): MaterialButton {
            val attr = when {
                textOnly -> androidx.appcompat.R.attr.borderlessButtonStyle
                tonal -> com.google.android.material.R.attr.materialButtonTonalStyle
                else -> com.google.android.material.R.attr.materialButtonStyle
            }
            return MaterialButton(context, null, attr).apply {
                tag = key(action); text = value; minimumHeight = context.resources.getDimensionPixelSize(R.dimen.settings_action_min_height); isAllCaps = false
                maxLines = 3; ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(-1, -2)
            }
        }
        private fun card(content: View, surface: Int = com.google.android.material.R.attr.colorSurfaceContainerLow) = MaterialCardView(context).apply {
            val corner = context.resources.getDimension(R.dimen.settings_card_corner)
            setCardBackgroundColor(color(surface)); radius = corner; cardElevation = 0f; strokeWidth = 0
            shapeAppearanceModel = ShapeAppearanceModel.builder().setAllCornerSizes(corner).build()
            addView(content, FrameLayout.LayoutParams(-1, -2))
        }
        private fun <T : View> T.space(top: Int = 0, bottom: Int = 0): T = apply {
            layoutParams = (layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(-1, -2)).apply {
                topMargin = dp(top); bottomMargin = dp(bottom)
            }
        }
        private fun add(view: View, top: Int = 0, bottom: Int = 0) { body.addView(view.space(top, bottom)) }
    }
}
