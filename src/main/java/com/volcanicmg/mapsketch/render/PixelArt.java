package com.volcanicmg.mapsketch.render;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Built-in pixel-art stamp symbols: 16x16 grids in four tones, Minecraft item-sprite style. Tones: {@code o} outline
 * (fixed near-black), {@code s} shade, {@code f} fill (the tint), {@code h} highlight, {@code .} transparent. The client
 * bakes them into one texture (a mask per tone) and tints three of the tones from the stroke colour. Pure.
 */
public final class PixelArt {
    public static final int SIZE = 16;
    /** Tone chars in mask order. */
    public static final String TONES = "osfh";
    private static final Map<String, String[]> ALL = new LinkedHashMap<>();

    static {
        add("pin",
                "................",
                ".....oooooo.....",
                "....offfffso....",
                "...ohffssfsso...",
                "...offshhssso...",
                "...offshhssso...",
                "...offfssfsso...",
                "...offffffsso...",
                "....offffsso....",
                "....offffsso....",
                ".....offsso.....",
                ".....offsso.....",
                "......ofso......",
                "......ofso......",
                ".......oo.......",
                "................");
        add("flag",
                "................",
                "..oo............",
                "..ohooooooo.....",
                "..ohhhhfffffo...",
                "..ohhfffffffo...",
                "..ohffffffffso..",
                "..ohfffffffsso..",
                "..ohffffffssso..",
                "..ohooooooooo...",
                "..oho...........",
                "..oho...........",
                "..oho...........",
                "..oho...........",
                "..oho...........",
                ".ooooo..........",
                "................");
        add("x",
                "................",
                "...o........o...",
                "..ofo......oho..",
                ".osffo....ohfso.",
                "..osffo..ohfso..",
                "...osffoohfso...",
                "....osffhfso....",
                ".....osffso.....",
                ".....ohfsfo.....",
                "....ohfssffo....",
                "...ohfsoosffo...",
                "..ohfso..osffo..",
                ".ohfso....osffo.",
                "..oso......oso..",
                "...o........o...",
                "................");
        add("star",
                ".......oo.......",
                "......ohfo......",
                "......ohfo......",
                ".....ohffso.....",
                "ooooohhfffsooooo",
                "ohhhhhffffffssso",
                ".ohhffffffffsso.",
                "..ohffffffffso..",
                "...offffffffo...",
                "...offffffsso...",
                "..offfsoosffso..",
                "..offso..offso..",
                ".offso....offso.",
                ".oso........oso.",
                ".oo..........oo.",
                "................");
        add("heart",
                "................",
                "..oooo....oooo..",
                ".ohhffo..ohffso.",
                "ohhhfffooffffsso",
                "ohhffffffffffsso",
                "ohfffffffffffsso",
                "offffffffffffsso",
                ".offffffffffsso.",
                "..offffffffsso..",
                "...offffffsso...",
                "....offffsso....",
                ".....offsso.....",
                "......osso......",
                ".......oo.......",
                "................",
                "................");
        add("skull",
                "................",
                "................",
                "....oooooooo....",
                "...ohhhffffso...",
                "..ohhffffffsso..",
                "..ohffffffffso..",
                "..ofoooffooofo..",
                "..ofoooffooofo..",
                "..offffoofffso..",
                "...offffffsso...",
                "....ofofofoo....",
                "....offffsso....",
                ".....oooooo.....",
                "................",
                "................",
                "................");
        add("house",
                "................",
                ".......oo.......",
                "......ohfo......",
                ".....ohhffo.....",
                "....ohhffffo....",
                "...ohhffffffo...",
                "..ohhffffffsso..",
                ".oooooooooooooo.",
                "..ohhffffffsso..",
                "..ohfoofffffso..",
                "..ohfoofooofso..",
                "..offfffosofso..",
                "..offfffosofso..",
                "..offfffososso..",
                "..oooooooooooo..",
                "................");
        add("tower",
                "................",
                "..ooo..oo..ooo..",
                "..oho..oo..oso..",
                "..ohooooooooso..",
                "..ohhffffffsso..",
                "...ohffffffso...",
                "...ohfoofffso...",
                "...ohfoofffso...",
                "...ohffffffso...",
                "...ohffffffso...",
                "...ohffoofsso...",
                "...ohfo..ofso...",
                "...ohfo..ofso...",
                "..oooo....oooo..",
                "................",
                "................");
        add("sword",
                "................",
                "............ooo.",
                "...........ohho.",
                "..........ohhso.",
                ".........ohhso..",
                "........ohhso...",
                ".......ohhso....",
                "......ohhso.....",
                "..oo.ohhso......",
                "..ohoohso.......",
                "...ohhso........",
                "...ossoo........",
                "..oso.oho.......",
                ".oso...oo.......",
                ".oo.............",
                "................");
        add("pickaxe",
                "................",
                "....ooooooo.....",
                "...ohhhfffsoo...",
                "..oossoooofsso..",
                "..oo...oooofso..",
                "......ohso.ofso.",
                ".....ohso...oso.",
                "....ohso.....oo.",
                "...ohso.........",
                "..ohso..........",
                ".ohso...........",
                "ohso............",
                "oso.............",
                "oo..............",
                "................",
                "................");
        add("chest",
                "................",
                "................",
                "..oooooooooooo..",
                "..ohhhffffffso..",
                "..ohffffffffso..",
                "..ohffffffffso..",
                "..oooooooooooo..",
                "..ohfffoofffso..",
                "..ohffohhoffso..",
                "..ohfffoofffso..",
                "..offffffffsso..",
                "..offfffffssso..",
                "..oooooooooooo..",
                "................",
                "................",
                "................");
        add("tree",
                ".......oo.......",
                "......ohfo......",
                ".....ohhffo.....",
                "....ohhfffso....",
                "...ohhffffsso...",
                "....offffsso....",
                "...ohffffffso...",
                "..ohhfffffsso...",
                ".ohhffffffsssso.",
                "..ooooooooooo...",
                "......oso.......",
                "......oso.......",
                "......oso.......",
                ".....ooooo......",
                "................",
                "................");
        add("cave",
                "................",
                "................",
                "................",
                ".....oooooo.....",
                "...ooffffffoo...",
                "..offhhhhffsso..",
                ".offho....oosso.",
                ".ofho......osso.",
                ".oho........oso.",
                "ofho........osso",
                "ofo..........oso",
                "ofo..........oso",
                "ooo..........ooo",
                "oooooooooooooooo",
                "................",
                "................");
        add("portal",
                "................",
                "..oooooooooooo..",
                "..ohhhhfffffso..",
                "..ohoooooooofo..",
                "..ohosshhssofo..",
                "..ohoshssfsofo..",
                "..ohosfhsssofo..",
                "..ohosshhfsofo..",
                "..ohosfsshsofo..",
                "..ohoshhsssofo..",
                "..ohossfhssofo..",
                "..ohosssfssofo..",
                "..ohoooooooofo..",
                "..offffffffsso..",
                "..oooooooooooo..",
                "................");
        add("question",
                "................",
                "....oooooooo....",
                "...ohhhffffso...",
                "..ohhoooooffso..",
                "..oho.....ofso..",
                "...o.....offso..",
                "........offso...",
                ".......offso....",
                "......offso.....",
                "......ofso......",
                "......oooo......",
                "................",
                "......oooo......",
                "......ohso......",
                "......oooo......",
                "................");
        add("exclamation",
                "................",
                "......oooo......",
                ".....ohhfso.....",
                ".....ohffso.....",
                ".....ohffso.....",
                ".....ohffso.....",
                "......offo......",
                "......offo......",
                "......offo......",
                "......oooo......",
                "................",
                "......oooo......",
                ".....ohhfso.....",
                ".....offsso.....",
                "......oooo......",
                "................");
        add("anchor",
                "................",
                "......oooo......",
                ".....oho.so.....",
                "......oooo......",
                "....oooffooo....",
                "....ohhffsso....",
                "......offo......",
                "......offo......",
                ".oo...offo...oo.",
                "ohso..offo..ofso",
                ".ofso.offo.ofso.",
                "..offoofsoofso..",
                "...offffffsso...",
                "....oooooooo....",
                "................",
                "................");
        add("arrow_up",
                ".......oo.......",
                "......ohfo......",
                ".....ohhffo.....",
                "....ohhfffso....",
                "...ohhffffsso...",
                "..ohhffffffsso..",
                ".ooooofffsooooo.",
                ".....ohffso.....",
                ".....ohffso.....",
                ".....ohffso.....",
                ".....ohffso.....",
                ".....offsso.....",
                ".....offsso.....",
                ".....oooooo.....",
                "................",
                "................");
        add("campfire",
                "................",
                ".......o........",
                "......ohf.......",
                "......ohfo......",
                ".....ohhffo.....",
                "....ohhfffso....",
                "....ohfhffso....",
                "....offhfsso....",
                ".....offsso.....",
                "..oooooooooooo..",
                "..ohhhffssssso..",
                "..oooooooooooo..",
                "...ohfo..ohso...",
                "...oooo..oooo...",
                "................",
                "................");
        add("crown",
                "................",
                "................",
                "..o....oo....o..",
                ".oho..ohho..oso.",
                ".ofo..offo..oso.",
                ".offooffffoofso.",
                ".offffffffffsso.",
                ".offffffffffsso.",
                ".offffffffffsso.",
                ".osssssssssssso.",
                ".ofhhffhhffhhso.",
                ".ofhhffhhffhhso.",
                ".offffffffffsso.",
                ".oooooooooooooo.",
                "................",
                "................");
        add("shield",
                "................",
                ".oooooooooooooo.",
                ".ohhhhhfffffsso.",
                ".ohhffffffffsso.",
                ".ohffffoofffsso.",
                ".ohfffoooofffso.",
                ".ohfffffffffsso.",
                ".ohffffffffssso.",
                "..ohfffffffsso..",
                "..offffffffsso..",
                "...offffffsso...",
                "....offffsso....",
                ".....offsso.....",
                "......oooo......",
                "................",
                "................");
        add("coin",
                "................",
                ".....oooooo.....",
                "...oohhhhffoo...",
                "..ohhhffffffso..",
                "..ohhfsssssfso..",
                ".ohhfsffffhfsso.",
                ".ohfsffffffhfso.",
                ".ohfsffffffhfso.",
                ".ohfsffffffhfso.",
                ".offsffffffhfso.",
                "..offshhhhhfso..",
                "..offffffffsso..",
                "...oofffsssoo...",
                ".....oooooo.....",
                "................",
                "................");
        add("home_base",
                "................",
                ".......oooo.....",
                ".......ohhfo....",
                ".......offfo....",
                ".......ooooo....",
                ".......oo.......",
                "......ohfo......",
                ".....ohhffo.....",
                "....ohhffffo....",
                "...ohhffffsso...",
                "..oooooooooooo..",
                "...ohfoooofso...",
                "...ohfossofso...",
                "...offossofso...",
                "...oooooooooo...",
                "................");
        add("danger",
                ".......oo.......",
                "......ohfo......",
                "......ohfo......",
                ".....ohffso.....",
                ".....ofoofo.....",
                "....offoofso....",
                "....offoofso....",
                "...offfoofsso...",
                "...offfoofsso...",
                "..offffffffsso..",
                "..offffoofffso..",
                ".offfffooffssso.",
                ".offfffffffssso.",
                "oooooooooooooooo",
                "................",
                "................");
    }

