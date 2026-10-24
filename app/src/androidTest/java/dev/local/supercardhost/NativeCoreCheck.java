package dev.local.supercardhost;

import android.animation.ValueAnimator;
import android.app.Instrumentation;
import android.app.AppOpsManager;
import android.os.ParcelFileDescriptor;
import java.io.FileInputStream;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.PixelFormat;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Choreographer;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

/** Real input monitor, secure Kotlin window, live swipe/cancel/animation and lifecycle checks. */
final class NativeCoreCheck implements AutoCloseable {
    private final Instrumentation test;
    private final boolean secureInput;
    private final AtomicBoolean allowed = new AtomicBoolean(true);
    private final AtomicBoolean inputEnabled = new AtomicBoolean(true);
    private NativeCardHost host;
    private SystemGestureInput input;
    private View root;
    private FrameLayout body;
    private View underlay;
    private final AtomicInteger underlayDowns = new AtomicInteger(), underlayUps = new AtomicInteger();
    private String previousOverlay;
    private Float previousAnimationScale;
    private Integer previousMonitorPermission;
    private boolean monitorOverridden;
    private boolean released;
    private final List<Float> openingFrames = new ArrayList<>();
    private Choreographer.FrameCallback frameProbe;
    NativeCoreCheck(Instrumentation test, boolean secureInput) { this.test = test; this.secureInput = secureInput; }
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
        context.getClassLoader().loadClass("org.lsposed.hiddenapibypass.HiddenApiBypass")
                .getMethod("addHiddenApiExemptions", String[].class).invoke(null, (Object) new String[]{"Landroid/"});
        main(() -> {
            previousAnimationScale = (Float) ValueAnimator.class.getMethod("getDurationScale").invoke(null);
            ValueAnimator.class.getMethod("setDurationScale", float.class).invoke(null, 1f);
            // A real lower window catches click-through, including taps above the card stage.
            underlay = new View(context) {
                @Override public boolean onTouchEvent(MotionEvent event) {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) underlayDowns.incrementAndGet();
                    if (event.getActionMasked() == MotionEvent.ACTION_UP) underlayUps.incrementAndGet();
                    return true;
                }
            };
            WindowManager.LayoutParams background = new WindowManager.LayoutParams(-1, -1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_SECURE, PixelFormat.TRANSLUCENT);
            background.setFitInsetsTypes(0);
            background.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            background.setTitle("SuperCardNativeTouchUnderlay");
            context.getSystemService(WindowManager.class).addView(underlay, background);
            host = new NativeCardHost(context, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, () -> allowed.get());
            Field field = NativeCardHost.class.getDeclaredField("root"); field.setAccessible(true); root = (View) field.get(host);
            body = root.findViewWithTag("native_content");
        });
        if (secureInput) {
            previousMonitorPermission = context.checkSelfPermission("android.permission.MONITOR_INPUT");
            permissionOverride("addOverridePermissionState");
            monitorOverridden = true;
            if (context.checkSelfPermission("android.permission.MONITOR_INPUT") != android.content.pm.PackageManager.PERMISSION_GRANTED)
                throw new AssertionError("Scoped monitor permission did not take effect");
            main(() -> {
                input = new SystemGestureInput(context, new SystemGestureInput.Events() {
                    public boolean enabled() { return inputEnabled.get(); }
                    public boolean visible() { return host.getShowing(); }
                    public GesturePolicy.Decision policy() {
                        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
                        return GesturePolicy.evaluate(new GesturePolicy.Settings(true, "side_back;side_card",
                                "0;0;" + metrics.widthPixels + ";" + metrics.heightPixels, false, 0, false, 0),
                                new GesturePolicy.Screen(false, true),
                                new GesturePolicy.Display(metrics.widthPixels, metrics.heightPixels, metrics.density));
                    }
                    public void begin() { host.beginEdge(List.of("dhu", "camera")); }
                    public void drag(float distance) { host.followEdge(distance); }
                    public void end(boolean commit, float velocity) { host.endEdge(commit, velocity); }
                });
            });
            // A small, slow edge pull must return to the edge and release the window.
            edgePull(40);
            await(() -> !host.getShowing(), "Short edge pull did not cancel");
            assertEmpty();
            // A deliberate slow pull followed by a pause must still open.
            edgePull(145);
            awaitSettled("dhu");
            main(() -> host.hide());
            assertEmpty();
            main(() -> {
                openingFrames.clear();
                frameProbe = new Choreographer.FrameCallback() {
                    @Override public void doFrame(long time) {
                        if (released && host.getShowing()) openingFrames.add(root.getTranslationX());
                        Choreographer.getInstance().postFrameCallback(this);
                    }
                };
                Choreographer.getInstance().postFrameCallback(frameProbe);
            });
            edgePull(165);
            await(() -> host.getShowing() && !host.getAnimating() && Math.abs(root.getTranslationX()) < .5f,
                    "Monitored edge pull did not settle");
            main(() -> {
                Choreographer.getInstance().removeFrameCallback(frameProbe); frameProbe = null;
                long moving = openingFrames.stream().filter(x -> x > 1 && x < root.getWidth()).count();
                if (moving < 2) throw new AssertionError("Entrance had no intermediate animation frames: " + openingFrames);
            });
        } else {
            main(() -> host.show(List.of("dhu", "camera"), "dhu"));
            awaitSettled("dhu");
        }
        main(() -> {
            if (!(front() instanceof DhuPayView)) throw new AssertionError("Native campus card missing");
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) root.getLayoutParams();
            if ((params.flags & WindowManager.LayoutParams.FLAG_SECURE) == 0) throw new AssertionError("Native window is not secure");
            if ((params.flags & (WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)) != 0)
                throw new AssertionError("Native window allows outside touches to pass through");
            DisplayMetrics metrics = new DisplayMetrics();
            context.getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(metrics);
            NativeCardLayout layout = NativeCardLayout.Companion.measure(metrics.widthPixels, metrics.heightPixels, metrics.density);
            View card = front();
            if (!card.getClipToOutline()) throw new AssertionError("Individual card outline is not clipped");
            int[] xy = new int[2]; card.getLocationOnScreen(xy);
            if (card.getWidth() != layout.getWidth() || card.getHeight() != layout.getHeight()
                    || xy[0] != layout.getLeft() || xy[1] != metrics.heightPixels - layout.getBottom() - layout.getHeight())
                throw new AssertionError("Card geometry differs from OEM: " + xy[0] + "," + xy[1] + " " + card.getWidth() + "x" + card.getHeight());
            if (root.getWidth() != metrics.widthPixels || root.getHeight() != metrics.heightPixels
                    || body.getBackground() != null || body.getClipChildren())
                throw new AssertionError("Native stage clips moving cards or leaves a fixed background");
            if (body.getChildCount() != 2 || !(body.getChildAt(1) instanceof NativeLensCard)
                    || body.getChildAt(1).getScaleX() >= 1f || body.getChildAt(1).getTranslationX() <= 0f)
                throw new AssertionError("Actual rear card is missing from the idle stack");
            android.util.Log.i("SuperCardNativeTest", "OEM bounds: " + xy[0] + "," + xy[1] + " " + card.getWidth() + "x" + card.getHeight());
        });
        inputEnabled.set(false);
        // Exercise actual content at the top, middle and bottom, including the LENS ScrollView/buttons.
        for (int y : new int[]{120, 250, 410}) {
            swipe(235, y, 35, y, 7, 40); awaitSettled("camera");
            swipe(45, y, 235, y, 7, 40); awaitSettled("dhu");
        }
        // A vertical scroll must stay with the content, even if it later drifts horizontally.
        int[] vertical = new int[1];
        main(() -> {
            View scroll = new View(context) {
                @Override public boolean onTouchEvent(MotionEvent event) {
                    if (event.getActionMasked() == MotionEvent.ACTION_MOVE) getParent().requestDisallowInterceptTouchEvent(true);
                    if (event.getActionMasked() == MotionEvent.ACTION_UP) vertical[0]++;
                    return true;
                }
            };
            scroll.setElevation(40 * context.getResources().getDisplayMetrics().density);
            body.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        });
        swipe(180, 100, 184, 270, 6, 70);
        main(() -> {
            if (vertical[0] != 1 || !"dhu".equals(host.getSelected())) throw new AssertionError("Vertical content scroll was stolen");
            body.removeViewAt(body.getChildCount() - 1);
        });
        int[] cancelled = new int[1], clicked = new int[1];
        main(() -> {
            View child = new View(context) {
            @Override public boolean onTouchEvent(MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) cancelled[0]++;
                if (event.getActionMasked() == MotionEvent.ACTION_UP) clicked[0]++;
                return true;
            }
            };
            child.setElevation(40 * context.getResources().getDisplayMetrics().density);
            body.addView(child, new FrameLayout.LayoutParams(-1, -1));
        });
        await(() -> body.getChildAt(body.getChildCount() - 1).getWidth() > 0, "Touch fixture did not lay out");
        // Short drag springs back; the child receives CANCEL rather than an accidental click.
        swipe(220, 210, 195, 210, 5, 80);
        awaitSettled("dhu");
        main(() -> {
            if (cancelled[0] != 1 || clicked[0] != 0 || body.getChildCount() != 2)
                throw new AssertionError("Cancelled swipe: child CANCEL=" + cancelled[0] + " UP=" + clicked[0]
                        + " mounted=" + body.getChildCount() + " selected=" + host.getSelected());
        });
        // Same physical stream now commits a page; old content survives until settling completes.
        List<Float> outgoingFrames = new ArrayList<>();
        main(() -> {
            View old = front();
            frameProbe = new Choreographer.FrameCallback() {
                @Override public void doFrame(long time) {
                    if (old.isAttachedToWindow() && old.getX() < 0) outgoingFrames.add(old.getX());
                    Choreographer.getInstance().postFrameCallback(this);
                }
            };
            Choreographer.getInstance().postFrameCallback(frameProbe);
        });
        swipe(235, 210, 35, 210, 7, 40);
        awaitSettled("camera");
        main(() -> {
            Choreographer.getInstance().removeFrameCallback(frameProbe); frameProbe = null;
            if (outgoingFrames.size() < 2) throw new AssertionError("Whole outgoing card never left its original panel: " + outgoingFrames);
            if (!(front() instanceof NativeLensCard) || body.getChildCount() != 2)
                throw new AssertionError("Native swipe did not switch real card content");
        });
        swipe(40, 210, 240, 210, 7, 40);
        awaitSettled("dhu");
        // Reverse during live settling: new input owns the current frame immediately, with the same views.
        AtomicReference<View> campus = new AtomicReference<>(), lens = new AtomicReference<>();
        main(() -> { campus.set(body.getChildAt(0)); lens.set(body.getChildAt(1)); host.move(1); });
        SystemClock.sleep(90);
        swipe(45, 250, 235, 250, 7, 8);
        awaitSettled("dhu");
        for (int repeat = 0; repeat < 4; repeat++) {
            swipe(235, 250, 35, 250, 7, 4);
            swipe(45, 250, 235, 250, 7, 4);
        }
        awaitSettled("dhu");
        main(() -> {
            if (body.getChildAt(0) != campus.get() || body.getChildAt(1) != lens.get())
                throw new AssertionError("Consecutive swipe recreated a live card");
        });
        main(() -> host.move(1));
        awaitSettled("camera");
        AtomicBoolean sawRollback = new AtomicBoolean();
        main(() -> {
            frameProbe = new Choreographer.FrameCallback() {
                @Override public void doFrame(long time) {
                    try {
                        Field field = NativeCardHost.class.getDeclaredField("scroll"); field.setAccessible(true);
                        float scroll = field.getFloat(host);
                        if (host.getShowing() && root.getTranslationX() > 1 && scroll < front().getWidth() * .5f)
                            sawRollback.set(true);
                    } catch (Exception error) { throw new AssertionError(error); }
                    Choreographer.getInstance().postFrameCallback(this);
                }
            };
            Choreographer.getInstance().postFrameCallback(frameProbe);
        });
        int[] blank = new int[2];
        main(() -> {
            body.getLocationOnScreen(blank);
            blank[0] = root.getWidth() / 2;
            blank[1] /= 2; // Above the card, where the old limited-height window passed taps through.
        });
        int downsBefore = underlayDowns.get(), upsBefore = underlayUps.get();
        long outside = SystemClock.uptimeMillis();
        inject(outside, MotionEvent.ACTION_DOWN, blank[0], blank[1]);
        inject(outside, MotionEvent.ACTION_UP, blank[0], blank[1]);
        await(() -> !host.getShowing(), "Second-card outside dismissal did not finish");
        if (underlayDowns.get() != downsBefore || underlayUps.get() != upsBefore)
            throw new AssertionError("Dismissal tap reached the underlying window");
        main(() -> { Choreographer.getInstance().removeFrameCallback(frameProbe); frameProbe = null; });
        if (!sawRollback.get()) throw new AssertionError("Second card left as a flat row instead of rolling back into a stack");
        assertEmpty();
        long afterDismiss = SystemClock.uptimeMillis();
        inject(afterDismiss, MotionEvent.ACTION_DOWN, blank[0], blank[1]);
        inject(afterDismiss, MotionEvent.ACTION_UP, blank[0], blank[1]);
        await(() -> underlayDowns.get() == downsBefore + 1 && underlayUps.get() == upsBefore + 1,
                "Underlying control did not become clickable after dismissal");
        android.util.Log.i("SuperCardNativeTest", "Blank dismissal consumed DOWN/UP; underlying control restored after close");
        main(() -> host.show(List.of("dhu", "camera"), "dhu"));
        awaitSettled("dhu");
        // Commands can also reverse immediately, without waiting for the former target.
        main(() -> { host.move(1); host.move(-1); });
        awaitSettled("dhu");
        main(() -> {
            if (!root.isAttachedToWindow() || body.getChildCount() != 2) throw new AssertionError("Rapid switching leaked content/window");
            host.move(1); allowed.set(false); host.move(-1);
        });
        assertEmpty();
        SystemClock.sleep(400); assertEmpty(); // cancelled animation callbacks must not restore a locked window.
        main(() -> {
            allowed.set(true); host.show(List.of("dhu", "camera"), "camera");
        });
        awaitSettled("camera");
        main(() -> {
            allowed.set(false);
            try { host.show(List.of("dhu", "camera"), "camera"); throw new AssertionError("Locked native show was accepted"); }
            catch (IllegalStateException expected) { }
        });
        assertEmpty();
        main(() -> { allowed.set(true); host.show(List.of("dhu", "camera"), "dhu"); });
        awaitSettled("dhu");
        swipe(30, 210, 235, 210, 7, 40);
        await(() -> !host.getShowing(), "Outward swipe on the first card did not dismiss");
        assertEmpty();
        // Leave a fully laid-out actual native container for the secure secondary-display test.
        main(() -> host.show(List.of("dhu", "camera"), "camera"));
        awaitSettled("camera");
    }
    private void edgePull(int distanceDp) throws Exception {
        DisplayMetrics metrics = test.getTargetContext().getResources().getDisplayMetrics();
        float x = metrics.widthPixels - 4 * metrics.density, y = metrics.heightPixels * .36f;
        motion(x, y, x - distanceDp * metrics.density, y, 6, 70, true);
    }
    private void swipe(int x1, int y1, int x2, int y2, int steps, int delay) throws Exception {
        test.waitForIdleSync();
        AtomicReference<int[]> location = new AtomicReference<>();
        main(() -> {
            int[] xy = new int[2]; body.getLocationOnScreen(xy);
            xy[0] += (body.getWidth() - front().getWidth()) / 2; location.set(xy);
        });
        float d = test.getTargetContext().getResources().getDisplayMetrics().density;
        int[] xy = location.get();
        motion(xy[0] + x1 * d, xy[1] + y1 * d, xy[0] + x2 * d, xy[1] + y2 * d, steps, delay, false);
    }
    private View front() throws Exception {
        Field field = NativeCardHost.class.getDeclaredField("current"); field.setAccessible(true);
        return ((NativeCardHost.Content) field.get(host)).getView();
    }
    private void motion(float x1, float y1, float x2, float y2, int steps, int delay, boolean edge) throws Exception {
        long down = SystemClock.uptimeMillis();
        inject(down, MotionEvent.ACTION_DOWN, x1, y1); SystemClock.sleep(35);
        for (int i = 1; i <= steps; i++) {
            float p = i / (float) steps;
            inject(down, MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * p, y1 + (y2 - y1) * p);
            test.waitForIdleSync(); SystemClock.sleep(delay);
        }
        SystemClock.sleep(100);
        if (edge && frameProbe != null) main(() -> released = true);
        inject(down, MotionEvent.ACTION_UP, x2, y2);
        test.waitForIdleSync();
    }
    private void inject(long down, int action, float x, float y) throws Exception {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            Object accepted = test.getUiAutomation().getClass()
                    .getMethod("injectInputEvent", InputEvent.class, boolean.class, boolean.class)
                    .invoke(test.getUiAutomation(), event, true, false);
            if (!Boolean.TRUE.equals(accepted)) throw new AssertionError("Native gesture input rejected");
        } finally { event.recycle(); }
    }
    private void awaitSettled(String selected) throws Exception {
        await(() -> host.getShowing() && !host.getAnimating() && selected.equals(host.getSelected())
                && root.hasWindowFocus() && root.getWidth() > 0 && Math.abs(root.getTranslationX()) < .5f, "Native card did not settle: " + selected);
    }
    private interface Condition { boolean value(); }
    private void await(Condition condition, String message) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 6000;
        while (SystemClock.uptimeMillis() < deadline) {
            AtomicBoolean ready = new AtomicBoolean();
            main(() -> ready.set(condition.value()));
            if (ready.get()) return;
            SystemClock.sleep(32);
        }
        throw new AssertionError(message);
    }
    private void assertEmpty() throws Exception {
        main(() -> {
            if (host.getShowing() || body.getChildCount() != 0 || root.isAttachedToWindow())
                throw new AssertionError("Locked/closed native host retained content or window");
        });
    }
    private void permissionOverride(String method) throws Exception {
        if (method.equals("addOverridePermissionState")) test.getUiAutomation().getClass()
                .getMethod(method, int.class, String.class, int.class)
                .invoke(test.getUiAutomation(), android.os.Process.myUid(), "android.permission.MONITOR_INPUT", 0);
        else test.getUiAutomation().getClass().getMethod(method, int.class, String.class)
                .invoke(test.getUiAutomation(), android.os.Process.myUid(), "android.permission.MONITOR_INPUT");
    }
    @Override public void close() {
        try {
            test.runOnMainSync(() -> {
                if (frameProbe != null) Choreographer.getInstance().removeFrameCallback(frameProbe);
                try { if (input != null) input.close(); }
                finally {
                    try { if (host != null) { host.close(); if (host.getShowing()) throw new AssertionError("Native host leaked window"); } }
                    finally {
                        if (underlay != null && underlay.isAttachedToWindow())
                            test.getTargetContext().getSystemService(WindowManager.class).removeViewImmediate(underlay);
                        underlay = null;
                    }
                }
            });
        } finally {
            try {
                if (previousAnimationScale != null) test.runOnMainSync(() -> {
                    try { ValueAnimator.class.getMethod("setDurationScale", float.class).invoke(null, previousAnimationScale); }
                    catch (Exception error) { throw new IllegalStateException(error); }
                });
            } finally {
                try {
                    if (monitorOverridden) {
                        permissionOverride("removeOverridePermissionState");
                        if (test.getTargetContext().checkSelfPermission("android.permission.MONITOR_INPUT") != previousMonitorPermission)
                            throw new AssertionError("Monitor permission override was not restored");
                    }
                } catch (Exception error) { throw new IllegalStateException(error); }
                finally {
                    if (previousOverlay != null) try {
                        shell("appops set " + test.getTargetContext().getPackageName() + " SYSTEM_ALERT_WINDOW " + previousOverlay);
                    } catch (Exception error) { throw new IllegalStateException("Cannot restore native-test app-op", error); }
                }
            }
        }
    }
    private void shell(String command) throws Exception {
        try (ParcelFileDescriptor descriptor = test.getUiAutomation().executeShellCommand(command);
             FileInputStream input = new FileInputStream(descriptor.getFileDescriptor())) { input.readAllBytes(); }
    }
    private interface Action { void run() throws Exception; }
    private void main(Action action) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        test.runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() != null) throw new Exception("Native core main-thread operation failed", error.get());
    }
}
