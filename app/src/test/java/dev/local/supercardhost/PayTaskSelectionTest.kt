package dev.local.supercardhost

import android.content.ComponentName
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 36])
class PayTaskSelectionTest {
    private fun task(display: Int, id: Int, user: Int, payment: Boolean = true) =
        PayTaskSnapshot(display, id, user, ComponentName(if (payment) "com.tencent.mm" else "com.oplus.camera", "Page$id"), payment)

    @Test fun selectsTheFirstPaymentTaskForTheExactDisplayAndAccount() {
        val main = task(0, 1, 0, false); val wrongUser = task(7, 2, 0)
        val camera = task(8, 3, 999, false); val selected = task(7, 4, 999)
        val next = task(7, 5, 999)
        assertSame(selected, PayTaskSelection.select(listOf(main, wrongUser, camera, selected, next), 7, 999))
        assertSame(wrongUser, PayTaskSelection.select(listOf(wrongUser, selected), 7, -1))
    }

    @Test fun aPendingVirtualLaunchCannotFallBackToTheMainDisplay() {
        val main = task(0, 1, 0)
        assertNull(PayTaskSelection.select(listOf(main, task(7, 2, 0)), 7, 999))
        assertNull(PayTaskSelection.select(listOf(main, task(7, 2, 999, false)), 7, 999))
        assertNull(PayTaskSelection.select(emptyList(), 7, 999))
    }

    @Test fun releasedDisplayReturnsToTheFirstMainTask() {
        val main = task(0, 2, 0, false)
        assertSame(main, PayTaskSelection.select(listOf(task(7, 1, 999), main, task(0, 3, 0)), -1, 999))
    }
}
