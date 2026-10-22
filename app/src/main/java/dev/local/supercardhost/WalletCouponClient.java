package dev.local.supercardhost;

import android.app.BroadcastOptions;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ContentProviderClient;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ProviderInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Queries the wallet process under its own account and database identity. */
public final class WalletCouponClient {
    public static final String PACKAGE = "com.finshell.wallet";
    public static final String ACTION = "dev.local.supercardhost.WALLET_COUPONS";
    private static final String BOOTSTRAP = "com.finshell.wallet.card.event.provider";
    private static final class Responses {
        static final Handler HANDLER = create();
        private static Handler create() {
            HandlerThread thread = new HandlerThread("SuperCardWalletResults");
            thread.start();
            return new Handler(thread.getLooper());
        }
    }

    private WalletCouponClient() { }

    static final class QueryFailure extends Exception {
        final String reason;
        QueryFailure(String reason, String message) { super(message); this.reason = reason; }
        boolean invalidatesAccount() {
            return java.util.Set.of("not_logged_in", "account_changed", "account_unavailable",
                    "service_disabled", "locked").contains(reason);
        }
    }

    /** Official exported wallet card-package management entry, also used by settings. */
    public static Intent managementIntent(Context host) {
        try {
            ComponentName component = new ComponentName(PACKAGE,
                    "com.nearme.pay.business.cardpackage.systemsearchindex.SearchRouterActivity");
            ActivityInfo info = host.getPackageManager().getActivityInfo(component, 0);
            if (!info.enabled || !info.exported || !info.applicationInfo.enabled) return null;
            if (info.permission != null && host.checkSelfPermission(info.permission)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) return null;
            return new Intent("com.finshell.wallet.CARD_PACKAGE").setComponent(component)
                    .setPackage(PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        } catch (Exception unavailable) { return null; }
    }

    /** Called on the original coupon loader's worker, never on the UI thread. */
    public static JSONObject query(Context host) throws Exception {
        return new JSONObject(querySnapshot(host).getString("data"));
    }

    /** Includes the wallet's account hash, needed before cached data can be displayed. */
    public static Bundle querySnapshot(Context host) throws Exception {
        return querySnapshot(host, new Connection(host));
    }

    // Package-private seam: tests exercise the real connection/retry decisions,
    // without starting a vendor app or using its account/network services.
    static Bundle querySnapshot(Context host, Connection connection) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("钱包查询必须在后台执行");
        if (Build.VERSION.SDK_INT < 34)
            throw new QueryFailure("unsupported_android", "当前系统不支持钱包连接所需的发送方身份校验");
        requireUnlocked(host);
        try (connection) {
            // A running, hooked wallet does not need Seedling. In some wallet
            // versions that optional provider is disabled/absent or inaccessible.
            Bundle hello = connection.ping();
            if (!connected(hello)) {
                connection.warmUp();
                long deadline = connection.now() + 8_000;
                do {
                    requireUnlocked(host);
                    hello = connection.ping();
                    if (connected(hello)) break;
                    connection.pause();
                } while (connection.now() < deadline);
                if (!connected(hello)) throw new QueryFailure(connection.bootstrapFailure == null
                        ? "bridge_unavailable" : connection.bootstrapFailure,
                        "钱包接入未响应，请确认模块已勾选钱包并重启钱包或手机");
            }
            requireUnlocked(host);
            // Only the handshake is retried here. A native query can run for
            // 18 seconds; never send a second one while waiting for its result.
            Bundle result = connection.exchange("query", 22_000);
            if (result == null || result.isEmpty())
                throw new QueryFailure("bridge_unavailable", "钱包连接已中断，请重新展开卡包");
            if (!"ok".equals(result.getString("status")))
                throw new QueryFailure(result.getString("reason", "native_unavailable"),
                        "钱包票券读取未完成");
            String data = result.getString("data");
            if (data == null || data.length() > 800_000)
                throw new QueryFailure("invalid_response", "钱包券码响应无效");
            JSONObject parsed;
            try { parsed = new JSONObject(data); }
            catch (org.json.JSONException invalid) {
                throw new QueryFailure("invalid_response", "钱包券码响应格式无效");
            }
            for (String key : new String[]{"films", "performances", "coupons"})
                if (parsed.optJSONArray(key) == null)
                    throw new QueryFailure("invalid_response", "钱包券码响应格式无效");
            // Do not return account-bound data after the screen locks.
            requireUnlocked(host);
            return result;
        }
    }

    private static boolean connected(Bundle hello) {
        return hello != null && ("connected".equals(hello.getString("status"))
                // An old wallet process can still be running after APK upgrade.
                || "unsupported_operation".equals(hello.getString("reason")));
    }

    static class Connection implements AutoCloseable {
        final Context host;
        ContentProviderClient provider;
        String bootstrapFailure;
        Connection(Context host) { this.host = host; }
        long now() { return SystemClock.elapsedRealtime(); }
        void pause() throws InterruptedException { Thread.sleep(150); }
        Bundle ping() throws Exception {
            try { return exchange("ping", 2_000); }
            catch (QueryFailure failure) {
                if (!"transport_timeout".equals(failure.reason)) throw failure;
                return null;
            }
        }
        void warmUp() {
            // Acquire only the known no-op public provider; never call wallet
            // business methods or launch a visible Activity to wake the process.
            try {
                ProviderInfo info = host.getPackageManager().resolveContentProvider(BOOTSTRAP, 0);
                if (info == null || !PACKAGE.equals(info.packageName) || !info.enabled || !info.exported
                        || info.applicationInfo == null || !info.applicationInfo.enabled) {
                    bootstrapFailure = "bootstrap_unavailable";
                    return;
                }
                provider = host.getContentResolver().acquireContentProviderClient(BOOTSTRAP);
                if (provider == null) bootstrapFailure = "bootstrap_unavailable";
            } catch (SecurityException denied) {
                bootstrapFailure = "bootstrap_denied";
            } catch (RuntimeException unavailable) {
                bootstrapFailure = "bootstrap_unavailable";
            }
            if (bootstrapFailure != null)
                Log.w("SuperCardWalletClient", "Wallet warm-up failed reason=" + bootstrapFailure);
        }
        Bundle exchange(String operation, long timeout) throws Exception {
            return request(host, operation, timeout);
        }
        @Override public void close() {
            if (provider != null) { provider.close(); provider = null; }
        }
    }

    private static void requireUnlocked(Context host) throws QueryFailure {
        KeyguardManager manager = host.getSystemService(KeyguardManager.class);
        if (manager == null || manager.isDeviceLocked() || manager.isKeyguardLocked())
            throw new QueryFailure("locked", "解锁后才能读取钱包券码");
    }

    private static Bundle request(Context host, String operation, long timeout) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Bundle> reply = new AtomicReference<>();
        BroadcastOptions options = BroadcastOptions.makeBasic();
        options.setShareIdentityEnabled(true);
        Intent intent = new Intent(ACTION).setPackage(PACKAGE).putExtra("op", operation);
        host.sendOrderedBroadcast(intent, null, options.toBundle(), new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent original) {
                Bundle data = getResultExtras(false);
                reply.set(data == null ? null : new Bundle(data));
                latch.countDown();
            }
        }, Responses.HANDLER, 0, null, null);
        if (!latch.await(timeout, TimeUnit.MILLISECONDS))
            throw new QueryFailure("transport_timeout", "等待钱包响应超时");
        return reply.get();
    }
}
