package io.github.oshogun.rabodepalha.rts;

import java.util.Arrays;

/**
 * Pure rules for the RTS cutaway: the region of the world hidden in Level mode, which is everything above
 * the view level. It has no Minecraft dependencies, so it can be unit-tested.
 *
 * <p>Render sections are 16 blocks tall and classified against an inflated block span
 * {@code [sy*16-1, sy*16+16]}: a section's mesh also reads the blocks just below and above it for face
 * culling, so a section whose neighbour layer is cut must be re-meshed too.
 */
public final class CutawayRegion {
	/** No cutaway: nothing is hidden. */
	public static final CutawayRegion NONE = new CutawayRegion(false, 0);

	private static final int SECTION_SIZE = 16;

	/** How a render section relates to the region. */
	public enum Section {
		/** Nothing in or next to the section is hidden; vanilla meshes it as usual. */
		OUTSIDE,
		/** The cut crosses the section or its neighbour layers; it is re-meshed with the cut applied. */
		BOUNDARY,
		/** The whole section is hidden; it is skipped without re-meshing. */
		INSIDE
	}

	private final boolean active;
	private final int level;

	private CutawayRegion(boolean active, int level) {
		this.active = active;
		this.level = level;
	}

	/** The region of every block with y above {@code viewLevel}. */
	public static CutawayRegion above(int viewLevel) {
		return new CutawayRegion(true, viewLevel);
	}

	/** Whether anything is hidden. */
	public boolean active() {
		return active;
	}

	/** The view level; only meaningful when {@link #active()}. */
	public int level() {
		return level;
	}

	/** Whether the block at {@code x, y, z} is hidden. */
	public boolean contains(int x, int y, int z) {
		return active && y > level;
	}

	/**
	 * How the render section at section coordinates {@code sx, sy, sz} relates to the region. Only
	 * {@code sy} matters for now; {@code sx} and {@code sz} are kept for regions that are not flat.
	 */
	public Section classify(int sx, int sy, int sz) {
		if (!active) {
			return Section.OUTSIDE;
		}
		int bottom = sy * SECTION_SIZE - 1;
		int top = sy * SECTION_SIZE + SECTION_SIZE;
		if (bottom > level) {
			return Section.INSIDE;
		}
		if (top <= level) {
			return Section.OUTSIDE;
		}
		return Section.BOUNDARY;
	}

	/** Whether an entity whose feet are in block {@code feetBlockY} is hidden. */
	public boolean hidesFeet(int feetBlockY) {
		return active && RtsViewLevel.feetAboveLevel(feetBlockY, level);
	}

	/**
	 * The section Ys in {@code [minSectionY, maxSectionY]}, sorted ascending, that must be re-meshed when
	 * the region changes from {@code old} to {@code now}: those that are {@link Section#BOUNDARY} on either
	 * side, since their cut mesh is stale or due. A section that only moves between
	 * {@link Section#OUTSIDE} and {@link Section#INSIDE} keeps its real mesh and is just hidden or shown.
	 */
	public static int[] changedSectionYs(CutawayRegion old, CutawayRegion now, int minSectionY, int maxSectionY) {
		if (maxSectionY < minSectionY) {
			return new int[0];
		}
		int[] out = new int[maxSectionY - minSectionY + 1];
		int n = 0;
		for (int sy = minSectionY; sy <= maxSectionY; sy++) {
			if (old.classify(0, sy, 0) == Section.BOUNDARY || now.classify(0, sy, 0) == Section.BOUNDARY) {
				out[n++] = sy;
			}
		}
		return Arrays.copyOf(out, n);
	}

	/**
	 * Whether the set of {@link Section#INSIDE} sections differs between {@code old} and {@code now}, so
	 * the visible-section graph must be rebuilt.
	 */
	public static boolean insideSetChanged(CutawayRegion old, CutawayRegion now) {
		if (old.active != now.active) {
			return true;
		}
		if (!old.active) {
			return false;
		}
		// The lowest INSIDE section is the first whose bottom - 1 is above the level.
		return Math.floorDiv(old.level + 1, SECTION_SIZE) != Math.floorDiv(now.level + 1, SECTION_SIZE);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof CutawayRegion other)) {
			return false;
		}
		return active == other.active && (!active || level == other.level);
	}

	@Override
	public int hashCode() {
		return active ? 31 + level : 0;
	}

	@Override
	public String toString() {
		return active ? "CutawayRegion[above " + level + "]" : "CutawayRegion[NONE]";
	}
}
