package dev.local.supercardhost

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/** Only the existing wallet router and explicit original-image action can leave this card. */
internal object NativeCouponActions {
    fun source(context: Context, row: NativeCouponData.Item): Intent? {
        try {
            val intent = Intent.parseUri(row.source, Intent.URI_INTENT_SCHEME)
            val uri = intent.data
            if (intent.component != ComponentName(WalletCouponClient.PACKAGE, "com.nearme.common.router.RouterActivity") ||
                intent.action != Intent.ACTION_VIEW || uri?.scheme != "wallet" || uri.host != "fintech" ||
                uri.path != "/ticket/unusedList" || uri.getQueryParameter("cardType") !in setOf("31", "32") ||
                uri.getQueryParameter("data").isNullOrEmpty()) return WalletCouponClient.managementIntent(context)
            intent.selector = null; intent.clipData = null; intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            return verified(context, intent)
        } catch (_: Exception) { return WalletCouponClient.managementIntent(context) }
    }
    private fun verified(context: Context, intent: Intent): Intent? {
        val info = context.packageManager.resolveActivity(intent, 0)?.activityInfo ?: return null
        if (!info.enabled || !info.exported || !info.applicationInfo.enabled || info.packageName != WalletCouponClient.PACKAGE) return null
        if (info.permission != null && context.checkSelfPermission(info.permission) != PackageManager.PERMISSION_GRANTED) return null
        return intent
    }
    fun original(row: NativeCouponData.Item): Intent? {
        val uri = Uri.parse(row.originalImage)
        if (uri.scheme != "content" || uri.authority.isNullOrBlank()) return null
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
