# Cutaway rendering: seeing underground and into buildings

Research for issue #26, part of the **camera** milestone. This doc picks how the RTS camera stops
drawing the blocks between the camera and what the player wants to see, before #27 (cut everything
above a level) and #29 (see into buildings) are built.

Everything below was read from the sources, not recalled from older versions:

- **Vanilla and Fabric:** Minecraft 26.3 (Mojang names) and Fabric API 0.162.0+26.3, under
  `build/mc-sources/`. The paths below drop that prefix and start at `client/`, `common/` or
  `fabric-api/`.
- **Sodium:** **Sodium 0.9.2 for MC 26.3**, under `build/sodium-sources/sodium-mc26.3-0.9.2/`.
  - Git tag `mc26.3-0.9.2` of CaffeineMC/sodium, commit `03198cb36ab831c99694403d18e4cf4a1726df72`.
  - Modrinth release `sodium-fabric-0.9.2+mc26.3`, version_number `mc26.3-0.9.2-fabric`, published
    2026-09-15.
  - A newer alpha, `mc26.3-0.9.3-alpha.1`, exists. This doc designs against 0.9.2 and lists the
    alpha only as a risk.
- **Path prefixes** used for Sodium below:
  - `sod/` = `build/sodium-sources/sodium-mc26.3-0.9.2/common/src/main/java/net/caffeinemc/mods/sodium/`
  - `sod-api/` = `build/sodium-sources/sodium-mc26.3-0.9.2/common/src/api/java/net/caffeinemc/mods/sodium/api/`
  - `sod-sh/` = `build/sodium-sources/sodium-mc26.3-0.9.2/common/src/main/resources/assets/sodium/shaders/`

`path:N` means line N of that file.

## 1. Background: how terrain gets on screen

### Vanilla 26.3

1. **Extract (main thread).** `LevelExtractor.extract` (`client/net/minecraft/client/renderer/extract/LevelExtractor.java:108`)
   builds the frame's `LevelRenderState`.
   - The visible sections come from `applyFrustum` (`:472-479`). It clears
     `LevelRenderer.visibleSections` (`client/.../renderer/LevelRenderer.java:135`) and refills it
     from `SectionOcclusionGraph.addSectionsInFrustum`. It runs only when
     `SectionOcclusionGraph.consumeFrustumUpdate()` is true or the camera rotated (`:143-145`).
   - For each visible section that is dirty, it copies the neighbourhood into a
     `RenderSectionRegion` with `cache.createRegion(...)` (`LevelExtractor.java:167-176`).
   - It then extracts entities (`:181`), block entities (`:183`) and particles (`:209`).
2. **Compile.**
   - **Scheduling (main thread).** `LevelRenderer.compileSections` (`LevelRenderer.java:937-963`)
     runs each update, either with `compileSync` (`:956`) or with `compileAsync` (`:959`).
     - The update is synchronous when it is player-caused (`playerChanged`).
     - Under the `NEARBY` option it is also synchronous when the section is within
       `distSqr < 768` (`:946-950`).
     - The async work runs on `Util.backgroundExecutor()` (`:1154`).
   - **Meshing (worker thread).** `SectionCompiler.compile` (`client/.../renderer/chunk/SectionCompiler.java:53`)
     walks the 16³ blocks (`:74-95`).
     - Every block read goes through `RenderSectionRegion.getBlockState` / `getFluidState` /
       `getBlockEntity` (`client/.../renderer/chunk/RenderSectionRegion.java:36/41/56`).
     - It skips air (`:76`) and marks opaque cells in a `VisGraph` (`:79`).
     - It collects block entities (`:83-85`, `:146-147`).
     - It tesselates fluids (`:91`) and models (`:95`).
     - The VisGraph resolves into the section's `visibilitySet` (`:129`).
   - **Face culling.** It happens in `ModelBlockRenderer` against the same region, so a
     neighbour read as air exposes the face.
   - **Light.** It also comes through the region. `RenderSectionRegion.getLightEngine()` returns
     the *live* level light engine (`:33`, `:52`). The light reads go through
     `BlockModelLighter.getLightCoords` (`client/.../renderer/block/BlockModelLighter.java:32`),
     which calls `LightCoordsUtil.getLightCoords(BrightnessGetter, level, state, pos)`
     (`common/net/minecraft/util/LightCoordsUtil.java:109`, called from `BlockModelLighter.java:677`).
     `FluidRenderer.java:428` uses the `:105` overload.
3. **Upload and swap.** The compiled mesh replaces the old one atomically when its upload
   finishes, and `SectionOcclusionGraph.schedulePropagationFrom` re-runs the graph from that section
   (`SectionOcclusionGraph.java:134`, passed in at `LevelRenderer.java:1154`).
4. **Occlusion culling.**
   - `SectionOcclusionGraph.runUpdates` (`client/.../renderer/SectionOcclusionGraph.java:256-314`)
     flood-fills from the camera section.
   - With `smartCull` it only crosses a section if
     `sectionMesh.facesCanSeeEachother(in, out)` (`:291-297`).
   - `invalidate()` (`:93`) forces a full rebuild of the graph. A change of `smartCull` also
     invalidates it (`:102`).
5. **Draw.**
   - `LevelRenderer.extractSectionDrawGroups` (`:702-729`) emits one `DynamicGpuData.ChunkSectionInfo`
     (`client/.../renderer/DynamicGpuData.java:121`: offset plus a fade `visibility` float) for
     each visible section.
   - These are uploaded at `LevelRenderer.java:826`. The pipelines are `TERRAIN_SNIPPET` and
     `MULTIDRAW_TERRAIN_SNIPPET` (`client/.../renderer/RenderPipelines.java:100-117`), with
     shaders `core/terrain`.
   - The per-pass uniform is `DynamicGpuData.TerrainTransform` (`DynamicGpuData.java:135`:
     modelView plus atlas size).
   - The `core/terrain.vsh/.fsh` text is **not** in `build/mc-sources`: the sources jars carry no
     shader assets. Everything below about where a vanilla discard would go is inferred from the
     pipeline definitions, not read from GLSL.
6. **Entities, block entities and particles.**
   - **Entities.** `LevelExtractor.isEntityVisible` (`:280-292`) calls
     `EntityRenderDispatcher.shouldRender`, which reaches `EntityRenderer.shouldRender`
     (`client/.../renderer/entity/EntityRenderer.java:62`), and then
     `LevelRenderer.isSectionCompiledAndVisible` (`:1246`).
   - **Block entities.** `extractVisibleBlockEntities` (`LevelExtractor.java:298-342`) walks
     `visibleSections`, plus `level.getGloballyRenderedBlockEntities()` (`:330`).
   - **Particles.** They are extracted with `new Frustum(cullFrustum).offset(-3)` (`:209`), and
     quad particles cull with `frustum.pointInFrustum` (`client/.../particle/QuadParticleGroup.java:23`;
     `Frustum.pointInFrustum` at `client/.../renderer/culling/Frustum.java:103`; `Frustum` is not final, `:12`).
   - **Fabric.** `LevelExtractionEvents.END_EXTRACTION` (`fabric-api/fabric-rendering-v1/net/fabricmc/fabric/api/client/rendering/v1/level/LevelExtractionEvents.java:70`)
     fires at the `RETURN` of `LevelExtractor.extract` (`fabric-api/fabric-rendering-v1/net/fabricmc/fabric/mixin/client/rendering/LevelExtractorMixin.java:69-71`).
     The callback's context exposes `levelState()` (`.../v1/level/AbstractLevelRenderContext.java:49`).
     That state's `entityRenderStates` and `blockEntityRenderStates` are plain lists
     (`client/.../renderer/state/level/LevelRenderState.java:14-15`). Each entry carries a position:
     `EntityRenderState.x/y/z` (`client/.../renderer/entity/state/EntityRenderState.java:17-19`) and
     `BlockEntityRenderState.blockPos` (`client/.../renderer/blockentity/state/BlockEntityRenderState.java:16`).

