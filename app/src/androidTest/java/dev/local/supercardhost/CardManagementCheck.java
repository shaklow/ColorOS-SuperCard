package dev.local.supercardhost;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.InputDevice;
import android.animation.ValueAnimator;
import android.view.ViewTreeObserver;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.CompoundButton;
import java.io.FileInputStream;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Actual unified APK: edits, drag input, original data compatibility and cached entry names. */
final class CardManagementCheck {
    private static final String KEY = "card_setting_data";
    private final Instrumentation test;
    private final Context context;
    private final String pkg;
    CardManagementCheck(Instrumentation test) { this.test = test; context = test.getTargetContext(); pkg = context.getPackageName(); }

    void run() throws Exception {
        String before = Settings.Secure.getString(context.getContentResolver(), KEY);
        boolean granted = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
        Activity page = null;
        Instrumentation.ActivityMonitor editor = test.addMonitor(pkg + ".CardFunctionSettingsActivity", null, true);
        String animationScale = Settings.Global.getString(context.getContentResolver(), "animator_duration_scale");
        test.getUiAutomation().adoptShellPermissionIdentity();
        try {
            shell("pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS");
            shell("settings put global animator_duration_scale 1");
            await(ValueAnimator::areAnimatorsEnabled, "Animation scale did not reach the test process");
            String sample = "{\"future\":{\"keep\":17},\"cards\":["
                    + "{\"type\":\"pay\",\"enable\":true,\"skinName\":\"custom\",\"functions\":[{\"type\":\"wx_pay\",\"enable\":false}]},"
                    + "{\"type\":\"memory\",\"enable\":true},{\"type\":\"staging\",\"enable\":true},"
                    + "{\"type\":\"camera\",\"enable\":false,\"functions\":[{\"type\":\"video\",\"enable\":false}]},"
                    + "{\"type\":\"dhu\",\"enable\":true}]}";
            write(sample);
            page = start(pkg + ".CardManagementActivity");
            Activity current = page;
            await(() -> ready(current, "toggle_camera"), "Management page did not load its original configuration");
            main(() -> {
                View toggle = named(current, "toggle_camera");
                if (!toggle.getContext().getClassLoader().getClass().getName().equals("dalvik.system.DelegateLastClassLoader"))
                    throw new AssertionError("Management UI did not use the isolated archive");
                toggle.performClick();
                if (!((CompoundButton) toggle).isChecked() || !toggle.isEnabled())
                    throw new AssertionError("Switch flashed back or changed disabled colors during save");
            });
            await(() -> enabled("camera") && ready(current, "up_camera"), "Card toggle was not confirmed by the provider");
            MotionWatch arrowMotion = new MotionWatch(current, "camera");
            main(() -> named(current, "up_camera").performClick());
            await(() -> order("pay", "memory", "camera", "staging", "dhu") && ready(current, "down_pay"), "Up arrow did not persist order");
            arrowMotion.finish();
            MotionWatch dragMotion = new MotionWatch(current, "pay");
            drag(current, "pay", "memory");
            await(() -> order("memory", "pay", "camera", "staging", "dhu") && ready(current, "down_pay"), "Drag/drop did not persist order: " + json());
            dragMotion.finish();
            JSONObject saved = json();
            if (saved.getJSONObject("future").getInt("keep") != 17) throw new AssertionError("Unknown root data was lost");
            JSONObject pay = card("pay"), camera = card("camera");
            if (!"custom".equals(pay.getString("skinName")) || pay.getJSONArray("functions").getJSONObject(0).getBoolean("enable")
                    || camera.getJSONArray("functions").getJSONObject(0).getBoolean("enable"))
                throw new AssertionError("Management edits altered function or skin configuration");
            JSONObject external = json();
            JSONArray externalCards = external.getJSONArray("cards");
            for (int i = 0; i < externalCards.length(); i++) {
                JSONObject value = externalCards.getJSONObject(i);
                if ("memory".equals(value.getString("type"))) value.put("enable", false);
            }
            write(external.toString());
            await(() -> ready(current, "toggle_memory") && onMain(() ->
                    !((CompoundButton) named(current, "toggle_memory")).isChecked()
                            && "4 / 5 张已启用".contentEquals(((TextView) named(current, "count")).getText())),
                    "External provider edit was not reflected in the management UI");
            main(() -> named(current, "settings_pay").performClick());
            if (editor.getHits() != 1) throw new AssertionError("Material PAY function editor was not reached");
            main(current::finish); test.waitForIdleSync(); page = null;
            android.content.pm.ActivityInfo alias = context.getPackageManager().getActivityInfo(
                    new ComponentName(pkg, "com.vivo.card.setting.CardSettingActivity"), 0);
            if (!(pkg + ".CardManagementActivity").equals(alias.targetActivity)) throw new AssertionError("Cached management entry was not redirected");
            page = start("com.vivo.card.setting.CardSettingActivity");
            Activity legacy = page;
            await(() -> ready(legacy, "down_pay"), "Legacy entry did not show the new management UI");
            if (!(pkg + ".CardManagementActivity").equals(page.getClass().getName()) || !order("memory", "pay", "camera", "staging", "dhu"))
                throw new AssertionError("Legacy entry reset the saved card order");
            main(legacy::finish); test.waitForIdleSync(); page = null;
            test.removeMonitor(editor);
            functionEditors();
            write(null);
            page = start(pkg + ".CardManagementActivity");
            Activity fresh = page;
            await(() -> hasCards() && onMain(() -> fresh.getWindow().getDecorView().findViewWithTag("management_toggle_pay") != null),
                    "Original first-install initializer did not populate management");
        } finally {
            try {
                if (page != null) { Activity current = page; main(current::finish); test.waitForIdleSync(); }
                write(before);
                if (animationScale == null) shell("settings delete global animator_duration_scale");
                else shell("settings put global animator_duration_scale " + Float.parseFloat(animationScale));
                if (!granted) shell("pm revoke " + pkg + " android.permission.WRITE_SECURE_SETTINGS");
            } finally { test.removeMonitor(editor); test.getUiAutomation().dropShellPermissionIdentity(); }
        }
    }

