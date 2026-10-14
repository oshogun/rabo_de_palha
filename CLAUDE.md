<!-- carcara:begin -->
## carcara agentic SDLC (profile: quality)

This project uses the carcara SDLC framework. Work is split across
specialised subagents in `.claude/agents/`, each pinned to the cheapest model
that does its job well:

| Agent | Model | Role |
|---|---|---|
| explorer | sonnet | read-only code search, file:line facts |
| architect | opus | plans for non-trivial changes |
| implementer | opus | writes code and tests |
| test-runner | haiku | runs build/lint/tests, summarises |
| reviewer | opus | high-signal diff review |
| doc-writer | sonnet | updates affected docs |

Commands: `/sdlc <task>` (full triaged pipeline), `/sdlc-plan`, `/sdlc-build`,
`/sdlc-test`, `/sdlc-review`. `carcara run "<task>"` runs this pipeline
headlessly from the terminal.

### Token discipline
- Code changes in this repo are routed to the carcara orchestrator: use the
  `carcara` skill (it runs `carcara run`); don't edit project files directly.
  `/sdlc*` remain available as a manual path.
- Delegate searching to `explorer` and command output to `test-runner`
  instead of reading files or logs in the main conversation.
- Give subagents only the context they need (task, plan, file paths); they
  reply with compact summaries — do not ask them for full files or logs.
- Do not re-read files a subagent already summarised unless you must edit them.
- Reserve extended thinking ("think hard"/"ultrathink") for architect-level
  design questions, not routine edits.
- Cap fix loops at 2 iterations, then ask the user.
- Run `/compact` between large phases of long sessions.
<!-- carcara:end -->

## Project

- Stack: Fabric (Loader 0.19.5, Fabric API 0.162.0+26.3, Loom 1.18), Minecraft 26.3
  with Mojang names (no Yarn), Java 25, Gradle 9.7.1 via the wrapper.
- Mod id `rabodepalha`; base package `io.github.oshogun.rabodepalha`.
- Source sets: `src/main` (common), `src/client` (client-only), `src/test`
  (JUnit 5 via fabric-loader-junit), `src/gametest` (Fabric GameTest test mod
  `rabodepalha-test`, `fabric-gametest` entrypoint).
- Verify changes with:
  - `./gradlew build` (compiles, runs unit tests, jar in `build/libs/`)
  - `./gradlew test` (JUnit unit tests)
  - `./gradlew runGametest` (headless server GameTests; exits non-zero on failure)
- Never launch `runClient` from an agent: it opens a game window.
- Readable sources (gitignored, under `build/mc-sources/`). To regenerate them:
  - Run `./gradlew genSources`.
  - Unzip `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-common-*/26.3/*-sources.jar`
    into `build/mc-sources/common`. Unzip the matching `minecraft-clientOnly-*` sources jar
    into `build/mc-sources/client`.
  - Download the Fabric API module sources jars (fabric-api 0.162.0+26.3) from
    `maven.fabricmc.net` and unzip each into `build/mc-sources/fabric-api/<module>`,
    e.g. `fabric-command-api-v2`.
  - Download the Sodium sources from
    `https://codeload.github.com/CaffeineMC/sodium/zip/refs/tags/mc26.3-0.9.2` and unzip
    them into `build/sodium-sources/`, giving `build/sodium-sources/sodium-mc26.3-0.9.2/`.
    The tree is read-only: never build or run it. Agents must grep it for Sodium code and
    must not rely on memory of older Sodium versions.
- Agents must grep `build/mc-sources` for vanilla and Fabric API code (Mojang names)
  and must not rely on memory of older Minecraft/Fabric versions.
