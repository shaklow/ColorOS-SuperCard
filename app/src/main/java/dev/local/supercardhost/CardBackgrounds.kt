package dev.local.supercardhost

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle

/** Uses bundled backgrounds as assets for the new preview picker, without the old skin manager. */
internal object CardBackgrounds {
    fun load(context: Context, type: String): List<Bundle> {
        val loader = context.classLoader
        val util = loader.loadClass("com.vivo.card.skin.utils.SkinUtil")
        val defaults = util.getMethod("getSkinsList").invoke(null) as List<*>
        return defaults.filterNotNull().filter { value ->
            value.javaClass.getMethod("getCardType").invoke(value) == type
        }.mapIndexed { index, value ->
            val name = value.javaClass.getMethod("getName").invoke(value) as String
            val small = value.javaClass.getMethod("getResId").invoke(value) as String
            val id = context.resources.getIdentifier(small, "drawable", "com.vivo.card")
            val drawable = if (id == 0) null else try { context.getDrawable(id) } catch (_: Exception) { null }
            choice(name, 1, "背景 ${index + 1}", drawable)
        }.distinctBy { it.getString("name") }
    }

    private fun choice(name: String, strategy: Int, title: String, drawable: Drawable?) = Bundle().apply {
        putString("name", name); putInt("strategy", strategy); putString("title", title)
        if (drawable != null) {
            val bitmap = Bitmap.createBitmap(144, 216, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, bitmap.width, bitmap.height); drawable.draw(Canvas(bitmap))
            putParcelable("preview", bitmap)
        }
    }
}
