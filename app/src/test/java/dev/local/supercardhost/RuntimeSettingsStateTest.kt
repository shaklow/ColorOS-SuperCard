package dev.local.supercardhost

import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 36], manifest = Config.NONE)
class RuntimeSettingsStateTest {
    private fun diagnostics() = Bundle().apply {
        putBoolean("systemUiHook", true); putBoolean("rootDisplayReady", false)
        putString("framework", "Vector"); putLong("frameworkVersion", 102)
        putBundle("payTouchOverrides", Bundle())
    }
    private fun users() = Bundle().apply {
        for (app in PayUserSettings.APPS) putBundle(app, Bundle().apply {
            putInt("selected", 0); putBoolean("available", true); putIntArray("users", intArrayOf(0, 999))
        })
    }

    @Test fun diagnosticsRequiresActualActivationAndTypedSettings() {
        assertFalse(RuntimeSettingsState.validDiagnostics(null))
        val state = diagnostics()
        assertTrue(RuntimeSettingsState.validDiagnostics(state))
        state.putBoolean("systemUiHook", false)
        assertFalse(RuntimeSettingsState.validDiagnostics(state))
        state.putBoolean("systemUiHook", true)
        state.getBundle("payTouchOverrides")!!.putString("wx_scan", "true")
        assertFalse(RuntimeSettingsState.validDiagnostics(state))
    }

    @Test fun unreadyDisplayIsValidAndDoesNotMeanServiceDisconnected() {
        val state = diagnostics()
        assertTrue(RuntimeSettingsState.validDiagnostics(state))
        assertFalse(state.getBoolean("rootDisplayReady"))
        state.putString("framework", "")
        assertFalse(RuntimeSettingsState.validDiagnostics(state))
    }

    @Test fun selectingRemovedCloneKeepsSelectionButRejectsMalformedUserLists() {
        val state = users()
        state.getBundle("wechat")!!.apply {
            putInt("selected", 999); putBoolean("available", false); putIntArray("users", intArrayOf(0))
        }
        assertTrue(RuntimeSettingsState.validUsers(state))
        state.getBundle("wechat")!!.putIntArray("users", intArrayOf(0, 999, 999))
        assertFalse(RuntimeSettingsState.validUsers(state))
        state.getBundle("wechat")!!.putIntArray("users", intArrayOf(999))
        assertFalse(RuntimeSettingsState.validUsers(state))
    }

    @Test fun successfulTransportAloneDoesNotConfirmToggleWrite() {
        val requested = Bundle().apply { putString("function", "wx_scan"); putBoolean("value", true) }
        val state = diagnostics()
        assertFalse(RuntimeSettingsState.confirmedWrite("payTouch", requested, state))
        state.getBundle("payTouchOverrides")!!.putBoolean("wx_scan", true)
        assertTrue(RuntimeSettingsState.confirmedWrite("payTouch", requested, state))
    }

    @Test fun userWriteNeedsValidReadbackOfRequestedAccount() {
        val requested = Bundle().apply { putString("app", "wechat"); putInt("user", 999) }
        val state = diagnostics().apply { putBundle("payUsers", users()) }
        assertFalse(RuntimeSettingsState.confirmedWrite("payUser", requested, state))
        state.getBundle("payUsers")!!.getBundle("wechat")!!.putInt("selected", 999)
        assertTrue(RuntimeSettingsState.confirmedWrite("payUser", requested, state))
        state.getBundle("payUsers")!!.remove("alipay")
        assertFalse(RuntimeSettingsState.confirmedWrite("payUser", requested, state))
    }
}
