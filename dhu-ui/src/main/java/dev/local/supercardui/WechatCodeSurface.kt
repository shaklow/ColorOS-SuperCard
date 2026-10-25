package dev.local.supercardui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper

/** Native layout receives Drawables only; it never sees the payment credential string. */
object WechatCodeSurface {
    @JvmStatic fun create(resources: Context): View {
        val context = ContextThemeWrapper(resources, R.style.Theme_DhuUi)
        val d = CardDesign(context)
        val root = FrameLayout(context).apply {
            tag = "wechat_native_code"
            background = d.shape(ColorStateList.valueOf(d.color(com.google.android.material.R.attr.colorSurfaceContainerLow)), R.dimen.card_inner_corner)
            clipToOutline = true
            setPadding(d.dp(8), d.dp(8), d.dp(8), d.dp(8))
            contentDescription = "微信付款码，点击在微信中管理"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        val heading = LinearLayout(context).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        content.addView(heading, LinearLayout.LayoutParams(-1, -2))
        heading.addView(ImageView(context).apply {
            tag = "wechat_native_icon"; scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.GONE
        }, LinearLayout.LayoutParams(d.dp(24), d.dp(24)).apply { marginEnd = d.dp(8) })
        heading.addView(d.text("微信支付", 14, bold = true).apply { gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(-2, -2))
        // One continuous white sheet preserves contrast and quiet zones in either theme.
        val sheet = LinearLayout(context).apply {
            tag = "wechat_native_sheet"; orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = d.dp(18).toFloat() }
            clipToOutline = true; setPadding(d.dp(10), d.dp(12), d.dp(10), d.dp(12))
        }
        content.addView(sheet, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = d.dp(10) })
        sheet.addView(ImageView(context).apply {
            tag = "wechat_native_barcode"; scaleType = ImageView.ScaleType.FIT_XY; setBackgroundColor(Color.WHITE)
            contentDescription = "付款条形码"
        }, LinearLayout.LayoutParams(-1, d.dp(38)).apply { bottomMargin = d.dp(8) })
        sheet.addView(ImageView(context).apply {
            tag = "wechat_native_qr"; scaleType = ImageView.ScaleType.FIT_XY; setBackgroundColor(Color.WHITE)
            contentDescription = "付款二维码"
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(d.text("向商家出示 · 点击进入微信", 11, secondary = true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.dp(8) })
        return root
    }
}
