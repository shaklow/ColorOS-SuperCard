package dev.local.supercardhost;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.SystemClock;

/** Owns credentials and network traffic in the module app, never in SystemUI's settings store. */
public final class DhuPayProvider extends ContentProvider {
    static final String AUTHORITY = "dev.local.supercardhost.dhu-pay";
    static final Uri URI = Uri.parse("content://" + AUTHORITY);
    private final Object lock = new Object();
    private DhuPayClient client;
    private long clientRevision = -1;
    private SharedPreferences preferences;

    @Override public boolean onCreate() {
        preferences = getContext().getSharedPreferences("dhu_pay", Context.MODE_PRIVATE);
        return true;
    }

    @Override public Bundle call(String method, String argument, Bundle extras) {
        enforceCaller("save".equals(method) || "readUrl".equals(method));
        if ("save".equals(method)) {
            String url = argument == null ? "" : argument.trim();
            if (!url.isEmpty()) url = DhuPayProtocol.authorizationUri(url).toString();
            synchronized (lock) {
                long revision = preferences.getLong("revision", 0) + 1;
                if (!preferences.edit().putString("url", url).putLong("revision", revision).commit())
                    return failure("storage", "无法保存授权链接，请重试");
                if (client != null) client.cancel();
                client = null; clientRevision = -1;
            }
            getContext().getContentResolver().notifyChange(URI, null);
            return status();
        }
        if ("status".equals(method)) return status();
        if ("readUrl".equals(method)) {
            Bundle result = status(); result.putString("url", preferences.getString("url", "")); return result;
        }
        if (!"fetch".equals(method)) throw new IllegalArgumentException("Unsupported operation");
        DhuPayClient current;
        long revision;
        synchronized (lock) {
            revision = preferences.getLong("revision", 0);
            // A view can supply the revision it expects, so an old request never switches accounts.
            if (extras != null && extras.containsKey("revision") && extras.getLong("revision") != revision)
                return failure("changed", "授权链接已更新，请重新获取付款码");
            String url = preferences.getString("url", "");
            if (url.isEmpty()) return failure("unconfigured", "请先在设置中添加授权链接");
            if (client == null || clientRevision != revision) {
                client = new DhuPayClient(url); clientRevision = revision;
            }
            current = client;
        }
        try {
            String code = current.fetch();
            synchronized (lock) {
                if (current != client || revision != preferences.getLong("revision", 0))
                    return failure("changed", "授权链接已更新，请重新获取付款码");
            }
            Bundle result = new Bundle();
            result.putString("code", code); result.putLong("revision", revision);
            result.putLong("fetchedAt", SystemClock.elapsedRealtime());
            return result;
        } catch (DhuPayClient.Failure error) { return failure(error.kind, error.getMessage()); }
        catch (java.net.SocketTimeoutException error) { return failure("network", "连接超时，请点击重试"); }
        catch (java.io.IOException error) { return failure("network", "无法连接校园卡服务，请检查网络后重试"); }
        catch (RuntimeException error) { return failure("parse", "校园卡数据暂时无法解析，请稍后重试"); }
    }

    private Bundle status() {
        synchronized (lock) {
            Bundle result = new Bundle();
            result.putBoolean("configured", !preferences.getString("url", "").isEmpty());
            result.putLong("revision", preferences.getLong("revision", 0));
            return result;
        }
    }

    private void enforceCaller(boolean ownOnly) {
        int uid = Binder.getCallingUid();
        if (uid == getContext().getApplicationInfo().uid) return;
        if (!ownOnly) try {
            if (uid == getContext().getPackageManager().getPackageUid("com.android.systemui", 0)) return;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
        throw new SecurityException("Caller cannot access campus payment data");
    }

    private static Bundle failure(String kind, String message) {
        Bundle result = new Bundle(); result.putString("error", kind); result.putString("message", message); return result;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
