package dev.local.supercardui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.util.AttributeSet;
import android.widget.TextView;
import androidx.appcompat.view.ContextThemeWrapper;
import com.google.android.material.color.MaterialColors;

/** The only cross-loader API is framework Context/View/Bitmap; no host or account dependency. */
public final class DhuCardSurface {
    private DhuCardSurface() { }

    public static View create(Context resources) {
        Context context = new ContextThemeWrapper(resources, R.style.Theme_DhuUi);
        View view = inflater(context).inflate(R.layout.dhu_card, null, false);
        // The scannable sheet is intentionally white even in dark mode. Select the
        // light accent tone of the dynamic hue so text stays readable on that sheet.
        int primary = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, 0xff415f91);
        ((TextView) view.findViewWithTag("dhu_numbers")).setTextColor(
                MaterialColors.getColorRoles(primary, true).getAccent());
        return view;
    }

    public static View createExpanded(Context resources) {
        Context context = new ContextThemeWrapper(resources, R.style.Theme_DhuUi);
        return inflater(context).inflate(R.layout.dhu_expanded, null, false);
    }

    /** A host AppCompat Factory2 would otherwise instantiate its own incompatible widgets. */
    public static LayoutInflater inflater(Context context) { return new UiInflater(context); }

    private static final class UiInflater extends LayoutInflater {
        UiInflater(Context context) { super(context); }
        @Override public LayoutInflater cloneInContext(Context context) { return new UiInflater(context); }
        @Override protected View onCreateView(String name, AttributeSet attrs) throws ClassNotFoundException {
            for (String prefix : new String[]{"android.widget.", "android.view."}) {
                try { return createView(name, prefix, attrs); }
                catch (ClassNotFoundException ignored) { }
            }
            return super.onCreateView(name, attrs);
        }
    }
}
