package dev.local.supercardhost;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.ScrollView;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Synthetic tickets through the production native card in a real secure non-Activity window. */
final class NativeCouponCheck {
    private final Instrumentation test;
    NativeCouponCheck(Instrumentation test) { this.test = test; }
    private static final class Feed implements NativeCouponFeed {
        JSONObject data;
        boolean valid = true;
        int subscribers;
        Runnable changed;
        final Set<String> hidden = new HashSet<>();
        Feed() throws Exception { data = snapshot("a".repeat(64)); }
        static JSONObject snapshot(String account) throws Exception {
            return new JSONObject().put("_walletAccountKey", account).put("films", new JSONArray()
                    .put(new JSONObject().put("card_id", "one").put("movieTitle", "Synthetic movie ticket")
                            .put("orderStatus", 1).put("qrCode", "native-coupon-fixture")))
                    .put("performances", new JSONArray()).put("coupons", new JSONArray());
        }
        public JSONObject snapshot() { return valid ? data : null; }
        public boolean current(String account) { return valid && data != null && account.equals(data.optString("_walletAccountKey")); }
        public boolean hidden(String key) { return hidden.contains(key); }
        public boolean remove(String key) { return valid && hidden.add(key); }
        public String state() { return valid ? "ready" : "unavailable"; }
        public String failure() { return valid ? "" : "account_changed"; }
        public void subscribe(Object owner, Runnable listener) { if (++subscribers != 1) throw new AssertionError("Duplicate coupon subscription"); changed = listener; }
        public void unsubscribe(Object owner) { subscribers--; changed = null; }
    }
    void run() throws Exception {
        Context context = test.getTargetContext();
        WindowManager windows = context.getSystemService(WindowManager.class);
        Feed feed = new Feed();
        AtomicReference<NativeCouponCard> holder = new AtomicReference<>();
        try {
            main(() -> {
                NativeCouponCard card = new NativeCouponCard(context, new CardWindowOwner() {
                    public void hideCard() { throw new AssertionError("Fixture unexpectedly opened an external destination"); }
                    public void secureCard(boolean secure) { }
                }, feed);
                holder.set(card); card.setActive(true);
                float density = context.getResources().getDisplayMetrics().density;
                WindowManager.LayoutParams params = new WindowManager.LayoutParams((int) (288 * density), (int) (466 * density),
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_SECURE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
                params.gravity = Gravity.RIGHT | Gravity.BOTTOM; params.setTitle("SuperCardNativeCouponFixture");
                windows.addView(card, params);
            });
            NativeCouponCard card = holder.get();
            await(() -> card.isAttachedToWindow() && card.getWidth() > 0 && card.findViewWithTag("coupon_item_film___one") != null);
            main(() -> {
                if ((((WindowManager.LayoutParams) card.getLayoutParams()).flags & WindowManager.LayoutParams.FLAG_SECURE) == 0)
                    throw new AssertionError("Coupon window is not secure");
                card.findViewWithTag("coupon_item_film___one").performClick();
            });
            ImageView image = card.findViewWithTag("coupon_qr");
            await(() -> image.getDrawable() != null);
            SystemClock.sleep(250);
            main(() -> {
                ScrollView detail = card.findViewWithTag("coupon_detail"); detail.scrollTo(0, 80);
                int before = detail.getScrollY();
                feed.data.getJSONArray("films").getJSONObject(0).put("qrCode", "refreshed-native-fixture");
                feed.changed.run();
                if (card.findViewWithTag("coupon_qr") != image || detail.getScrollY() != before)
                    throw new AssertionError("Coupon refresh replaced the current detail or its scroll position");
                card.setActive(false);
                if (image.getDrawable() != null) throw new AssertionError("Inactive coupon retained its QR");
                card.findViewWithTag("coupon_remove").performClick(); card.findViewWithTag("coupon_confirm_remove").performClick();
                if (!feed.hidden.isEmpty()) throw new AssertionError("Inactive coupon accepted a removal");
                card.setActive(true);
            });
            await(() -> image.getDrawable() != null);
            main(() -> {
                feed.valid = false; feed.changed.run();
                if (image.getDrawable() != null || card.findViewWithTag("coupon_item_film___one") != null)
                    throw new AssertionError("Account invalidation retained ticket data");
                feed.data = Feed.snapshot("b".repeat(64)); feed.valid = true; feed.changed.run();
            });
            await(() -> card.findViewWithTag("coupon_item_film___one") != null);
            main(() -> {
                card.findViewWithTag("coupon_item_film___one").performClick();
                card.findViewWithTag("coupon_remove").performClick();
                if (!feed.hidden.isEmpty()) throw new AssertionError("Removal did not wait for confirmation");
                card.findViewWithTag("coupon_confirm_remove").performClick();
                if (!feed.hidden.equals(Set.of("film___one")) || feed.data.getJSONArray("films").length() != 1)
                    throw new AssertionError("Local removal changed the wallet fixture");
                if (image.getDrawable() != null) throw new AssertionError("Removed coupon retained its QR");
                Runnable late = feed.changed;
                windows.removeViewImmediate(card); late.run();
                if (feed.subscribers != 0 || card.isAttachedToWindow() || image.getDrawable() != null)
                    throw new AssertionError("Coupon subscription or QR survived detach");
            });
            android.util.Log.i("SuperCardNativeTest", "Native coupons: secure detail, refresh retention, inactive/account clearing, local-only removal and detach passed");
        } finally {
            main(() -> { NativeCouponCard card = holder.get(); if (card != null) { if (card.isAttachedToWindow()) windows.removeViewImmediate(card); card.close(); } });
        }
    }
    private interface Action { void run() throws Exception; }
    private void main(Action action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        test.runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new Exception("Native coupon regression", failure.get());
    }
    private interface Condition { boolean value(); }
    private void await(Condition condition) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 6000;
        while (SystemClock.uptimeMillis() < deadline) {
            AtomicReference<Boolean> ready = new AtomicReference<>(false);
            main(() -> ready.set(condition.value()));
            if (ready.get()) return;
            SystemClock.sleep(32);
        }
        throw new AssertionError("Native coupon fixture did not become ready");
    }
}
