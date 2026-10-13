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

Tasks run one at a time in FIFO order; the queue is saved with the worker, and the current
task restarts from scratch after a reload.

A worker with a current or queued task keeps its chunks loaded (`rabodepalha:worker` ticket, a
7x7 chunk area around it that follows it), so it keeps working when no player is nearby. The
ticket persists across restarts and goes away when the worker goes idle, dies or is removed. At
most 32 workers (1568 chunks) hold chunks at once; others wait until a slot frees up. `/rdp list`
shows `chunks loaded`, `chunks not loaded (cap reached)` or `chunks not loaded` for each worker.

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
