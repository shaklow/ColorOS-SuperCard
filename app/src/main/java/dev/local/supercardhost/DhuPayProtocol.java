package dev.local.supercardhost;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The observed DHU protocol. Never treats an authorization URL as a payment payload. */
public final class DhuPayProtocol {
    private static final Pattern INITIAL_CODE = Pattern.compile(
            "<div\\b[^>]*\\bid\\s*=\\s*(['\"])qrcode0\\1[^>]*>\\s*([0-9]{20})\\s*</div\\s*>",
            Pattern.CASE_INSENSITIVE);
    public static final long REFRESH_MS = 60_000;

    private DhuPayProtocol() { }

    public static URI authorizationUri(String input) {
        if (input == null || input.length() > 8192) throw invalidUrl();
        try {
            URI uri = new URI(input.trim().replace("&amp;", "&"));
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || !"ecardhall.dhu.edu.cn".equalsIgnoreCase(uri.getHost())
                    || uri.getPort() != 9001 || uri.getUserInfo() != null || uri.getFragment() != null
                    || !"/thirdweb/authpaycodedhu".equalsIgnoreCase(uri.getPath())) throw invalidUrl();
            Map<String, String> query = new HashMap<>();
            if (uri.getRawQuery() != null) for (String part : uri.getRawQuery().split("&")) {
                String[] pair = part.split("=", 2);
                String name = URLDecoder.decode(pair[0], StandardCharsets.UTF_8.name());
                String value = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8.name()) : "";
                if (query.put(name, value) != null) throw invalidUrl();
            }
            for (String key : new String[]{"timestamp", "token", "sign"})
                if (!query.containsKey(key) || query.get(key).trim().isEmpty()) throw invalidUrl();
            return uri;
        } catch (Exception invalid) { throw invalidUrl(); }
    }

    public static String initialCode(String html) {
        Matcher match = INITIAL_CODE.matcher(html);
        return match.find() ? match.group(2) : null;
    }

    public static boolean validCode(String code) { return code != null && code.matches("[0-9]{20}"); }

    static boolean sameOrigin(URI a, URI b) {
        return a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost())
                && a.getPort() == b.getPort() && b.getUserInfo() == null;
    }

    private static IllegalArgumentException invalidUrl() {
        return new IllegalArgumentException("请粘贴完整的东华校园卡授权链接，包含 timestamp、token 和 sign 参数");
    }
}
