package dev.local.supercardhost;

import android.graphics.Bitmap;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Offline protocol/session/round-trip checks against the production client and encoders. */
public final class TestDhuPay {
    private static final String URL = "http://ecardhall.dhu.edu.cn:9001/thirdweb/authpaycodedhu?timestamp=test&token=test&sign=test";
    private static final String A = "00001234567890123456", B = "40961234567890123456";
    private static int assertions;

    public static void main(String[] args) throws Exception {
        require(DhuPayProtocol.authorizationUri("  " + URL + "  ").toString().equals(URL), "trim URL");
        for (String bad : new String[]{URL.replace("ecardhall.dhu.edu.cn", "evil.example"),
                URL.replace(":9001", ":9002"), URL.replace("&sign=test", ""), URL + "&token=duplicate",
                URL.replace("http://", "file://"), URL + "#fragment", URL.replace("//ecard", "//user@ecard")}) {
            try { DhuPayProtocol.authorizationUri(bad); throw new AssertionError("accepted unsafe URL"); }
            catch (IllegalArgumentException expected) { assertions++; }
        }
        require(A.equals(DhuPayProtocol.initialCode("<DIV style='display:none' id = 'qrcode0'>\n" + A + " </DIV>")), "server HTML");
        require(DhuPayProtocol.initialCode("<div id='qrcode0'>" + A + "0</div>") == null, "reject wrong length");
        require(!DhuPayProtocol.validCode(URL), "URL is never a payment payload");

        Script session = new Script();
        Fake first = session.add(html(A)).cookie("ASP.NET_SessionId=one; Path=/; HttpOnly");
        Fake second = session.add(json(B));
        session.add("{\"IsSucceed\":false,\"Msg\":\"-404\"}");
        Fake recovered = session.add(html(A)).cookie("ASP.NET_SessionId=two; Path=/; HttpOnly");
        DhuPayClient client = new DhuPayClient(URL, session);
        require(client.fetch().equals(A), "first authorization gets code");
        require(first.getRequestProperty("Cookie") == null, "bootstrap requires no prior Cookie");
        require(client.fetch().equals(B), "refresh gets new code");
        require(second.getRequestProperty("Cookie").contains("ASP.NET_SessionId=one"), "private cookie preserved");
        require(second.output.toString().equals("acctype=000&json=true"), "observed refresh form");
        require(session.paths.get(1).equals("/ThirdWeb/GetBarCode"), "refresh endpoint");
        require(client.fetch().equals(A), "expired session bootstraps once");
        require(recovered.getRequestProperty("Cookie") == null, "expired Cookie removed");
        require(session.paths.size() == 4, "bounded recovery");

        Script isolated = new Script(); Fake other = isolated.add(html(A));
        new DhuPayClient(URL, isolated).fetch();
        require(other.getRequestProperty("Cookie") == null, "sessions are isolated");
        Script failed = new Script(); failed.add("<html>login required</html>");
        expectFailure(new DhuPayClient(URL, failed), "authorization");
        Script badPayload = new Script(); badPayload.add(html(A)); badPayload.add(json(URL));
        DhuPayClient malformed = new DhuPayClient(URL, badPayload); malformed.fetch(); expectFailure(malformed, "parse");
        Script redirect = new Script(); Fake external = redirect.add(""); external.status = 302;
        external.headers = Map.of("Location", List.of("http://evil.example/collect"));
        expectFailure(new DhuPayClient(URL, redirect), "authorization");
        require(redirect.paths.size() == 1, "no cross-origin request");
        Script cancelled = new Script(); DhuPayClient old = new DhuPayClient(URL, cancelled); old.cancel();
        expectFailure(old, "cancelled"); require(cancelled.paths.isEmpty(), "cancel before network");
        Script tooLarge = new Script(); tooLarge.add("x".repeat(512 * 1024 + 1));
        expectFailure(new DhuPayClient(URL, tooLarge), "parse");

        for (String payload : new String[]{A, B}) for (Bitmap image : new Bitmap[]{DhuPayCodes.qr(payload), DhuPayCodes.barcode(payload)}) {
            var source = new RGBLuminanceSource(image.getWidth(), image.getHeight(), image.pixels);
            String decoded = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(source))).getText();
            require(payload.equals(decoded), "QR/CODE128 round trip including leading zeroes");
        }
        System.out.println("DHU checks passed: " + assertions);
    }

    private static String html(String code) { return "<html><div style='display:none' id='qrcode0'>" + code + "</div></html>"; }
    private static String json(String code) { return "{\"IsSucceed\":true,\"Obj\":[\"" + code + "\"]}"; }
    private static void require(boolean value, String label) { if (!value) throw new AssertionError(label); assertions++; }
    private static void expectFailure(DhuPayClient client, String kind) throws Exception {
        try { client.fetch(); throw new AssertionError("expected " + kind); }
        catch (DhuPayClient.Failure expected) { require(expected.kind.equals(kind), "failure category " + kind); }
    }
    private static final class Script implements DhuPayClient.Connections {
        final ArrayDeque<Fake> replies = new ArrayDeque<>(); final List<String> paths = new ArrayList<>();
        Fake add(String body) throws Exception { Fake result = new Fake(body); replies.add(result); return result; }
        @Override public HttpURLConnection open(URI uri) {
            paths.add(uri.getPath()); if (replies.isEmpty()) throw new AssertionError("unexpected extra request"); return replies.remove();
        }
    }
    private static final class Fake extends HttpURLConnection {
        final byte[] body; final ByteArrayOutputStream output = new ByteArrayOutputStream();
        Map<String, List<String>> headers = Map.of(); int status = 200;
        Fake(String body) throws Exception { super(new URI(URL).toURL()); this.body = body.getBytes(java.nio.charset.StandardCharsets.UTF_8); }
        Fake cookie(String value) { headers = Map.of("Set-Cookie", List.of(value)); return this; }
        @Override public int getResponseCode() { return status; }
        @Override public Map<String, List<String>> getHeaderFields() { return headers; }
        @Override public String getHeaderField(String key) { List<String> values = headers.get(key); return values == null ? null : values.get(0); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(body); }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }
}
