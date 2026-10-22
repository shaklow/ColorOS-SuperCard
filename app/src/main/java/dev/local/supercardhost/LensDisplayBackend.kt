package dev.local.supercardhost

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.view.InputEvent
import android.view.MotionEvent
import android.view.Surface

/** The installed SystemUI process owns activity launching and input; root only creates the display. */
internal class LensDisplayBackend(private val context: Context) : LensCameraSession.Backend {
    private val input = context.getSystemService(Context.INPUT_SERVICE)
        ?: error("Input service unavailable")
    private val inject = input.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    private val setDisplay = InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)

    override fun create(surface: Surface, width: Int, height: Int, density: Int): LensCameraSession.Display {
        val display = RootDisplayClient.createCamera(width, height, density, surface)
        return object : LensCameraSession.Display {
            override val id = display.display.displayId
            override fun close() { UiCompat.releaseVirtualDisplay(display) }
        }
    }

    override fun launch(intent: Intent, display: Int) {
        require(display > 0)
        // An explicit display is mandatory. Failure stays in the card; no main-display fallback.
        context.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle())
    }

    override fun inject(event: MotionEvent, display: Int): Boolean {
        require(display > 0)
        setDisplay.invoke(event, display)
        return inject.invoke(input, event, 0) == true
    }
}
