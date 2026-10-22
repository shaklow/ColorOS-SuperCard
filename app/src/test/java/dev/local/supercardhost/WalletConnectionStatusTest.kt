package dev.local.supercardhost

import org.junit.Assert.*
import org.junit.Test

class WalletConnectionStatusTest {
    @Test fun accountAndTransportFailuresHaveDifferentGuidance() {
        assertEquals("请先登录钱包", WalletConnectionStatus.title("not_logged_in"))
        assertTrue(WalletConnectionStatus.description("bridge_unavailable").contains("勾选钱包"))
        assertTrue(WalletConnectionStatus.description("bootstrap_denied").contains("先打开钱包"))
        assertTrue(WalletConnectionStatus.description("native_code_incomplete").contains("已读取的券仍可查看"))
    }

    @Test fun nativeExceptionTextCannotReachPageMessages() {
        val error = IllegalStateException("secret-account-and-signed-ticket-url")
        val reason = WalletConnectionStatus.reason(error)
        assertEquals("native_unavailable", reason)
        assertFalse(WalletConnectionStatus.description(reason).contains("secret"))
        assertEquals("native_unavailable", WalletConnectionStatus.reason(LinkageError("secret")))
    }
}
