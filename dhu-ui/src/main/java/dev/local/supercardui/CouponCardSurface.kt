package dev.local.supercardui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.BaseAdapter
import android.widget.ListView
import android.view.ViewGroup
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.loadingindicator.LoadingIndicator
import java.util.function.Consumer

/** Wallet-neutral presentation. Only framework values and user actions cross the archive boundary. */
object CouponCardSurface {
    @JvmStatic fun create(context: Context): View = Page(ContextThemeWrapper(context, R.style.Theme_DhuUi)).root
    @JvmStatic fun bind(root: View, action: Consumer<Bundle>) { (root.tag as Page).action = action }
    @JvmStatic fun render(root: View, state: Bundle) = (root.tag as Page).render(state)

    private data class Row(val key: String, val title: String, val category: String, val merchant: String,
        val time: String, val status: String, val usable: Boolean, val hint: String, val code: Boolean, val original: Boolean)
    private class Page(private val context: Context) {
        private val d = CardDesign(context)
        var action: Consumer<Bundle>? = null
        private var rows = emptyList<Row>()
        private var selected: Row? = null
        private var generation = 0
        private var confirming = false
        private var listBound = false
        private class Cell(val root: LinearLayout, val state: TextView, val title: TextView, val metadata: TextView) { var key = "" }
        private val adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(position: Int) = rows[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
                val cell = (recycled?.tag as? Cell) ?: run {
                    val wrapper = FrameLayout(context)
                    val column = d.column().apply {
                        background = d.shape(ColorStateList.valueOf(d.color(com.google.android.material.R.attr.colorSurfaceContainerHigh)), R.dimen.card_inner_corner)
                        setPadding(d.dp(14), d.dp(12), d.dp(14), d.dp(16)); isClickable = true; isFocusable = true
                    }
                    val status = d.text("", 12, secondary = true)
                    val title = d.text("", 16, bold = true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
                    val metadata = d.text("", 12, secondary = true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
                    column.addView(status); column.addView(title); column.addView(metadata)
                    wrapper.addView(column, FrameLayout.LayoutParams(-1, -2).apply { topMargin = d.dp(8) })
                    Cell(column, status, title, metadata).also { holder ->
                        wrapper.tag = holder; column.setOnClickListener { emit("select", holder.key) }
                    }
                }
                val row = rows[position]
                cell.key = row.key; cell.root.tag = "coupon_item_${row.key}"
                cell.root.contentDescription = "${row.category}，${row.title}，${row.status}"
                cell.state.text = "${row.category} · ${row.status}"; cell.title.text = row.title
                cell.metadata.text = listOf(row.merchant, row.time).filter(String::isNotBlank).joinToString("\n")
                return cell.root.parent as View
            }
        }
        private val listScroll = ListView(context).apply {
            tag = "coupon_list"; isVerticalScrollBarEnabled = false; divider = null; dividerHeight = 0
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        }
        private val detailColumn = d.column()
        private val details = ScrollView(context).apply { tag = "coupon_detail"; isVerticalScrollBarEnabled = false; addView(detailColumn); visibility = View.INVISIBLE }
        private val emptyContent = d.column().apply { gravity = Gravity.CENTER }
        private val empty = ScrollView(context).apply { tag = "coupon_empty"; isFillViewport = true; isVerticalScrollBarEnabled = false; addView(emptyContent) }
        private val busy = LoadingIndicator(context).apply { tag = "coupon_loading"; contentDescription = "正在更新钱包券码" }
        private val emptyTitle = d.text("正在更新券码", 17, bold = true).apply { gravity = Gravity.CENTER; tag = "coupon_empty_title" }
        private val emptyText = d.text("正在从钱包读取当前账号的券码", 13, secondary = true).apply { gravity = Gravity.CENTER }
        private val notice = d.text("", 12, secondary = true).apply { tag = "coupon_notice" }
        private val detailTitle = d.text("", 18, bold = true).apply { tag = "coupon_title" }
        private val metadata = d.text("", 13, secondary = true)
        private val hint = d.text("", 13, secondary = true).apply { tag = "coupon_hint"; gravity = Gravity.CENTER }
        private val code = ImageView(context).apply {
            tag = "coupon_qr"; scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "钱包提供的券码"
            setPadding(d.dp(8), d.dp(8), d.dp(8), d.dp(8))
        }
        private val codeBusy = LoadingIndicator(context).apply { tag = "coupon_code_loading"; contentDescription = "正在准备券码" }
        private val codeSheet = FrameLayout(context).apply {
            background = d.shape(ColorStateList.valueOf(Color.WHITE), R.dimen.card_inner_corner)
            clipToOutline = true; addView(code, FrameLayout.LayoutParams(-1, -1))
            addView(codeBusy, FrameLayout.LayoutParams(d.dp(48), d.dp(48), Gravity.CENTER))
        }
        private val original = d.button("coupon_original", "查看原图").apply { tag = "coupon_original"; setOnClickListener { emit("original") } }
        private val remove = d.button("coupon_remove", "从卡包移除").apply { tag = "coupon_remove"; setOnClickListener { confirmation(true) } }
        private val confirm = d.column().apply { tag = "coupon_confirm"; visibility = View.GONE }
        private val back = d.icon("coupon_back", "返回券列表", R.drawable.pay_ic_back).apply {
            tag = "coupon_back"; visibility = View.INVISIBLE; setOnClickListener { emit("back") }
        }
        private val count = d.text("卡券", 18, bold = true)
        private val stage = FrameLayout(context)
        val root = object : FrameLayout(context) {
            override fun onDetachedFromWindow() {
                generation++; listScroll.animate().cancel(); details.animate().cancel(); confirmation(false)
                super.onDetachedFromWindow()
            }
        }.apply {
            tag = this@Page
            background = d.shape(ColorStateList.valueOf(d.color(com.google.android.material.R.attr.colorSurfaceContainerLow)), R.dimen.card_surface_corner)
            clipToOutline = true
        }
        init {
            val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            header.addView(back)
            header.addView(count, LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(d.icon("coupon_settings", "钱包券管理", R.drawable.dhu_ic_settings).apply {
                tag = "coupon_settings"; setOnClickListener { emit("manage") }
            })
            root.addView(header, FrameLayout.LayoutParams(-1, d.dp(48)).apply { leftMargin = d.dp(6); rightMargin = d.dp(6); topMargin = d.dp(6) })
            root.addView(stage, FrameLayout.LayoutParams(-1, -1).apply {
                leftMargin = d.dp(16); rightMargin = d.dp(16); topMargin = d.dp(58); bottomMargin = d.dp(16)
            })
            stage.addView(listScroll, FrameLayout.LayoutParams(-1, -1))
            stage.addView(details, FrameLayout.LayoutParams(-1, -1))
            stage.addView(empty, FrameLayout.LayoutParams(-1, -1))
            val statusHeader = FrameLayout(context).apply { addView(notice, FrameLayout.LayoutParams(-1, -2)) }
            listScroll.addHeaderView(statusHeader, null, false); listScroll.adapter = adapter; listBound = true
            emptyContent.addView(busy, LinearLayout.LayoutParams(d.dp(48), d.dp(48)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = d.dp(16) })
            emptyContent.addView(emptyTitle)
            emptyContent.addView(emptyText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.dp(10) })
            emptyContent.addView(d.button("coupon_manage_empty", "钱包中查看").apply { tag = "coupon_manage_empty"; setOnClickListener { emit("manage") } },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.dp(16) })
            detailColumn.addView(detailTitle)
            detailColumn.addView(metadata, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.dp(6); bottomMargin = d.dp(12) })
            detailColumn.addView(codeSheet, LinearLayout.LayoutParams(-1, d.dp(200)))
            detailColumn.addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d.dp(10); bottomMargin = d.dp(10) })
            detailColumn.addView(d.button("coupon_open", "钱包中查看 / 管理").apply { tag = "coupon_open"; setOnClickListener { emit("open") } })
            detailColumn.addView(original)
            detailColumn.addView(remove)
            confirm.addView(d.text("从卡包移除此券？", 16, bold = true))
            confirm.addView(d.text("钱包中的原券和原始截图会保留。", 13, secondary = true))
            confirm.addView(d.button("coupon_cancel_remove", "取消").apply { tag = "coupon_cancel_remove"; setOnClickListener { confirmation(false) } })
            confirm.addView(d.button("coupon_confirm_remove", "从卡包移除").apply { tag = "coupon_confirm_remove"; setOnClickListener { emit("remove"); confirmation(false) } })
            detailColumn.addView(confirm)
            confirm.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                if (confirming) view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), false)
            }
        }
        private fun confirmation(value: Boolean) {
            confirming = value; confirm.visibility = if (value) View.VISIBLE else View.GONE
            remove.visibility = if (value) View.GONE else View.VISIBLE
            if (value) details.post { if (confirming) details.smoothScrollTo(0, detailColumn.height) }
        }
        private fun emit(name: String, key: String? = selected?.key) = action?.accept(Bundle().apply {
            putString("action", name); key?.let { putString("key", it) }
        })
        private fun rebuild() {
            val position = listScroll.firstVisiblePosition
            val top = listScroll.getChildAt(0)?.top ?: 0
            if (rows.isEmpty()) { listScroll.adapter = null; listBound = false }
            else if (!listBound) { listScroll.adapter = adapter; listBound = true }
            else adapter.notifyDataSetChanged()
            if (rows.isNotEmpty()) listScroll.setSelectionFromTop(position.coerceAtMost(rows.size), top)
        }
        @Suppress("DEPRECATION") fun render(state: Bundle) {
            val next = state.getParcelableArrayList<Bundle>("items").orEmpty().map {
                Row(it.getString("key", ""), it.getString("title", ""), it.getString("category", ""), it.getString("merchant", ""),
                    it.getString("time", ""), it.getString("status", ""), it.getBoolean("usable"), it.getString("hint", ""),
                    it.getBoolean("code"), it.getBoolean("original"))
            }
            require(next.all { it.key.isNotEmpty() } && next.distinctBy { it.key }.size == next.size)
            if (rows != next) { rows = next; rebuild() }
            val row = rows.firstOrNull { it.key == state.getString("selected") }
            val changedPage = (selected != null) != (row != null)
            if (row != selected) {
                if (row?.key != selected?.key) { confirmation(false); details.scrollTo(0, 0) }
                detailTitle.text = row?.title.orEmpty()
                metadata.text = row?.let { listOf("${it.category} · ${it.status}", it.merchant, it.time).filter(String::isNotBlank).joinToString("\n") }.orEmpty()
                hint.text = row?.hint.orEmpty()
                codeSheet.visibility = if (row?.code == true) View.VISIBLE else View.GONE
                original.visibility = if (row?.original == true) View.VISIBLE else View.GONE
            }
            selected = row; back.visibility = if (row != null) View.VISIBLE else View.INVISIBLE
            count.text = if (row == null) "卡券 · ${rows.size}" else "券码详情"
            empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            emptyTitle.text = state.getString("title", "正在更新券码")
            emptyText.text = state.getString("description", "正在从钱包读取当前账号的券码")
            busy.visibility = if (state.getBoolean("loading")) View.VISIBLE else View.GONE
            notice.text = state.getString("notice", "")
            notice.visibility = if (notice.text.isEmpty()) View.GONE else View.VISIBLE
            if (changedPage) transition(row != null) else if (row == null) details.visibility = View.INVISIBLE
            if (rows.isEmpty()) listScroll.visibility = View.INVISIBLE
            else if (row == null) listScroll.visibility = View.VISIBLE
        }
        private fun transition(enter: Boolean) {
            val ticket = ++generation
            listScroll.animate().cancel(); details.animate().cancel()
            val show = if (enter) details else listScroll
            val hide = if (enter) listScroll else details
            listScroll.alpha = 1f; details.alpha = 1f; details.translationY = 0f
            if (!root.isAttachedToWindow || !root.isLaidOut || !android.animation.ValueAnimator.areAnimatorsEnabled()) {
                show.visibility = View.VISIBLE; hide.visibility = View.INVISIBLE; return
            }
            show.visibility = View.VISIBLE; show.alpha = 0f
            if (enter) show.translationY = d.dp(12).toFloat()
            show.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(PathInterpolator(.25f, .1f, .25f, 1f)).start()
            hide.animate().alpha(0f).setDuration(180).withEndAction {
                if (ticket == generation) { hide.visibility = View.INVISIBLE; hide.alpha = 1f }
            }.start()
        }
    }
}
