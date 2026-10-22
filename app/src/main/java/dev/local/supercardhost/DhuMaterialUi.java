package dev.local.supercardhost;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Display;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import dalvik.system.DelegateLastClassLoader;
import java.io.File;
import java.util.function.IntConsumer;

/** Private MDC archive: never flatten its resources or AndroidX classes into the OEM graph. */
final class DhuMaterialUi {
    private static final String PACKAGE = "dev.local.supercardui";
    private static File archive;
    private static ClassLoader loader;
    private static Class<?> factory;

    private DhuMaterialUi() { }

    static final class Surface {
        final View root, settings, codes, empty, emptyIcon, loading;
        final ImageView qr, barcode;
        final TextView status, countdown, numbers, emptyMessage;
        final ProgressBar progress;
        final boolean available;

        private Surface(Context context, View root) {
            this.root = root;
            settings = required(root, "settings", View.class);
            codes = required(root, "codes", View.class);
            empty = required(root, "empty", View.class);
            emptyIcon = required(root, "empty_icon", View.class);
            loading = required(root, "loading", View.class);
            qr = required(root, "qr", ImageView.class);
            barcode = required(root, "barcode", ImageView.class);
            status = required(root, "status", TextView.class);
            countdown = required(root, "countdown", TextView.class);
            numbers = required(root, "numbers", TextView.class);
            emptyMessage = required(root, "empty_message", TextView.class);
            progress = required(root, "progress", ProgressBar.class);
            available = true;
            // Reuse the very same vendor icon as the other cards when it is available.
            int icon = context.getResources().getIdentifier("ic_setting", "drawable", "com.vivo.card");
            if (icon != 0) {
                try {
                    Drawable drawable = context.getResources().getDrawable(icon, context.getTheme());
                    settings.getClass().getMethod("setGlyph", Drawable.class).invoke(settings, drawable);
                } catch (ReflectiveOperationException | RuntimeException ignored) { /* bundled standard icon */ }
            }
        }

        private Surface(Context context) {
            // A failed UI archive must not crash SystemUI or expose an unprotected code.
            LinearLayout body = new LinearLayout(context);
            body.setOrientation(LinearLayout.VERTICAL); body.setGravity(Gravity.CENTER);
            int padding = Math.round(20 * context.getResources().getDisplayMetrics().density);
            body.setPadding(padding, padding, padding, padding);
            status = new TextView(context); status.setGravity(Gravity.CENTER);
            status.setText("校园卡界面暂不可用，请更新或重新打开卡包"); body.addView(status);
            ImageButton gear = new ImageButton(context);
            gear.setImageResource(android.R.drawable.ic_menu_preferences);
            gear.setContentDescription("校园卡设置"); body.addView(gear);
            root = body; settings = gear; available = false;
            qr = new ImageView(context); barcode = new ImageView(context);
            countdown = new TextView(context); numbers = new TextView(context);
            emptyMessage = new TextView(context); progress = new ProgressBar(context);
            codes = new View(context); empty = new View(context);
            emptyIcon = new View(context); loading = new View(context);
        }
    }

    static Surface create(Context host) {
        try { return new Surface(host, createView(host, "create")); }
        catch (Exception | LinkageError error) {
            Log.w("SuperCardDhuUi", "Embedded M3E surface unavailable", error);
            return new Surface(host);
        }
    }

    static View cards(Context host) throws Exception { return createView(host, "createCards"); }
    static void renderLens(View root, android.os.Bundle values) throws Exception {
        factory.getMethod("renderLens", View.class, android.os.Bundle.class).invoke(null, root, values);
    }

    static View expanded(Context host) throws Exception { return createView(host, "createExpanded"); }

    static View settings(Context host, String page) throws Exception {
        Context context = archiveContext(host);
        return (View) factory.getMethod("createSettings", Context.class, String.class).invoke(null, context, page);
    }

    static void renderSettings(View root, android.os.Bundle values) throws Exception {
        factory.getMethod("renderSettings", View.class, android.os.Bundle.class).invoke(null, root, values);
    }