### Sodium 0.9.2

Sodium replaces the section renderer. Vanilla `SectionCompiler`, `RenderSectionRegion`,
`SectionOcclusionGraph` and `visibleSections` are bypassed.

1. **Hooks into vanilla.** `sod/mixin/core/render/world/LevelExtractorMixin.java`:
   - injects at `extract` before `consumeFrustumUpdate()` and calls `SodiumWorldRenderer.setupTerrain`
     (`:137-153`). Occlusion culling follows `levelRenderState.cameraRenderState.smartCull` (`:146`);
   - **redirects `applyFrustum` to a no-op** (`:155-162`);
   - **cancels `extractVisibleBlockEntities`** and calls `SodiumWorldRenderer.extractBlockEntities`
     instead (`:57-66`);
   - **overwrites** `setBlocksDirty`, `setSectionDirtyWithNeighbors`, `setBlockDirty` and
     `setSectionDirty(x,y,z,important)` so they forward to `SodiumWorldRenderer.scheduleRebuildFor*`
     (`:92-135`).

   `sod/mixin/core/render/world/LevelRendererMixin.java` wraps `prepareChunkRenders` (`:137`),
   overwrites `isSectionCompiledAndVisible` (`:157`) and cancels `invalidateCompiledGeometry` (`:162`).
2. **Snapshot (main thread).** `LevelSlice.prepare` (`sod/client/world/LevelSlice.java:110`)
   clones the section and a 2-block margin (`NEIGHBOR_BLOCK_RADIUS = 2`, `:55`) into
   `ClonedChunkSection`s (`sod/client/world/cloned/ClonedChunkSection.java:46`).
   - Light arrays are copied too (`ClonedChunkSection.getLightArray`, `:254`), unlike vanilla's
     live light engine.
   - Sections that hold only air never get a task (`LevelSlice.java:117`).
3. **Meshing (worker thread).**
   - `ChunkBuilderMeshingTask.execute` (`sod/client/render/chunk/compile/tasks/ChunkBuilderMeshingTask.java:72`)
     calls `cache.init`, so `LevelSlice.copyData` (`LevelSlice.java:175`) unpacks the snapshot
     into flat arrays.
   - It then loops y/z/x calling `slice.getBlockState(x, y, z)` (`:116`) and skips air with no
     block entity (`:118`).
   - Blocks go to `BlockRenderer.renderModel` (`:131`) and fluids to the `FluidRenderer` (`:137`).
   - Block entities are filtered through `ExtendedBlockEntityType.shouldRender` (`:143`) and
     collected (`:147`).
   - `DirectionalVisGraph.setOpaque` (`:153`) builds the occlusion data (`:246`).
4. **Every read in Sodium's meshing goes through `LevelSlice`.**
   - **Blocks.** `getBlockState(BlockPos)` (`:253`) delegates to `getBlockState(int,int,int)`
     (`:257`). `getFluidState` (`:271`) is derived from the block state. `getBlockEntity(BlockPos)`
     (`:328`) delegates to `getBlockEntity(int,int,int)` (`:332`).
   - **Face culling.** It is `AbstractBlockRenderContext.shouldDrawSide` (`sod/client/render/model/AbstractBlockRenderContext.java:104`),
     which reads `this.level.getBlockState(neighborPos)` (`:109`) through `ShapeComparisonCache`
     (`sod/client/render/chunk/compile/pipeline/ShapeComparisonCache.java:9`). `BlockRenderer`
     (`sod/client/render/chunk/compile/pipeline/BlockRenderer.java:37`) holds the slice (`:65`).
     There is no separate `BlockOcclusionCache` class in 0.9.2.
   - **Fluids.** `DefaultFluidRenderer` (`sod/client/render/chunk/compile/pipeline/DefaultFluidRenderer.java:56`)
     reads neighbours through the slice (`:108`, `:176`, `:251`, `:340-345`).
   - **Light.** `LightDataAccess.compute` (`sod/client/model/light/data/LightDataAccess.java:61-94`)
     reads `level.getBlockState(pos)` (`:65`). For non-emissive blocks it calls the **same vanilla**
     `LightCoordsUtil.getLightCoords(BrightnessGetter, level, state, pos)` (`:85`); for emissive ones
     it calls `level.getBrightness` (`:82-83`, which is `LevelSlice.getBrightness` at `:283`).
     `ArrayLightDataCache` wraps the slice (`sod/client/render/chunk/compile/pipeline/BlockRenderCache.java:24`).
     The pipelines are `LightPipeline`, `SmoothLightPipeline` and `FlatLightPipeline` under
     `sod/client/model/light/`.
5. **Graph and culling.** `RenderSectionManager` is at `sod/client/render/chunk/RenderSectionManager.java`.
   - `scheduleRebuild(x, y, z, playerChanged)` (`:984-1003`) marks a section `REBUILD`, adding
     `IMPORTANT` only if it was player-caused and nearby (`:995-996`).
   - `markGraphDirty()` (`:339`) is public, and `SodiumWorldRenderer.scheduleTerrainUpdate()`
     (`sod/client/render/SodiumWorldRenderer.java:163-166`) wraps it. Finished builds mark the
     graph dirty on their own (`:489-491`).
   - Culling runs on an async thread (`:117`). `OcclusionCuller`
     (`sod/client/render/chunk/occlusion/OcclusionCuller.java`) reads `section.getVisibilityData()`
     only if `useOcclusionCulling` is set (`:299-300`). The visibility data is packed by
     `VisibilityEncoding` (`sod/client/render/chunk/occlusion/VisibilityEncoding.java:8`).
   - The render list is built in `readRenderListFromTree` (`:290-316`), which traverses with a
     `VisibleChunkCollector`. `VisibleChunkCollector.visit(x, y, z)` (`sod/client/render/chunk/lists/VisibleChunkCollector.java:25-48`)
     is the single place a section enters a render list.
