package dev.local.supercardhost;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.util.function.IntConsumer;
import dev.local.supercardhost.MaterialUiArchive.Operation;

/** Private MDC archive: never flatten its resources or AndroidX classes into the OEM graph. */
final class DhuMaterialUi {
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
        try { return new Surface(host, (View) MaterialUiArchive.create(host, Operation.CARD)); }
        catch (Exception | LinkageError error) {
            Log.w("SuperCardDhuUi", "Embedded M3E surface unavailable", error);
            return new Surface(host);
        }
    }

    static View lens(Context host) throws Exception { return (View) MaterialUiArchive.create(host, Operation.LENS); }

    static void preparePay(Context host) throws Exception {
        MaterialUiArchive.prepare(host);
    }

    static void stylePay(Context host, String part, View[] views) throws Exception {
        MaterialUiArchive.create(host, Operation.STYLE_PAY, part, views);
    }
    static void renderLens(View root, android.os.Bundle values) throws Exception {
        MaterialUiArchive.update(root, Operation.RENDER_LENS, values);
    }

    static View expanded(Context host) throws Exception { return (View) MaterialUiArchive.create(host, Operation.EXPANDED); }

    static View settings(Context host, String page) throws Exception {
        return (View) MaterialUiArchive.create(host, Operation.SETTINGS, page);
    }

    static void renderSettings(View root, android.os.Bundle values) throws Exception {
        MaterialUiArchive.update(root, Operation.RENDER_SETTINGS, values);
    }

    static void chooseSettings(View root, String title, String[] labels, int selected, IntConsumer action) throws Exception {
        MaterialUiArchive.update(root, Operation.CHOOSE_SETTINGS, title, labels, selected, action);
    }

    static void confirmSettings(View root, String title, String message, Runnable action) throws Exception {
        MaterialUiArchive.update(root, Operation.CONFIRM_SETTINGS, title, message, action);
    }

    static <T extends View> T required(View root, String name, Class<T> type) {
        View result = root.findViewWithTag("dhu_" + name);
        if (!type.isInstance(result)) throw new IllegalStateException("Missing M3E UI binding: " + name);
        return type.cast(result);
    }

}
