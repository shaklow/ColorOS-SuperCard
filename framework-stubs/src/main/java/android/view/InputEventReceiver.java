package android.view;
import android.os.Looper;
/** Compile-only Android 12–16 ABI; the device supplies every implementation. */
public abstract class InputEventReceiver {
    public InputEventReceiver(InputChannel channel, Looper looper) { throw new AssertionError(); }
    public void onInputEvent(InputEvent event) { throw new AssertionError(); }
    public final void finishInputEvent(InputEvent event, boolean handled) { throw new AssertionError(); }
    public void dispose() { throw new AssertionError(); }
}