    private PixelArt() {
    }

    private static void add(String name, String... rows) {
        ALL.put(name, rows);
    }

    public static boolean has(String name) {
        return ALL.containsKey(name);
    }

    /** @return the 16 rows of {@code name}, or null */
    public static String[] grid(String name) {
        return ALL.get(name);
    }

    public static Set<String> names() {
        return ALL.keySet();
    }

    /** @return the tone index (0..3, see {@link #TONES}) at (x, y), or -1 for transparent */
    public static int tone(String[] grid, int x, int y) {
        return TONES.indexOf(grid[y].charAt(x));
    }

    /**
     * The four tone colours for a tint (RGB): outline, shade (tint x 0.65), fill, highlight (45% toward white). Very
     * dark tints are lifted first so the sprite stays readable against its own outline.
     *
     * @return {outline, shade, fill, highlight} as RGB
     */
    public static int[] tones(int rgb) {
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        double lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
        if (lum < 0.3) {
            double t = (0.3 - lum) / (1 - lum);
            r = (int) Math.round(r + (255 - r) * t);
            g = (int) Math.round(g + (255 - g) * t);
            b = (int) Math.round(b + (255 - b) * t);
        }
        return new int[]{0x1B1B1B, mix(r, g, b, 0.65, 0), r << 16 | g << 8 | b, mix(r, g, b, 1, 0.45)};
    }

    /** Scales by {@code k}, then moves {@code w} of the way to white. */
    private static int mix(int r, int g, int b, double k, double w) {
        int[] c = {r, g, b};
        int out = 0;
        for (int v : c) out = out << 8 | (int) Math.round(Math.min(255, v * k + (255 - v * k) * w));
        return out;
    }

    /**
     * The smallest square around an inclusive pixel bounding box, centred on it and kept inside a {@code w} x {@code h}
     * image (map icons are cropped to it so they fill the stamp box).
     *
     * @return {x, y, side}
     */
    public static int[] squareAround(int x0, int y0, int x1, int y1, int w, int h) {
        int side = Math.min(Math.min(w, h), Math.max(x1 - x0, y1 - y0) + 1);
        int sx = Math.max(0, Math.min(w - side, x0 - (side - (x1 - x0 + 1)) / 2));
        int sy = Math.max(0, Math.min(h - side, y0 - (side - (y1 - y0 + 1)) / 2));
        return new int[]{sx, sy, side};
    }
}
