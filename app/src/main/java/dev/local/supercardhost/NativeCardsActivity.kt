package dev.local.supercardhost

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/** Old 0.2.14 shortcuts now request the existing overlay without showing a card-page Activity. */
class NativeCardsActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        overridePendingTransition(0, 0)
        // An already-running 0.2.14 SystemUI can still have the old hook until reboot.
        // Disable its preference before requesting the existing overlay.
        CardConfiguration.request(this, "nativeCards", Bundle().apply { putBoolean("value", false) }) { _, _ ->
            if (isDestroyed || isFinishing) return@request
            CardConfiguration.request(this, "openLegacyCards", null) { _, error ->
                if (!isDestroyed && !isFinishing) {
                    if (error != null) Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                    finish()
                    overridePendingTransition(0, 0)
                }
            }
        }
    }
}
