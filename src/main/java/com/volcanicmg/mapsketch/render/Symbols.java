package com.volcanicmg.mapsketch.render;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The built-in vector stamp symbols, in a unit square (x right, z down): strokes (polylines) and filled
 * star-shaped polygons. Drawn by {@link Tessellator} in the stroke colour. Pure.
 */
public final class Symbols {
    /** One symbol: polylines drawn as strokes, polygons drawn filled. */
    public record Symbol(List<float[]> strokes, List<float[]> fills) {
    }

    private static final Map<String, Symbol> ALL = new LinkedHashMap<>();

    static {
        add("pin", List.of(), List.of(pin()));
        add("flag", List.of(new float[]{0.22f, 0.06f, 0.22f, 0.94f}), List.of(new float[]{0.22f, 0.08f, 0.86f, 0.26f, 0.22f, 0.46f}));
        add("x", List.of(new float[]{0.15f, 0.15f, 0.85f, 0.85f}, new float[]{0.85f, 0.15f, 0.15f, 0.85f}), List.of());
        add("star", List.of(), List.of(star()));
        add("heart", List.of(), List.of(heart()));
        add("skull", List.of(circle(0.5f, 0.42f, 0.32f, 24, true), new float[]{0.32f, 0.7f, 0.32f, 0.88f, 0.68f, 0.88f, 0.68f, 0.7f},
                circle(0.38f, 0.42f, 0.07f, 10, true), circle(0.62f, 0.42f, 0.07f, 10, true),
                new float[]{0.44f, 0.75f, 0.44f, 0.88f}, new float[]{0.56f, 0.75f, 0.56f, 0.88f}), List.of());
        add("house", List.of(new float[]{0.1f, 0.48f, 0.5f, 0.12f, 0.9f, 0.48f},
                new float[]{0.2f, 0.4f, 0.2f, 0.9f, 0.8f, 0.9f, 0.8f, 0.4f}, new float[]{0.42f, 0.9f, 0.42f, 0.64f, 0.58f, 0.64f, 0.58f, 0.9f}),
                List.of());
        add("tower", List.of(new float[]{0.25f, 0.92f, 0.25f, 0.12f, 0.35f, 0.12f, 0.35f, 0.22f, 0.45f, 0.22f, 0.45f, 0.12f,
                0.55f, 0.12f, 0.55f, 0.22f, 0.65f, 0.22f, 0.65f, 0.12f, 0.75f, 0.12f, 0.75f, 0.92f, 0.25f, 0.92f},
                new float[]{0.44f, 0.92f, 0.44f, 0.7f, 0.56f, 0.7f, 0.56f, 0.92f}, new float[]{0.45f, 0.4f, 0.55f, 0.4f}), List.of());
        add("sword", List.of(new float[]{0.86f, 0.14f, 0.36f, 0.64f}, new float[]{0.26f, 0.54f, 0.46f, 0.74f},
                new float[]{0.36f, 0.64f, 0.16f, 0.84f}), List.of());
        add("pickaxe", List.of(new float[]{0.2f, 0.85f, 0.65f, 0.4f},
                new float[]{0.32f, 0.18f, 0.55f, 0.12f, 0.75f, 0.2f, 0.88f, 0.4f, 0.86f, 0.66f}), List.of());
        add("chest", List.of(new float[]{0.12f, 0.3f, 0.88f, 0.3f, 0.88f, 0.84f, 0.12f, 0.84f, 0.12f, 0.3f},
                new float[]{0.12f, 0.48f, 0.88f, 0.48f}, new float[]{0.45f, 0.44f, 0.55f, 0.44f, 0.55f, 0.58f, 0.45f, 0.58f, 0.45f, 0.44f}),
                List.of());
        add("tree", List.of(new float[]{0.5f, 0.66f, 0.5f, 0.92f}), List.of(new float[]{0.5f, 0.08f, 0.82f, 0.68f, 0.18f, 0.68f}));
        add("cave", List.of(new float[]{0.06f, 0.86f, 0.94f, 0.86f}, arch()), List.of());
        add("portal", List.of(new float[]{0.24f, 0.08f, 0.76f, 0.08f, 0.76f, 0.92f, 0.24f, 0.92f, 0.24f, 0.08f}, spiral()), List.of());
        add("question", List.of(new float[]{0.3f, 0.32f, 0.34f, 0.18f, 0.5f, 0.12f, 0.66f, 0.18f, 0.7f, 0.32f, 0.62f, 0.44f,
                0.5f, 0.52f, 0.5f, 0.66f}), List.of(circle(0.5f, 0.84f, 0.06f, 10, false)));
        add("exclamation", List.of(new float[]{0.5f, 0.1f, 0.5f, 0.64f}), List.of(circle(0.5f, 0.84f, 0.06f, 10, false)));
    }

