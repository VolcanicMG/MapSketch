# MapSketch design

Excalidraw-style vector drawing on Xaero's World Map, shown on the World Map and Minimap, edited by several players
at once in real time through the server. NeoForge 1.21.1 (21.1.262), Java 21. modid `mapsketch`, package
`com.volcanicmg.mapsketch`, MIT, author VolcanicMG. Build, mixin gating, optional channel, FTB Teams soft dependency,
config screens and the self-check follow MapHorizons (`~/repos/maphorizons`) and MapColonies
(`~/repos/colony-borders`); code is copied, not shared.

Not a raster paint tool: every drawing is a vector element that can be selected, moved, restyled and deleted.

## Packages

| Package | Minecraft? | What |
|---|---|---|
| `model` | no | `Element`, `ElementCodec` (one byte encoding for wire and disk), `Rules`, `Geometry`, `FracIndex` |
| `render` | no | `Tessellator` (element → triangles), `Rough` (hand-drawn wobble) |
| `sync` | no | `SyncServer` (the server state machine), `ElementStore`, `Live`, `Outbox`, `TokenBucket` |
| `net` | yes | payloads |
| `server` | yes | `SketchServer` adapter, `SketchData` (SavedData), `SketchCommands`, `FtbTeamsCompat` |
| `client`, `client.xaero`, `mixin` | yes, client only | editor, UI, renderer, Xaero glue |

Everything that decides who may do what is in the pure packages, so `SelfCheck` and the two-client `SyncSim` run
without Minecraft.

## Element model (`model/Element`)

