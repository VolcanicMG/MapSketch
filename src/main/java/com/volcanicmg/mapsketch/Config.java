package com.volcanicmg.mapsketch;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server and client config specs. */
public final class Config {
    public enum DefaultVisibility { AUTO, PRIVATE, TEAM, PUBLIC }

    public enum Width { THIN, BOLD, EXTRA_BOLD }

    public static final ModConfigSpec SERVER_SPEC;
    public static final ModConfigSpec.EnumValue<DefaultVisibility> DEFAULT_VISIBILITY;
    public static final ModConfigSpec.BooleanValue TEAM_CAN_EDIT;
    public static final ModConfigSpec.BooleanValue SHARE_WITH_ALLIES;
    public static final ModConfigSpec.IntValue MAX_POINTS;
    public static final ModConfigSpec.IntValue MAX_PER_PLAYER;
    public static final ModConfigSpec.IntValue MAX_PER_DIMENSION;
    public static final ModConfigSpec.IntValue MAX_TEXT;
    public static final ModConfigSpec.IntValue EDIT_KBPS;

    public static final ModConfigSpec CLIENT_SPEC;
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.BooleanValue SHOW_ON_MINIMAP;
    public static final ModConfigSpec.BooleanValue SHOW_OTHERS_CURSORS;
    public static final ModConfigSpec.ConfigValue<String> DEFAULT_STROKE;
    public static final ModConfigSpec.ConfigValue<String> DEFAULT_FILL;
    public static final ModConfigSpec.EnumValue<Width> DEFAULT_WIDTH;
    public static final ModConfigSpec.IntValue DEFAULT_ROUGHNESS;
    public static final ModConfigSpec.DoubleValue UI_SCALE;

    static {
        ModConfigSpec.Builder s = new ModConfigSpec.Builder();
        DEFAULT_VISIBILITY = s.comment("Visibility of new drawings. AUTO = TEAM when FTB Teams is installed, else PUBLIC.")
                .defineEnum("defaultVisibility", DefaultVisibility.AUTO);
        TEAM_CAN_EDIT = s.comment("Players who can see a drawing may edit it (locked drawings: owner and ops only).",
                        "False = only the owner and ops edit.")
                .define("teamCanEdit", true);
        SHARE_WITH_ALLIES = s.comment("TEAM drawings are also visible to FTB Teams allies.").define("shareWithAllies", true);
        MAX_POINTS = s.comment("Most points in one line, arrow or pen stroke.").defineInRange("maxPointsPerElement", 2000, 2, 10000);
        MAX_PER_PLAYER = s.comment("Most drawings one player may own.").defineInRange("maxElementsPerPlayer", 2000, 1, 1_000_000);
        MAX_PER_DIMENSION = s.comment("Most drawings in one dimension.").defineInRange("maxElementsPerDimension", 20000, 1, 1_000_000);
        MAX_TEXT = s.comment("Longest text in characters.").defineInRange("maxTextLength", 500, 1, 4096);
        EDIT_KBPS = s.comment("Edit bandwidth per player, KB/s. Edits over it are rejected and resynced.")
                .defineInRange("editKBps", 256, 8, 65536);
        SERVER_SPEC = s.build();

        ModConfigSpec.Builder c = new ModConfigSpec.Builder();
        ENABLED = c.comment("Show drawings and the draw tools.").define("enabled", true);
        SHOW_ON_MINIMAP = c.define("showOnMinimap", true);
        SHOW_OTHERS_CURSORS = c.comment("Show other players' cursors, shapes in progress and laser on the world map.")
                .define("showOthersCursors", true);
        DEFAULT_STROKE = c.comment("Stroke colour of new drawings, #RRGGBB or #AARRGGBB.").define("defaultStrokeColor", "#1e1e1e");
        DEFAULT_FILL = c.comment("Background colour of new shapes, #AARRGGBB; alpha 00 = transparent.")
                .define("defaultFillColor", "#00000000");
        DEFAULT_WIDTH = c.defineEnum("defaultStrokeWidth", Width.BOLD);
        DEFAULT_ROUGHNESS = c.comment("0 = architect, 1 = artist, 2 = cartoonist.").defineInRange("defaultSloppiness", 0, 0, 2);
        UI_SCALE = c.comment("Size of the toolbar and properties panel.").defineInRange("uiScale", 1.0, 0.5, 3.0);
        CLIENT_SPEC = c.build();
    }

    private Config() {
    }

    /** @return ARGB from {@code #RRGGBB} / {@code #AARRGGBB}, or {@code fallback} */
    public static int parseColor(String s, int fallback) {
        try {
            String hex = s.startsWith("#") ? s.substring(1) : s;
            if (hex.length() == 6) return 0xFF000000 | Integer.parseUnsignedInt(hex, 16);
            if (hex.length() == 8) return Integer.parseUnsignedInt(hex, 16);
        } catch (NumberFormatException ignored) {
        }
        return fallback;
    }
}