    private Symbols() {
    }

    private static void add(String name, List<float[]> strokes, List<float[]> fills) {
        ALL.put(name, new Symbol(strokes, fills));
    }

    public static boolean has(String name) {
        return ALL.containsKey(name);
    }

    public static Symbol get(String name) {
        return ALL.get(name);
    }

    public static Set<String> names() {
        return ALL.keySet();
    }

    private static float[] circle(float cx, float cz, float r, int n, boolean closed) {
        float[] p = new float[(n + (closed ? 1 : 0)) * 2];
        for (int i = 0; i < p.length / 2; i++) {
            double a = 2 * Math.PI * i / n;
            p[i * 2] = cx + (float) Math.cos(a) * r;
            p[i * 2 + 1] = cz + (float) Math.sin(a) * r;
        }
        return p;
    }

    private static float[] pin() {
        List<Float> p = new ArrayList<>();
        for (int i = 0; i <= 16; i++) {
            double a = Math.toRadians(150 + 240.0 * i / 16);
            p.add(0.5f + (float) Math.cos(a) * 0.3f);
            p.add(0.38f + (float) Math.sin(a) * 0.3f);
        }
        p.add(0.5f);
        p.add(0.95f);
        return toArray(p);
    }

    private static float[] star() {
        float[] p = new float[20];
        for (int i = 0; i < 10; i++) {
            double a = -Math.PI / 2 + Math.PI * i / 5;
            float r = i % 2 == 0 ? 0.46f : 0.19f;
            p[i * 2] = 0.5f + (float) Math.cos(a) * r;
            p[i * 2 + 1] = 0.53f + (float) Math.sin(a) * r;
        }
        return p;
    }

    private static float[] heart() {
        float[] p = new float[64];
        for (int i = 0; i < 32; i++) {
            double t = 2 * Math.PI * i / 32;
            double x = 16 * Math.pow(Math.sin(t), 3), z = 13 * Math.cos(t) - 5 * Math.cos(2 * t) - 2 * Math.cos(3 * t) - Math.cos(4 * t);
            p[i * 2] = 0.5f + (float) (x / 36);
            p[i * 2 + 1] = 0.45f - (float) (z / 36);
        }
        return p;
    }

    private static float[] arch() {
        float[] p = new float[34];
        for (int i = 0; i <= 16; i++) {
            double a = Math.PI + Math.PI * i / 16;
            p[i * 2] = 0.5f + (float) Math.cos(a) * 0.34f;
            p[i * 2 + 1] = 0.86f + (float) Math.sin(a) * 0.5f;
        }
        return p;
    }

    private static float[] spiral() {
        float[] p = new float[48];
        for (int i = 0; i < 24; i++) {
            double a = i * 0.55, r = 0.02 + i * 0.008;
            p[i * 2] = 0.5f + (float) (Math.cos(a) * r);
            p[i * 2 + 1] = 0.5f + (float) (Math.sin(a) * r * 1.6);
        }
        return p;
    }

    private static float[] toArray(List<Float> l) {
        float[] out = new float[l.size()];
        for (int i = 0; i < out.length; i++) out[i] = l.get(i);
        return out;
    }
}
