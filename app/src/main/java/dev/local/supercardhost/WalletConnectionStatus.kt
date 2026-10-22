package dev.local.supercardhost

/** Fixed, non-sensitive messages. Never display native exception text or account/ticket fields. */
object WalletConnectionStatus {
    @JvmStatic fun reason(error: Throwable): String = when (error) {
        is WalletCouponClient.QueryFailure -> error.reason
        is SecurityException -> "connection_denied"
        is InterruptedException -> "cancelled"
        else -> "native_unavailable"
    }

    @JvmStatic fun title(reason: String): String = when (reason) {
        "not_logged_in" -> "请先登录钱包"
        "account_changed" -> "钱包账号已切换"
        "account_unavailable" -> "钱包账号服务暂不可用"
        "service_disabled" -> "请先开启钱包服务"
        "locked" -> "解锁后查看券码"
        "bootstrap_unavailable", "bootstrap_denied", "bridge_unavailable" -> "钱包尚未连接"
        "transport_timeout", "timeout", "busy" -> "钱包响应超时"
        "native_code_incomplete" -> "部分券码尚未读取成功"
        "unsupported_android" -> "当前系统暂不支持钱包连接"
        else -> "钱包票券读取失败"
    }

    @JvmStatic fun description(reason: String): String = when (reason) {
        "not_logged_in" -> "请在钱包中登录，之后重新展开卡包"
        "account_changed" -> "旧账号券码已清除，重新展开卡包可读取当前账号"
        "account_unavailable" -> "请先在钱包确认账号状态，再重新展开卡包"
        "service_disabled" -> "请先打开钱包，完成原应用的协议与服务设置"
        "locked" -> "解锁后会从钱包更新当前账号的券码"
        "bootstrap_unavailable", "bootstrap_denied" -> "请先打开钱包再展开卡包；仍未连接时，请确认模块的钱包作用域并重启手机"
        "bridge_unavailable" -> "请确认 LSPosed / Vector 中已为模块勾选钱包，更新模块后需重启手机"
        "transport_timeout", "timeout", "busy" -> "钱包未及时完成读取，可打开钱包确认状态后重新展开卡包"
        "native_code_incomplete" -> "已读取的券仍可查看，缺失的二维码可在钱包中查看"
        "unsupported_android" -> "此连接方式需要 Android 14 或更高版本"
        "invalid_response" -> "钱包返回的数据无法读取，可先在钱包中查看，并提供模块日志以便适配"
        else -> "连接或原生读取未完成，可先在钱包中查看，并提供模块日志以便定位"
    }
}
