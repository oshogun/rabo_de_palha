# Rabo-de-palha

Rabo-de-palha is a Fabric mod for Minecraft 26.3: a colony-management game in the vein of
RimWorld and Dwarf Fortress, built on top of Minecraft's world. The first milestone is an NPC
worker bot (`rabodepalha:worker`) that runs a queue of basic tasks: move, mine and place.

## Worker and `/rdp` debug commands

Workers have an 18-slot inventory, never despawn, and have no spawn egg. Operators (permission
level 2) drive them with `/rdp`. `[workers]` is an optional entity selector; it defaults to the
nearest worker within 64 blocks. Terminal task outcomes are reported in chat to the ops who
commanded that worker.

```
/rdp spawn [pos]
/rdp list
/rdp goto <pos> [workers]
/rdp mine <pos> [workers]
/rdp place <pos> <block> [workers]
/rdp give <item> [count] [workers]
/rdp deposit <pos> [item] [workers]
/rdp withdraw <pos> <item> [count] [workers]
/rdp build <origin> <blueprint> [rotation] [workers]
/rdp plant <from> <to> <seed> [workers]
/rdp queue [workers]
/rdp clear [workers]
```

Input the commands would ignore is rejected with an error: `place` takes no block-entity NBT
(`chest{...}`), and `deposit`/`withdraw` take no item components (`dirt[...]`), since they match
by item only. In `deposit`, a word that parses as an item id is read as the item, so name workers
with a selector (`@e[type=rabodepalha:worker,name=...]`) or a UUID.

`deposit` with no item puts the worker's whole inventory into the container except damageable
items (tools, weapons, armor), which the worker keeps; naming an item deposits it even if
damageable.

`build` places a vanilla structure template (`.nbt`, as saved by structure blocks) by id, with
`origin` as its corner, optionally turned by `rotation` (`none`, `clockwise_90`, `180`,
`counterclockwise_90`). It builds bottom-up, skips blocks that are already correct, and uses
items from the worker's inventory. If materials are short at the start, the task fails with
`MISSING_ITEM` listing what is needed. Air, structure voids and piston heads are not placed,
waterlogging is dropped, extended pistons are retracted, and a lone chest half becomes a single
chest. Blueprints are limited to 4096 entries.

`plant` farms the rectangle between `from` and `to`: each column's field block is its top block
within 3 blocks of the picked corners' heights. Fields are limited to 1024 columns and 16 blocks
of height difference between the corners. `seed` is wheat, beetroot, melon, pumpkin or torchflower
seeds, pitcher pods, carrots, potatoes or nether wart. Grass, dirt and dirt path with air above are
tilled (needs a hoe in the worker's inventory; each block costs 1 durability, and a broken hoe is
replaced by the next one); farmland is planted directly, and nether wart goes on soul sand. Plants
in the way are cleared, anything else is skipped and counted. The worker uses up one seed per
block. Running out of seeds or hoes ends the task with counts (`planted 40 of 64: out of wheat
seeds`). If part of the field is not near water the task still succeeds but warns that those crops
grow slowly. Workers never trample farmland, whatever `mobGriefing` says.

Tasks run one at a time in FIFO order; the queue is saved with the worker, and the current
task restarts from scratch after a reload.

Every walking task paths through closed wooden doors (any door that opens by hand) and fence
gates. The worker opens them as it arrives, with sound and an arm swing, and closes the ones it
opened once it is through. It leaves open anything it found open, and keeps a door open while
another worker within 2 blocks is about to use it. Doors it opened are closed once it steps
clear, even if its task was cancelled, and when it dies or is removed; a worker that unloads or
changes dimension leaves the doors it opened open. Iron doors stay impassable, and this does not
depend on `mobGriefing`.

A worker with a current or queued task keeps its chunks loaded (`rabodepalha:worker` ticket, a
7x7 chunk area around it that follows it), so it keeps working when no player is nearby. The
ticket persists across restarts and goes away when the worker goes idle, dies or is removed. At
most 32 workers (1568 chunks) hold chunks at once; others wait until a slot frees up. `/rdp list`
shows `chunks loaded`, `chunks not loaded (cap reached)` or `chunks not loaded` for each worker.

