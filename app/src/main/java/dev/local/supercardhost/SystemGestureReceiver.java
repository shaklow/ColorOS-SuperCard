package dev.local.supercardhost;
import android.os.Looper;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import java.util.function.Predicate;

/** Narrow hidden-framework boundary. Motion decisions remain in the Kotlin core. */
public final class SystemGestureReceiver extends InputEventReceiver implements AutoCloseable {
    private final Predicate<InputEvent> listener;
    public SystemGestureReceiver(Object channel, Looper looper, Predicate<InputEvent> listener) {
        super((InputChannel) channel, looper); this.listener = listener;
    }
    @Override public void onInputEvent(InputEvent event) {
        boolean handled = false;
        try { handled = listener.test(event); }
        finally { finishInputEvent(event, handled); }
    }
    @Override public void close() { dispose(); }
}
