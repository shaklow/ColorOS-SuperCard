package dev.local.supercardhost;

import android.app.Instrumentation;
import android.app.AppOpsManager;
import android.os.ParcelFileDescriptor;
import java.io.FileInputStream;
import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Actual independent Kotlin host, actual private Material controls, no original card service. */
final class NativeCoreCheck implements AutoCloseable {
    private final Instrumentation test;
    private final AtomicBoolean allowed = new AtomicBoolean(true);
    private NativeCardHost host;
    private View root;
    private FrameLayout body;
    private String previousOverlay;
    NativeCoreCheck(Instrumentation test) { this.test = test; }
    FrameLayout container() { return body; }
    void run() throws Exception {
        Context context = test.getTargetContext();
        int before = context.getSystemService(AppOpsManager.class).unsafeCheckOpRawNoThrow(
                AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, context.getApplicationInfo().uid, context.getPackageName());
        previousOverlay = switch (before) {
            case AppOpsManager.MODE_ALLOWED -> "allow";
            case AppOpsManager.MODE_IGNORED -> "ignore";
            case AppOpsManager.MODE_ERRORED -> "deny";
            case AppOpsManager.MODE_FOREGROUND -> "foreground";
            case AppOpsManager.MODE_DEFAULT -> "default";
            default -> throw new AssertionError("Unknown overlay app-op " + before);
        };
        shell("appops set " + context.getPackageName() + " SYSTEM_ALERT_WINDOW allow");
        main(() -> {
            host = new NativeCardHost(context, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, () -> allowed.get());
            Field field = NativeCardHost.class.getDeclaredField("root"); field.setAccessible(true); root = (View) field.get(host);
            body = root.findViewWithTag("native_content");
            host.show(List.of("dhu", "camera"), "dhu");
            if (!(body.getChildAt(0) instanceof DhuPayView)) throw new AssertionError("Native campus card missing");
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) root.getLayoutParams();
            if ((params.flags & WindowManager.LayoutParams.FLAG_SECURE) == 0) throw new AssertionError("Native window is not secure");
            host.show(List.of("dhu", "camera"), "dhu");
            if (body.getChildCount() != 1) throw new AssertionError("Duplicate native content");
        });
        awaitLayout(); tap("native_next");
        main(() -> {
            if (!(body.getChildAt(0) instanceof NativeLensCard)) throw new AssertionError("Real native navigation did not switch cards");
            allowed.set(false); host.move(-1);
            if (host.getShowing() || body.getChildCount() != 0 || root.isAttachedToWindow())
                throw new AssertionError("Locked native host retained content/window");
            allowed.set(true); host.show(List.of("dhu", "camera"), "camera");
        });
        awaitLayout();
        main(() -> {
            if (!(body.getChildAt(0) instanceof NativeLensCard)) throw new AssertionError("Native reopening did not restore camera content");
        });
    }
    private void awaitLayout() throws Exception {
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (SystemClock.uptimeMillis() < deadline) {
            AtomicBoolean ready = new AtomicBoolean();
            main(() -> ready.set(root.isAttachedToWindow() && root.getWidth() > 0 && root.hasWindowFocus()));
            if (ready.get()) return;
            test.waitForIdleSync(); SystemClock.sleep(50);
        }
        throw new AssertionError("Native host did not finish layout/focus");
    }
    private void tap(String name) throws Exception {
        Rect bounds = new Rect();
        main(() -> {
            View view = root.findViewWithTag(name);
            if (view == null || !view.isEnabled() || !view.getGlobalVisibleRect(bounds)) throw new AssertionError("Native control unavailable: " + name);
            int[] location = new int[2]; view.getLocationOnScreen(location);
            bounds.set(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight());
        });
        long now = SystemClock.uptimeMillis();
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, bounds.centerX(), bounds.centerY(), 0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            try { if (!test.getUiAutomation().injectInputEvent(event, true)) throw new AssertionError("Native touch rejected"); }
            finally { event.recycle(); }
        }
        test.waitForIdleSync();
    }
    @Override public void close() {
        try {
            if (host != null) test.runOnMainSync(() -> { host.close(); if (host.getShowing()) throw new AssertionError("Native host leaked window"); });
        } finally {
            if (previousOverlay != null) try {
                shell("appops set " + test.getTargetContext().getPackageName() + " SYSTEM_ALERT_WINDOW " + previousOverlay);
            } catch (Exception error) { throw new IllegalStateException("Cannot restore native-test app-op", error); }
        }
    }
    private void shell(String command) throws Exception {
        try (ParcelFileDescriptor descriptor = test.getUiAutomation().executeShellCommand(command);
             FileInputStream input = new FileInputStream(descriptor.getFileDescriptor())) {
            input.readAllBytes();
        }
    }
    private interface Action { void run() throws Exception; }
    private void main(Action action) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        test.runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() != null) throw new Exception("Native core main-thread operation failed", error.get());
    }
}
