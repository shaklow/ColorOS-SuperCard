package dev.local.supercardhost;

import android.app.Dialog;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Native payment UI. Previews contain no generated or cached payment credentials. */
public final class DhuPayView extends LinearLayout {
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final boolean preview;
    private final Context services;
    private final ImageView qr, barcode;
    private final TextView status, numbers;
    private final Button refresh;
    private final ContentObserver observer;
    private boolean active = true, attached, observing, visible, loading, secureApplied;
    private long generation, nextRefresh;
    private String code;
    private Bitmap qrBitmap, barBitmap;
    private Future<?> task;
    private Dialog enlarged;

    public DhuPayView(Context context, boolean preview) {
        super(context); this.preview = preview; this.services = context.getApplicationContext();
        setOrientation(VERTICAL); setGravity(Gravity.CENTER_HORIZONTAL);
        setPadding(dp(20), dp(16), dp(20), dp(16));
        GradientDrawable background = new GradientDrawable(); background.setColor(Color.WHITE);
        background.setCornerRadius(dp(24)); setBackground(background);
        TextView title = label("东华校园卡", 22); title.setTypeface(null, Typeface.BOLD);
        addView(title, new LayoutParams(-1, -2));
        TextView account = label("默认电子账户", 12); account.setTextColor(0xff68707d);
        account.setPadding(0, dp(4), 0, dp(8)); addView(account);
        barcode = new ImageView(context); barcode.setScaleType(ImageView.ScaleType.FIT_CENTER);
        barcode.setContentDescription("校园卡付款条形码"); addView(barcode, new LayoutParams(-1, dp(58)));
        numbers = label(preview ? "付款码将在打开卡片后获取" : "查看付款码数字", 12);
        numbers.setPadding(0, dp(6), 0, dp(6)); addView(numbers);
        qr = new ImageView(context); qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qr.setContentDescription("校园卡付款二维码，点击刷新");
        addView(qr, new LayoutParams(-1, 0, 1));
        status = label(preview ? "二维码与条形码" : "正在准备校园卡…", 12);
        status.setPadding(0, dp(8), 0, dp(4)); addView(status, new LayoutParams(-1, -2));
        LinearLayout actions = new LinearLayout(context); actions.setGravity(Gravity.CENTER);
        refresh = new Button(context); refresh.setText("刷新"); actions.addView(refresh);
        Button settings = new Button(context); settings.setText("设置"); actions.addView(settings); addView(actions);
        refresh.setOnClickListener(v -> requestCode()); qr.setOnClickListener(v -> requestCode());
        barcode.setOnClickListener(v -> enlarge()); numbers.setOnClickListener(v -> enlarge());
        qr.setOnLongClickListener(v -> { enlarge(); return true; });
        settings.setOnClickListener(v -> {
            setActive(false);
            if (PluginRuntime.systemHost) PluginRuntime.hide();
            services.startActivity(settingsIntent(services).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        });
        observer = new ContentObserver(main) {
            @Override public void onChange(boolean self) { invalidateRequest(); if (canShow()) requestCode(); }
        };
        if (preview) {
            qr.setImageDrawable(null); qr.setBackgroundColor(0xfff0f3f7);
            barcode.setBackgroundColor(0xfff0f3f7); refresh.setEnabled(false); settings.setEnabled(false);
        }
    }

    static Intent settingsIntent(Context context) {
        return new Intent().setClassName("dev.local.supercardhost", DhuPaySettingsActivity.class.getName());
    }

    public void setActive(boolean active) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> setActive(active)); return; }
        this.active = active;
        if (!active) { visible = false; main.removeCallbacks(tick); invalidateRequest(); }
        else if (attached && !preview) { main.removeCallbacks(tick); main.post(tick); }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow(); attached = true;
        if (preview) return;
        try { services.getContentResolver().registerContentObserver(DhuPayProvider.URI, false, observer); observing = true; }
        catch (RuntimeException unavailable) { status.setText("校园卡服务暂不可用"); }
        main.post(tick);
    }

    @Override protected void onDetachedFromWindow() {
        attached = false; main.removeCallbacks(tick); invalidateRequest();
        if (observing) { services.getContentResolver().unregisterContentObserver(observer); observing = false; }
        super.onDetachedFromWindow();
    }

    private boolean canShow() {
        if (preview || !attached || !active || !isShown() || getWindowVisibility() != VISIBLE) return false;
        PowerManager power = services.getSystemService(PowerManager.class);
        KeyguardManager keyguard = services.getSystemService(KeyguardManager.class);
        if (power == null || !power.isInteractive() || keyguard == null || keyguard.isKeyguardLocked()) return false;
        Rect area = new Rect();
        return getGlobalVisibleRect(area) && area.width() >= getWidth() * .9f && area.height() > 0;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!attached || preview) return;
            boolean now = canShow();
            if (!now && visible) invalidateRequest();
            if (now && !visible) { visible = true; requestCode(); }
            else visible = now;
            if (now && code != null) {
                long remaining = nextRefresh - SystemClock.elapsedRealtime();
                if (remaining <= 0) requestCode();
                else status.setText("下次刷新 " + ((remaining + 999) / 1000) + " 秒 · 长按二维码放大");
            }
            if (!now) status.setText("解锁并打开卡片后获取付款码");
            main.postDelayed(this, 1000);
        }
    };

    private void requestCode() {
        if (loading || !canShow()) return;
        loading = true; long expected = ++generation;
        clearCode(); refresh.setEnabled(false); status.setText("正在获取付款码…");
        task = IO.submit(() -> {
            Bundle reply = null; Bitmap q = null, b = null;
            try {
                reply = services.getContentResolver().call(DhuPayProvider.URI, "fetch", null, null);
                if (reply != null && DhuPayProtocol.validCode(reply.getString("code"))) {
                    q = DhuPayCodes.qr(reply.getString("code")); b = DhuPayCodes.barcode(reply.getString("code"));
                }
            } catch (Exception unavailable) { reply = null; }
            Bundle result = reply; Bitmap imageQr = q, imageBar = b;
            main.post(() -> {
                if (expected != generation) return;
                task = null; loading = false; refresh.setEnabled(true);
                if (!canShow()) { clearCode(); return; }
                if (result == null || imageQr == null || imageBar == null) {
                    clearCode();
                    status.setText(result == null ? "校园卡服务暂不可用，点击刷新重试" : result.getString("message", "付款码获取失败，请重试"));
                    return;
                }
                long fetched = result.getLong("fetchedAt", 0);
                if (fetched <= 0 || SystemClock.elapsedRealtime() - fetched >= DhuPayProtocol.REFRESH_MS) {
                    clearCode(); status.setText("本次请求已超时，请点击刷新"); return;
                }
                code = result.getString("code"); qrBitmap = imageQr; barBitmap = imageBar;
                if (PluginRuntime.systemHost) { PluginRuntime.setCampusSecure(true); secureApplied = true; }
                qr.setImageBitmap(imageQr); barcode.setImageBitmap(imageBar);
                numbers.setText("查看付款码数字");
                nextRefresh = fetched + DhuPayProtocol.REFRESH_MS;
                status.setText("下次刷新 60 秒 · 长按二维码放大");
            });
        });
    }

    private void invalidateRequest() {
        generation++; loading = false;
        if (task != null) { task.cancel(true); task = null; }
        refresh.setEnabled(true); clearCode();
    }

    private void clearCode() {
        if (enlarged != null) { enlarged.dismiss(); enlarged = null; }
        code = null; qrBitmap = null; barBitmap = null; nextRefresh = 0;
        if (secureApplied) { secureApplied = false; PluginRuntime.setCampusSecure(false); }
        qr.setImageDrawable(null); barcode.setImageDrawable(null);
        numbers.setText("查看付款码数字");
    }

    private void enlarge() {
        if (code == null || !canShow() || SystemClock.elapsedRealtime() >= nextRefresh) return;
        if (enlarged != null) enlarged.dismiss();
        Dialog dialog = new Dialog(getContext());
        WindowManager.LayoutParams attributes = dialog.getWindow().getAttributes();
        attributes.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
        attributes.token = getWindowToken();
        dialog.getWindow().setAttributes(attributes);
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout body = new LinearLayout(getContext()); body.setOrientation(VERTICAL); body.setGravity(Gravity.CENTER);
        body.setPadding(dp(20), dp(20), dp(20), dp(20)); body.setBackgroundColor(Color.WHITE);
        ImageView bigQr = new ImageView(getContext()); bigQr.setImageBitmap(qrBitmap);
        bigQr.setScaleType(ImageView.ScaleType.FIT_CENTER); body.addView(bigQr, new LayoutParams(-1, dp(240)));
        ImageView bigBar = new ImageView(getContext()); bigBar.setImageBitmap(barBitmap);
        bigBar.setScaleType(ImageView.ScaleType.FIT_CENTER); body.addView(bigBar, new LayoutParams(-1, dp(90)));
        TextView digits = label(code.replaceAll("(.{4})(?!$)", "$1 "), 16); body.addView(digits);
        Button close = new Button(getContext()); close.setText("关闭"); close.setOnClickListener(v -> dialog.dismiss()); body.addView(close);
        dialog.setContentView(body); dialog.setOnDismissListener(d -> { if (enlarged == dialog) enlarged = null; });
        enlarged = dialog;
        try { dialog.show(); dialog.getWindow().setLayout(-1, -2); }
        catch (RuntimeException unavailable) { enlarged = null; status.setText("暂时无法放大，请使用卡片中的付款码"); }
    }

    private TextView label(String text, int size) {
        TextView view = new TextView(getContext()); view.setText(text); view.setTextSize(size);
        view.setTextColor(0xff202836); view.setGravity(Gravity.CENTER); return view;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
