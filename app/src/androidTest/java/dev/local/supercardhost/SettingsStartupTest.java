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
            checkPage("HostActivity", "open_cards", Intent.CATEGORY_LAUNCHER); checked++;
            checkPage("HostActivity", "open_runtime", "de.robv.android.xposed.category.MODULE_SETTINGS"); checked++;
            checkPage("HostToolsActivity", "toggle_wx_scan", null); checked++;
            checkPage("DhuPaySettingsActivity", "url", null); checked++;
            result.putString("stream", "Settings startup: " + checked + " entry points passed\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
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
                    View view = activity.getWindow().getDecorView().findViewWithTag("settings_" + binding);
                    if (activity.isFinishing() || view == null || !view.isAttachedToWindow() || view.getWidth() <= 0)
                        throw new AssertionError(name + ": M3E page missing or not laid out");
                    if (!view.getContext().getClassLoader().loadClass("dev.local.supercardui.SettingsSurface")
                            .getClassLoader().getClass().getName().equals("dalvik.system.DelegateLastClassLoader"))
                        throw new AssertionError(name + ": UI was not loaded from its isolated archive");
                } catch (Throwable error) { failure.set(error); }
            });
            if (failure.get() != null) throw new AssertionError(name + ": startup failed", failure.get());
        } finally { runOnMainSync(activity::finish); waitForIdleSync(); }
    }
}
