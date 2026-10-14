package io.github.oshogun.rabodepalha.client.cutaway;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;

import io.github.oshogun.rabodepalha.client.cutaway.sodium.SodiumCutaway;
import io.github.oshogun.rabodepalha.rts.CutawayRegion;
import io.github.oshogun.rabodepalha.rts.CutawayShape;

/**
 * Client state of the RTS cutaway: the region hidden in Level mode and by the local cuts. The region is set on the main thread
 * and read by section meshing threads, so it is a volatile immutable value. Changing it re-meshes the
 * sections the cut crosses and refreshes the visible-section graph when whole sections are hidden or shown.
 */
public final class Cutaway {
	private static volatile CutawayRegion region = CutawayRegion.NONE;

	private static final boolean SODIUM = FabricLoader.getInstance().isModLoaded("sodium");

	private Cutaway() {
	}

	public static void init() {
		LevelExtractionEvents.END_EXTRACTION.register(context -> hideExtracted(context.levelState()));
		ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> clear());
	}

	/** The region hidden now. */
	public static CutawayRegion current() {
		return region;
	}

	public static boolean isActive() {
		return region.active();
	}

	/** Switches to {@code next}, re-meshing and re-culling what it changes. Main thread only. */
	public static void set(CutawayRegion next) {
		CutawayRegion old = region;
		if (old.equals(next)) {
			return;
		}
		region = next;
		Minecraft client = Minecraft.getInstance();
		ClientLevel level = client.level;
		if (level == null) {
			return;
		}
		// Only the async path: never setBlockDirty or a player-changed rebuild, which compile synchronously.
		boolean insideChanged = CutawayRegion.diff(old, next, renderWindow(client, level),
				client.levelExtractor::setSectionDirty);
		if (insideChanged) {
			if (SODIUM) {
				SodiumCutaway.invalidate();
			} else {
				client.levelRenderer.sectionOcclusionGraph().invalidate();
			}
		}
	}

	/** Drops the region without re-meshing, for when the level is going away. */
	public static void clear() {
		region = CutawayRegion.NONE;
	}

	/** Whether {@code entity} is hidden: its feet block is in a part of the region that hides entities. */
	public static boolean hides(Entity entity) {
		return region.hidesEntityAt(Mth.floor(entity.getX()), Mth.floor(entity.getY()), Mth.floor(entity.getZ()));
	}

	/** Whether a block entity at {@code pos} is hidden. */
	public static boolean hides(BlockPos pos) {
		return region.hidesEntityAt(pos.getX(), pos.getY(), pos.getZ());
	}

	/** The sections that can be on screen: the render distance plus one around the camera, the level's full height. */
	private static CutawayShape.SectionBounds renderWindow(Minecraft client, ClientLevel level) {
		int radius = client.options.getEffectiveRenderDistance() + 1;
		SectionPos camera = SectionPos.of(client.gameRenderer.mainCamera().position());
		return new CutawayShape.SectionBounds(camera.x() - radius, level.getMinSectionY(), camera.z() - radius,
				camera.x() + radius, level.getMaxSectionY(), camera.z() + radius);
	}

	private static void hideExtracted(LevelRenderState state) {
		CutawayRegion now = region;
		if (!now.active()) {
			return;
		}
		state.entityRenderStates.removeIf(entity -> now.hidesEntityAt(Mth.floor(entity.x), Mth.floor(entity.y), Mth.floor(entity.z)));
		state.blockEntityRenderStates.removeIf(blockEntity -> now.hidesEntityAt(blockEntity.blockPos.getX(),
				blockEntity.blockPos.getY(), blockEntity.blockPos.getZ()));
	}
}