## RTS camera mode

Press `R` (rebindable, category "Rabo-de-palha RTS") to toggle a top-down camera. The server
decides: the request goes to the server, which answers with the new state. The player body is the
camera, so Minecraft keeps streaming chunks wherever you pan. Internally the player is put in
vanilla spectator mode; the entry position, rotation, game mode and abilities are saved and
restored on exit. Entering needs permission level 2; leaving is always allowed.

- Pan with `W A S D` or by moving the mouse to the screen edge. Speed grows with height and is
  capped, and the server skips its "moved too quickly" check for players in RTS mode only.
- Zoom (camera height) with the mouse wheel (`Shift`+wheel while setting a dig depth); rotate with `Q`/`E` or middle-drag. `E` does not
  open the inventory while in RTS mode. The cursor stays free, and the spectator hotbar is hidden.
- View level (underground and indoors): the camera is in *Surface* mode (follows the terrain) or
  *Level* mode, where a view level Y acts as the top of the world: the camera follows the highest
  solid block at or below it, and clicks and worker picks ignore everything above it. `Page Up` /
  `Page Down` step the level one block (hold to repeat), `End` switches Surface/Level, `Home` jumps
  the level to the selected worker's feet. Entering RTS mode below the surface starts in Level mode
  at the player's level. The HUD's first line shows the mode, the level and the depth below the
  surface at the screen centre. In Level mode everything above the level (blocks, fluids, block
  entities, entities and workers) is hidden client-side, and cut walls show as a dimmer cap than
  walkable floors; Surface mode and leaving RTS mode restore normal rendering. Works on vanilla and
  with Sodium 0.9.2 (optional; other Sodium versions are refused by `breaks`). All four keys are rebindable.
- Orders: with workers selected, right-click a block to send them there (move). A command bar at
  the bottom centre of the screen (it scales with the GUI scale) has buttons for Move, Mine, Place,
  Dig, Build, Stop, Spawn worker, Deposit and Withdraw; hovering a button shows its name and hotkey. The
  hotkeys are `M` (Move), `B` (Mine), `V` (Place), `C` (Dig), `G` (Stop), `N` (Spawn worker), `J`
  (Deposit), `K` (Withdraw) and `Y` (Build), all rebindable. Clicks on the bar
  never select or order anything behind it.
  - Move, Mine, Place, Spawn worker, Deposit and Withdraw arm a mode; the next left-click on a block
    carries it out. Pressing the same button or hotkey again disarms it.
  - Items come from the selected workers' inventories, not yours. Click an item in the worker panel
    to pick it (click it again to unpick); with several workers selected the panel shows their
    combined items and counts. The pick is highlighted and persists across selection changes.
    Place uses the picked block, and the HUD shows `Place: <block>`; a worker that does not carry
    it fails the task.
  - Deposit puts everything, or only the picked item if one is picked, into the clicked container.
    The HUD shows `Deposit: everything` or `Deposit: <item>`.
  - Withdraw: click a container to open a read-only view of its contents (a snapshot, not the
    vanilla menu). Click an item, set the count with `−`/`+` (`Shift` steps by 16) and confirm;
    `Esc` or Close closes the view. The count is per worker, so N selected workers take N × count
    in total (the view shows `N each`).
  - Dig (`C` or the Dig button) digs out an area. Left-drag on the terrain to mark the rectangle;
    its top is the higher of the two picked blocks. Release, then scroll the wheel down to dig
    deeper or up to make it shallower (at least 1 layer); `Shift`+wheel still zooms meanwhile.
    Left-click confirms (`Shift`-click appends and keeps Dig armed). Right-click or `Esc` cancels
    the area and keeps Dig armed; a second press disarms it. The area is drawn as a box with its
    size and volume (e.g. `12×8×3 = 288`); the box turns red when the area is invalid: over 4096
    blocks, beyond 128 blocks, outside the world height or over unloaded chunks.
  - Plant (Farm page of the command bar; no default key, bind `Plant` in the controls) tills and
    plants a field. Pick the seed in the worker panel first (the picker above), then left-drag the
    rectangle on the ground; there is no depth step. Release to plant (hold `Shift` when releasing to
    queue it after the current orders); right-click or `Esc` cancels. The overlay tints each ground block: amber to be tilled (or cleared), green to be
    planted, red skipped; the whole field turns red when over 1024 columns or 16 blocks of height
    difference. Same range and loaded-chunk limits as Dig.
  - Build (`Y` or the Build button) opens a blueprint picker (saved-in-world templates first, then
    pack ones; tick the toggle to also list vanilla structures). The picked blueprint stays armed.
    A box shows the footprint at the hovered block, using the same origin and rotation as
    `/rdp build`; the hovered block becomes the footprint's lowest corner after rotation. `U` cycles
    the rotation through 0/90/180/270 (rebindable). The box is red when too far (128 blocks) or over
    unloaded chunks. A materials panel lists what the blueprint needs against the best single
    selected worker's inventory and warns when workers lack items. Left-click orders the build;
    `Shift`-click appends. No ghost blocks yet, only the box.
  - `Shift`-click appends to each worker's queue and keeps the mode armed for the next target;
    otherwise the queue is replaced and the mode disarms. An invalid target keeps the mode armed
    and shows the reason. Right-click or `Esc` disarms the mode (`Esc` disarms before it clears
    the selection).
  - Stop clears the selected workers' queues and current task, like `/rdp clear`. It works on
    any loaded selected worker, with no distance limit; workers in unloaded chunks are left alone.
  - Spawn worker puts a worker commanded by you on top of the clicked block, if there is room for
    it there.
  - `Left Alt` and `Left Ctrl` are no longer order modifiers (`Left Ctrl` made the camera sprint).

  The server needs permission level 2 and rejects unknown or dead workers and targets too far away
  (128 blocks); the HUD shows the result in green or red for a few
  seconds.
