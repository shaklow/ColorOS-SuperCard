package dev.local.supercardhost;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** One private session per configured URL; no global CookieHandler or persistent payment cache. */
final class DhuPayClient {
    static final class Failure extends IOException {
        final String kind;
        Failure(String kind, String message) { super(message); this.kind = kind; }
    }
    private final URI authorization;
    interface Connections { HttpURLConnection open(URI uri) throws IOException; }
    private final Connections connections;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    private volatile HttpURLConnection active;
    private volatile boolean cancelled;
    private boolean established;

    DhuPayClient(String url) { this(url, uri -> (HttpURLConnection) uri.toURL().openConnection()); }
    DhuPayClient(String url, Connections connections) {
        authorization = DhuPayProtocol.authorizationUri(url); this.connections = connections;
    }

    synchronized String fetch() throws IOException {
        checkCancelled();
        if (established) {
            String body = request(authorization.resolve("/ThirdWeb/GetBarCode"), "acctype=000&json=true");
            try {
                JSONObject result = new JSONObject(body);
                JSONArray codes = result.optJSONArray("Obj");
                if (result.optBoolean("IsSucceed") && codes != null && codes.length() > 0) {
                    String code = codes.optString(0);
                    if (DhuPayProtocol.validCode(code)) return code;
                    throw new Failure("parse", "付款码格式已变化，请稍后重试");
                }
                if (result.optBoolean("IsSucceed") || !"-404".equals(result.optString("Msg")))
                    throw new Failure("service", "校园卡服务暂时未提供付款码，请稍后重试");
            } catch (org.json.JSONException expiredSession) {
                // This endpoint returns HTML rather than JSON when the session is absent.
            }
            cookies.getCookieStore().removeAll();
            established = false;
        }
        String code = DhuPayProtocol.initialCode(request(authorization, null));
        if (code == null) throw new Failure("authorization", "授权链接无法获取付款码，请在设置中更新链接");
        established = true;
        return code;
    }

    void cancel() {
        cancelled = true;
        HttpURLConnection connection = active;
        if (connection != null) connection.disconnect();
    }

    private void checkCancelled() throws Failure {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new Failure("cancelled", "请求已取消");
    }

    private String request(URI target, String form) throws IOException {
        for (int redirects = 0; redirects <= 3; redirects++) {
            checkCancelled();
            if (!DhuPayProtocol.sameOrigin(authorization, target))
                throw new Failure("authorization", "授权页面跳转异常，请检查链接");
            HttpURLConnection connection = connections.open(target);
            active = connection;
            try {
                checkCancelled();
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(10_000);
                connection.setUseCaches(false);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Accept", form == null ? "text/html" : "application/json");
                connection.setRequestProperty("User-Agent", "SuperCard-DHU/1.0");
                for (Map.Entry<String, List<String>> entry : cookies.get(target, Map.of()).entrySet())
                    if (!entry.getValue().isEmpty())
                        connection.setRequestProperty(entry.getKey(), String.join("; ", entry.getValue()));
                if (form != null) {
                    byte[] data = form.getBytes(StandardCharsets.UTF_8);
                    connection.setRequestMethod("POST");
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                    connection.setRequestProperty("X-Requested-With", "XMLHttpRequest");
                    connection.setDoOutput(true);
                    connection.setFixedLengthStreamingMode(data.length);
                    try (var output = connection.getOutputStream()) { output.write(data); }
                }
                int status = connection.getResponseCode();
                cookies.put(target, connection.getHeaderFields());
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new Failure("authorization", "授权页面跳转异常，请检查链接");
                    try { target = target.resolve(location); }
                    catch (IllegalArgumentException invalid) { throw new Failure("authorization", "授权页面跳转异常，请检查链接"); }
                    form = null;
                    continue;
                }
                if (status == 401 || status == 403) {
                    if (form != null) return ""; // Re-establish the session once using the saved URL.
                    throw new Failure("authorization", "授权链接已失效，请在设置中更新链接");
                }
                if (status != 200) throw new Failure("network", "校园卡服务连接失败，请稍后重试");
                try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        checkCancelled();
                        if (output.size() + count > 512 * 1024) throw new Failure("parse", "校园卡返回内容异常");
                        output.write(buffer, 0, count);
                    }
                    return output.toString(StandardCharsets.UTF_8.name());
                }
            } finally { active = null; connection.disconnect(); }
        }
        throw new Failure("authorization", "授权页面跳转过多，请检查链接");
    }
}
