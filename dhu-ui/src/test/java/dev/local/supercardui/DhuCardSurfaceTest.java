package dev.local.supercardui;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.loadingindicator.LoadingIndicator;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** Inflate real MDC widgets/resources, including the framework-only cross-loader contract. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {31, 36})
public class DhuCardSurfaceTest {
    @Test public void surfaceUsesOfficialExpressiveComponentsAndCompleteBindings() {
        View root = DhuCardSurface.create(RuntimeEnvironment.getApplication());
        assertTrue(root instanceof MaterialCardView);
        assertTrue(tag(root, "settings") instanceof MaterialButton);
        assertTrue(tag(root, "loading") instanceof LoadingIndicator);
        assertTrue(tag(root, "progress") instanceof LinearProgressIndicator);
        for (String name : new String[]{"status", "countdown", "numbers", "empty_message"})
            assertTrue(name, tag(root, name) instanceof TextView);
        for (String name : new String[]{"qr", "barcode"}) assertTrue(tag(root, name) instanceof ImageView);
        for (String name : new String[]{"codes", "empty", "empty_icon"}) assertNotNull(tag(root, name));
        assertEquals("校园卡设置", tag(root, "settings").getContentDescription().toString());
        assertFalse("No independent refresh button", hasRefreshButton(root));
    }

    @Test public void previewContainsNoPaymentCredentialOrScannablePlaceholder() {
        View root = DhuCardSurface.create(RuntimeEnvironment.getApplication());
        assertNull(((ImageView) tag(root, "qr")).getDrawable());
        assertNull(((ImageView) tag(root, "barcode")).getDrawable());
        assertEquals(View.INVISIBLE, tag(root, "codes").getVisibility());
        assertEquals(View.VISIBLE, tag(root, "empty").getVisibility());
        assertEquals(View.GONE, tag(root, "loading").getVisibility());
    }

    @Test public void lightAndDarkCodeSheetsRemainOpaqueWhite() {
        for (int mode : new int[]{Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES}) {
            Context app = RuntimeEnvironment.getApplication();
            Configuration config = new Configuration(app.getResources().getConfiguration());
            config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | mode;
            View root = DhuCardSurface.create(app.createConfigurationContext(config));
            View qr = tag(root, "qr");
            MaterialCardView sheet = (MaterialCardView) qr.getParent().getParent().getParent();
            assertEquals(Color.WHITE, sheet.getCardBackgroundColor().getDefaultColor());
        }
    }

    @Test public void enlargedSurfaceUsesSameBindingsAndMaterialCloseAction() {
        View root = DhuCardSurface.createExpanded(RuntimeEnvironment.getApplication());
        assertTrue(root instanceof MaterialCardView);
        assertTrue(tag(root, "close") instanceof MaterialButton);
        assertTrue(tag(root, "numbers") instanceof TextView);
        assertNull(((ImageView) tag(root, "qr")).getDrawable());
        assertNull(((ImageView) tag(root, "barcode")).getDrawable());
    }

    // Measure with the same native text engine as screenshots and the installed Android UI.
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void paymentAreaFitsHostPreviewBoundsWithLargerText() {
        Context app = RuntimeEnvironment.getApplication();
        Configuration config = new Configuration(app.getResources().getConfiguration());
        config.fontScale = 1.3f;
        Context context = app.createConfigurationContext(config);
        View root = DhuCardSurface.create(context);
        float density = context.getResources().getDisplayMetrics().density;
        int width = Math.round(280 * density), height = Math.round(450 * density);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        assertTrue("QR area must remain usable: " + tag(root, "qr").getHeight() / density + "dp", tag(root, "qr").getHeight() >= 128 * density);
        assertTrue("Barcode must have a measurable scan area", tag(root, "barcode").getWidth() >= 200 * density);
        TextView account = (TextView) tag(root, "account");
        View heading = (View) account.getParent().getParent();
        int[] subtitle = new int[2], header = new int[2];
        account.getLocationOnScreen(subtitle); heading.getLocationOnScreen(header);
        assertTrue("Large-type subtitle must not be clipped", subtitle[1] + account.getHeight() <= header[1] + heading.getHeight());
        assertTrue("Settings stays inside the card", tag(root, "settings").getRight() <= width);
    }

    private static View tag(View root, String name) { return root.findViewWithTag("dhu_" + name); }

    private static boolean hasRefreshButton(View view) {
        if (view instanceof MaterialButton && "刷新".contentEquals(((MaterialButton) view).getText())) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (hasRefreshButton(group.getChildAt(i))) return true;
        }
        return false;
    }
}
