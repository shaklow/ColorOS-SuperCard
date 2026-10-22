package dev.local.supercardhost

import android.os.Bundle
import android.widget.CompoundButton
import android.widget.Toast

class LensSettingsActivity : M3eSettingsActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (!showPage("lens")) return
        var selected = NativeLensPreferences.selected(this)
        ColorOsLensModes.query(this).forEach { mode ->
            ui.view<CompoundButton>("toggle_${mode.type()}").apply {
                isChecked = mode.type() in selected; isEnabled = true
                setOnCheckedChangeListener { _, checked ->
                    val next = if (checked) selected + mode.type() else selected - mode.type()
                    if (NativeLensPreferences.save(this@LensSettingsActivity, next)) selected = next
                    else {
                        isChecked = mode.type() in selected
                        Toast.makeText(this@LensSettingsActivity, "保存失败，请重试", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}
