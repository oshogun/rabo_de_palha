# Rabo-de-palha

Rabo-de-palha is a Fabric mod for Minecraft 26.3: a colony-management game in the vein of
RimWorld and Dwarf Fortress, built on top of Minecraft's world. It will start with an NPC
worker bot that can mine and build. For now the repository is only the mod skeleton.

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