6. **Terrain shaders.** These are `sod-sh/blocks/block_layer_opaque.vsh` and `.fsh`, with includes
   `sod-sh/include/{globals,fog,chunk_material,chunk_vertex}.glsl`.
   - Sodium 0.9.2 has **no `ChunkShaderInterface` or `ChunkShaderOptions`**. Programs are vanilla
     `RenderPipeline`s built in `ShaderChunkRenderer.createShader` (`sod/client/render/chunk/ShaderChunkRenderer.java:52`, builder at `:70-100`).
   - Uniforms are push constants (`DefaultChunkRenderer.PUSH_CONSTANT_RANGE = 20`, `sod/client/render/chunk/DefaultChunkRenderer.java:38`;
     declared at `block_layer_opaque.vsh:19-29`). Defines are set with `withShaderDefine`, e.g.
     `ALPHA_CUTOUT` (`ShaderChunkRenderer.java:93-95`).
   - The fragment shader's only discard is the alpha cutout (`block_layer_opaque.fsh:102-106`),
     and it receives no world position (`:8-11`).
7. **Entities and block entities.**
   - **Entities.** `sod/mixin/features/render/entity/cull/EntityRendererMixin.java:18-27`
     `@WrapMethod`s `EntityRenderer.shouldRender`. It returns
     `renderer.isEntityVisible(...) && original.call(...)`. `SodiumWorldRenderer.isEntityVisible`
     (`SodiumWorldRenderer.java:452-472`) then tests `isBoxVisible` (`:474-482`), which goes to
     `RenderSectionManager.isBoxVisible` (`:477`) against the culled tree.
   - **So Sodium does *not* replace `LevelExtractor.isEntityVisible`.** That vanilla method still
     runs and reaches Sodium only through the wrapped `EntityRenderer.shouldRender`.
   - **Block entities.** Sodium extracts them from its render lists
     (`SodiumWorldRenderer.extractBlockEntities`, `:338-380`). The vanilla
     `extractVisibleBlockEntities` never runs, so a hook there does nothing under Sodium.
   - **Particles.** Sodium leaves `ParticleEngine.extract` alone. Its particle mixins target
     `QuadParticleFeatureRenderer.executeGroup` (`sod/mixin/core/render/world/ParticleFeatureRendererMixin.java:18-31`)
     and `QuadParticleRenderState.renderRotatedQuad` (`sod/mixin/features/render/particle/QuadParticleRenderStateMixin.java:18-30`).
   - **Camera and frustum.** Sodium's `CameraMixin` only touches `createProjectionMatrixForCulling`
     (`sod/mixin/core/render/frustum/CameraMixin.java:19`), so `Camera.extractRenderState`, where
     `smartCull` is set, is untouched.
8. **Supported Sodium API.** `sod-api/blockentity/BlockEntityRenderHandler.addRenderPredicate`
   (`@ApiStatus.Experimental`) adds a per-type `BlockEntityRenderPredicate`
   (`sod-api/blockentity/BlockEntityRenderPredicate.java`). It is evaluated at mesh time
   (`ChunkBuilderMeshingTask.java:143`). The `sod-api` package has no hook for block-state
   overrides or render-list filtering, so everything else below targets Sodium internals.

## 2. The approaches

- **A: Re-meshing.** Hidden positions read as air during meshing. Rebuild the affected sections
  whenever the region changes.
- **B: Shader clip.** Discard fragments inside the region. Meshes are untouched.
- **C: Hybrid.** Use A for every section the region boundary cuts through. Sections entirely
  inside the region are dropped from the render list at once, with no rebuild; their real mesh
  stays cached.

