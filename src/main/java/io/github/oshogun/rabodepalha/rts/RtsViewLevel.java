package io.github.oshogun.rabodepalha.rts;

/**
 * Pure rules for the RTS camera's view level. In {@link Mode#LEVEL} the player picks a view level Y and
 * the camera treats it as the top of the world: each column's ground reference is the highest solid
 * block at or below that level, found by scanning down instead of reading the heightmap. It has no
 * Minecraft dependencies, so it can be unit-tested.
 *
 * <p>Heights follow heightmap semantics: a column's height is the Y just above its top solid block.
 */
public final class RtsViewLevel {
	/** How many blocks a column is scanned down from the view level before it counts as unloaded. */
	public static final int SCAN_LIMIT = 64;
	/** Ticks a level key must be held before it starts repeating. */
	public static final int REPEAT_DELAY = 6;
	/** Ticks between repeated steps once a held level key repeats. */
	public static final int REPEAT_INTERVAL = 2;

	private RtsViewLevel() {
	}

	/** How the camera finds the ground: from the heightmap, or by scanning down from a view level. */
	public enum Mode {
		SURFACE,
		LEVEL
	}

	/** Whether the block at height {@code y} of one column is solid. */
	@FunctionalInterface
	public interface SolidTest {
		boolean solid(int y);
	}

	/**
	 * The height of a column seen from {@code viewLevel}: one above the highest solid block at or below
	 * the level, scanning at most {@code scanLimit} blocks and never below {@code minY}. Returns NaN when
	 * nothing solid is found, so the camera filter treats the column as unloaded.
	 */
	public static double columnTop(int viewLevel, int minY, int scanLimit, SolidTest test) {
		int lowest = Math.max(minY, viewLevel - scanLimit + 1);
		for (int y = viewLevel; y >= lowest; y--) {
			if (test.solid(y)) {
				return y + 1;
			}
		}
		return Double.NaN;
	}

	/**
	 * Whether a player whose feet are in block {@code feetBlockY} is below the surface of a column whose
	 * heightmap height is {@code surfaceHeight}, for example in a cave or under a roof.
	 */
	public static boolean startsUnderground(int feetBlockY, int surfaceHeight) {
		return feetBlockY < surfaceHeight;
	}

	/**
	 * Whether a worker whose feet are in block {@code feetBlockY} is above {@code viewLevel}, so level mode
	 * cannot pick it. The level is a feet block (the air above a floor), so a worker standing on the floor
	 * the level looks at, feet at the level, can still be picked.
	 */
	public static boolean feetAboveLevel(int feetBlockY, int viewLevel) {
		return feetBlockY > viewLevel;
	}

	/** {@code level} clamped to the world's block range {@code [minY, maxYExclusive)}. */
	public static int clampLevel(int level, int minY, int maxYExclusive) {
		return Math.max(minY, Math.min(maxYExclusive - 1, level));
	}

	/**
	 * Whether a level key held for {@code heldTicks} ticks (0 on the tick it went down) steps the level
	 * this tick: once on the press, then every {@link #REPEAT_INTERVAL} ticks from {@link #REPEAT_DELAY}.
	 */
	public static boolean repeatStep(int heldTicks) {
		if (heldTicks == 0) {
			return true;
		}
		return heldTicks >= REPEAT_DELAY && (heldTicks - REPEAT_DELAY) % REPEAT_INTERVAL == 0;
	}

	/**
	 * How many blocks the view level lies below the top solid block of a column whose heightmap height is
	 * {@code surfaceHeight}: 0 when the level is that block, negative above it. NaN when the height is NaN.
	 */
	public static double depthBelowSurface(double surfaceHeight, int viewLevel) {
		if (Double.isNaN(surfaceHeight)) {
			return Double.NaN;
		}
		return surfaceHeight - 1 - viewLevel;
	}
}