| Field | Notes |
|---|---|
| `id` UUID, `dim` String | dimension id string |
| `type` | RECTANGLE, DIAMOND, ELLIPSE, ARROW, LINE, FREEDRAW, TEXT, STAMP |
| `x, z` double | origin in world block coordinates of the viewed map dimension |
| `w, h` double | box types: unrotated size. Linear types: derived from points |
| `pts` float[] | linear types: `x0,z0,x1,z1…` relative to `(x, z)` (floats keep payloads small and precise near the origin) |
| `angle` float | radians, around the bounds centre |
| `stroke`, `fill` ARGB, `fillStyle`, `strokeWidth` (blocks), `strokeStyle`, `roughness` 0-2, `round`, `opacity` | style |
| `arrowStart`, `arrowEnd` | NONE, ARROW, TRIANGLE, BAR, DOT (lines and arrows) |
| `text`, `fontSize` (blocks), `align` | TEXT elements; box types draw `text` centred inside, linear at the bounds centre |
| `stamp` | STAMP elements: `sym:<name>` (24 pixel-art symbols, `render/PixelArt` 16x16 grids in four tones from the stroke colour), `deco:<name>` (vanilla map icons, `Rules.DECORATIONS`) or `item:<namespace>:<path>` (the item's flat sprite); all drawn as nearest-filtered textured quads through the element transform; validated by syntax, symbol/icon set and, on the server, the item registry |
| `startBind`, `endBind`, `startFixed`, `endFixed` | arrow/line binding targets; a fixed point (normalized in the target's local frame) for node and inside bindings, null for an edge binding |
| `seed` | roughness RNG seed fixed at creation: every client draws the same wobble |
| `groups` UUID[] | innermost first; selecting one member selects the outermost group |
| `order` | fractional index, Excalidraw's scheme (rocicorp fractional-indexing port): base-62 integer part whose head char encodes its length, then a fraction never ending in `0`; appends/prepends stay ~3-5 chars; ties broken by id. Saves without the `format: 2` marker are re-keyed once on load, keeping their order |
| `version`, `nonce`, `baseNonce` | `version` is assigned by the server; `nonce` is a random token naming this exact state. On an edit, `version`/`baseNonce` name the state it was made from (0/0 = new) |
| `owner`, `visibility` (PRIVATE/TEAM/PUBLIC), `locked`, `deleted` (tombstone), `updated` | |

Presets: stroke thin/bold/extra = 1/2/4 and text S/M/L/XL = 8/12/18/28, in blocks per GUI pixel rounded to a power of
two at the zoom you draw at, so a shape drawn zoomed out stays visible at that zoom; after that everything is in
blocks and scales with zoom (drawn at least 1.5 screen px wide). Freehand is simplified with Ramer–Douglas–Peucker
(0.5 GUI px) on release.

## Rules (`model/Rules`)

- `basedOn` (server): an edit applies only to the exact stored state it was made from (same version and token);
  0/0 only to an id that does not exist.
- `replaces` (client): a server state replaces ours when its version is higher, or equal with another token while
  we have nothing pending for that element.
- `canSee`: owner, PUBLIC, or TEAM and same FTB team (allies when `shareWithAllies`). TEAM without FTB Teams = owner.
- `canEdit`: ops always; owner; others need `teamCanEdit` and `canSee`. Locked: owner/op only. Non-owners cannot
  change `visibility` or `locked`. "Members who can see it" read literally: with `teamCanEdit`, PUBLIC drawings are
  editable by everyone; `locked` protects one.
- `validate`: enums, finite numbers, |x|,|z| < 3e7, sizes, point count, text length, stroke ≥ 0.05 and font ≥ 0.5
  blocks, order format.
- `unchangedSince`: the undo guard (below).

## Collaboration protocol

Server authoritative. Optional channel `registrar("5").optional()` (bumped with every wire format change), every send guarded by `hasChannel`.

| Payload | Dir | Content |
|---|---|---|
| `ServerInfoPayload` | S→C | default visibility, teams available, teamCanEdit, op flag, limits |
| `ElementsPayload` | S→C | `seq`, `reset`, `force`, elements |
| `RemovePayload` | S→C | `seq`, ids no longer visible / deleted |
| `EditPayload` | C→S | `opId`, elements (deletes are tombstones) |
| `AckPayload` | S→C | `opId`, rejected ids, reason |
| `LeasePayload` | C→S | request id, ACQUIRE / RENEW / RELEASE, ids, optional ids (bound arrows); at most 512 in all |
| `LeaseReplyPayload` | S→C | granted ids / denied (`held:<name>`, `too many`, `rate limit`), or `requestId = -1` = revoked (expired, permission lost, dimension changed, died, taken over by the owner/an operator, released by an operator) |
| `LeasesPayload` | S→C | delta of what one holder is editing (added, removed; `reset` on join/resync), filtered to what you can see |
| `LivePayload` | both | transient live state (`sync/Live`) |
| `ResyncPayload` | C→S | "sequence gap, send everything" |

### Ops: optimistic apply, ack/reject

Versions are server-assigned. The client applies every edit locally at once with a fresh state token, keeping
the version and token of the state it was made from; every 50 ms queued elements go out as ops (`EditPayload` with
a client op id, split under the 32 KiB serverbound limit). After sending, the client predicts the stored state
(version + 1, its own token), so follow-up edits queued before the ack are based on it and apply in order. The
server checks each element: rate limit, `validate`, dimension, unknown id (a non-new edit to an id it does not
have is "deleted meanwhile", never a create), visibility, id freshness, lease, deleted-meanwhile, `canEdit`,
`basedOn`, the version ceiling (`Integer.MAX_VALUE` refuses further edits; reaching it takes ~2^31 accepted,
rate-limited edits), element limits. It stores base version + 1 with the edit's token and acks the assigned
versions. Rejected elements are first resynced to the sender with `force` once each (or removed), then the op is
acked with the rejected ids and the reason, which the client shows in an in-map toast. A forced resync ends any
local gesture on that element. Every broadcast sends removals to peers that knew an id and can no longer see it
(private, deleted, left the team).

### Edit leases (grab conflicts)

- Starting a grab (move, resize, rotate, point edit) or editing an existing element's text sends ACQUIRE for the ids,
  plus the arrows bound to them as optional ids; the gesture runs optimistically meanwhile. The server adds the other
  members of their outermost groups that the requester can see and edit (others are skipped, so nobody can block a
  group by planting a member in it) and grants all or none: denied when any id is held by someone else
  (`held:<name>`), deleted, or not editable by the requester. Optional ids are granted where free; bound arrows that
  were not granted stop following. First request wins; the server thread serialises them.
- Owners and operators are not blocked: their ACQUIRE or edit takes the lease over and the holder is told
  ("taken over by the owner / an operator") and rolls back.
- Caps: 512 leased ids per player, 20 lease requests per second, and a 60 s lifetime that renewals cannot extend
  (a gesture still running then re-acquires; an idle grab simply ends). A holder who lost a lease to a take-over, or
  to the lifetime cap while someone else was refused it, cannot re-acquire it for 3 s. Leases only expire in the
  server's expiry pass, so holders and viewers are always told. Every lease id told to a peer gets its removal,
  even after the peer lost sight of the element (no stale "held" outlines).
- After a take-over the old holder's in-flight ops are refused: held by the new holder, or made from a now stale
  state.
- Denied or revoked while the gesture runs: the client restores its drag-start snapshot (nothing was sent) and
  toasts "being edited by <name>". If the denial arrives after the gesture ended, the op is rejected by the
  server the same way.
- Held elements reject everyone else's ops, deletes included. The client also refuses to start a grab on something
  it already knows is held.
- RENEW every 2 s while the gesture runs and the map is open; expiry after 5 s idle; RELEASE after the final op is
  flushed at gesture end, or on right-click cancel. Released on disconnect, dimension change, death; revoked when the
  holder loses access (team change, the element went private or was deleted). `/mapsketch release <player>|all`
  force-releases.
- Others see held elements outlined in the holder's colour with their name. Leases are in memory only: a server
  restart starts with none.

### Live state

`Live` = cursor, visibility, ghosts, freehand `appendFrom`, laser trail. Sent at 20 Hz while drawing on an open map:
the shape being drawn (freehand streams only new points, with a full copy every second), copies of grabbed elements
and their bound arrows during a drag, the element whose text is being typed. The server relays it (at most 25/s and a
byte budget per player, inactive updates included): ghosts of stored elements only while the sender holds their lease
and only to players who can see them; every ghost, append chunks included, must pass `validate`, and laser points
must be finite. New-shape ghosts and the cursor follow the sender's visibility; when the cursor is withheld the relay
carries ghosts only, without dimension, position or laser. An inactive update is relayed as inactive, never with
ghosts.
Receivers draw ghosts in place of the stored element at 85 % with the sender's colour tag, and drop a player's live
state on "inactive", on disconnect / dimension change (server sends inactive), or after 3 s silence. Never persisted.

### Ordering and resync

Resync requests are served at most once per 2 s per player; extra requests coalesce into one pending resync sent from
the tick. Team events resync only the players whose view can change (old and new team members, the mover, allies),
debounced ~1 s. Rate-limited edits are rejected without resending the stored elements; they, and rejections whose
authoritative copies would exceed 64 KB, schedule that one throttled full resync instead.

Every `Elements`/`Remove` message carries a per-player sequence number (per player rather than per dimension because
each player receives a visibility-filtered subset, so a per-dimension counter would have gaps by design). The client
checks `seq == last + 1`; on a gap it sends `ResyncPayload` and the server replies with a full sync (`reset`). Login,
reconnect and team changes are full syncs too. Full sync covers all dimensions, because Xaero's map can view a
dimension the player is not in.

### Races and how they resolve

| Race | Resolution |
|---|---|
| Two players grab the same element | first ACQUIRE wins; the other rolls back with "being edited by <name>" |
| Multi-element grab overlapping someone's lease | all or nothing: denied, nothing leased |
| Delete vs someone holding it | rejected (`held:<name>`), element resynced |
| Edit of an element deleted meanwhile | rejected `deleted`, removed on the sender, toast; only whoever deleted may restore (their undo) |
| Unleased concurrent edits (panel properties on a multi-select) | first to arrive applies; the other was made from an older state, is rejected `stale` and resynced; the user retries |
| Undo/redo after someone else changed the element | `Rules.unchangedSince(token of our last write)`; skipped with a toast instead of clobbering. Our own tombstones stay in the client store so undoing a delete restores from them |
| Racing group/ungroup or z-order | per-element exact-state edits; equal fractional indices sort by id identically everywhere |
| Arrow bound to a shape another user deletes | the server unbinds the arrow (server-authored version bump) and sends it to all viewers, the deleter included; unless someone holds the arrow |
| Arrow bound to a shape another user moves | the mover's client moves bound arrows it may edit in the same op |
| Concurrent paste/duplicate | fresh random ids; a new (version 0) element whose id already exists is rejected (`id taken`) |
| Team/visibility change mid-edit | leases revoked, element removed for players who lost access |
| Simultaneous text edits on one element | the lease admits one editor |
| Reconnect mid-gesture | leases released on disconnect, full resync on join |
| Lost or reordered message | sequence gap → resync |
| Server restart | elements persist (SavedData), leases do not |
| Delete of an id that never existed | dropped (no tombstone). Tombstones are capped per deleter (their oldest beyond `maxElementsPerPlayer` are dropped), with a large global backstop |
| Edit to an id the server no longer has | rejected `deleted` (it cannot resurrect a pruned tombstone) |
| Client claiming any version | ignored: versions are assigned by the server; a wrong base is just `stale` |
| Someone holding elements with no gesture | owner/op take over; 512-id cap; 60 s lifetime; 3 s re-acquire cooldown when contested |

`SyncSim` (`./gradlew syncSim`, part of `check`) drives `SyncServer` with two fake clients that apply what they
receive in sequence order, and covers every row above except the client-only ones (undo guard and z-order tie are
covered by `SelfCheck` / the comparator).

## Client

- `ClientStore`: elements, z-sorted per dimension; outgoing queue and pending ops; sequence check; other players'
  leases and live state.
- `Editor`: draw mode, tools, selection, transforms, leases, live sending, text editing, binding, clipboard, undo/redo.
- `EditorUi`: toolbar (top centre, below Xaero's coordinates), properties panel (left, below Xaero's settings
  button), selection frames, handles, remote cursors, lease outlines, toasts. Rebuilt from state every frame.
- `Tessellator` (pure): thick lines = per-segment quads with mitred joins, round discs only at ends and sharp
  (over 60°) corners; dashes/dots by arc length;
  hachure/cross-hatch = parallel lines at -41°/49° clipped to the convex outline; solid fill = fan. `Rough`: seeded
  `java.util.Random` (spec'd algorithm, identical on all JVMs): bowed, overshooting strokes drawn twice for straight
  edges; low-frequency normal displacement with overshoot for curves.
- Mesh cache: two slots per element (world map and minimap draw at different widths in one frame), keyed by
  version + state token and stroke-width bucket. Live ghosts have their own cache keyed by a hash of every field
  the mesh depends on except the origin, so a dragged ghost is only translated.
- Tessellation caps: one budget of 2000 dashes/dots per element shared by all its polylines (the pattern
  stretches); polylines over 200 points wobble as one curve instead of per-segment strokes; generation stops at a
  hard 120k vertices per element.
- Opacity without double-blending at joins: each element gets its own depth (`-200 + i * step`) with `GL_LESS` after
  a depth clear, so overlapping triangles of one element blend once. Not on the minimap.
- Text: Minecraft font scaled to `fontSize / 9`; hidden under 3 screen px.

## Xaero hook points (World Map 1.47.0, Minimap 26.6.0)

| Hook | What | Fragility |
|---|---|---|
| `GuiMapAccessor` (`cameraX`, `cameraZ`, `scale`) | `world = (rawPixel - floor(winW/2)) / scale + camera` | private field names |
| `GuiMapMixin`, `@Inject` before `MapElementRenderHandler.render(...)` in `GuiMap.render` | draws above terrain/highlights, below waypoints and the player arrow; pose there = `scale(1/guiScale)`, `translate(winW/2, winH/2)`, `scale(scale)` | `require = 0`; if it never fires, `ScreenEvent.Render.Post` draws instead (over waypoints) |
| NeoForge `ScreenEvent` mouse/key/char `Pre` on `GuiMap` | input in draw mode; cancelling the press keeps Xaero's `leftMouseButton.isDown` false so it does not pan; clicks on Xaero's own buttons pass through; right-click and Xaero's menus are off while drawing | public API |
| `MapProcessor.getMapWorld().getCurrentDimension().getDimId()` | viewed dimension | public |
| `MinimapFboAccessor` (`minimapElementMapRendererHandler`) + handler `add` | one anchor element in the IN_MINIMAP pass, drawn into the rotation framebuffer, so Xaero's circle/square mask clips it | private field; re-registered when Xaero rebuilds the handler |
| `MinimapHandlerAccessor` (`ps`, `pc`, `zoom`, `halfWView`) | `X = (ps*dx - pc*dz)*zoom`, `Y = (pc*dx + ps*dz)*zoom` | private fields |
| `GuiWorldMapSettings` + `ScreenEvent.Init.Post` | "MapSketch…" button | public class |

The mixin plugin applies `Minimap*` mixins only with `xaerominimap`, the rest only with `xaeroworldmap`; the mixin
config is `required: false`. `neoforge.mods.toml` pins World Map `[1.47.0,1.48)` and Minimap `[26.6.0,26.7)`. Every
Xaero-facing callback (screen events, the in-map hook, minimap pre-render and render) catches `Throwable`, logs once
and disables that layer for the session. The safe-mode (non-FBO) minimap is not supported.

## Config and commands

Server: `defaultVisibility` (AUTO = TEAM with FTB Teams, else PUBLIC), `teamCanEdit` (true), `shareWithAllies`
(true), `maxPointsPerElement` (2000), `maxElementsPerPlayer` (2000), `maxElementsPerDimension` (20000),
`maxTextLength` (500), `editKBps` (256, also the live budget). Client: `enabled`, `showOnMinimap`,
`showOthersCursors`, `defaultStrokeColor`, `defaultFillColor`, `defaultStrokeWidth`, `defaultSloppiness`, `uiScale`.

Commands (op level 2): `/mapsketch clear player <p> | dimension <d> | all`, `/mapsketch release <player> | all`,
`/mapsketch stats`.

## Risks

- Mixin targets and accessors are Xaero internals; see the table for how each degrades.
- The depth trick assumes GUI z in [-200, 0] is inside the projection (true for vanilla and NeoForge).
- PUBLIC drawings are editable by anyone who sees them while `teamCanEdit` is on.
- Never run in a real client by the author: rendering, input and the Xaero hooks are compile- and design-checked
  only.
