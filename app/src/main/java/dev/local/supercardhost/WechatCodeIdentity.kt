package dev.local.supercardhost

import android.content.Context
import android.content.pm.PackageManager

/** SystemUI commonly uses android.uid.systemui, which is not the fixed system-server UID. */
internal object WechatCodeIdentity {
    fun systemUi(context: Context): Int {
        val packages = context.packageManager
        val uid = packages.getApplicationInfo("com.android.systemui", 0).uid
        check(uid >= 1000 && uid < 100000 &&
            packages.checkSignatures("android", "com.android.systemui") == PackageManager.SIGNATURE_MATCH) {
            "Trusted SystemUI identity unavailable"
        }
        return uid
    }
}
