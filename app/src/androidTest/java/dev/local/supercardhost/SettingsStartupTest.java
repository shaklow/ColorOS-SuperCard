package dev.local.supercardhost;

import android.app.Activity;
import android.app.AppOpsManager;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.view.TextureView;
import android.view.Surface;
import android.view.MotionEvent;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/** Installed unified APK: settings startup and private M3E content in a non-Activity window. */
public final class SettingsStartupTest extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            checkPage("HostActivity", "show_cards", Intent.CATEGORY_LAUNCHER);
            checkPage("HostActivity", "open_runtime", "de.robv.android.xposed.category.MODULE_SETTINGS");
            checkPage("HostToolsActivity", "toggle_wx_scan", null);
            checkPage("DhuPaySettingsActivity", "url", null);
            checkFloatingWindow();
            checkFloatingSessions();
            result.putString("stream", "Settings startup: 4 entry points passed\nFloating cards: window embedding passed\nFloating lifecycle: detach/reattach passed\nLens display: embedded activity and touch passed\nRoot display: callback wrapper passed\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void checkFloatingSessions() throws Exception {
        Context target = getTargetContext();
        Class<?> type = target.getClassLoader().loadClass("dev.local.supercardhost.FloatingCardScope");
        Method attach = type.getMethod("attach", ContentResolver.class, Uri.class, Runnable.class);
        Method detach = type.getMethod("detach"), token = type.getMethod("token");
        Method post = type.getMethod("post", Runnable.class);
        Method postFor = type.getMethod("postFor", token.getReturnType(), Runnable.class);
        Class<?> provider = target.getClassLoader().loadClass("dev.local.supercardhost.DhuPayProvider");
        java.lang.reflect.Field uriField = provider.getDeclaredField("URI"); uriField.setAccessible(true);
        Uri uri = (Uri) uriField.get(null);
        AtomicInteger actions = new AtomicInteger();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        Object scope = type.getConstructor(BiConsumer.class).newInstance(
                (BiConsumer<String, RuntimeException>) (stage, error) -> failure.set(error));
        try {
            main(() -> {
                if (!(Boolean) attach.invoke(scope, target.getContentResolver(), uri, (Runnable) () -> {}))
                    throw new AssertionError("Initial floating attachment missing");
                if ((Boolean) attach.invoke(scope, target.getContentResolver(), uri, (Runnable) () -> {}))
                    throw new AssertionError("Duplicate floating attachment accepted");
                Object old = token.invoke(scope);
                post.invoke(scope, (Runnable) () -> actions.addAndGet(100));
                detach.invoke(scope);
                attach.invoke(scope, target.getContentResolver(), uri, (Runnable) () -> {});
                if ((Boolean) postFor.invoke(scope, old, (Runnable) () -> actions.addAndGet(100)))
                    throw new AssertionError("Old floating result crossed a reattachment");
                if (!(Boolean) postFor.invoke(scope, token.invoke(scope), (Runnable) actions::incrementAndGet))
                    throw new AssertionError("Current floating work was rejected");
            });
            waitForIdleSync();
            if (failure.get() != null) throw new AssertionError("Floating observation failed", failure.get());
            if (actions.get() != 1) throw new AssertionError("Detached work still ran: " + actions.get());
        } finally { main(() -> detach.invoke(scope)); }
    }

    private void checkFloatingWindow() throws Exception {
        Context target = getTargetContext();
        String pkg = target.getPackageName();
        AppOpsManager appOps = target.getSystemService(AppOpsManager.class);
        int before = appOps.unsafeCheckOpRawNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                target.getApplicationInfo().uid, pkg);
        String previous = switch (before) {
            case AppOpsManager.MODE_ALLOWED -> "allow";
            case AppOpsManager.MODE_IGNORED -> "ignore";
            case AppOpsManager.MODE_ERRORED -> "deny";
            case AppOpsManager.MODE_FOREGROUND -> "foreground";
            case AppOpsManager.MODE_DEFAULT -> "default";
            default -> throw new AssertionError("Unexpected overlay app-op: " + before);
        };
        ActivityMonitor dashboard = addMonitor(pkg + ".NativeCardsActivity", null, false);
        AtomicReference<FrameLayout> owner = new AtomicReference<>();
        AtomicReference<WindowManager> manager = new AtomicReference<>();
        AtomicInteger modesClicked = new AtomicInteger(), settingsClicked = new AtomicInteger();
        try {
            shell("appops set " + pkg + " SYSTEM_ALERT_WINDOW allow");
            main(() -> {
                Context window = target.createDisplayContext(target.getSystemService(DisplayManager.class).getDisplay(0))
                        .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
                WindowManager wm = window.getSystemService(WindowManager.class); manager.set(wm);
                FrameLayout card = new FrameLayout(window); owner.set(card);
                Class<?> bridge = target.getClassLoader().loadClass(pkg + ".DhuMaterialUi");
                Method create = bridge.getDeclaredMethod("lens", Context.class); create.setAccessible(true);
                Method render = bridge.getDeclaredMethod("renderLens", View.class, Bundle.class); render.setAccessible(true);
                View surface = (View) create.invoke(null, window);
                checkArchiveConfiguration(surface.getContext());
                Bundle modes = new Bundle();
                String[] types = {"take_photo", "portrait", "video", "night_view", "master", "high_piexl"};
                modes.putStringArray("types", types);
                modes.putStringArray("titles", new String[]{"拍照", "人像", "录像", "夜景", "大师模式", "高像素"});
                render.invoke(null, surface, modes);
                for (String type : types) surface.findViewWithTag("lens_mode_" + type)
                        .setOnClickListener(v -> modesClicked.incrementAndGet());
                surface.findViewWithTag("lens_settings").setOnClickListener(v -> settingsClicked.incrementAndGet());
                card.addView(surface, new FrameLayout.LayoutParams(-1, -1));
                float density = window.getResources().getDisplayMetrics().density;
                WindowManager.LayoutParams attributes = new WindowManager.LayoutParams(Math.round(280 * density), Math.round(450 * density),
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
                attributes.gravity = Gravity.CENTER; attributes.setTitle("SuperCard floating UI test");
                wm.addView(card, attributes);
                checkCampusBounds(surface.getContext());
            });
            waitForIdleSync(); SystemClock.sleep(1000); waitForIdleSync();
            main(() -> {
                FrameLayout card = owner.get();
                if (!card.isAttachedToWindow() || card.getWidth() <= 0)
                    throw new AssertionError("Floating card was not attached to WindowManager");
                View surface = card.getChildAt(0), gear = surface.findViewWithTag("lens_settings");
                if (!surface.getContext().getClassLoader().loadClass("dev.local.supercardui.LensCardSurface")
                        .getClassLoader().getClass().getName().equals("dalvik.system.DelegateLastClassLoader"))
                    throw new AssertionError("Floating UI did not use the isolated M3E archive");
                if (gear == null || gear.getWidth() <= 0 || !gear.isAttachedToWindow())
                    throw new AssertionError("Floating card settings icon missing");
                gear.performClick();
                for (String type : new String[]{"take_photo", "portrait", "video", "night_view", "master", "high_piexl"}) {
                    View button = surface.findViewWithTag("lens_mode_" + type);
                    if (button == null || button.getWidth() <= 0) throw new AssertionError("Floating mode missing: " + type);
                    button.performClick();
                }
            });
            if (modesClicked.get() != 6 || settingsClicked.get() != 1 || dashboard.getHits() != 0)
                throw new AssertionError("Floating controls should work in the same window without opening a dashboard");
            main(() -> checkPayAppearance(owner.get(), target.getClassLoader()));
            if (dashboard.getHits() != 0) throw new AssertionError("PAY styling must not launch a dashboard");
            checkLensDisplay(owner.get());
        } finally {
            try {
                main(() -> { if (owner.get() != null && owner.get().isAttachedToWindow()) manager.get().removeViewImmediate(owner.get()); });
            } finally { removeMonitor(dashboard); shell("appops set " + pkg + " SYSTEM_ALERT_WINDOW " + previous); }
        }
    }

    /** Real secondary display + production activity/input adapter, without depending on an OEM camera APK. */
    private void checkLensDisplay(FrameLayout card) throws Exception {
        Context target = getTargetContext();
        AtomicReference<TextureView> texture = new AtomicReference<>();
        AtomicReference<Surface> output = new AtomicReference<>();
        AtomicReference<VirtualDisplay> display = new AtomicReference<>();
        AtomicInteger clicks = new AtomicInteger();
        CountDownLatch clicked = new CountDownLatch(1);
        Activity activity = null;
        ActivityMonitor monitor = addMonitor(target.getPackageName() + ".HostActivity", null, false);
        getUiAutomation().adoptShellPermissionIdentity();
        try {
            target.getClassLoader().loadClass("org.lsposed.hiddenapibypass.HiddenApiBypass")
                    .getMethod("addHiddenApiExemptions", String[].class).invoke(null, (Object) new String[]{"Landroid/"});
            main(() -> {
                TextureView view = new TextureView(card.getContext()); texture.set(view);
                card.addView(view, new FrameLayout.LayoutParams(-1, -1));
            });
            waitForIdleSync(); SystemClock.sleep(500); waitForIdleSync();
            main(() -> {
                TextureView view = texture.get();
                if (!view.isAvailable()) throw new AssertionError("Lens texture unavailable");
                view.getSurfaceTexture().setDefaultBufferSize(600, 900);
                Surface surface = new Surface(view.getSurfaceTexture()); output.set(surface);
                // Use the production touch/focus/group/rotation/removal policy, without
                // SECURE. Secure root creation remains a separate target-ROM boundary.
                display.set(target.getSystemService(DisplayManager.class).createVirtualDisplay(
                        "SuperCard_Lens_Test", 600, 900, 240, surface, 19917 & ~DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE));
            });
            int id = display.get().getDisplay().getDisplayId();
            if (id <= 0) throw new AssertionError("Lens selected the main display");
            checkRootDisplayWrapper(target, output.get(), display.get());
            Class<?> backendType = target.getClassLoader().loadClass(target.getPackageName() + ".LensDisplayBackend");
            Object backend = backendType.getConstructor(Context.class).newInstance(target);
            Intent intent = new Intent().setClassName(target.getPackageName(), target.getPackageName() + ".HostActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            backendType.getMethod("launch", Intent.class, int.class).invoke(backend, intent, id);
            activity = monitor.waitForActivityWithTimeout(10_000);
            if (activity == null || activity.getDisplay().getDisplayId() != id)
                throw new AssertionError("Lens activity escaped to the main display");
            Activity current = activity;
            CountDownLatch laidOut = new CountDownLatch(1);
            AtomicReference<View> control = new AtomicReference<>();
            main(() -> {
                Runnable ready = () -> {
                    View button = current.getWindow().getDecorView().findViewWithTag("settings_show_cards");
                    if (button != null && button.getWidth() > 0 && button.getHeight() > 0 && current.hasWindowFocus()) {
                        control.set(button); laidOut.countDown();
                    }
                };
                // Wait for a laid-out control in the visible secondary window, rather
                // than assuming the first traversal has finished after launch.
                current.getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(ready::run);
                current.getWindow().getDecorView().getViewTreeObserver()
                        .addOnWindowFocusChangeListener(focused -> ready.run());
                ready.run();
            });
            if (!laidOut.await(10, TimeUnit.SECONDS))
                throw new AssertionError("Lens control did not finish secondary-display content/layout/focus");
            int[] location = new int[2];
            main(() -> {
                View button = control.get();
                button.setOnClickListener(v -> { clicks.incrementAndGet(); clicked.countDown(); });
                button.getLocationOnScreen(location);
                location[0] += button.getWidth() / 2; location[1] += button.getHeight() / 2;
            });
            long down = SystemClock.uptimeMillis();
            Method inject = backendType.getMethod("inject", MotionEvent.class, int.class);
            for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
                MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, location[0], location[1], 0);
                event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                try { if (!Boolean.TRUE.equals(inject.invoke(backend, event, id))) throw new AssertionError("Lens input rejected"); }
                finally { event.recycle(); }
            }
            if (!clicked.await(5, TimeUnit.SECONDS) || clicks.get() != 1)
                throw new AssertionError("Lens touch did not reach the embedded activity: " + clicks.get());
        } finally {
            try {
                if (activity != null) { Activity current = activity; main(current::finish); }
                main(() -> {
                    try { if (display.get() != null) display.get().release(); }
                    finally {
                        if (output.get() != null) output.get().release();
                        if (texture.get() != null) card.removeView(texture.get());
                    }
                });
            } finally { removeMonitor(monitor); getUiAutomation().dropShellPermissionIdentity(); }
        }
    }

    /** Exercise the production hidden-framework adapter with a real display callback. */
    private void checkRootDisplayWrapper(Context target, Surface surface, VirtualDisplay original) throws Exception {
        ClassLoader loader = target.getClassLoader();
        Class<?> daemon = loader.loadClass("dev.local.supercardhost.RootDisplayDaemon");
        Method callback = daemon.getDeclaredMethod("callbackBinder", VirtualDisplay.class); callback.setAccessible(true);
        android.os.IBinder binder = (android.os.IBinder) callback.invoke(null, original);
        Class<?> tokenType = loader.loadClass("dev.local.supercardhost.RootDisplayTransactions$DisplayToken");
        java.lang.reflect.Constructor<?> constructor = tokenType.getDeclaredConstructor(int.class, android.os.IBinder.class);
        constructor.setAccessible(true);
        int id = original.getDisplay().getDisplayId();
        Object token = constructor.newInstance(id, binder);
        Class<?> wrapper = loader.loadClass("dev.local.supercardhost.RootDisplayWrapper");
        Method wrap = wrapper.getDeclaredMethod("wrap", String.class, int.class, int.class, int.class,
                Surface.class, int.class, tokenType); wrap.setAccessible(true);
        VirtualDisplay result = (VirtualDisplay) wrap.invoke(null, "SuperCard_Lens_Test", 600, 900, 240,
                surface, 19917 & ~DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE, token);
        if (result == null || result.getDisplay().getDisplayId() != id)
            throw new AssertionError("Root callback wrapper changed the display");
        // Both wrappers refer to the same callback. The original owner releases it in finally.
    }

    private void checkPayAppearance(FrameLayout card, ClassLoader hostLoader) throws Exception {
        Context context = card.getContext();
        FrameLayout panel = new FrameLayout(context);
        android.widget.ImageView frame = new android.widget.ImageView(context);
        android.widget.ImageView logo = new android.widget.ImageView(context);
        android.widget.ImageView settings = new android.widget.ImageView(context);
        android.widget.TextView hint = new android.widget.TextView(context);
        float density = context.getResources().getDisplayMetrics().density;
        View shadow = new View(context);
        GradientDrawable legacy = new GradientDrawable(); legacy.setColor(0xb3ffffff); legacy.setCornerRadius(20 * density);
        shadow.setBackground(legacy);
        android.widget.LinearLayout actions = new android.widget.LinearLayout(context);
        FrameLayout displayOwner = new FrameLayout(context); // Ownership fixture; not a real payment display.
        panel.addView(shadow, new FrameLayout.LayoutParams(-1, -1));
        panel.addView(frame, new FrameLayout.LayoutParams(-1, -1));
        panel.addView(logo); panel.addView(settings); panel.addView(hint);
        panel.addView(actions, new FrameLayout.LayoutParams(-1, Math.round(145 * density), Gravity.BOTTOM));
        FrameLayout.LayoutParams displayParams = new FrameLayout.LayoutParams(160, 240);
        panel.addView(displayOwner, displayParams);
        AtomicInteger clicks = new AtomicInteger();
        settings.setOnClickListener(view -> clicks.incrementAndGet());
        card.removeAllViews(); card.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        Class<?> bridge = hostLoader.loadClass("dev.local.supercardhost.DhuMaterialUi");
        Method style = bridge.getDeclaredMethod("stylePay", Context.class, String.class, View[].class);
        style.setAccessible(true);
        View[] views = new View[]{frame, logo, settings, hint, shadow, actions};
        style.invoke(null, context, "card", views);
        style.invoke(null, context, "card", views); // Rebinding must not add decorative Views.
        panel.measure(View.MeasureSpec.makeMeasureSpec(card.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(card.getHeight(), View.MeasureSpec.EXACTLY));
        panel.layout(0, 0, panel.getMeasuredWidth(), panel.getMeasuredHeight());
        if (!((LayerDrawable) frame.getBackground()).getDrawable(0).getClass().getClassLoader().getClass().getName()
                .equals("dalvik.system.DelegateLastClassLoader"))
            throw new AssertionError("PAY appearance escaped the private UI loader");
        if (settings.getWidth() < 48 * density || settings.getHeight() < 48 * density)
            throw new AssertionError("PAY settings touch target is too small");
        settings.performClick();
        if (clicks.get() != 1 || settings.getParent() != panel || displayOwner.getParent() != panel
                || displayOwner.getLayoutParams() != displayParams || panel.getChildCount() != 7)
            throw new AssertionError("PAY styling changed ownership, geometry or callbacks");
        Bitmap idle = Bitmap.createBitmap(panel.getWidth(), panel.getHeight(), Bitmap.Config.ARGB_8888);
        panel.draw(new Canvas(idle));
        int x = Math.round(3 * density), y = Math.round(12 * density);
        for (int[] corner : new int[][]{{x, y}, {idle.getWidth() - 1 - x, y},
                {x, idle.getHeight() - 1 - y}, {idle.getWidth() - 1 - x, idle.getHeight() - 1 - y}})
            if (Color.alpha(idle.getPixel(corner[0], corner[1])) != 0)
                throw new AssertionError("Legacy PAY underlay is visible outside the new corners");
        frame.setVisibility(View.INVISIBLE);
        Bitmap payment = Bitmap.createBitmap(panel.getWidth(), panel.getHeight(), Bitmap.Config.ARGB_8888);
        panel.draw(new Canvas(payment));
        int cx = panel.getWidth() / 2, cy = Math.round(180 * density);
        if (idle.getPixel(cx, cy) == payment.getPixel(cx, cy))
            throw new AssertionError("PAY artwork is missing or did not hide with its idle frame");
        frame.setVisibility(View.VISIBLE); idle.recycle(); payment.recycle();
        FrameLayout tile = new FrameLayout(context);
        android.widget.ImageView selection = new android.widget.ImageView(context), icon = new android.widget.ImageView(context);
        android.widget.TextView label = new android.widget.TextView(context); label.setText("微信付款码");
        FrameLayout.LayoutParams tileParams = new FrameLayout.LayoutParams(76, 60);
        tile.addView(selection, tileParams); tile.addView(icon); tile.addView(label);
        tile.setOnClickListener(view -> clicks.incrementAndGet());
        style.invoke(null, context, "function", new View[]{tile, selection, icon, label});
        tile.performClick();
        if (selection.getLayoutParams() != tileParams || clicks.get() != 2 || tile.getChildCount() != 3
                || !"微信付款码".contentEquals(label.getText()))
            throw new AssertionError("PAY shortcut styling replaced original controls");
    }

    private void checkArchiveConfiguration(Context original) {
        Configuration before = new Configuration(original.getResources().getConfiguration());
        Configuration changed = new Configuration(before); changed.fontScale = 1.6f;
        changed.uiMode = (changed.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_YES;
        Context configured = original.createConfigurationContext(changed);
        Configuration actual = configured.getResources().getConfiguration();
        if (actual.fontScale != 1.6f || (actual.uiMode & Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES
                || original.getResources().getConfiguration().fontScale != before.fontScale
                || configured.getClassLoader() != original.getClassLoader() || configured.getTheme() == original.getTheme())
            throw new AssertionError("Shared UI archive reused a host configuration or theme");
    }

    private void checkCampusBounds(Context privateAppearance) throws Exception {
        Configuration config = new Configuration(privateAppearance.getResources().getConfiguration()); config.fontScale = 1.3f;
        Context appearance = privateAppearance.createConfigurationContext(config);
        Class<?> factory = privateAppearance.getClassLoader().loadClass("dev.local.supercardui.DhuCardSurface");
        View surface = (View) factory.getMethod("create", Context.class).invoke(null, appearance);
        float density = appearance.getResources().getDisplayMetrics().density;
        surface.measure(View.MeasureSpec.makeMeasureSpec(Math.round(280 * density), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Math.round(450 * density), View.MeasureSpec.EXACTLY));
        surface.layout(0, 0, surface.getMeasuredWidth(), surface.getMeasuredHeight());
        View qr = surface.findViewWithTag("dhu_qr"), barcode = surface.findViewWithTag("dhu_barcode");
        if (qr.getHeight() < 128 * density || barcode.getWidth() < 200 * density)
            throw new AssertionError("Large-font scan area too small on Android: QR=" + qr.getHeight() / density);
    }

    private void checkPage(String name, String binding, String category) throws Exception {
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
            waitForIdleSync(); SystemClock.sleep(name.equals("HostToolsActivity") ? 8000 : 1000); waitForIdleSync();
            main(() -> {
                View view = activity.getWindow().getDecorView().findViewWithTag("settings_" + binding);
                if (activity.isFinishing() || view == null || !view.isAttachedToWindow() || view.getWidth() <= 0)
                    throw new AssertionError(name + ": M3E page missing or not laid out");
                if (!view.getContext().getClassLoader().loadClass("dev.local.supercardui.SettingsSurface")
                        .getClassLoader().getClass().getName().equals("dalvik.system.DelegateLastClassLoader"))
                    throw new AssertionError(name + ": UI was not loaded from its isolated archive");
            });
        } finally { runOnMainSync(activity::finish); waitForIdleSync(); }
    }

    private interface CheckedAction { void run() throws Exception; }
    private void main(CheckedAction action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new AssertionError("Main thread check failed", failure.get());
    }
    private void shell(String command) throws Exception {
        try (ParcelFileDescriptor pipe = getUiAutomation().executeShellCommand(command);
             FileInputStream input = new FileInputStream(pipe.getFileDescriptor())) {
            byte[] buffer = new byte[1024]; while (input.read(buffer) != -1) { /* drain to await completion */ }
        }
    }
}