    private void drag(Activity page, String source, String target) throws Exception {
        await(() -> onMain(() -> page.hasWindowFocus()) && ready(page, "drag_" + source),
                "Management window was not ready for physical input");
        int[] from = new int[2], to = new int[2];
        CountDownLatch laidOut = new CountDownLatch(1);
        CountDownLatch touched = new CountDownLatch(1), dragging = new CountDownLatch(1);
        main(() -> {
            ScrollView scroll = (ScrollView) named(page, "scroll");
            int[] location = new int[2]; named(page, "card_" + source).getLocationOnScreen(location);
            scroll.scrollBy(0, location[1] - Math.round(100 * page.getResources().getDisplayMetrics().density));
            scroll.postOnAnimation(() -> scroll.postOnAnimation(laidOut::countDown));
        });
        if (!laidOut.await(5, TimeUnit.SECONDS)) throw new AssertionError("Management scroll did not settle");
        test.waitForIdleSync();
        main(() -> {
            View grip = named(page, "drag_" + source);
            if (!grip.isShown() || !grip.isEnabled() || !grip.isLongClickable() || !grip.hasWindowFocus())
                throw new AssertionError("Drag handle is not interactive");
            grip.setOnTouchListener((view, event) -> {
                android.util.Log.i("CardManagementCheck", "handle input=" + event.getActionMasked() + " enabled=" + view.isEnabled());
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) touched.countDown();
                if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) dragging.countDown();
                return false;
            });
            center(grip, from); center(named(page, "card_" + target), to);
            android.util.Log.i("CardManagementCheck", "drag " + from[0] + "," + from[1] + " to " + to[0] + "," + to[1]);
        });
        long down = SystemClock.uptimeMillis();
        try {
            pointer(down, MotionEvent.ACTION_DOWN, from[0], from[1]);
            if (!touched.await(5, TimeUnit.SECONDS)) throw new AssertionError("Physical input did not reach the drag handle");
            // A delayed notification for our already-confirmed write must not cancel the hold.
            context.getContentResolver().notifyChange(Settings.Secure.getUriFor(KEY), null);
            if (!dragging.await(5, TimeUnit.SECONDS)) throw new AssertionError("Physical long press did not start dragging");
            test.waitForIdleSync();
            for (int i = 1; i <= 8; i++) {
                pointer(down, MotionEvent.ACTION_MOVE, from[0] + (to[0] - from[0]) * i / 8f, from[1] + (to[1] - from[1]) * i / 8f);
                SystemClock.sleep(35);
            }
        } finally {
            try { pointer(down, MotionEvent.ACTION_UP, to[0], to[1]); }
            finally { main(() -> {
                named(page, "drag_" + source).setOnTouchListener(null);
            }); }
        }
    }

    private String prefix(Activity page) {
        return page.getClass().getName().endsWith(".CardFunctionSettingsActivity") ? "function_" : "management_";
    }

    private final class MotionWatch {
        private final Activity page;
        private final View row;
        private final java.util.concurrent.atomic.AtomicBoolean moved = new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicInteger detached = new java.util.concurrent.atomic.AtomicInteger();
        private final ViewTreeObserver.OnPreDrawListener frames;
        private final View.OnAttachStateChangeListener attachment = new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View view) { }
            public void onViewDetachedFromWindow(View view) { detached.incrementAndGet(); }
        };
        MotionWatch(Activity page, String type) throws Exception {
            this.page = page; row = named(page, "card_" + type);
            frames = () -> { if (Math.abs(row.getTranslationY()) > 1) moved.set(true); return true; };
            main(() -> { page.getWindow().getDecorView().getViewTreeObserver().addOnPreDrawListener(frames); row.addOnAttachStateChangeListener(attachment); });
        }
        void finish() throws Exception {
            try {
                await(() -> moved.get() && onMain(() -> Math.abs(row.getTranslationY()) < .1f && row.getAlpha() == 1f), "Reorder did not animate and settle");
                if (detached.get() != 0) throw new AssertionError("Reorder detached its existing row");
            } finally { main(() -> { page.getWindow().getDecorView().getViewTreeObserver().removeOnPreDrawListener(frames); row.removeOnAttachStateChangeListener(attachment); }); }
        }
    }

    private void functionEditors() throws Exception {
        JSONObject fixture = new JSONObject().put("future", new JSONObject().put("keep", 17));
        JSONArray cards = new JSONArray();
        String[][] sets = {{"wx_scan", "wx_pay", "alipay_scan", "alipay_pay", "wx_ride", "alipay_ride", "wx2_pay"},
                {"take_photo", "portrait", "video", "night_view", "mirror", "master", "panoramic"}};
        for (int i = 0; i < sets.length; i++) {
            JSONArray functions = new JSONArray();
            for (int j = 0; j < sets[i].length; j++) functions.put(new JSONObject().put("type", sets[i][j]).put("enable", j < 4)
                    .put("support", true).put("info", "launch:" + sets[i][j]).put("future", 17));
            functions.put(new JSONObject().put("type", "future_native").put("enable", false).put("support", false).put("opaque", "keep"));
            cards.put(new JSONObject().put("type", i == 0 ? "pay" : "camera").put("enable", i == 0).put("skinName", "custom").put("functions", functions));
        }
        fixture.put("cards", cards); write(fixture.toString());
        android.content.pm.ActivityInfo alias = context.getPackageManager().getActivityInfo(new ComponentName(pkg, "com.vivo.card.setting.CardEditActivity"), 0);
        if (!(pkg + ".CardFunctionSettingsActivity").equals(alias.targetActivity)) throw new AssertionError("Legacy function entry was not redirected");
        for (String type : new String[]{"pay", "camera"}) {
            Activity page = test.startActivitySync(new Intent().setClassName(pkg, "com.vivo.card.setting.CardEditActivity")
                    .putExtra("mCardType", type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try {
                if (!(pkg + ".CardFunctionSettingsActivity").equals(page.getClass().getName())) throw new AssertionError("Function alias reached an old editor");
                String added = type.equals("pay") ? "wx_ride" : "mirror";
                String first = type.equals("pay") ? "wx_scan" : "take_photo";
                String second = type.equals("pay") ? "wx_pay" : "portrait";
                await(() -> ready(page, "toggle_" + added), "Function editor did not load " + type);
                if (type.equals("camera") && onMain(() -> named(page, "toggle_take_photo").isEnabled())) throw new AssertionError("LENS minimum was not retained");
                main(() -> { named(page, "toggle_" + added).performClick();
                    if (!((CompoundButton) named(page, "toggle_" + added)).isChecked()) throw new AssertionError("Function switch flashed back"); });
                await(() -> functionEnabled(type, added) && ready(page, "up_" + added), "Function enable did not persist");
                MotionWatch arrow = new MotionWatch(page, added);
                main(() -> named(page, "up_" + added).performClick());
                await(() -> functionIndex(type, added) == 3 && ready(page, "down_" + first), "Function arrow did not persist");
                arrow.finish();
                MotionWatch dragging = new MotionWatch(page, first);
                drag(page, first, second);
                await(() -> functionIndex(type, first) == 1 && ready(page, "down_" + first), "Function drag did not persist");
                dragging.finish();
                JSONObject card = card(type); JSONArray functions = card.getJSONArray("functions");
                if (!"custom".equals(card.getString("skinName")) || !"keep".equals(functions.getJSONObject(functions.length() - 1).getString("opaque")))
                    throw new AssertionError("Function edits changed skin or hidden data");
                JSONObject changed = json();
                JSONArray changedFunctions = changed.getJSONArray("cards").getJSONObject(type.equals("pay") ? 0 : 1).getJSONArray("functions");
                for (int i = 0; i < changedFunctions.length(); i++) if (added.equals(changedFunctions.getJSONObject(i).getString("type"))) changedFunctions.getJSONObject(i).put("enable", false);
                write(changed.toString());
                await(() -> ready(page, "toggle_" + added) && onMain(() -> !((CompoundButton) named(page, "toggle_" + added)).isChecked()), "External function edit did not refresh");
            } finally { main(page::finish); test.waitForIdleSync(); }
        }
        if (json().getJSONObject("future").getInt("keep") != 17) throw new AssertionError("Function editor lost unknown root data");
    }
    private int functionIndex(String type, String key) {
        try { JSONArray functions = card(type).getJSONArray("functions"); for (int i = 0; i < functions.length(); i++) if (key.equals(functions.getJSONObject(i).getString("type"))) return i; }
        catch (Exception ignored) { } return -1;
    }
    private boolean functionEnabled(String type, String key) {
        try { return card(type).getJSONArray("functions").getJSONObject(functionIndex(type, key)).getBoolean("enable"); }
        catch (Exception ignored) { return false; }
    }

    private void pointer(long down, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        // A platform drag leaves the Activity window for the system drag window.
        // Use the shell input channel so Android 16 does not reject that UID change.
        try {
            if (!test.getUiAutomation().injectInputEvent(event, true))
                throw new AssertionError("Platform drag input was rejected");
        } finally { event.recycle(); }
    }
    private static void center(View view, int[] point) {
        view.getLocationOnScreen(point); point[0] += view.getWidth() / 2; point[1] += view.getHeight() / 2;
    }
    private Activity start(String name) { return test.startActivitySync(new Intent().setClassName(pkg, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
    private View named(Activity page, String name) {
        View value = page.getWindow().getDecorView().findViewWithTag(prefix(page) + name);
        if (value == null) throw new AssertionError("Missing management control: " + name);
        return value;
    }
    private boolean ready(Activity page, String name) {
        return onMain(() -> {
            View value = page.getWindow().getDecorView().findViewWithTag(prefix(page) + name);
            return value != null && value.isEnabled() && value.isClickable() && value.isAttachedToWindow() && value.getWidth() > 0;
        });
    }
    private JSONObject json() throws Exception { return new JSONObject(Settings.Secure.getString(context.getContentResolver(), KEY)); }
    private JSONObject card(String type) throws Exception {
        JSONArray cards = json().getJSONArray("cards");
        for (int i = 0; i < cards.length(); i++) if (type.equals(cards.getJSONObject(i).getString("type"))) return cards.getJSONObject(i);
        throw new AssertionError("Missing saved card " + type);
    }
    private boolean enabled(String type) { try { return card(type).getBoolean("enable"); } catch (Exception unavailable) { return false; } }
    private boolean hasCards() { try { return json().getJSONArray("cards").length() > 0; } catch (Exception unavailable) { return false; } }
    private boolean order(String... types) {
        try {
            JSONArray cards = json().getJSONArray("cards"); if (cards.length() != types.length) return false;
            for (int i = 0; i < types.length; i++) if (!types[i].equals(cards.getJSONObject(i).getString("type"))) return false;
            return true;
        } catch (Exception unavailable) { return false; }
    }
    private void write(String raw) {
        if (!Settings.Secure.putString(context.getContentResolver(), KEY, raw)) throw new AssertionError("Cannot prepare/restore management data");
    }
    private boolean onMain(BooleanSupplier action) {
        java.util.concurrent.atomic.AtomicBoolean result = new java.util.concurrent.atomic.AtomicBoolean();
        test.runOnMainSync(() -> result.set(action.getAsBoolean())); return result.get();
    }
    private void await(BooleanSupplier condition, String message) {
        long end = SystemClock.uptimeMillis() + 15_000;
        while (SystemClock.uptimeMillis() < end) { if (condition.getAsBoolean()) return; SystemClock.sleep(100); }
        throw new AssertionError(message);
    }
    private interface Checked { void run() throws Exception; }
    private void main(Checked action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        test.runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new AssertionError("Management UI check failed", failure.get());
    }
    private void shell(String command) throws Exception {
        try (ParcelFileDescriptor pipe = test.getUiAutomation().executeShellCommand(command); FileInputStream input = new FileInputStream(pipe.getFileDescriptor())) {
            byte[] bytes = new byte[1024]; while (input.read(bytes) != -1) { }
        }
    }
}
