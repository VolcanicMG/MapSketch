package com.volcanicmg.mapsketch.model;

/** HSV/RGB conversion, ARGB packing and hex parsing. Pure so the self-check covers it. */
public final class Colors {
    private Colors() {
    }

    /**
     * @param h hue 0..1 (wraps), {@code s}, {@code v} 0..1
     * @return opaque-free RGB {@code 0xRRGGBB}
     */
    public static int hsvToRgb(float h, float s, float v) {
        h = (h % 1f + 1f) % 1f * 6f;
        int i = (int) Math.floor(h) % 6;
        float f = h - (float) Math.floor(h), p = v * (1 - s), q = v * (1 - s * f), t = v * (1 - s * (1 - f));
        float r, g, b;
        switch (i) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return channel(r) << 16 | channel(g) << 8 | channel(b);
    }

    /** @return {h, s, v}, each 0..1; hue is 0 for greys */
    public static float[] rgbToHsv(int rgb) {
        float r = (rgb >> 16 & 0xFF) / 255f, g = (rgb >> 8 & 0xFF) / 255f, b = (rgb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        float h = 0;
        if (d > 0) {
            if (max == r) h = ((g - b) / d % 6 + 6) % 6;
            else if (max == g) h = (b - r) / d + 2;
            else h = (r - g) / d + 4;
            h /= 6;
        }
        return new float[]{h, max == 0 ? 0 : d / max, max};
    }

    /** @return ARGB from alpha 0..255 and RGB */
    public static int argb(int alpha, int rgb) {
        return Math.max(0, Math.min(255, alpha)) << 24 | rgb & 0xFFFFFF;
    }

    public static int alpha(int argb) {
        return argb >>> 24;
    }

    /** @return {@code #rrggbb} when opaque, else {@code #aarrggbb} */
    public static String hex(int argb) {
        return alpha(argb) == 0xFF ? String.format("#%06x", argb & 0xFFFFFF) : String.format("#%08x", argb);
    }

    /** @return ARGB from {@code #RRGGBB} / {@code #AARRGGBB} (with or without {@code #}), or {@code fallback} */
    public static int parse(String s, int fallback) {
        try {
            String hex = s.startsWith("#") ? s.substring(1) : s;
            if (hex.length() == 6) return 0xFF000000 | Integer.parseUnsignedInt(hex, 16);
            if (hex.length() == 8) return Integer.parseUnsignedInt(hex, 16);
        } catch (NumberFormatException ignored) {
        }
        return fallback;
    }

    private static int channel(float c) {
        return Math.max(0, Math.min(255, Math.round(c * 255)));
    }
}
