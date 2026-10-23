package dev.local.supercardhost;

import android.os.Bundle;

/** Immutable values shown by the gesture controls. Read tolerance preserves pixel rounding. */
final class FloatSettingsState {
    static final float MIN_LENGTH = GestureSettingsRules.EDIT_MIN_LENGTH;
    final float top, bottom, opacity;
    final boolean visible, autoHide, enabled, unlocked;
    final int lockStyle;
    private final Bundle source;

    FloatSettingsState(float top, float bottom, float opacity, boolean visible, boolean autoHide) {
        this(top, bottom, opacity, visible, autoHide, new Bundle());
    }
    private FloatSettingsState(float top, float bottom, float opacity, boolean visible, boolean autoHide, Bundle source) {
        this.top = top; this.bottom = bottom; this.opacity = opacity;
        this.visible = visible; this.autoHide = autoHide;
        this.source = new Bundle(source); enabled = source.getBoolean("enabled", true);
        unlocked = source.getBoolean("unlocked", true); lockStyle = GestureSettingsRules.style(source.getInt("lockStyle"));
    }

    static FloatSettingsState defaults() { return new FloatSettingsState(GestureSettingsRules.DEFAULT_TOP, GestureSettingsRules.DEFAULT_BOTTOM, GestureSettingsRules.DEFAULT_OPACITY, true, true); }

    static FloatSettingsState read(Bundle value) {
        if (value == null || !value.containsKey("top") || !value.containsKey("bottom")) return null;
        float top = value.getFloat("top"), bottom = value.getFloat("bottom");
        if (!GestureSettingsRules.validFractionRange(top, bottom)) return null;
        return new FloatSettingsState(top, bottom, GestureSettingsRules.opacity(value.getFloat("handleOpacity", GestureSettingsRules.DEFAULT_OPACITY)),
                value.getBoolean("handleVisible", true), value.getBoolean("handleAutoHide", true), value);
    }

    FloatSettingsState range(int kind, int progress) {
        float fraction = clamp(progress / 1000f, 0, 1), length = bottom - top;
        float nextTop;
        if (kind == 0) nextTop = fraction * (1 - length);
        else {
            length = MIN_LENGTH + fraction * (1 - MIN_LENGTH);
            nextTop = clamp((top + bottom - length) / 2, 0, 1 - length);
        }
        return range(nextTop, nextTop + length);
    }

    FloatSettingsState range(float start, float end) { return copy(start, end, opacity, visible, autoHide).normalized(); }
    private FloatSettingsState copy(float start, float end, float alpha, boolean shown, boolean hide) {
        return new FloatSettingsState(start, end, alpha, shown, hide, source);
    }

    FloatSettingsState normalized() {
        float start = clamp(top, 0, 1), end = clamp(bottom, start, 1);
        if (end - start < MIN_LENGTH) { start = Math.min(start, 1 - MIN_LENGTH); end = Math.min(1, start + MIN_LENGTH); }
        return copy(start, end, opacity, visible, autoHide);
    }

    FloatSettingsState opacity(float value) { return copy(top, bottom, GestureSettingsRules.opacity(value), visible, autoHide); }
    FloatSettingsState visible(boolean value) { return copy(top, bottom, opacity, value, autoHide); }
    FloatSettingsState autoHide(boolean value) { return copy(top, bottom, opacity, visible, value); }
    FloatSettingsState reset() { return copy(GestureSettingsRules.DEFAULT_TOP, GestureSettingsRules.DEFAULT_BOTTOM, GestureSettingsRules.DEFAULT_OPACITY, visible, autoHide); }
    FloatSettingsState unlocked(boolean value) {
        Bundle next = snapshot(); next.putBoolean("unlocked", value); return read(next);
    }
    FloatSettingsState lockStyle(int value) {
        Bundle next = snapshot(); next.putInt("lockStyle", GestureSettingsRules.style(value)); return read(next);
    }
    int progress(int kind) {
        if (kind == 2) return Math.round(opacity * 1000);
        float length = bottom - top;
        return Math.max(0, Math.min(1000, Math.round(1000 * (kind == 0 ? (length >= 1 ? 0 : top / (1 - length))
                : (length - MIN_LENGTH) / (1 - MIN_LENGTH)))));
    }
    Bundle snapshot() {
        Bundle result = new Bundle(source); result.putAll(range()); result.putFloat("handleOpacity", opacity);
        result.putBoolean("handleVisible", visible); result.putBoolean("handleAutoHide", autoHide);
        result.putFloat("minimumLength", MIN_LENGTH);
        result.putBoolean("enabled", enabled); result.putBoolean("unlocked", unlocked); result.putInt("lockStyle", lockStyle);
        return result;
    }
    Bundle range() { Bundle result = new Bundle(); result.putFloat("top", top); result.putFloat("bottom", bottom); return result; }
    Bundle preview() {
        Bundle result = normalized().range(); result.putFloat("handleOpacity", opacity); result.putBoolean("handleVisible", visible); return result;
    }
    private static float clamp(float value, float min, float max) { return Float.isFinite(value) ? Math.max(min, Math.min(max, value)) : min; }
}
