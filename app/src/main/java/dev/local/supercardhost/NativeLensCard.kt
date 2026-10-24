package dev.local.supercardhost

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout

/** Original-mode contracts and system-camera display, without any vivo card instance or model. */
internal class NativeLensCard(context: Context, private val owner: CardWindowOwner) : FrameLayout(context), AutoCloseable {
    private val lifecycle = FloatingCardScope { _, _ -> }
    private val modes = DhuMaterialUi.lens(context)
    private var camera: LensCameraView? = null
    private val refresh = Runnable { render() }
    init { addView(modes, LayoutParams(-1, -1)); render() }
    private fun render() {
        val raw = Settings.Secure.getString(context.contentResolver, "card_setting_data")
        val choices = if (raw.isNullOrBlank()) ColorOsLensModes.query(context).take(6).map { it.type() to it.title() }
            else LensCardConfiguration.parse(raw).map { it.type() to it.title() }
        DhuMaterialUi.renderLens(modes, Bundle().apply {
            putStringArray("types", choices.map { it.first }.toTypedArray())
            putStringArray("titles", choices.map { it.second }.toTypedArray())
        })
        choices.forEach { (type, _) -> modes.findViewWithTag<View>("lens_mode_$type")?.setOnClickListener {
            camera?.let { removeView(it); it.close() }
            modes.visibility = View.GONE
            val next = LensCameraView(context, type, Runnable { closeCamera() }) { owner.secureCard(it) }
            camera = next; addView(next, LayoutParams(-1, -1))
        } }
        modes.findViewWithTag<View>("lens_settings").setOnClickListener {
            owner.hideCard()
            context.startActivity(Intent().setClassName("dev.local.supercardhost", CardFunctionSettingsActivity::class.java.name)
                .putExtra("mCardType", "camera").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    private fun closeCamera() {
        val previous = camera; camera = null
        try { previous?.close() } finally { if (previous != null) removeView(previous); modes.visibility = View.VISIBLE }
    }
    fun suspendCamera() = closeCamera()
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (lifecycle.attach(context.contentResolver, Settings.Secure.getUriFor("card_setting_data"), Runnable { lifecycle.post(refresh) })) lifecycle.post(refresh)
    }
    override fun onDetachedFromWindow() { try { close() } finally { super.onDetachedFromWindow() } }
    override fun close() { lifecycle.detach(); closeCamera() }
}
