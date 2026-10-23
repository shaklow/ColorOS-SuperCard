package dev.local.supercardhost;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.CompoundButton;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Real Material controls and production session; test storage never changes the user's settings. */
final class GestureSettingsCheck {
    private final Instrumentation test;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    GestureSettingsCheck(Instrumentation test) { this.test = test; context = test.getTargetContext(); }
    void run() throws Exception {
        Activity page = test.startActivitySync(new Intent().setClassName(context.getPackageName(),
                context.getPackageName() + ".GestureAreaActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        AtomicReference<Bundle> saved = new AtomicReference<>(state());
        try {
            Class<?> bridge = context.getClassLoader().loadClass(context.getPackageName() + ".DhuMaterialUi");
            Method render = bridge.getDeclaredMethod("renderGestureSettings", View.class, Bundle.class); render.setAccessible(true);
            Field field = page.getClass().getDeclaredField("session"); field.setAccessible(true);
            main(() -> {
                ((FloatSettingsSession) field.get(page)).close();
                final FloatSettingsSession[] holder = new FloatSettingsSession[1];
                holder[0] = new FloatSettingsSession((operation, value, result) -> {
                    Bundle next = new Bundle(saved.get());
                    if (operation.equals("range")) { next.putFloat("top", value.getFloat("top")); next.putFloat("bottom", value.getFloat("bottom")); }
                    else if (operation.equals("handleOpacity")) next.putFloat(operation, value.getFloat("value"));
                    else if (operation.equals("lockStyle")) next.putInt(operation, value.getInt("value"));
                    else if (operation.equals("handleVisible") || operation.equals("handleAutoHide") || operation.equals("unlocked")) next.putBoolean(operation, value.getBoolean("value"));
                    saved.set(next); result.complete(new Bundle(next), null);
                }, new FloatSettingsSession.Scheduler() {
                    public long now() { return SystemClock.elapsedRealtime(); }
                    public long epoch() { return SystemClock.elapsedRealtimeNanos(); }
                    public void execute(Runnable action) { main.post(action); }
                    public void after(Runnable action, long delay) { main.postDelayed(action, delay); }
                    public void cancel(Runnable action) { main.removeCallbacks(action); }
                }, new FloatSettingsSession.Listener() {
                    public void changed() {
                        try { Bundle value = holder[0].snapshot(); value.putString("message", "调整后自动保存"); render.invoke(null, named(page, "preview").getRootView().findViewWithTag("gesture_scroll").getParent(), value); }
                        catch (Exception error) { throw new IllegalStateException(error); }
                    }
                    public void failed(String message, String error) { throw new AssertionError(message + ": " + error); }
                });
                field.set(page, holder[0]); holder[0].resume();
            });
            await(() -> onMain(() -> named(page, "unlocked").isEnabled() && page.hasWindowFocus()), "Gesture page did not load");
            main(() -> {
                named(page, "unlocked").performClick();
                if (((CompoundButton) named(page, "unlocked")).isChecked() || !named(page, "unlocked").isEnabled())
                    throw new AssertionError("Gesture switch flashed or disabled during save");
            });
            await(() -> !saved.get().getBoolean("unlocked"), "Gesture switch did not persist");
            float previous = saved.get().getFloat("top"); dragSlider(page);
            await(() -> Math.abs(saved.get().getFloat("top") - previous) > .02f, "Real Material slider did not persist");
            dragPreview(page, saved);
            main(() -> named(page, "lock").performClick());
            await(() -> {
                AccessibilityNodeInfo root = test.getUiAutomation().getRootInActiveWindow();
                return root != null && !root.findAccessibilityNodeInfosByText("整条右侧").isEmpty();
            }, "Lock choices did not open");
            AccessibilityNodeInfo root = test.getUiAutomation().getRootInActiveWindow();
            AccessibilityNodeInfo choice = root.findAccessibilityNodeInfosByText("整条右侧").get(0);
            Rect bounds = new Rect(); choice.getBoundsInScreen(bounds); tap(bounds.centerX(), bounds.centerY());
            await(() -> saved.get().getInt("lockStyle") == 1, "Lock choice did not persist");
            main(() -> named(page, "reset").performClick());
            await(() -> saved.get().getFloat("top") == .76f && saved.get().getFloat("bottom") == .98f, "Appearance reset did not persist");
            if (saved.get().getBoolean("unlocked") || saved.get().getInt("lockStyle") != 1)
                throw new AssertionError("Appearance reset changed trigger choices");
        } finally { main(page::finish); test.waitForIdleSync(); }
        ownership();
    }
    private void dragSlider(Activity page) throws Exception {
        View slider = named(page, "position"); show(slider);
        int[] point = new int[2]; main(() -> slider.getLocationOnScreen(point));
        long down = SystemClock.uptimeMillis(); float y = point[1] + slider.getHeight() / 2f;
        pointer(down, MotionEvent.ACTION_DOWN, point[0] + slider.getWidth() * .3f, y);
        pointer(down, MotionEvent.ACTION_MOVE, point[0] + slider.getWidth() * .6f, y);
        pointer(down, MotionEvent.ACTION_UP, point[0] + slider.getWidth() * .6f, y);
    }
    private void dragPreview(Activity page, AtomicReference<Bundle> saved) throws Exception {
        View preview = named(page, "preview"); show(preview);
        float previous = saved.get().getFloat("top"); float middle = (previous + saved.get().getFloat("bottom")) / 2;
        float density = preview.getResources().getDisplayMetrics().density;
        int[] point = new int[2]; main(() -> preview.getLocationOnScreen(point));
        float x = point[0] + preview.getWidth() / 2f + 58 * density;
        float y = point[1] + 10 * density + (preview.getHeight() - 20 * density) * middle;
        long down = SystemClock.uptimeMillis(); pointer(down, MotionEvent.ACTION_DOWN, x, y);
        pointer(down, MotionEvent.ACTION_MOVE, x, y - 20 * density); pointer(down, MotionEvent.ACTION_UP, x, y - 20 * density);
        await(() -> saved.get().getFloat("top") < previous - .02f, "Phone preview drag did not persist");
    }
    private void ownership() throws Exception {
        ClassLoader loader = context.getClassLoader(); Class<?> configuration = loader.loadClass(context.getPackageName() + ".CardConfiguration");
        Method apply = configuration.getDeclaredMethod("apply", Context.class, Intent.class, int.class); apply.setAccessible(true);
        Class<?> handle = loader.loadClass(context.getPackageName() + ".GestureHandle");
        Field preview = handle.getDeclaredField("preview"); preview.setAccessible(true);
        Method clear = handle.getMethod("clearPreview");
        String before = Settings.Secure.getString(context.getContentResolver(), "navigation_slide_card_range");
        long epoch = SystemClock.elapsedRealtimeNanos();
        Intent first = preview("first", epoch - 2, .2f), second = preview("second", epoch - 1, .3f);
        main(() -> {
            try {
                apply.invoke(null, context, first, 1); apply.invoke(null, context, second, 1);
                Object current = preview.get(null);
                apply.invoke(null, context, new Intent(first).putExtra("operation", "previewEnd"), 1);
                if (current == null || preview.get(null) != current) throw new AssertionError("Old page cleared the new preview");
                apply.invoke(null, context, first, 1);
                if (preview.get(null) != current) throw new AssertionError("Old page replaced the new preview");
                apply.invoke(null, context, new Intent(second).putExtra("operation", "previewEnd"), 1);
                apply.invoke(null, context, second, 1);
                if (preview.get(null) != null) throw new AssertionError("A closed preview was resurrected");
            } finally { clear.invoke(null); }
        });
        if (!java.util.Objects.equals(before, Settings.Secure.getString(context.getContentResolver(), "navigation_slide_card_range")))
            throw new AssertionError("Preview changed persistent gesture settings");
    }
    private Intent preview(String owner, long epoch, float top) {
        return new Intent().putExtra("operation", "preview").putExtra("top", top).putExtra("bottom", top + .4f)
                .putExtra("handleOpacity", .5f).putExtra("handleVisible", true).putExtra("previewOwner", owner)
                .putExtra("previewEpoch", epoch).putExtra("previewDeadline", SystemClock.elapsedRealtime() + 5000);
    }
    private static Bundle state() {
        Bundle result = new Bundle(); result.putFloat("top", .76f); result.putFloat("bottom", .98f); result.putFloat("handleOpacity", .4f);
        result.putBoolean("enabled", true); result.putBoolean("unlocked", true); result.putBoolean("handleVisible", true); result.putBoolean("handleAutoHide", true); return result;
    }
    private View named(Activity page, String name) { return page.getWindow().getDecorView().findViewWithTag("gesture_" + name); }
    private void show(View view) throws Exception { main(() -> view.requestRectangleOnScreen(new Rect(0, 0, view.getWidth(), view.getHeight()), true)); test.waitForIdleSync(); SystemClock.sleep(150); }
    private void tap(float x, float y) { long down = SystemClock.uptimeMillis(); pointer(down, MotionEvent.ACTION_DOWN, x, y); pointer(down, MotionEvent.ACTION_UP, x, y); }
    private void pointer(long down, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0); event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { if (!test.getUiAutomation().injectInputEvent(event, true)) throw new AssertionError("Gesture test input rejected"); } finally { event.recycle(); }
    }
    private void main(Checked action) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>(); test.runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() != null) throw new AssertionError("Gesture main-thread check failed", error.get());
    }
    private boolean onMain(BooleanSupplier condition) { java.util.concurrent.atomic.AtomicBoolean result = new java.util.concurrent.atomic.AtomicBoolean(); test.runOnMainSync(() -> result.set(condition.getAsBoolean())); return result.get(); }
    private void await(BooleanSupplier condition, String message) { long end = SystemClock.uptimeMillis() + 15000; while (SystemClock.uptimeMillis() < end) { if (condition.getAsBoolean()) return; SystemClock.sleep(50); } throw new AssertionError(message); }
    private interface Checked { void run() throws Exception; }
}
