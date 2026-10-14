package io.github.oshogun.rabodepalha.rts;

/**
 * Pure rule for shading the cut in the RTS cutaway. It has no Minecraft dependencies, so it can be
 * unit-tested.
 *
 * <p>A face on the cut takes its light from a cell inside the hidden region. If that cell was solid, the
 * face is a cap on a block the level slices through, so it gets a fixed dim light that reads as a cut wall.
 * If that cell was air, the face is a real floor a worker can walk on, so it keeps its real light with a
 * floor, which keeps cave floors readable without making them look like caps.
 */
public final class CutawayShading {
	/** Block light of a cut-wall cap. */
	static final int CAP_BLOCK = 7;
	/** Sky light of a cut-wall cap. */
	static final int CAP_SKY = 0;
	/** Lowest block light of a walkable floor under the cut. */
	static final int FLOOR_BLOCK = 12;

	private CutawayShading() {
	}

	/**
	 * The {@code {block, sky}} light for a light sample at a hidden cell, given whether the hidden cell is
	 * solid and the real light there.
	 */
	public static int[] capLight(boolean hiddenCellIsSolid, int realBlock, int realSky) {
		if (hiddenCellIsSolid) {
			return new int[] {CAP_BLOCK, CAP_SKY};
		}
		return new int[] {Math.max(realBlock, FLOOR_BLOCK), realSky};
	}
}
