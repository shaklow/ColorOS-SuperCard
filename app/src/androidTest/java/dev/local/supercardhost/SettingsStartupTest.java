package dev.local.supercardhost;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import java.util.concurrent.atomic.AtomicReference;

/** Run against the installed unified APK, including its real private DEX/resources. */
public final class SettingsStartupTest extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int checked = 0;
        try {
            checkPage("NativeCardsActivity", "settings", Intent.CATEGORY_LAUNCHER); checked++;
            checkPage("HostActivity", "open_runtime", "de.robv.android.xposed.category.MODULE_SETTINGS"); checked++;
            checkPage("HostToolsActivity", "toggle_wx_scan", null); checked++;
            checkPage("DhuPaySettingsActivity", "url", null); checked++;
            checkPage("LensSettingsActivity", "toggle_take_photo", null); checked++;
            checkNativeNavigation();
            result.putString("stream", "Settings startup: " + checked + " entry points passed\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            checkPage("LensSettingsActivity", "toggle_take_photo", null); checked++;
            checkNativeNavigation();
            result.putString("stream", android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void checkNativeNavigation() throws Exception {
        String pkg = getTargetContext().getPackageName();
        Activity cards = startActivitySync(new Intent().setClassName(pkg, pkg + ".NativeCardsActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        android.content.SharedPreferences preferences = getTargetContext().getSharedPreferences("native_cards", 0);
        boolean existed = preferences.contains("lens");
        java.util.Set<String> before = preferences.getStringSet("lens", java.util.Set.of());
        try {
            waitForIdleSync(); SystemClock.sleep(1000); waitForIdleSync();
            runOnMainSync(() -> {
                if ((cards.getWindow().getAttributes().flags & android.view.WindowManager.LayoutParams.FLAG_SECURE) == 0)
                    throw new AssertionError("Campus window must be secure before rendering");
                if (cards.getWindow().getDecorView().findViewWithTag("dhu_settings") == null)
                    throw new AssertionError("Real campus surface missing");
            });
            ActivityMonitor settingsMonitor = addMonitor(pkg + ".HostActivity", null, false);
            runOnMainSync(() -> cards.getWindow().getDecorView().findViewWithTag("native_settings").performClick());
            Activity settings = waitForMonitorWithTimeout(settingsMonitor, 5000);
            removeMonitor(settingsMonitor);
            if (settings == null) throw new AssertionError("Card settings icon not connected");
            runOnMainSync(settings::finish); waitForIdleSync();
            ActivityMonitor lensMonitor = addMonitor(pkg + ".LensSettingsActivity", null, false);
            runOnMainSync(() -> cards.getWindow().getDecorView().findViewWithTag("native_lens_settings").performClick());
            Activity lens = waitForMonitorWithTimeout(lensMonitor, 5000);
            removeMonitor(lensMonitor);
            if (lens == null) throw new AssertionError("LENS settings icon not connected");
            waitForIdleSync();
            java.util.concurrent.atomic.AtomicBoolean selected = new java.util.concurrent.atomic.AtomicBoolean();
            runOnMainSync(() -> {
                android.widget.CompoundButton toggle = lens.getWindow().getDecorView().findViewWithTag("settings_toggle_take_photo");
                toggle.performClick(); selected.set(toggle.isChecked());
            });
            if (preferences.getStringSet("lens", java.util.Set.of()).contains("take_photo") != selected.get())
                throw new AssertionError("LENS selection not persisted");
            runOnMainSync(lens::finish); waitForIdleSync();
            runOnMainSync(() -> {
                boolean visible = cards.getWindow().getDecorView().findViewWithTag("native_lens_take_photo") != null;
                if (visible != selected.get()) throw new AssertionError("LENS selection not rendered after return");
            });
        } finally {
            android.content.SharedPreferences.Editor editor = preferences.edit();
            if (existed) editor.putStringSet("lens", before); else editor.remove("lens");
            editor.commit();
            runOnMainSync(cards::finish); waitForIdleSync();
        }
    }

    private void checkPage(String name, String binding, String category) {
        String pkg = getTargetContext().getPackageName();
        if (category != null) {
            android.content.pm.ResolveInfo entry = getTargetContext().getPackageManager().resolveActivity(
                    new Intent(Intent.ACTION_MAIN).setPackage(pkg).addCategory(category), 0);
            if (entry == null || !(pkg + "." + name).equals(entry.activityInfo.name))
                throw new AssertionError("Wrong settings entry for " + category);
        }
        Intent intent = new Intent(Intent.ACTION_MAIN).setClassName(pkg, pkg + "." + name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (category != null) intent.addCategory(category);
        Activity activity = startActivitySync(intent);
        try {
            waitForIdleSync();
            // Covers first traversal and asynchronous root/diagnostics callbacks.
            SystemClock.sleep(name.equals("HostToolsActivity") ? 8000 : 1000);
            waitForIdleSync();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            runOnMainSync(() -> {
                try {
                    View view = activity.getWindow().getDecorView().findViewWithTag((name.equals("NativeCardsActivity") ? "native_" : "settings_") + binding);
                    if (activity.isFinishing() || view == null || !view.isAttachedToWindow() || view.getWidth() <= 0)
                        throw new AssertionError(name + ": M3E page missing or not laid out");
                    if (!view.getContext().getClassLoader().loadClass("dev.local.supercardui." + (name.equals("NativeCardsActivity") ? "NativeCardsSurface" : "SettingsSurface"))
                            .getClassLoader().getClass().getName().equals("dalvik.system.DelegateLastClassLoader"))
                        throw new AssertionError(name + ": UI was not loaded from its isolated archive");
                } catch (Throwable error) { failure.set(error); }
            });
            if (failure.get() != null) throw new AssertionError(name + ": startup failed", failure.get());
        } finally { runOnMainSync(activity::finish); waitForIdleSync(); }
    }
}
