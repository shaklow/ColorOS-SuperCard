package dev.local.supercardhost;

/** Value rules shared by settings, defaults and the two SystemUI consumers. No platform access. */
final class GestureSettingsRules {
    static final String RANGE_KEY = "navigation_slide_card_range";
    static final float DEFAULT_TOP = .76f, DEFAULT_BOTTOM = .98f, DEFAULT_OPACITY = .4f;
    static final int DEFAULT_OPACITY_PERCENT = 40;
    static final float MIN_LENGTH = .04f;
    // Edits remain valid after float arithmetic; saved pixel ranges may be shorter.
    static final float EDIT_MIN_LENGTH = .0401f;
    static final String DEFAULT_SIDE = "side_back;side_card";

    private GestureSettingsRules() {}

    static boolean validFractionRange(float top, float bottom) {
        return Float.isFinite(top) && Float.isFinite(bottom) && top >= 0 && bottom <= 1 && top < bottom;
    }
    static boolean validEditRange(float top, float bottom) {
        return validFractionRange(top, bottom) && bottom - top + .000001f >= MIN_LENGTH;
    }
    static boolean validStyle(int value) { return value >= 0 && value <= 3; }
    static int style(int value) { return validStyle(value) ? value : 0; }
    static boolean validOpacity(float value) { return Float.isFinite(value) && value >= 0 && value <= 1; }
    static float opacity(int percent) { return Math.max(0, Math.min(100, percent)) / 100f; }
    static float opacity(float value) {
        return Float.isFinite(value) ? Math.max(0, Math.min(1, value)) : DEFAULT_OPACITY;
    }
    static int edge(int width, float density) {
        if (width <= 0 || !Float.isFinite(density) || density <= 0) throw new IllegalArgumentException("Invalid display");
        return Math.min(width, Math.max(1, Math.round(24f * density)));
    }

    /** Original format uses four finite floats and scales all coordinates by source width. */
    static Float[] parseRange(String raw) {
        if (raw == null) return null;
        String[] parts = raw.split(";", -1);
        if (parts.length != 4) return null;
        Float[] values = new Float[4];
        try {
            for (int i = 0; i < 4; i++) {
                values[i] = Float.parseFloat(parts[i].trim());
                if (!Float.isFinite(values[i])) return null;
            }
        } catch (NumberFormatException ignored) { return null; }
        if (values[0] < 0 || values[2] <= values[0] || values[1] < 0 || values[3] <= values[1]) return null;
        return values;
    }

    static Pixels project(String raw, int width, int height) { return project(parseRange(raw), width, height); }
    static Pixels project(Float[] values, int width, int height) {
        if (values == null || width <= 0 || height <= 0) return null;
        double scale = (double) width / values[2];
        double top = values[1] * scale, bottom = values[3] * scale;
        if (!Double.isFinite(top) || !Double.isFinite(bottom) || top < 0 || bottom > height || top >= bottom) return null;
        int start = (int) Math.round(top), end = (int) Math.round(bottom);
        return start < end ? new Pixels(start, end) : null;
    }

    static String encode(float top, float bottom, int width, int height, float density) {
        if (!validFractionRange(top, bottom) || height <= 0) throw new IllegalArgumentException("Invalid range");
        int edge = edge(width, density);
        int start = Math.min(height - 1, Math.round(top * height));
        int end = Math.min(height, Math.max(start + 1, Math.round(bottom * height)));
        return (width - edge) + ";" + start + ";" + width + ";" + end;
    }

    static boolean validSide(String raw) {
        if (raw == null) return false;
        String[] parts = raw.split(";", -1);
        return parts.length == 2 && sideToken(parts[0]) && sideToken(parts[1]);
    }
    private static boolean sideToken(String token) {
        return switch (token.trim()) {
            case "side_back", "side_card", "side_music", "side_none" -> true;
            default -> false;
        };
    }
    static boolean sideEnabled(String raw) {
        if (raw == null || raw.isEmpty()) raw = DEFAULT_SIDE;
        for (String token : raw.split(";", -1)) if ("side_card".equals(token.trim())) return true;
        return false;
    }
    static String unlocked(String raw, boolean enabled) {
        String first = raw == null ? "side_back" : raw.split(";", -1)[0].trim();
        if (!sideToken(first)) first = "side_back";
        return first + ";" + (enabled ? "side_card" : "side_back");
    }

    static final class Pixels {
        final int top, bottom;
        Pixels(int top, int bottom) { this.top = top; this.bottom = bottom; }
    }
}
