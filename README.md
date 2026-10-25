# MapSketch

NeoForge 1.21.1 mod by VolcanicMG: an Excalidraw-style **vector** drawing editor on Xaero's World Map. Draw
rectangles, diamonds, ellipses, arrows, lines, pen strokes and text on the map; select, move, resize, rotate,
restyle, group and reorder them; see them on the World Map and the Minimap; draw together with other players in
real time.

Unlike raster map-paint mods, every drawing stays an editable shape stored by the server.

## Install

| Side | Requires | Optional |
|---|---|---|
| Server | NeoForge 21.1.262+ | FTB Teams (team visibility) |
| Client | NeoForge 21.1.262+, Xaero's World Map | Xaero's Minimap, FTB Teams |

Drawings are stored and shared by the server, so the server needs MapSketch too; on a server without it the draw
button says so. The network channel is optional both ways: vanilla clients can join a MapSketch server. A dedicated
server never loads Xaero or client classes. Singleplayer works through the integrated server.

## Drawing

Open Xaero's World Map and click the ✐ button at the top (or press `G`, rebindable) to enter draw mode. With draw
mode off, Xaero behaves exactly as normal.

| Tool | Keys | |
|---|---|---|
| Hand | `H` | pan and zoom the map as usual (holding `Space` or dragging with the middle mouse button does the same with any tool) |
| Select | `V`, `1` | click, shift-click, drag a box; drag to move, handles to resize (shift = keep ratio) and rotate |
| Rectangle / Diamond / Ellipse | `R` `2` / `D` `3` / `O` `4` | drag; shift = square/circle |
| Arrow / Line | `A` `5` / `L` `6` | drag for a straight one, or click to add points; double-click, `Enter` or `Esc` to finish; shift snaps to 15° |
| Pen | `P` `7` | freehand |
| Text | `T` `8` | click and type; `Enter` new line, `Esc` or click elsewhere to finish; double-click any shape to edit its text |
| Eraser | `E` `0` | drag over drawings |
| Laser | `K` | a fading trail everyone sees, not saved |

`Ctrl+Z` / `Ctrl+Y` (`Ctrl+Shift+Z`) undo/redo your own edits, `Ctrl+C/X/V` copy/cut/paste, `Ctrl+D` duplicate,
`Ctrl+A` select all, `Ctrl+G` / `Ctrl+Shift+G` group/ungroup, `Ctrl+]` / `Ctrl+[` forward/backward (with shift: to
front/back), `Delete` delete, arrow keys nudge. Arrows dropped on a shape bind to it and follow it.

The panel on the left sets the style of the selection and of the next shape: stroke and background colour (or a
hex code), fill, stroke width and style, sloppiness, edges, opacity, arrowheads, text size and alignment, who can
see it, layers, duplicate/delete/group/lock.

While draw mode is on, Xaero's right-click menu and waypoint list clicks are off; switch to Hand or leave draw mode
to use them.

## Sharing and permissions

| Visibility | Who sees it |
|---|---|
| Me | only you |
| Team | your FTB Teams team (and allies, `shareWithAllies`) |
| All | everyone |

New drawings default to Team when FTB Teams is installed, otherwise All (`defaultVisibility`). Anyone who can see a
drawing may edit it (`teamCanEdit`, default on); locked drawings only by their owner and ops. Ops can edit
everything. The server checks every edit and size/rate limit and undoes refused ones on your screen.

## Working together

Other players in draw mode show up live on your map: their cursor with their name, the shape they are drawing, what
they are dragging or typing, their laser. When someone grabs a drawing it is outlined in their colour; trying to grab
it yourself says "Being edited by <name>" (the drawing's owner and ops take it over instead; the other player is told). Undo skips drawings someone else changed since, rather than overwriting
their work.

## Commands (ops)

- `/mapsketch clear player <player>` · `clear dimension <dimension>` · `clear all`
- `/mapsketch release <player>` · `release all` — free stuck edit locks
- `/mapsketch stats`

## Config

Mods > MapSketch > Config, or the "MapSketch…" button in Xaero's World Map settings. Server: default visibility,
viewers can edit, share with allies, limits (points, drawings per player/dimension, text length, KB/s). Client:
enabled, show on minimap, show other players live, default stroke/background colour, stroke width, sloppiness, UI
scale.

## Building

`./gradlew build` (needs the exact Xaero, xaerolib, FTB Teams and Architectury jars in the gitignored `libs/`, see
`build.gradle`). `check` runs `selfCheck` (model, rules, geometry) and `syncSim` (two-client multiplayer race
simulation of the server logic). Design notes: [DESIGN.md](DESIGN.md).

## License

MIT
