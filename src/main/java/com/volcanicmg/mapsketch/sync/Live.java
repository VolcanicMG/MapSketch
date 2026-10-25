package com.volcanicmg.mapsketch.sync;

import com.volcanicmg.mapsketch.model.Element;
import java.util.List;

/**
 * Transient live state of one player on the map, relayed by the server and never stored: cursor, ghosts of
 * shapes in progress or being dragged/typed, and the laser trail.
 *
 * @param active     false = left draw mode or the map; receivers drop this player's state
 * @param ghosts     live copies; a ghost with a stored id is drawn instead of the stored element
 * @param appendFrom per ghost: -1 = full copy, k = its points continue the previous ghost from point k (freehand
 *                   streaming)
 * @param laser      trail {@code x,z} pairs relative to (x, z), newest last
 */
public record Live(String dim, double x, double z, boolean active, Element.Visibility visibility, List<Element> ghosts,
                   int[] appendFrom, float[] laser) {
    public static final int MAX_GHOSTS = 64, MAX_LASER = 128;

    public static Live inactive() {
        return new Live("", 0, 0, false, Element.Visibility.PUBLIC, List.of(), new int[0], new float[0]);
    }
}