    static void chooseSettings(View root, String title, String[] labels, int selected, IntConsumer action) throws Exception {
        factory.getMethod("chooseSettings", View.class, String.class, String[].class, int.class, IntConsumer.class)
                .invoke(null, root, title, labels, selected, action);
    }

    static void confirmSettings(View root, String title, String message, Runnable action) throws Exception {
        factory.getMethod("confirmSettings", View.class, String.class, String.class, Runnable.class)
                .invoke(null, root, title, message, action);
    }

    static <T extends View> T required(View root, String name, Class<T> type) {
        View result = root.findViewWithTag("dhu_" + name);
        if (!type.isInstance(result)) throw new IllegalStateException("Missing M3E UI binding: " + name);
        return type.cast(result);
    }

    private static synchronized View createView(Context host, String method) throws Exception {
        Context context = archiveContext(host);
        return (View) factory.getMethod(method, Context.class).invoke(null, context);
    }

    private static synchronized Context archiveContext(Context host) throws Exception {
        if (factory == null) {
            Context module = host.createPackageContext("dev.local.supercardhost", Context.CONTEXT_IGNORE_SECURITY);
            File directory = new File(host.getCodeCacheDir(), "dhu_material_ui");
            archive = MemoryArchiveResources.copyAsset(module, "dhu-ui.apk", directory);
            // Boot framework classes remain shared. The archive's own complete MDC,
            // AndroidX and Kotlin graph wins over incidental SystemUI/plugin versions.
            loader = new DelegateLastClassLoader(archive.getAbsolutePath(), DhuMaterialUi.class.getClassLoader());
            factory = loader.loadClass(PACKAGE + ".DhuCardSurface");
        }
        PackageInfo info = host.getPackageManager().getPackageArchiveInfo(archive.getAbsolutePath(), 0);
        if (info == null || info.applicationInfo == null || !PACKAGE.equals(info.packageName))
            throw new IllegalStateException("M3E archive manifest mismatch");
        info.applicationInfo.sourceDir = archive.getAbsolutePath();
        info.applicationInfo.publicSourceDir = archive.getAbsolutePath();
        info.applicationInfo.splitSourceDirs = null; info.applicationInfo.splitPublicSourceDirs = null;
        Resources source = host.getPackageManager().getResourcesForApplication(info.applicationInfo);
        Resources resources = new Resources(source.getAssets(), host.getResources().getDisplayMetrics(),
                host.getResources().getConfiguration());
        return new ArchiveContext(host, resources, loader);
    }

    private static final class ArchiveContext extends ContextWrapper {
        private final Resources resources;
        private final ClassLoader uiLoader;
        private final Resources.Theme theme;

        ArchiveContext(Context host, Resources resources, ClassLoader loader) {
            super(host); this.resources = resources; this.uiLoader = loader;
            int style = resources.getIdentifier("Theme.DhuUi", "style", PACKAGE);
            if (style == 0) throw new Resources.NotFoundException("Missing M3E theme");
            theme = resources.newTheme(); theme.applyStyle(style, true);
        }
        @Override public Resources getResources() { return resources; }
        @Override public AssetManager getAssets() { return resources.getAssets(); }
        @Override public ClassLoader getClassLoader() { return uiLoader; }
        @Override public Resources.Theme getTheme() { return theme; }
        @Override public void setTheme(int id) { theme.applyStyle(id, true); }
        @Override public Context createConfigurationContext(Configuration configuration) {
            return configured(getBaseContext().createConfigurationContext(configuration));
        }
        @Override public Context createDisplayContext(Display display) {
            return configured(getBaseContext().createDisplayContext(display));
        }
        private Context configured(Context host) {
            return new ArchiveContext(host, new Resources(resources.getAssets(),
                    host.getResources().getDisplayMetrics(), host.getResources().getConfiguration()), uiLoader);
        }
        @Override public Object getSystemService(String name) {
            if (LAYOUT_INFLATER_SERVICE.equals(name)) {
                try { return (LayoutInflater) factory.getMethod("inflater", Context.class).invoke(null, this); }
                catch (ReflectiveOperationException error) { throw new IllegalStateException("M3E inflater unavailable", error); }
            }
            return super.getSystemService(name);
        }
    }
}
