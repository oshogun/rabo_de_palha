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
/rdp queue [workers]
/rdp clear [workers]
```

Input the commands would ignore is rejected with an error: `place` takes no block-entity NBT
(`chest{...}`), and `deposit`/`withdraw` take no item components (`dirt[...]`), since they match
by item only. In `deposit`, a word that parses as an item id is read as the item, so name workers
with a selector (`@e[type=rabodepalha:worker,name=...]`) or a UUID.

Tasks run one at a time in FIFO order; the queue is saved with the worker, and the current
task restarts from scratch after a reload.

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
