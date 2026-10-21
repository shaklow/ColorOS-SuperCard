package dev.local.supercardhost;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The link is edited locally; it is never accepted from an external Intent. */
public final class DhuPaySettingsActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private EditText url;
    private TextView status;
    private Button save, test;
    private long generation;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setTitle("东华校园卡设置");
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        body.setPadding(pad, pad, pad, pad); scroll.addView(body); setContentView(scroll);
        TextView heading = new TextView(this); heading.setText("东华校园卡"); heading.setTextSize(24); body.addView(heading);
        TextView description = new TextView(this);
        description.setText("授权链接\n粘贴校园卡支付页面的完整链接。链接失效时，在这里更新即可。");
        description.setPadding(0, pad, 0, pad / 2); body.addView(description);
        url = new EditText(this); url.setSingleLine(false); url.setMinLines(4); url.setMaxLines(8);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        url.setHint("http://ecardhall.dhu.edu.cn:9001/thirdweb/authpaycodedhu?…");
        url.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        url.setSaveEnabled(false); body.addView(url, new LinearLayout.LayoutParams(-1, -2));
        save = new Button(this); save.setText("保存授权链接"); body.addView(save);
        test = new Button(this); test.setText("验证已保存的链接"); body.addView(test);
        Button clear = new Button(this); clear.setText("清除授权链接"); body.addView(clear);
        status = new TextView(this); status.setPadding(0, pad / 2, 0, 0); body.addView(status);
        Bundle stored = getContentResolver().call(DhuPayProvider.URI, "readUrl", null, null);
        url.setText(stored == null ? "" : stored.getString("url", ""));
        status.setText(stored != null && stored.getBoolean("configured") ? "已配置授权链接" : "尚未配置授权链接");
        save.setOnClickListener(v -> {
            String value;
            try { value = DhuPayProtocol.authorizationUri(url.getText().toString()).toString(); }
            catch (IllegalArgumentException invalid) { url.setError(invalid.getMessage()); return; }
            save(value, true);
        });
        test.setOnClickListener(v -> verify());
        clear.setOnClickListener(v -> { url.setText(""); save("", false); });
    }

    private void save(String value, boolean verify) {
        generation++;
        test.setEnabled(true);
        Bundle result = getContentResolver().call(DhuPayProvider.URI, "save", value, null);
        if (result == null || result.containsKey("error")) {
            status.setText(result == null ? "保存失败，请重试" : result.getString("message")); return;
        }
        status.setText(value.isEmpty() ? "授权链接已清除" : "已保存，正在验证…");
        if (verify) verify();
    }

    private void verify() {
        long expected = ++generation;
        status.setText("正在连接校园卡服务…"); test.setEnabled(false);
        worker.execute(() -> {
            Bundle result;
            try { result = getContentResolver().call(DhuPayProvider.URI, "fetch", null, null); }
            catch (RuntimeException failure) { result = null; }
            Bundle reply = result;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || expected != generation) return;
                test.setEnabled(true);
                status.setText(reply == null ? "服务暂不可用，请重试" : reply.containsKey("error")
                        ? reply.getString("message") : "验证成功，可以在卡包中使用东华校园卡");
                // Testing must not silently invalidate a code already visible in SystemUI.
                if (reply != null && !reply.containsKey("error")) getContentResolver().notifyChange(DhuPayProvider.URI, null);
            });
        });
    }

    @Override protected void onDestroy() { generation++; worker.shutdownNow(); super.onDestroy(); }
}
