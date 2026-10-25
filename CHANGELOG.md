# Changelog

## 1.0.0 — 2026-10-24

First release.

- Fix: arrows bound to a shape now follow it when it is moved, resized or rotated (the grab's lease request was
  emptied before it was sent), and on arrow-key nudges, in the same undo step; arrows bound at both ends to moved
  shapes aim centre to centre.
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
