package dev.local.supercardhost;

import android.app.Dialog;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Native payment UI. Previews contain no generated or cached payment credentials. */
public final class DhuPayView extends LinearLayout {
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);
    private final Handler control = new Handler(Looper.getMainLooper());
    private final FloatingCardScope lifecycle = new FloatingCardScope((stage, error) -> {
        Log.w("SuperCardDhuUi", "Campus observation failed: " + stage, error);
        if ("register".equals(stage)) showMessage("校园卡服务暂不可用，点击码区重试");
    });
    private final boolean preview;
    private final Context services;
    private Context appearance;
    private DhuMaterialUi.Surface ui;
    private boolean active = true, visible, loading, secureApplied;
    private long generation, nextRefresh;
    private String code;
    private Bitmap qrBitmap, barBitmap;
    private Future<?> task;
    private Dialog enlarged;

    public DhuPayView(Context context, boolean preview) {
        super(context); this.preview = preview; this.services = context.getApplicationContext();
        setOrientation(VERTICAL);
        bindSurface(context);
    }

    private void bindSurface(Context context) {
        DhuMaterialUi.Surface next = DhuMaterialUi.create(context);
        // Prepare and attach the replacement before releasing the cleared old surface.
        try { addView(next.root, new LayoutParams(-1, -1)); }
        catch (RuntimeException error) {
            if (next.root.getParent() == this) removeView(next.root);
            throw error;
        }
        View previous = ui == null ? null : ui.root;
        ui = next; appearance = context;
        if (previous != null) removeView(previous);
        ui.qr.setOnClickListener(v -> requestCode()); ui.empty.setOnClickListener(v -> requestCode());
        ui.barcode.setOnClickListener(v -> enlarge()); ui.numbers.setOnClickListener(v -> enlarge());
        ui.qr.setOnLongClickListener(v -> { enlarge(); return true; });
        ui.settings.setOnClickListener(v -> {
            setActive(false);
            if (PluginRuntime.systemHost) PluginRuntime.hide();
            services.startActivity(settingsIntent(services).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        });
        if (preview) {
            showMessage("打开卡片后获取付款码");
            ui.settings.setEnabled(false); ui.qr.setEnabled(false); ui.empty.setEnabled(false);
        } else if (ui.available) {
            showMessage("正在准备校园卡…");
        }
    }

    @Override protected void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        invalidateRequest();
        try { bindSurface(getContext().createConfigurationContext(config)); }
        catch (RuntimeException | LinkageError error) { Log.w("SuperCardDhuUi", "Cannot update campus appearance", error); }
        visible = false;
        lifecycle.remove(tick);
        if (lifecycle.isAttached() && active && !preview) lifecycle.post(tick);
    }

    static Intent settingsIntent(Context context) {
        return new Intent().setClassName("dev.local.supercardhost", DhuPaySettingsActivity.class.getName());
    }

    public void setActive(boolean active) {
        if (Looper.myLooper() != Looper.getMainLooper()) { control.post(() -> setActive(active)); return; }
        this.active = active;
        if (!active) { visible = false; lifecycle.remove(tick); invalidateRequest(); }
        else if (lifecycle.isAttached() && !preview) { lifecycle.post(tick); }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (preview) return;
        if (lifecycle.attach(services.getContentResolver(), DhuPayProvider.URI, () -> {
            invalidateRequest(); if (canShow()) requestCode();
        }) && active) lifecycle.post(tick);
    }

    @Override protected void onDetachedFromWindow() {
        try { lifecycle.detach(); }
        finally { invalidateRequest(); visible = false; super.onDetachedFromWindow(); }
    }

    private boolean canShow() {
        if (preview || !ui.available || !lifecycle.isAttached() || !active || !isShown() || getWindowVisibility() != VISIBLE) return false;
        PowerManager power = services.getSystemService(PowerManager.class);
        KeyguardManager keyguard = services.getSystemService(KeyguardManager.class);
        if (power == null || !power.isInteractive() || keyguard == null || keyguard.isKeyguardLocked()) return false;
        Rect area = new Rect();
        return getGlobalVisibleRect(area) && area.width() >= getWidth() * .9f && area.height() >= getHeight() * .6f;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!lifecycle.isAttached() || !active || preview) return;
            boolean now = canShow();
            if (!now && visible) invalidateRequest();
            if (now && !visible) { visible = true; requestCode(); }
            else visible = now;
            if (now && code != null) {
                long remaining = nextRefresh - SystemClock.elapsedRealtime();
                if (remaining <= 0) requestCode();
                else showCountdown(remaining);
            }
            if (!now && ui.available) showMessage("解锁并打开卡片后获取付款码");
            lifecycle.postDelayed(this, 1000);
        }
    };

    private void requestCode() {
        if (loading || !canShow()) return;
        FloatingCardScope.Token attachment = lifecycle.token();
        loading = true; long expected = ++generation;
        clearCode(); ui.qr.setEnabled(false); showMessage("正在获取付款码…");
        ui.emptyIcon.setVisibility(GONE); ui.loading.setVisibility(VISIBLE);
        task = IO.submit(() -> {
            Bundle reply = null; Bitmap q = null, b = null;
            try {
                reply = services.getContentResolver().call(DhuPayProvider.URI, "fetch", null, null);
                if (reply != null && DhuPayProtocol.validCode(reply.getString("code"))) {
                    q = DhuPayCodes.qr(reply.getString("code")); b = DhuPayCodes.barcode(reply.getString("code"));
                }
            } catch (Exception unavailable) { reply = null; }
            Bundle result = reply; Bitmap imageQr = q, imageBar = b;
            lifecycle.postFor(attachment, () -> {
                if (expected != generation) return;
                task = null; loading = false; ui.qr.setEnabled(true);
                if (!canShow()) { clearCode(); return; }
                if (result == null || imageQr == null || imageBar == null) {
                    clearCode();
                    showMessage(result == null ? "校园卡服务暂不可用，点击码区重试" : result.getString("message", "付款码获取失败，点击码区重试"));
                    return;
                }
                long fetched = result.getLong("fetchedAt", 0);
                if (fetched <= 0 || SystemClock.elapsedRealtime() - fetched >= DhuPayProtocol.REFRESH_MS) {
                    clearCode(); showMessage("本次请求已超时，点击码区重试"); return;
                }
                code = result.getString("code"); qrBitmap = imageQr; barBitmap = imageBar;
                if (PluginRuntime.systemHost) { PluginRuntime.setCampusSecure(true); secureApplied = true; }
                ui.qr.setImageBitmap(imageQr); ui.barcode.setImageBitmap(imageBar);
                ui.numbers.setText("查看付款码数字");
                ui.empty.setVisibility(GONE); ui.codes.setVisibility(VISIBLE); ui.loading.setVisibility(GONE);
                nextRefresh = fetched + DhuPayProtocol.REFRESH_MS;
                showCountdown(nextRefresh - SystemClock.elapsedRealtime());
            });
        });
    }

    private void invalidateRequest() {
        generation++; loading = false;
        if (task != null) { task.cancel(true); task = null; }
        ui.qr.setEnabled(true); clearCode();
    }

    private void clearCode() {
        if (enlarged != null) { enlarged.dismiss(); enlarged = null; }
        code = null; qrBitmap = null; barBitmap = null; nextRefresh = 0;
        if (secureApplied) { secureApplied = false; PluginRuntime.setCampusSecure(false); }
        ui.qr.setImageDrawable(null); ui.barcode.setImageDrawable(null);
        ui.codes.setVisibility(INVISIBLE); ui.empty.setVisibility(VISIBLE);
        ui.loading.setVisibility(GONE); ui.emptyIcon.setVisibility(VISIBLE);
        ui.numbers.setText("查看付款码数字");
        ui.countdown.setVisibility(GONE); ui.progress.setVisibility(INVISIBLE);
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
        View body;
        try {
            body = DhuMaterialUi.expanded(appearance);
            DhuMaterialUi.required(body, "qr", android.widget.ImageView.class).setImageBitmap(qrBitmap);
            DhuMaterialUi.required(body, "barcode", android.widget.ImageView.class).setImageBitmap(barBitmap);
            DhuMaterialUi.required(body, "numbers", android.widget.TextView.class)
                    .setText(code.replaceAll("(.{4})(?!$)", "$1 "));
            DhuMaterialUi.required(body, "close", View.class).setOnClickListener(v -> dialog.dismiss());
        } catch (Exception | LinkageError unavailable) {
            ui.status.setText("暂时无法放大，请使用卡片中的付款码"); return;
        }
        dialog.setContentView(body); dialog.setOnDismissListener(d -> { if (enlarged == dialog) enlarged = null; });
        enlarged = dialog;
        try {
            dialog.show(); dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(-1, -2);
        } catch (RuntimeException unavailable) { enlarged = null; ui.status.setText("暂时无法放大，请使用卡片中的付款码"); }
    }

    private void showMessage(String message) {
        if (!ui.available) return;
        ui.status.setText(message); ui.emptyMessage.setText(message);
        ui.countdown.setVisibility(GONE); ui.progress.setVisibility(INVISIBLE);
    }

    private void showCountdown(long remaining) {
        if (remaining <= 0) return;
        int seconds = (int) ((remaining + 999) / 1000);
        ui.status.setText("下次刷新"); ui.countdown.setText(seconds + " 秒");
        ui.countdown.setVisibility(VISIBLE); ui.progress.setVisibility(VISIBLE);
        ui.progress.setMax((int) (DhuPayProtocol.REFRESH_MS / 1000));
        ui.progress.setProgress(seconds);
    }
}
