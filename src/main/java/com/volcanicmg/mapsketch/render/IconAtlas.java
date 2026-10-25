package com.volcanicmg.mapsketch.render;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Layout of the UI icon atlas {@code textures/gui/icons.png}: square cells in rows of {@link #COLS}, white ink on
 * transparent, listed in {@code icons/atlas.txt}. Shared by the build-time generator ({@code ./gradlew iconAtlas})
 * and the client, which never rasterizes SVG itself.
 */
public final class IconAtlas {
    /** Cell size in texels, cell columns per row, and the empty margin inside each cell. */
    public static final int CELL = 64, COLS = 8, PAD = 4;
    /** Mip levels below the base; cells stay power-of-two aligned down to 8 texels so levels never bleed. */
    public static final int MIP_LEVELS = 3;

    /** @param index cell index; @param aspect viewBox width / height (2 for the 40x20 arrowheads) */
    public record Icon(int index, float aspect) {
        public int cellX() {
            return index % COLS * CELL;
        }

        public int cellY() {
            return index / COLS * CELL;
        }
    }

    private IconAtlas() {
    }

    /**
     * Parses the manifest: one {@code id aspect} per line in cell order; blank lines and {@code #} comments skipped.
     *
     * @return icons by id, in atlas order
     */
    public static Map<String, Icon> parse(List<String> lines) {
        Map<String, Icon> out = new LinkedHashMap<>();
        for (String l : lines) {
            l = l.strip();
            if (l.isEmpty() || l.startsWith("#")) continue;
            String[] p = l.split("\\s+");
            out.put(p[0], new Icon(out.size(), p.length > 1 ? Float.parseFloat(p[1]) : 1f));
        }
        return out;
    }

    /** @return atlas height in texels for {@code n} icons */
    public static int height(int n) {
        return (n + COLS - 1) / COLS * CELL;
    }
}
