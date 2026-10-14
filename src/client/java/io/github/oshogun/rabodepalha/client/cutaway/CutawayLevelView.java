package io.github.oshogun.rabodepalha.client.cutaway;

import org.jspecify.annotations.Nullable;

import io.github.oshogun.rabodepalha.rts.CutawayRegion;

/**
 * Duck interface mixed into the block views a section is meshed from (vanilla's {@code RenderSectionRegion},
 * Sodium's {@code LevelSlice}). The view hides the blocks inside the region it captured when it was built,
 * so a mesh reads one region from start to end even if the level moves meanwhile.
 */
public interface CutawayLevelView {
	/** The region captured when the view was built, or null if its section is not a cut (BOUNDARY) section. */
	@Nullable CutawayRegion rabodepalha$cutRegion();

	/** Whether the real block at {@code x, y, z} is solid, reading past the cut. */
	boolean rabodepalha$realSolid(int x, int y, int z);
}
