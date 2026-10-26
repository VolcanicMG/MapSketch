# Changelog

## 1.0.0 — 2026-10-24

First release.

- Live sync on both maps: the world map and the minimap now draw from one shared scene (stored elements, other
  players' live ghosts replacing the shapes they are moving, our own shape in progress), so the minimap shows moves,
  new strokes and deletions as they happen. A ghost of a stored element is placed by the stored element's dimension,
  and a lease outline follows its holder's ghost. The server logs a once-a-minute summary of dropped live ghosts by
  reason, so a peer not seeing a drag can be diagnosed from the server log.
- Fix: arrows bound to a shape now follow it when it is moved, resized or rotated (the grab's lease request was
  emptied before it was sent), and on arrow-key nudges, in the same undo step; arrows bound at both ends to moved
  shapes aim centre to centre.
- Review round 4: item stamp ids cached only on hits and registry-checked as live ghosts too, air rejected;
  small/excess item stamps drawn as batched tiles (at most 200 item models per pass); stamp picker names cached and
  guarded; popovers only claim releases of presses they consumed; queued edits flushed before a colour preview;
  bound arrows follow only geometry changes with a zoom-independent gap, and are re-followed after a stale reject;
  exact encoded element size for batching and rate limits.
- Review fixes: the panel's scissor is always popped (a failure can no longer leave Xaero's map clipped); a middle-
  button pan always ends on release or when the map closes, even after the map layer was disabled by an error; a Space
  released while typing still ends Space-panning; a stamp whose item or icon fails to texture now draws as a tile on
  its own instead of turning every stamp into tiles; missing sprites are cached instead of looked up every frame;
  stamp sprites are re-resolved once a resource reload has fully finished.
