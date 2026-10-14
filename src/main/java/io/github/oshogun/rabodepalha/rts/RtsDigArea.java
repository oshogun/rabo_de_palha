package io.github.oshogun.rabodepalha.rts;

/**
 * An inclusive cuboid of block positions to dig, built from the two blocks picked by an RTS drag and
 * a depth. It holds no Minecraft types so it can be unit-tested. It is always normalised so that
 * {@code min <= max} on every axis.
 */
public record RtsDigArea(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
	/** The deepest area the depth step allows, in layers; the full height of an overworld. */
	public static final int MAX_DEPTH = 384;

	/**
	 * The area spanned by two picked blocks given in any order and a depth. The top layer is the higher
	 * of the two blocks, and {@code depth} counts layers down from it, including it. The depth is
	 * clamped with {@link #clampDepth}.
	 */
	public static RtsDigArea of(int ax, int ay, int az, int bx, int by, int bz, int depth) {
		int maxY = Math.max(ay, by);
		int minY = maxY - clampDepth(depth) + 1;
		return new RtsDigArea(Math.min(ax, bx), minY, Math.min(az, bz), Math.max(ax, bx), maxY, Math.max(az, bz));
	}

	/** The area spanned by two corners given in any order, with no depth applied. */
	public static RtsDigArea between(int ax, int ay, int az, int bx, int by, int bz) {
		return new RtsDigArea(Math.min(ax, bx), Math.min(ay, by), Math.min(az, bz),
				Math.max(ax, bx), Math.max(ay, by), Math.max(az, bz));
	}

	/** The depth the depth step starts at: enough layers to reach the lower of the two picked blocks. */
	public static int initialDepth(int ay, int by) {
		return Math.abs(ay - by) + 1;
	}

	/** {@code depth} clamped to {@code [1, MAX_DEPTH]}. */
	public static int clampDepth(int depth) {
		return Math.max(1, Math.min(MAX_DEPTH, depth));
	}

	public int sizeX() {
		return maxX - minX + 1;
	}

	public int sizeY() {
		return maxY - minY + 1;
	}

	public int sizeZ() {
		return maxZ - minZ + 1;
	}

	/** The number of blocks in the area. */
	public long volume() {
		return (long) sizeX() * sizeY() * sizeZ();
	}

	/** Whether the area holds more than {@code max} blocks. */
	public boolean exceeds(long max) {
		return volume() > max;
	}

	/**
	 * The squared distance from the eye to the farthest corner of the area's outer faces, so a block at
	 * {@code max} reaches out to {@code max + 1}.
	 */
	public double farthestCornerDistanceSqr(double eyeX, double eyeY, double eyeZ) {
		double dx = Math.max(Math.abs(minX - eyeX), Math.abs(maxX + 1 - eyeX));
		double dy = Math.max(Math.abs(minY - eyeY), Math.abs(maxY + 1 - eyeY));
		double dz = Math.max(Math.abs(minZ - eyeZ), Math.abs(maxZ + 1 - eyeZ));
		return dx * dx + dy * dy + dz * dz;
	}

	/** The world x of the area's centre, measured between its outer faces. */
	public double centerX() {
		return (minX + maxX + 1) / 2.0;
	}

	/** The world y of the area's centre, measured between its outer faces. */
	public double centerY() {
		return (minY + maxY + 1) / 2.0;
	}

	/** The world z of the area's centre, measured between its outer faces. */
	public double centerZ() {
		return (minZ + maxZ + 1) / 2.0;
	}

	/** The size and volume as shown next to the box, for example {@code "12×8×3 = 288"}: x × z × depth. */
	public String label() {
		return sizeX() + "×" + sizeZ() + "×" + sizeY() + " = " + volume();
	}
}
