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
| Stamp | `S` `9` | pick an item/block icon or a symbol, then click to place it; resize, rotate, move and fade it like any shape |
| Laser | `K` | a fading trail everyone sees, not saved |

`Ctrl+Z` / `Ctrl+Y` (`Ctrl+Shift+Z`) undo/redo your own edits, `Ctrl+C/X/V` copy/cut/paste, `Ctrl+D` duplicate,
`Ctrl+A` select all, `Ctrl+G` / `Ctrl+Shift+G` group/ungroup, `Ctrl+]` / `Ctrl+[` forward/backward (with shift: to
front/back), `Delete` delete, arrow keys nudge.

Arrow and line ends bind to rectangles, diamonds, ellipses and text boxes, and follow them when they move, resize or
rotate. While you drag an end near a shape its link nodes appear (side midpoints and centre; rectangles and diamonds
also their corners). Drop on a node to bind there; drop well inside the shape to bind at that exact spot; drop near
the outline to bind to the edge (the end then slides along the outline, aimed at the other end). Hold `Ctrl` while
dropping to not bind.

The panel on the left sets the style of the selection and of the next shape: stroke and background colour, fill,
stroke width and style, sloppiness, edges, opacity, arrowheads, text size and alignment, who can see it, layers,
duplicate/delete/group/lock. For text the Background row becomes Text background: pick a colour to draw a padded
box behind the text (round or sharp per Edges, rotated with it, transparent = no box).

Stamps: the Stamp tool opens a picker under the toolbar with a search field and three tabs:

- **Symbols**: 24 pixel-art icons in Minecraft item style (pin, flag, X, star, heart, skull, house, tower, sword,
  pickaxe, chest, tree, cave, portal, question and exclamation marks, anchor, arrow, campfire, crown, shield, coin,
  home base, danger), coloured from the stroke colour (dark outline, shade, fill, highlight).
- **Map icons**: the game's own map markers (red X, target, players, banners in 16 colours, mansion, monument,
  villages, temple, swamp hut, trial chambers, frame).
- **Items**: every item and block icon, including modded ones.

Typing searches all three at once (items by name or id); `@modid` limits items to one mod (e.g. `@create`,
`@minecolonies cog`). Arrow keys move the highlight, Enter picks it, Esc clears the search and then closes. Your last
8 stamps sit in a row above the tabs. With a stamp selected, the panel's Stamp row changes it.

Stamps are drawn as crisp pixel textures on the World Map and the Minimap, rotated and scaled with the stamp; when
smaller than a few pixels they become a tinted tile. The server only accepts known symbols and map icons and items
that exist in its registry.

Colours: click a palette swatch, or the current-colour chip at the end of the row to open the colour picker:
saturation/value square, hue bar, opacity bar, old/new preview (click the old half to go back), the palette (the
background palette includes transparent), your last 8 colours and a hex field. Changes show live on the selection;
Enter or a click outside keeps them as one edit (one undo step), Esc puts the old colour back.

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
`build.gradle`). `check` runs `selfCheck` (model, rules, geometry, pixel icons) and `syncSim` (two-client multiplayer
race simulation of the server logic). `./gradlew iconSheet` and `./gradlew panelPreview` render review PNGs of the
icons and the panel with the real vanilla font and widget sprites, read at run time from the Minecraft 1.21.1 client
jar in the Gradle cache (`-DmcClientJar=...` to point elsewhere; nothing from it is committed). Design notes:
[DESIGN.md](DESIGN.md).

## License

MIT. UI icons are pixel versions of icons from [Excalidraw](https://github.com/excalidraw/excalidraw) (MIT, v0.18.1) and
[Tabler Icons](https://github.com/tabler/tabler-icons) (MIT, v3.49.0); see
`src/main/resources/assets/mapsketch/icons/LICENSE-excalidraw.txt` and `LICENSE-tabler.txt`.
