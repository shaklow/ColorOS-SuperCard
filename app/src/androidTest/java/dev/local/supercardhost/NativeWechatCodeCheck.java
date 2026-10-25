package dev.local.supercardhost;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Parcel;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

/** Synthetic data only: real private archive, Canvas drawables, secure window and production cleanup. */
final class NativeWechatCodeCheck {
    private final Instrumentation test;
    NativeWechatCodeCheck(Instrumentation test) { this.test = test; }
    private static Field field(String name) throws Exception {
        Field field = NativePayCard.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
    void run() throws Exception {
        String nonce = "a".repeat(64), challenge = "b".repeat(64);
        WechatCodeControl control = new WechatCodeControl(nonce, 9, challenge, () -> { throw new AssertionError("Foreign process stopped code source"); });
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            WechatCodeWire.INSTANCE.header(data, nonce, 9); data.writeString(challenge);
            control.transact(WechatCodeWire.AUTHORIZE, data, reply, 0); reply.readException();
            if (reply.readInt() != 0 || control.getAuthorized()) throw new AssertionError("Application UID authorized code disclosure");
        } finally { control.close(); data.recycle(); reply.recycle(); }
        Context context = test.getTargetContext();
        WindowManager windows = context.getSystemService(WindowManager.class);
        AtomicReference<NativePayCard> holder = new AtomicReference<>();
        AtomicReference<ImageView> qrHolder = new AtomicReference<>(), barHolder = new AtomicReference<>();
        try {
            main(() -> {
                NativePayCard card = new NativePayCard(context, new CardWindowOwner() {
                    public void hideCard() { throw new AssertionError("Synthetic code launched payment app"); }
                    public void secureCard(boolean secure) { }
                });
                holder.set(card); card.setActive(true);
                float density = context.getResources().getDisplayMetrics().density;
                WindowManager.LayoutParams params = new WindowManager.LayoutParams((int) (288 * density), (int) (466 * density),
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_SECURE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
                params.setTitle("SuperCardNativeWechatFixture"); windows.addView(card, params);
            });
            test.waitForIdleSync(); SystemClock.sleep(100);
            main(() -> {
                NativePayCard card = holder.get();
                View nativeView = (View) MaterialUiArchive.create(context, MaterialUiArchive.Operation.WECHAT_CODE);
                FrameLayout viewport = card.findViewWithTag("pay_viewport");
                viewport.addView(nativeView, new FrameLayout.LayoutParams(-1, -1)); field("codeSurface").set(card, nativeView);
                ImageView qr = nativeView.findViewWithTag("wechat_native_qr"), bar = nativeView.findViewWithTag("wechat_native_barcode");
                qrHolder.set(qr); barHolder.set(bar);
                qr.setImageDrawable(PayCodeDrawable.Companion.qr("100000000000000001"));
                bar.setImageDrawable(PayCodeDrawable.Companion.barcode("100000000000000001"));
                if (!(qr.getDrawable() instanceof PayCodeDrawable) || !(bar.getDrawable() instanceof PayCodeDrawable))
                    throw new AssertionError("Native payment code became a pixel image");
                if ((((WindowManager.LayoutParams) card.getLayoutParams()).flags & WindowManager.LayoutParams.FLAG_SECURE) == 0)
                    throw new AssertionError("Payment window lost FLAG_SECURE");
                field("paintedValue").set(card, "100000000000000001");
                card.setActive(false);
                if (qr.getDrawable() != null || bar.getDrawable() != null || nativeView.getParent() != null
                        || field("paintedValue").get(card) != null || field("codeSurface").get(card) != null)
                    throw new AssertionError("Inactive payment card retained its native code");
                card.setActive(true); card.close();
            });
            android.util.Log.i("SuperCardNativeTest", "Native WeChat: real archive, Canvas drawables, secure window, foreign-UID denial and clear passed");
        } finally {
            main(() -> { NativePayCard card = holder.get(); if (card != null) { card.close(); if (card.isAttachedToWindow()) windows.removeViewImmediate(card); } });
        }
    }
    private interface Action { void run() throws Exception; }
    private void main(Action action) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        test.runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() != null) throw new Exception("Native WeChat code regression", error.get());
    }
}