| Criterion | A: Re-meshing | B: Shader clip | C: Hybrid (A plus whole-section hide) |
|---|---|---|---|
| **How (vanilla)** | Mixin `RenderSectionRegion.getBlockState/getFluidState/getBlockEntity` (`:36/41/56`) to return AIR, empty fluid or null inside the region. | Add a uniform: extend `TerrainTransform` (`DynamicGpuData.java:135`, which changes the std140 layout) or add a bind group to `TERRAIN_SNIPPET`/`MULTIDRAW_TERRAIN_SNIPPET` (`RenderPipelines.java:100-117`). Override `core/terrain.vsh/.fsh` to pass a world position and discard. | A, plus remove fully hidden sections from `visibleSections()` at the `TAIL` of `LevelExtractor.applyFrustum` (`:472-479`). |
| **How (Sodium)** | Mixin `LevelSlice.getBlockState(int,int,int)` (`:257`) and `getBlockEntity(int,int,int)` (`:332`); the `BlockPos` overloads and `getFluidState` delegate to these. | `ChunkShaderInterface` is gone in 0.9.2. You would have to grow the push-constant block (`DefaultChunkRenderer.java:38`, `block_layer_opaque.vsh:19-29`) or add a bind group in `ShaderChunkRenderer.createShader` (`:70-100`), then edit both `sod-sh/blocks/block_layer_opaque.vsh` and `.fsh` (a discard next to `:102-106`). | A, plus cancel `VisibleChunkCollector.visit` (`:25`) for fully hidden sections. |
| **General region (#29 bubble, near walls)** | Any `contains(x,y,z)` predicate works; the shape costs nothing extra. | A plane or a sphere is a few ALU ops. Arbitrary shapes, such as the near-side walls of one building, need region data on the GPU (an SSBO or 3D texture). | Same as A. Whole-section hide applies only where a section lies entirely inside the shape. |
| **Clean cross-section** | Yes. Faces at the cut are meshed, because their neighbour reads as air. | No. Faces between solid blocks were culled at mesh time (`AbstractBlockRenderContext.shouldDrawSide:104`, vanilla `ModelBlockRenderer`), so you see down into caves through the missing caps. Caps would need their own mesh, which is re-meshing again. | Yes, as in A. |
| **Entities** | Filter at `END_EXTRACTION` by feet block: drop `entityRenderStates` whose `(x,y,z)` is in the region. This works on both renderers, because Sodium never cancels `extract`. | Same (the shader does not touch entities). | Same. |
| **Block entities** | Same `END_EXTRACTION` filter on `blockEntityRenderStates[*].blockPos`. It also catches globally rendered BEs (`LevelExtractor.java:330`) and Sodium's own extraction. Hidden blocks read as air never collect a BE at mesh time anyway. A vanilla hook on `extractVisibleBlockEntities` would be cancelled under Sodium (`LevelExtractorMixin:57`). | Same `END_EXTRACTION` filter. | Same. Whole-section hide also drops the BEs of fully hidden sections on both renderers. |
| **Particles** | `@WrapOperation` the `new Frustum(...)` passed to `ParticleEngine.extract` (`LevelExtractor.java:209`) with a `Frustum` subclass whose `pointInFrustum` (`Frustum.java:103`) also rejects the region. Sodium does not touch this call. | Same. | Same. |
| **Fluids** | They follow the block state. Water under a hidden water block gets a top surface at non-source height (`DefaultFluidRenderer.java:251`). | Fluid faces at the cut are missing, like everything else. | As A. |
| **Occlusion culling (hidden terrain occluding caves)** | Re-meshed sections lose their opaque cells (`SectionCompiler.java:79`, `ChunkBuilderMeshingTask.java:153`), so the graph opens up once the rebuild lands. Fully hidden sections keep stale opaque data unless they too are rebuilt. | The graph still treats hidden stone as opaque, so caves behind it get culled. Occlusion culling must be turned off. | Fully hidden sections keep their real visibility data, so occlusion culling must be off while a cut is active. This takes one vanilla hook that both renderers honour, setting `cameraState.smartCull = false` in `Camera.extractRenderState` (`client/net/minecraft/client/Camera.java:124-127`): <ul><li>vanilla invalidates its graph on the change (`SectionOcclusionGraph.java:102`);</li><li>Sodium reads the same field (`LevelExtractorMixin:146`).</li></ul> Vanilla already does this for a spectator inside a wall. |
| **Light floor (dark cut faces)** | A cut face samples light from the hidden cell above it, which is really stone, so the light is 0. Mixin `LightCoordsUtil.getLightCoords(BrightnessGetter, …)` (`:109`) to return at least a floor (e.g. block light 12) when `pos` is in the region and `level` is a cutaway view. This one hook serves vanilla (`BlockModelLighter.java:677`, `FluidRenderer.java:428`) and Sodium (`LightDataAccess.java:85`). On Sodium, also floor `LevelSlice.getBrightness` (`:283`) for the emissive path (`LightDataAccess.java:82-83`). | Not applicable: there is no cap to light. | As A. |
| **Cost of moving the cut** | Asynchronous rebuild of every touched section (see §4). There is no per-frame cost when idle. | Free: one uniform write per frame. | Rebuild of the boundary layer only. Fully hidden sections cost nothing. |
| **Frame time** | Unchanged, or lower (less geometry). | One extra varying and a compare per terrain fragment. | Same as A, and lower when large volumes are fully hidden. |
| **Vanilla mixin targets** | `RenderSectionRegion` (3 methods), `LightCoordsUtil`, `Camera`, `LevelExtractor` (particle frustum), plus the Fabric event. | Shader resource overrides, `DynamicGpuData`/`RenderPipelines` or the bind-group plumbing, `LevelRenderer` per-pass uniform writes. | A's targets plus `LevelExtractor.applyFrustum`. |
| **Sodium mixin targets** | `LevelSlice` (2 methods, plus `getBrightness`). | `ShaderChunkRenderer`, `DefaultChunkRenderer`, and overrides of both shader files. | A's targets plus `VisibleChunkCollector.visit`. |
| **Iris (later)** | Survives: it only changes geometry, and Iris uses Sodium's meshing. | Breaks: shader packs replace the terrain programs, so the discard disappears. | Survives. |

## 3. Recommendation: C

Use **re-meshing as the source of truth**, plus an **instant whole-section hide** for sections
entirely inside the region. Make no shader edits.

### Common code (pure, unit-tested)

- **A `CutawayRegion` predicate.** It has `contains(x, y, z)` and `classify(sectionX, sectionY, sectionZ)`
  → `OUTSIDE`, `BOUNDARY` or `INSIDE`. `BOUNDARY` means the section, inflated by one block,
  intersects the region without lying fully inside it.
  - For #27 it is `y > viewLevel`. That matches `RtsLevelClip`, which skips every block above the
    view level, so the feet block at the level stays drawn.
  - It lives in `src/main` next to `RtsViewLevel`, with no Minecraft types.
- **Publication.** The predicate is published as an immutable snapshot through one `volatile`
  field. Worker threads (meshing) and Sodium's cull thread read it.
- **Applying a change** (main thread):
  1. Publish the new snapshot.
  2. Call the public vanilla `LevelExtractor.setSectionDirty(x, y, z)` (`LevelExtractor.java:565`)
     for each section whose classification or boundary changed.
     - This works unchanged on both renderers. On vanilla it goes to the update tracker with
       `playerChanged = false`, which means async unless the `NEARBY` option and `distSqr < 768`
       apply (`LevelRenderer.java:946-950`).
     - On Sodium the `@Overwrite` routes it to `scheduleRebuild(..., false)`, which is `REBUILD`
       and never `IMPORTANT` (`RenderSectionManager.java:984-1003`).
     - Never route through `setBlockDirty(pos, true)` or any player-changed path: both lead to
       vanilla's `compileSync` (`LevelRenderer.java:956`) or Sodium's `IMPORTANT` lane.
  3. If the set of fully hidden sections changed, invalidate the graph:
     `SectionOcclusionGraph.invalidate()` (`:93`) on vanilla, or
     `SodiumWorldRenderer.instanceNullable().scheduleTerrainUpdate()` (`:163`) on Sodium.
     Finished rebuilds re-propagate on their own on both renderers (`SectionOcclusionGraph.java:134`,
     `RenderSectionManager.java:489-491`).
- **Mesh-time override.** It applies only when the section *being built* is `BOUNDARY`. The
  region/slice captures that flag when it is created:
  - vanilla: the `RenderSectionRegion` constructor, on the main thread;
  - Sodium: `LevelSlice.copyData` (`:175`).

  A section that is fully `INSIDE` is meshed normally and simply not drawn. Its real mesh is then
  ready the moment the cut moves back up past it. Every published change re-dirties the affected
  sections, so a build that captured an older snapshot is always followed by a correct one.
- **Shared hooks for both renderers.** `END_EXTRACTION` filters entities and block entities; the
  particle `Frustum` wrapper filters particles; `smartCull = false` in `Camera.extractRenderState`
  turns off occlusion culling while a cut is active.
- **Light floor.** The `LightCoordsUtil` mixin checks a duck interface (`CutawayLevelView`) that
  our mixins add to both `RenderSectionRegion` and `LevelSlice`. That way common code never names
  a Sodium class.

### Vanilla path (client mixins, always applied)

| Target | Change |
|---|---|
| `RenderSectionRegion.<init>` | Capture the snapshot and the `BOUNDARY` flag. |
| `RenderSectionRegion.getBlockState`/`getFluidState`/`getBlockEntity` (`:36/41/56`) | Return AIR, empty fluid or null in the region. |
| `LevelExtractor.applyFrustum` `TAIL` (`:472-479`) | Remove `INSIDE` sections from `visibleSections()` (and `nearbyVisibleSections()`). Under Sodium this call is redirected to a no-op (`LevelExtractorMixin:155`), so this hook does not fire there. That is harmless. |
| `LevelExtractor.extract`, the `new Frustum` at `:209` | Wrap it in the cutaway particle frustum. |
| `Camera.extractRenderState` (`Camera.java:124-127`) | Set `smartCull = false` while a cut is active. |
| `LightCoordsUtil.getLightCoords(BrightnessGetter, …)` (`:109`) | Light floor for `CutawayLevelView` levels. |
| Fabric `LevelExtractionEvents.END_EXTRACTION` | Filter entities and block entities. This is an event, not a mixin. |

### Sodium path (`rabodepalha.sodium.mixins.json`, applied only when `sodium` is loaded)

| Target | Change |
|---|---|
| `LevelSlice.copyData` (`:175`) | Capture the snapshot and the `BOUNDARY` flag from `context.getOrigin()`. |
| `LevelSlice.getBlockState(int,int,int)` (`:257`), `getBlockEntity(int,int,int)` (`:332`) | AIR or null in the region. This covers the meshing loop (`ChunkBuilderMeshingTask:116`), face culling (`AbstractBlockRenderContext:109`), fluids and light-state reads. |
| `LevelSlice.getBrightness` (`:283`) | Light floor for the emissive path. |
| `VisibleChunkCollector.visit` (`:25`), `HEAD` cancellable | Skip `INSIDE` sections. This runs on the async cull thread, so it reads only the volatile snapshot. Block entities of skipped sections disappear with them (`SodiumWorldRenderer.extractBlockEntities` walks the render lists). |

No Sodium-specific entity hook is needed. With occlusion culling off, `RenderSectionManager.isBoxVisible`
(`:477`) no longer culls entities behind hidden terrain, and `END_EXTRACTION` removes the entities
inside the region. If #27 measurements force occlusion culling back on, the hook to add is a
`@WrapMethod` on `SodiumWorldRenderer.isBoxVisible` (`:474`) that returns true for boxes the cut
exposes.

## 4. Costs

Estimates are labelled **[est.]**. The #27 prototype replaces them with measurements (§7).

**Assumptions.**

- Render distance R = 12.
- About π·12² ≈ 452 columns lie inside the render radius. A tilted top-down RTS frustum covers
  roughly 2/3 to all of them, so **C_vis ≈ 300–450 columns** [est.].
- Meshing one section takes **t = 0.2–1 ms** of worker time [est.]. The low end is a mostly-stone
  section; the high end is a busy one with AO and fluids.
- **T = 4–8 worker threads** [est.]: `Util.backgroundExecutor` on vanilla, the Sodium `ChunkBuilder`
  pool on Sodium.

**One-level step** (L → L−1, or L → L+1, in a Y cut):

- **Sections rebuilt.** Only the section layer that contains the cut changes. When the step
  crosses a section boundary, the layer below also changes (its top faces become exposed or
  covered again), and a layer that becomes fully `INSIDE` is hidden instantly with no rebuild.
  So a step costs **1 layer** (15 of every 16 steps) or **2 layers** at a boundary.
- **N** = layers × C_vis = **300–450** (typical) to **600–900** (boundary, worst case).
- **Worker time** = N × t = **60–900 ms** in total.
- **Latency until the cut is correct** = N × t / T:
  - best: 300 × 0.2 / 8 ≈ **8 ms**;
  - typical: 450 × 0.5 / 6 ≈ **38 ms**, about 2–3 frames at 60 fps;
  - worst: 900 × 1 / 4 ≈ **225 ms**.
- **Main thread.** No meshing runs there: rebuilds are async-only. What remains is the snapshot
  copies, `RenderRegionCache.createRegion` on vanilla (`LevelExtractor.java:174`) and
  `LevelSlice.prepare` on Sodium, at an estimated **1–5 ms** spread over the frames in which the
  sections are submitted [est.]. Vanilla only collects dirty sections that are visible
  (`LevelExtractor.java:167`), and Sodium spreads submissions under its upload budget.
- **During the gap,** the old cut stays on screen: the stale layer shows its previous mesh until
  its rebuild swaps in atomically. There is no hole and no flicker.
- **Held PageUp/PageDown.** It repeats every 2 ticks (`RtsViewLevel.REPEAT_INTERVAL`), i.e. about
  10 steps/s, so up to ~4,500 section builds/s [est.] at the typical figure. Builds of the same
  section collapse into one pending update, so in practice only the layers the cut passes through
  are rebuilt.
- **Jumping many levels at once** (Home, Surface↔Level) costs the same as a single step: 1–2
  layers. Every layer in between becomes `INSIDE` (instant hide) or goes back to its cached real
  mesh.

**Moving bubble** of radius r (for #29: a sphere, or a vertical cylinder, around the focused
worker):

- Every section intersecting the bubble, inflated by one block for face culling, changes its
  intersection when the bubble moves. Sections fully inside it are hidden instantly instead.
- **Sections per move ≈ ⌈(2r+2)/16⌉ to ⌈(2r+2)/16⌉+1 per axis, cubed:**
  - **r = 8:** 18 blocks spans 2–3 sections per axis, so **8–27 sections**. Latency is
    8–27 × 0.2–1 ms / 4–8 ≈ **0.2–7 ms**.
  - **r = 16:** 34 blocks spans 3–4 per axis, so **27–64 sections**, of which about 1 is fully
    inside. Latency is **0.7–16 ms**.
- **Steady cost.** A worker walks about 4.3 blocks/s. If the bubble re-centres on every block
  moved, the cost is about 4.3 × 64 ≈ **275 builds/s** at r = 16, which is 55–275 ms of worker CPU
  per second [est.]. That competes with chunk loading.
- **Debounce.** Quantize the bubble centre to a 4-block grid with 2 blocks of hysteresis. That
  divides this by about 4, to **~70 builds/s** and 14–70 ms of CPU per second [est.].
- **Frame time.** It does not change, because all builds are async.

## 5. Keeping vanilla and Sodium apart

- **Mixin config.** Add `src/client/resources/rabodepalha.sodium.mixins.json`, listed in
  `fabric.mod.json` next to `rabodepalha.client.mixins.json` (environment `client`).
  - Its `"plugin"` is an `IMixinConfigPlugin` whose `shouldApplyMixin` returns
    `FabricLoader.getInstance().isModLoaded("sodium")`.
  - Mixin targets in it are Sodium classes. When Sodium is absent, the plugin refuses them before
    any target lookup.
- **Runtime code.** Code that names Sodium types, such as calls to
  `SodiumWorldRenderer.scheduleTerrainUpdate()` or a cast to the `LevelSlice` duck, lives in its
  own package (e.g. `client.cutaway.sodium`). It is reached only behind
  `FabricLoader.getInstance().isModLoaded("sodium")`, so its classes never load on vanilla.
- **Common code never names a Sodium class.** It talks to both renderers through the
  `CutawayLevelView` duck interface and the public vanilla methods both renderers honour
  (`LevelExtractor.setSectionDirty`, `Camera`'s `smartCull`, `END_EXTRACTION`).
- **`fabric.mod.json`.** Add `"suggests": { "sodium": "*" }`. When #27 lands, add a `"breaks"`
  entry for Sodium ranges whose internals the mixins can't handle, e.g. `"<0.9.2"`. The mod must
  never `depends` on Sodium.
- **Gradle, for #27** (do not edit `build.gradle` in this issue).
  - The artifact is `maven.modrinth:sodium:mc26.3-0.9.2-fabric`. Its version string is the
    Modrinth `version_number`, `mc26.3-0.9.2-fabric`, and it comes from the Modrinth maven
    `https://api.modrinth.com/maven`.
  - With the classic remapping Loom plugin, the dependency lines would be `modCompileOnly` and
    `modLocalRuntime`.
  - This build applies `net.fabricmc.fabric-loom`, the non-remapping plugin for unobfuscated 26.x,
    and declares Fabric API with plain `implementation` (`build.gradle:2`, `:31`). The `mod*`
    configurations are therefore probably absent, so use the plain names below. #27 should confirm
    them with `./gradlew dependencies`.

  ```groovy
  repositories {
      maven { url = 'https://api.modrinth.com/maven' }
  }
  dependencies {
      // splitEnvironmentSourceSets: Sodium is client-only, so compile it into src/client only.
      // (modCompileOnly on the client source set if this build ever moves to the remapping plugin)
      clientCompileOnly 'maven.modrinth:sodium:mc26.3-0.9.2-fabric'
      // Manual testing only; default runs stay vanilla.
      // (modLocalRuntime with the remapping plugin)
      if (project.hasProperty('withSodium')) {
          localRuntime 'maven.modrinth:sodium:mc26.3-0.9.2-fabric'
      }
  }
  ```

  Run `./gradlew runClient -PwithSodium` for manual Sodium testing. Agents still never launch
  `runClient`.

  Keep every Sodium import inside `src/client`.

## 6. Open risks

- **Sodium internals are not API.** `LevelSlice`, `VisibleChunkCollector`, `SodiumWorldRenderer`
  and `RenderSectionManager` can change in any release, and `0.9.3-alpha.1` (`mc26.3-0.9.3-alpha.1`)
  may already have moved them.
  - Mitigation: the Sodium mixins should use `require = 1` so they fail loudly.
  - Pin the tested range in `"breaks"`.
  - Keep the Sodium surface down to 4–5 injection points.
- **Sodium overwrites vanilla methods.** Vanilla hooks that **do not fire** under Sodium:
  - `LevelExtractor.applyFrustum` (redirected to a no-op);
  - `extractVisibleBlockEntities` (cancelled);
  - the private `setSectionDirty`/`setBlockDirty` (overwritten, so never *inject* into them; only
    call the public one);
  - `LevelRenderer.isSectionCompiledAndVisible` (overwritten);
  - anything in `SectionCompiler`, `RenderSectionRegion` or `SectionOcclusionGraph` (not used).

  Hooks that **do** fire under both renderers: `Camera.extractRenderState`, the `LightCoordsUtil`
  static, the `extract` particle call site, and `END_EXTRACTION`.
- **Turning occlusion culling off costs extra draws.** Every section in the frustum is drawn,
  including cave systems. Spectator mode already does this, but the GPU cost at high render
  distance is unmeasured. If it is too high, the follow-up is to keep culling on and make
  `INSIDE` sections report fully connected visibility (vanilla `facesCanSeeEachother` at
  `SectionOcclusionGraph.java:297`; Sodium `getVisibilityData` at `OcclusionCuller.java:300`).
- **The vanilla `NEARBY` "Prioritize chunk updates" option** compiles sections within
  `distSqr < 768` synchronously even if they are not player-caused (`LevelRenderer.java:948`).
  Since the RTS camera is usually high above the cut, few sections qualify, but a low camera could
  hitch. Measure it.
- **Per-section mesh times are estimates** until the prototype measures them (§7).
- **The vanilla terrain shader text is not in `build/mc-sources`,** so B's vanilla side is
  inferred. This doesn't affect the recommendation, which makes no shader edits.
- **Air-ing hidden blocks changes AO and fluid heights at the cut,** so cross-sections can look
  wrong as well as dark. The light floor fixes darkness, not the AO direction. See the manual
  checks.
- **Light differs between renderers.** Vanilla reads live light during off-thread meshing
  (`RenderSectionRegion.getLightEngine`), while Sodium reads copied arrays. The floor hook covers
  both, but their results may differ slightly.
- **Iris (not required).** C doesn't block it: there are no shader edits, and Iris builds on
  Sodium's meshing. Iris's own shadow pass may re-cull. Check when Iris support comes up.

## 7. Scope changes for #27 and #29

**#27: cut everything above a level.**

1. **Start with a prototype.** Make a fixed-Y cut behind `-Drabodepalha.cutaway=<y>`, **off by
   default**. Use it to measure:
   - per-section mesh time on vanilla and Sodium;
   - one-level-step latency;
   - the GPU cost of `smartCull = false`.

   These measurements replace the estimates in §4. This issue ships **no prototype**.
2. Write the pure `CutawayRegion` predicate and section classifier, with unit tests in `src/test`.
3. Write the vanilla mixins listed in §3.
4. Write the Sodium mixins in `rabodepalha.sodium.mixins.json` behind the `IMixinConfigPlugin`.
5. Add the Gradle coordinates from §5.
6. **Dirty marking:** call `LevelExtractor.setSectionDirty` for the changed layer(s) only, never
   the sync paths. Invalidate the graph on each renderer.
7. **Entity, block-entity and particle filtering:** the `END_EXTRACTION` filter, which covers
   vanilla and Sodium (Sodium culls only through the wrapped `EntityRenderer.shouldRender`), plus
   the particle frustum.
8. **Light floor:** the `LightCoordsUtil` hook, plus `LevelSlice.getBrightness` on Sodium.
9. Turn occlusion culling off through `Camera.extractRenderState` while the cut is active.
10. Wire it to `RtsViewLevel`'s `LEVEL` mode, with the predicate `y > viewLevel`.

**#27 as built.**

- **Cap shading.** `CutawayShading.capLight` goes further than the plain light floor in item 8. A
  light sample inside the region is a *cut wall* when the hidden cell is solid. It then gets a fixed
  light (`CAP_BLOCK = 7`, `CAP_SKY = 0`). When the hidden cell is air, the sample is a *real floor*
  and gets `max(real, FLOOR_BLOCK = 12)` with the real sky light. So a sliced wall looks darker and
  flatter than a floor a worker can walk on. The constants are package-visible so they can be tuned.
- **Region sync.** `RtsClient.syncCutaway()` publishes the region at most once per client tick, at
  the end of `END_CLIENT_TICK`, and again from `setActive`. It sets `CutawayRegion.above(viewLevel)`
  in `LEVEL` mode and `CutawayRegion.NONE` otherwise. `Cutaway.set` returns early when the region
  hasn't changed. Otherwise it dirties only the layers from `CutawayRegion.changedSectionYs`, through
  `LevelExtractor.setSectionDirty`, and invalidates the graph only when `insideSetChanged`.
- **Sodium version pin.** `fabric.mod.json` has `"suggests": {"sodium": "*"}` and no `depends`
  entry for Sodium. It also has `"breaks": {"sodium": ["<0.9.2", ">=0.9.3-"]}`. Sodium 0.9.2's
  version string is `0.9.2+mc26.3`, which is allowed. The `-` makes the lower bound include the
  0.9.3 pre-releases, such as `0.9.3-alpha.1+mc26.3`, so they are broken along with everything
  after them. That way an untested Sodium gets Fabric's clear incompatibility message, not a mixin
  crash at startup under `defaultRequire 1`. Widen the pin only after a new Sodium release has been
  tested against `LevelSlice` and `VisibleChunkCollector`. Update `SodiumCompatPinTest` in the same
  change.
- **Costs** are a manual check, not recorded numbers. Watch F3's chunk-update counters and FPS
  while stepping levels and while a cut is active, on vanilla and with `-PwithSodium`. No
  `-Drabodepalha.cutaway` prototype flag ships.

**#29: see into buildings.**

- Reuse the same predicate, with new shapes:
  - a sphere or cylinder bubble around the focused worker;
  - a box for a room;
  - near-side walls: the blocks of a building between the camera and its interior, for example
    the building's AABB clipped to the half-space facing the camera.
- Keep camera yaw out of the region definition, or quantize it (e.g. 4 or 8 facings). Otherwise
  every rotation re-meshes the walls.
- Debounce or add hysteresis to the bubble centre (§4).
- **No cap geometry is needed,** because re-meshing draws the cut faces. Drop any "draw caps"
  task.
- No new renderer hooks are needed beyond #27's. The only new work is the shapes and their
  `classify` implementations.

**#29 as built.**

- **Shapes.** `CutawayRegion` is now a union of immutable `CutawayShape` records:
  - `Above(level)`: the #27 view level;
  - `Box`: inclusive, `maxY` may be `Integer.MAX_VALUE`; used for roofs and walls;
  - `Bubble`: an oblique vertical cylinder that leans toward the camera, tested at block centres.

  `contains` is the OR over the shapes. `classify` returns `INSIDE` if any shape is `INSIDE`, else
  `BOUNDARY` if any shape is, else `OUTSIDE`. The renderer hooks and `Cutaway.set` didn't change; no
  new mixins were added. Each shape's `classify` uses the inflated span on all three axes. The
  bubble's `INSIDE` is exact (every layer's disc covers the span's corners) and its `OUTSIDE` is
  conservative (the span's rectangle is farther than the radius from every layer's disc centre).
- **Diff.** `CutawayRegion.diff(old, now, window, sink)` replaces `changedSectionYs` and
  `insideSetChanged`. Only the sections inside the `sectionBounds()` of the shapes that changed
  (the symmetric difference of the two shape lists), clipped to the render window, are visited.
  Elsewhere both regions hide the same blocks. A visited section is re-meshed when it is `BOUNDARY`
  on either side. The graph is invalidated when a visited section moves in or out of `INSIDE`.
  `Above` is unbounded on x/z, so a level change still dirties its boundary layer across the
  window, as in #27. Moving the bubble with the level unchanged dirties only the bubble's
  sections.
- **Entities.** `hidesEntityAt(x, feetY, z)` replaces `hidesFeet`. `Above` and `Box` hide the
  entities whose feet block they contain; the bubble doesn't, so workers in it stay visible and
  clickable. Picking reads the published region: `RtsLevelClip.traverse` takes a hidden-voxel
  predicate (`region::contains`) whenever any cut is active, and workers that `hidesEntityAt`
  hides can't be hovered, clicked, box-selected or get order markers. `RtsClient.pickLevel()` is
  gone.
- **Debounce** (`RtsCutawayFocus`).
  - The bubble anchor snaps to a 4-block grid on x/z, and stays while the raw point is within
    4 blocks (2 of hysteresis past its cell).
  - Y snaps *up* to the grid, and stays only while it is not below the raw cell and less than
    8 above it. So the bubble never hides the hovered block, which stops the cursor ray from
    drilling down through it.
  - Yaw is quantized to 8 facings with 5° of hysteresis.
  - The lean per block of height is cot(pitch), snapped to 0, 0.5, 1 or 2.
  - The bubble has radius 5 and is 24 blocks tall from the cell above the hovered block, or from
    the hovered or focused worker's feet.
  - There is no soft or stepped edge: the doc's quantized, re-meshed cut wins over the issue's
    wording, and the cylinder's voxel edge is the step.
- **Rooms** (`CutawayRooms`, pure, over a `Solidity` predicate).
  - **Flood fill.** A 4-connected fill over the air cells of the feet layer. A cell joins only if a
    ceiling block lies within 6 above it. Cells without a ceiling count as openings; solid
    neighbours count as walls.
  - **Rejection.** The fill gives up past 512 cells or a 32-block extent (large caves, forests),
    or when openings make up more than 25% of the edges (overhangs).
  - **Doorways.** Cells between two solid blocks on one axis are filled through, but they don't
    widen the room's box or lower its ceiling to the lintel.
  - **Start cell.** The start is the air cell in front of the hovered face, if it has a ceiling.
    Otherwise it is the first air cell under the hovered block, through at most 3 solid blocks
    (a roof). Either one then drops to the floor. A hovered or focused worker starts from its
    feet.
  - **Roofs off.** `roofBox` covers the interior widened by 1, from the lowest ceiling up.
  - **Walls down.** `nearWalls` gives 1-thick slabs on the 1 or 2 sides of the widened box that
    face the quantized camera, from one above the feet row up to under the ceiling. The feet row
    stays as a stub.
  - **Solidity.** On the client (`CutawaySolidity`), walls are blocks in
    `blocks_motion_in_heightmap_no_leaves`, and any non-replaceable block is a ceiling, leaves
    included.
- **Room caching.** `RtsCutawayModes` keeps one room for the cursor and one for the focused worker
  (the first selected). Each is detected again when its start cell changes, or every 20 game
  ticks so that block edits show up. The cursor keeps its room while the hovered block stays
  within the widened box, so dropping through the hidden roof onto the floor doesn't switch
  rooms. No room means no shape, so roofs-on doesn't by itself switch occlusion culling off.
- **Known limitation.** Hovering ground with a cave pocket 1–3 blocks under it finds that pocket
  through the roof probe, and lifts the ground over it.
- **Keys.**
  - `I` toggles the bubble (default off), `[` toggles roofs off (default on) and `]` toggles walls
    down (default off). Each shows HUD feedback.
  - The issue's B/J/K were already taken by the mine, deposit and withdraw commands. Other free
    letters clash with vanilla bindings.
  - The toggles reset on disconnect, like the brightness choice. Anchors and cached rooms reset on
    every RTS toggle.
- **Sodium origin check.** In 0.9.2, `RenderSectionManager` passes `render.getPosition()` to
  `LevelSlice.prepare`, and that becomes `ChunkRenderContext.getOrigin()`. So the origin the
  `LevelSlice` hook classifies is the section being built, in section coordinates. This matches
  the vanilla hook's `minSection + 1`. No fix was needed; a comment records it.

## 8. Classes and methods relied on

**Vanilla 26.3** (under `build/mc-sources/`):

- `client/net/minecraft/client/renderer/extract/LevelExtractor.java`:
  - `extract` (`:108`), `applyFrustum` (`:472`), and the section-update collection (`:167-176`);
  - `isEntityVisible` (`:280`) and `extractVisibleBlockEntities` (`:298`);
  - `setSectionDirty(int,int,int)` (`:565`) and the private overload (`:569`);
  - the particle extract (`:209`).
- `client/net/minecraft/client/renderer/LevelRenderer.java`:
  - `visibleSections` (`:135`, accessor `:1289`) and `extractSectionDrawGroups` (`:702-729`);
  - `writeChunkSections` (`:826`) and `compileSections` (`:937-963`);
  - `isSectionCompiledAndVisible` (`:1246`), `sectionOcclusionGraph()` (`:1301`), and the
    compile executor (`:1154`).
- `client/net/minecraft/client/renderer/chunk/SectionCompiler.java`: `compile` (`:53`), the
  block loop (`:74-95`), and `visibilitySet` (`:129`).
- `client/net/minecraft/client/renderer/chunk/RenderSectionRegion.java`: `getBlockState`
  (`:36`), `getFluidState` (`:41`), `getLightEngine` (`:52`), and `getBlockEntity` (`:56`).
- `client/net/minecraft/client/renderer/SectionOcclusionGraph.java`: `invalidate` (`:93`), the
  `smartCull` change check (`:102`), `schedulePropagationFrom` (`:134`), `update` (`:148`), and
  `runUpdates` (`:256-314`).
- `client/net/minecraft/client/renderer/DynamicGpuData.java`: `ChunkSectionInfo` (`:121`) and
  `TerrainTransform` (`:135`).
- `client/net/minecraft/client/renderer/RenderPipelines.java`: `TERRAIN_SNIPPET` and
  `MULTIDRAW_TERRAIN_SNIPPET` (`:100-117`).
- Light: `client/net/minecraft/client/renderer/block/BlockModelLighter.java` (`:32`, `:677`),
  `client/net/minecraft/client/renderer/block/FluidRenderer.java` (`:428`), and
  `common/net/minecraft/util/LightCoordsUtil.java` (`:105`, `:109`).
- `client/net/minecraft/client/Camera.java`: `extractRenderState` and `smartCull` (`:118-127`).
- `client/net/minecraft/client/renderer/culling/Frustum.java`: the class (`:12`) and
  `pointInFrustum` (`:103`).
- `client/net/minecraft/client/particle/ParticleEngine.java`: `extract` (`:123`).
- `client/net/minecraft/client/particle/QuadParticleGroup.java`: `:23`.
- `client/net/minecraft/client/renderer/entity/EntityRenderer.java`: `shouldRender` (`:62`).
- Render states: `client/net/minecraft/client/renderer/state/level/LevelRenderState.java`
  (`:14-15`), `client/net/minecraft/client/renderer/entity/state/EntityRenderState.java`
  (`:17-19`), and `client/net/minecraft/client/renderer/blockentity/state/BlockEntityRenderState.java`
  (`:16`).

**Fabric API 0.162.0+26.3** (under `build/mc-sources/fabric-api/fabric-rendering-v1/`):

- `net/fabricmc/fabric/api/client/rendering/v1/level/LevelExtractionEvents.java`: `END_EXTRACTION` (`:70`).
- `net/fabricmc/fabric/api/client/rendering/v1/level/AbstractLevelRenderContext.java`: `levelState()` (`:49`).
- `net/fabricmc/fabric/mixin/client/rendering/LevelExtractorMixin.java`: the injection at `extract` `RETURN` (`:69-71`).

**Sodium 0.9.2** (tag `mc26.3-0.9.2`, commit `03198cb36ab831c99694403d18e4cf4a1726df72`, Modrinth
`mc26.3-0.9.2-fabric`, under `build/sodium-sources/sodium-mc26.3-0.9.2/`):

- `common/src/main/java/net/caffeinemc/mods/sodium/client/world/LevelSlice.java`:
  - `prepare` (`:110`) and `copyData` (`:175`);
  - `getBlockState` (`:253`, `:257`), `getFluidState` (`:271`) and `getBlockEntity` (`:328`, `:332`);
  - `getBrightness` (`:283`).
- `common/src/main/java/net/caffeinemc/mods/sodium/client/world/cloned/ClonedChunkSection.java`: the constructor (`:46`) and `getLightArray` (`:254`).
- `common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/compile/tasks/ChunkBuilderMeshingTask.java`: `execute` (`:72-164`, `:246`).
- `common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderer.java` (`:37`, `:65`), `DefaultFluidRenderer.java` (`:56`), `ShapeComparisonCache.java` (`:9`), `BlockRenderCache.java` (`:24`).
- `common/src/main/java/net/caffeinemc/mods/sodium/client/render/model/AbstractBlockRenderContext.java`: `shouldDrawSide` (`:104-109`).
- `common/src/main/java/net/caffeinemc/mods/sodium/client/model/light/data/LightDataAccess.java`: `compute` (`:61-94`), plus `client/model/light/{LightPipeline,smooth/SmoothLightPipeline,flat/FlatLightPipeline}.java`.
- `common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager.java`:
  - `markGraphDirty` (`:339`) and `readRenderListFromTree` (`:290-316`);
  - `isBoxVisible` (`:477`) and `processChunkBuilds` (`:481-492`);
  - `scheduleRebuild` (`:984-1003`).
- `common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/lists/VisibleChunkCollector.java`: `visit` (`:25-48`).
- `common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/occlusion/OcclusionCuller.java` (`:299-300`) and `VisibilityEncoding.java` (`:8`).
- `common/src/main/java/net/caffeinemc/mods/sodium/client/render/SodiumWorldRenderer.java`:
  - `scheduleTerrainUpdate` (`:163`) and `extractBlockEntities` (`:338`);
  - `isEntityVisible` (`:452`) and `isBoxVisible` (`:474`);
  - `scheduleRebuildForChunk` (`:516`).
- `common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer.java`
  (`:52`, `:70-100`) and `DefaultChunkRenderer.java` (`:38`).
- `common/src/main/java/net/caffeinemc/mods/sodium/mixin/core/render/world/LevelExtractorMixin.java`
  (`:57`, `:92-135`, `:137-162`) and `LevelRendererMixin.java` (`:137`, `:157`, `:162`).
- `common/src/main/java/net/caffeinemc/mods/sodium/mixin/features/render/entity/cull/EntityRendererMixin.java` (`:18-27`).
- `common/src/main/java/net/caffeinemc/mods/sodium/mixin/core/render/frustum/CameraMixin.java` (`:19`).
- `common/src/main/java/net/caffeinemc/mods/sodium/mixin/core/render/world/ParticleFeatureRendererMixin.java` (`:18-31`) and `mixin/features/render/particle/QuadParticleRenderStateMixin.java` (`:18-30`).
- `common/src/api/java/net/caffeinemc/mods/sodium/api/blockentity/BlockEntityRenderHandler.java` and `BlockEntityRenderPredicate.java`.
- Shaders: `common/src/main/resources/assets/sodium/shaders/blocks/block_layer_opaque.vsh` (`:19-29`), `block_layer_opaque.fsh` (`:8-11`, `:102-106`), and `include/{globals,fog,chunk_material,chunk_vertex}.glsl`.
- Absent from 0.9.2 (looked for and not found): `ChunkShaderInterface`, `ChunkShaderOptions`, and
  `BlockOcclusionCache` (replaced by `ShapeComparisonCache`).

## 9. Check manually (for the #27/#29 PRs; agents never run `runClient`)

Run each item on vanilla **and** with `-PwithSodium`:

- **Cross-section.** A Y cut through a hillside shows a solid top face on every cut block, with
  no see-through holes into caves.
- **Lighting.** Cut faces deep underground are lit at the floor level, not black. AO at the cut
  edge looks acceptable.
- **Step behaviour.** Holding PageDown steps the cut smoothly. A layer may lag a few frames, but
  there is no hole or flicker. With "Prioritize chunk updates = Nearby", there is no hitch.
- **Jumps.** Home and Surface↔Level hide the upper layers on the same frame.
- **Caves.** Caves below the cut are drawn: occlusion culling is off while the cut is active.
- **Entities.** Mobs, item frames, chests, signs and particles above the cut are hidden, and
  workers standing at the level stay visible.
- **Water and lava.** At the cut they look plausible: no floating surfaces, no missing sides.
- **Leaving the cut.** Leaving Level mode restores full terrain and occlusion culling, and frame
  time returns to baseline.
- **Iris (when available).** A shader pack doesn't bring back hidden blocks.