- Controls guide: in draw mode a "Controls" panel docks top-right on the world map (below Xaero's widgets there)
  with key caps for the current tool or state (Select, each shape tool, Arrow/Line, Pen, Text, Stamp, Eraser, Laser,
  Hand; "Attaching the end" while an arrow end is placed near a shape, "Typing" while editing text) and the general
  controls. Its ? button collapses it; the choice is kept in the client config (`showHints`, on by default). An
  unbound "Show/hide the controls guide" keybind does the same. Hidden while a picker overlaps it; only the button
  takes clicks. `./gradlew hintsPreview` renders a mock.
- Stamps: redrawn X (symmetric 45-degree arms, rounded ends, clean crossing), map pin (round head with a hole,
  sharp centred point, shaded right side), crown (three ball-tipped peaks, band with three jewels) and home base
  (symmetric house with a centred door and a flag on the ridge, clearly unlike House). Vanilla map-icon stamps are
  cropped to the square around their pixels, so the 8x8 markers fill the stamp box like the symbols.
- Vanilla-styled UI: toolbar, properties panel, colour picker and stamp picker use inventory-style panels, the
  vanilla button / slider / scroller / text-field sprites and the Minecraft font (#404040 labels). Icons are 16x16
  pixel art at 1 texel = 1 GUI pixel: pixel versions of Excalidraw's icons (MIT; a few from Tabler Icons, MIT) with
  1 px lines. Buttons: vanilla sprite with dark ink, highlighted sprite on hover, a selected toggle is pressed in like
  an inventory slot with white ink, disabled is the greyed sprite. Stamp cells are inventory slots. Opacity is a
  vanilla slider ("Opacity: 100%"). The `uiScale` option is removed: everything is whole GUI pixels and follows
  Minecraft's GUI scale. `./gradlew iconSheet` / `panelPreview` render previews with the real vanilla assets.
- Properties panel layout (Excalidraw's structure): labelled sections, equal-width buttons per row, swatches plus the
  current-colour chip. "Visible to" is text (Only me / Team / Everyone); Team is disabled with a tooltip when the
  server has no FTB Teams. The panel sits below the toolbar and scrolls (vanilla scrollbar) with the mouse wheel when
  taller than the screen; the wheel over it never zooms the map. Text sizes show S / M / L / XL.
- Text background: text can have a padded background box (colour from the panel's Text background row, none or
  solid; round or sharp edges; follows opacity and rotation; included in selection and hit-testing). Default is no
  box.
- Typing in MapSketch inputs (in-map text, stamp search, colour picker / hex) never triggers Xaero's map keybinds or
  MapSketch tool shortcuts any more: while typing, every key press and release is MapSketch's. Ctrl+A/C/V/X work on
  the whole text.
- Item stamps keep their colours: flat item icons are drawn layer by layer with the game's item tints (spawn eggs,
  potions, leather armour, tipped arrows, firework stars, maps). The stamp and colour pickers are opaque, and stamp
  icons are no longer covered by the picker's own background.
- Stamps redone as pixel art: the symbols are 16x16 Minecraft-style sprites in four tones from the stroke colour
  (same ids, 8 new ones: anchor, arrow_up, campfire, crown, shield, coin, home_base, danger). New `deco:` stamps
  with the vanilla map icons. Every stamp, items included, is now a flat nearest-filtered textured quad on both
  maps (items use their sprite instead of the 3D item renderer, which drew squashed, blurry and oddly lit inside
  Xaero's minimap); tiny or over-budget stamps are tinted tiles. Picker: visible search field across all tabs with
  grouped results, `@modid` item filter, keyboard highlight, and Symbols / Map icons / Items tabs. Network channel 5.
- Stamps: a Stamp tool (`S`, `9`) placing item/block icons (any registry item, searchable, paged) or one of 16
  built-in vector symbols tinted with the stroke colour; recent stamps; resize/rotate/move/opacity like other shapes;
  on the World Map and Minimap (a tinted tile if item icons cannot render there). The server validates stamp ids
  (known symbols, items in its registry). Element format 4, network channel 4.
- Arrow binding as in current Excalidraw: link nodes (side midpoints, centre, plus corners on rectangles and
  diamonds) shown while dragging an end; drops on a node or well inside a shape bind to that fixed point (stored in
  the shape's local frame, so it follows moves, resizes and rotations); drops near the outline bind to the edge;
  `Ctrl` disables binding. Lines bind too. Network channel 3, element format 3 (older saves still load).
- Colour picker for stroke and background: saturation/value square, hue and opacity bars, old/new preview,
  palette, 8 recent colours, hex field. Live preview on the selection; kept as one edit and one undo step.
- Middle-mouse drag pans the world map with any tool (and outside draw mode). New shapes default to the
  "architect" sloppiness (no wobble).

- Vector drawing editor on Xaero's World Map in a draw mode (toolbar button or `G`): hand, select, rectangle,
  diamond, ellipse, arrow, line (click-to-add polyline), pen, text, eraser, laser pointer, with Excalidraw-style
  number/letter shortcuts.
- Properties: stroke and background colour (palette + hex), fill none/hachure/cross-hatch/solid, stroke width and
  style, sloppiness (deterministic hand-drawn wobble), sharp/round edges, opacity, arrowheads, text size and
  alignment, visibility (me / team / all), lock.
- Selection: click, shift-click, box select, move (drag, arrow keys), 8 resize handles (shift keeps the ratio),
  rotate handle, group/ungroup, z-order, duplicate, copy/cut/paste, delete, select all, undo/redo of your own edits,
  double-click to edit text, line/arrow point editing, arrows bound to shapes follow them.
- Drawings on Xaero's World Map (above terrain, below waypoints) and Minimap (rotated and clipped with it).
- Real-time collaboration through the server: live cursors, shapes in progress, drags, typing and lasers; edit
  leases so two players never grab the same drawing (first wins, the other is told who has it); optimistic edits
  with server ack/reject and rollback; per-player sequence numbers with resync on gaps.
- Server authoritative: per-element visibility (private / FTB Teams team / public), edit permissions, locks, size
  and rate limits, SavedData storage. Commands `/mapsketch clear`, `/mapsketch release`, `/mapsketch stats`.
- Optional network channel both ways; dedicated servers never load client or Xaero classes.
- Hardening from review (before release): tessellation caps (dashes, joins, 120k vertices per element; minimum
  stroke 0.05 blocks); Excalidraw-style fractional indices (old keys re-keyed on load); unknown-id deletes ignored
  and tombstones capped; throttled resyncs, lease requests and live updates (inactive included); version bounds with
  rebase; owners and ops take over leases, 512-id cap and 60 s lease lifetime; group expansion limited to members you
  can edit; bound arrows leased with the grab; resync instead of per-element resend on rate limits; lease deltas;
  team resyncs debounced and limited to affected players; Xaero version ranges pinned and every Xaero callback
  guarded.
- Second review pass: server-assigned versions (edits name the exact state they were made from; the server stores
  base + 1 and acks it; no client-chosen versions, no rebase); lease removals always reach every peer told about the
  lease; inactive live updates never dropped; partial lease revokes release the rest; try/finally around every pose
  push and buffer; tombstones capped per deleter, and non-new edits to unknown ids rejected; 3 s re-acquire cooldown
  after a contested lifetime expiry or a take-over; team resync at most 1 s after the first event; per-element dash
  budget, mitred joins and a separate translate-only ghost cache; saves carry a format marker and older ones are
  re-keyed once.