- Hover: the block under the cursor is outlined with its hit face highlighted (vanilla's crosshair
  outline is hidden), and a worker under the cursor gets a yellow box, distinct from the selection
  glow. A hint line in the HUD says what a click would do: the armed mode, prefixed with its name
  ("Mine <block>", "Place <block>", "Spawn worker"), or the right-click move when nothing is armed.
  It is grey with the reason when the order would be refused (nothing selected, mining air, no
  block in hand, no room to spawn, more than 128 blocks away), and hidden while the cursor is over
  the command bar. Clicking an invalid target shows the same reason.
  The overlay uses `client/render/WorldOverlay`, which other features can reuse.
- Worker panel: a side panel at the top right of the HUD (up to 160 px wide, above the command
  bar). With one worker selected it shows its name, chunk status, current task, queued tasks in
  order (each with a Remove button), inventory and a Clear button (same as Stop). With several
  selected it lists each worker's current task; clicking a row narrows the selection to that
  worker. The client tells the server which selected workers it watches (at most 16), and the
  server sends snapshots (at most 16 queued tasks and the inventory) only while the player is in
  RTS mode. Clicks on the panel are consumed, and right-click or scroll over it do nothing.
- Logging out, dying, respawning or a server restart while in RTS mode returns the player to
  normal mode at the entry position. Using `/gamemode` also ends RTS mode.

## Prerequisites

- JDK 25 or newer.
- Gradle comes with the wrapper (`./gradlew`, or `gradlew.bat` on Windows). You do not need to install it.

## Commands

```sh
./gradlew build        # compile, run unit tests, produce build/libs/rabodepalha-<version>.jar
./gradlew runClient    # launch the Minecraft client with the mod
./gradlew runServer    # launch a dedicated server with the mod
./gradlew test         # JUnit 5 unit tests (src/test)
./gradlew runGametest  # headless Fabric GameTests (src/gametest)
```

## IDE

- **IntelliJ IDEA**: install the Minecraft Development plugin and open the project folder.
  IDEA imports the Gradle project directly, and Loom generates the run configurations.
- **VS Code**: install the Extension Pack for Java, then run `./gradlew vscode` to generate
  launch configurations.
