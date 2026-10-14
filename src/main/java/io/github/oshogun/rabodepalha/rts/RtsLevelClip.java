package io.github.oshogun.rabodepalha.rts;

import org.jspecify.annotations.Nullable;

/**
 * Pure voxel traversal for picking in the RTS Level view: it walks the blocks a ray passes through and
 * skips every block above the view level. It has no Minecraft dependencies, so it can be unit-tested.
 *
 * <p>It is a 3D DDA (Amanatides and Woo) that visits voxels in the same order as vanilla's
 * {@code BlockGetter.traverseBlocks}, including its tiny nudge of both ends along the ray. Unlike a
 * normal clip it starts in the voxel that contains the start point even when that voxel is solid, so a
 * camera sitting inside terrain above the level still reaches a cave or room floor below it.
 */
public final class RtsLevelClip {
	/** How far the ends are nudged along the ray, as a fraction of its length, as vanilla does. */
	private static final double END_NUDGE = 1.0E-7;

	private RtsLevelClip() {
	}

	/** Checks one block on the ray; a non-null result stops the traversal. */
	@FunctionalInterface
	public interface Visitor<T> {
		@Nullable T visit(int x, int y, int z);
	}

	/**
	 * Walks the blocks from the start to the end point in ray order and returns the first non-null
	 * result of the visitor, or null when nothing is hit. Blocks with y above {@code viewLevel} are
	 * skipped without calling the visitor; pass {@link Integer#MAX_VALUE} to skip nothing.
	 */
	public static <T> @Nullable T traverse(double sx, double sy, double sz, double ex, double ey, double ez,
			int viewLevel, Visitor<T> visitor) {
		if (sx == ex && sy == ey && sz == ez) {
			return null;
		}
		double endX = lerp(-END_NUDGE, ex, sx);
		double endY = lerp(-END_NUDGE, ey, sy);
		double endZ = lerp(-END_NUDGE, ez, sz);
		double startX = lerp(-END_NUDGE, sx, ex);
		double startY = lerp(-END_NUDGE, sy, ey);
		double startZ = lerp(-END_NUDGE, sz, ez);

		int x = (int) Math.floor(startX);
		int y = (int) Math.floor(startY);
		int z = (int) Math.floor(startZ);
		T hit = visit(x, y, z, viewLevel, visitor);
		if (hit != null) {
			return hit;
		}

		double dx = endX - startX;
		double dy = endY - startY;
		double dz = endZ - startZ;
		int stepX = (int) Math.signum(dx);
		int stepY = (int) Math.signum(dy);
		int stepZ = (int) Math.signum(dz);
		double deltaX = stepX == 0 ? Double.MAX_VALUE : stepX / dx;
		double deltaY = stepY == 0 ? Double.MAX_VALUE : stepY / dy;
		double deltaZ = stepZ == 0 ? Double.MAX_VALUE : stepZ / dz;
		// A still axis never steps, even when its coordinate sits exactly on a block boundary.
		double tX = stepX == 0 ? Double.MAX_VALUE : deltaX * (stepX > 0 ? 1.0 - frac(startX) : frac(startX));
		double tY = stepY == 0 ? Double.MAX_VALUE : deltaY * (stepY > 0 ? 1.0 - frac(startY) : frac(startY));
		double tZ = stepZ == 0 ? Double.MAX_VALUE : deltaZ * (stepZ > 0 ? 1.0 - frac(startZ) : frac(startZ));

		while (tX <= 1.0 || tY <= 1.0 || tZ <= 1.0) {
			if (tX < tY) {
				if (tX < tZ) {
					x += stepX;
					tX += deltaX;
				} else {
					z += stepZ;
					tZ += deltaZ;
				}
			} else if (tY < tZ) {
				y += stepY;
				tY += deltaY;
			} else {
				z += stepZ;
				tZ += deltaZ;
			}
			hit = visit(x, y, z, viewLevel, visitor);
			if (hit != null) {
				return hit;
			}
		}
		return null;
	}

	private static <T> @Nullable T visit(int x, int y, int z, int viewLevel, Visitor<T> visitor) {
		return y > viewLevel ? null : visitor.visit(x, y, z);
	}

	private static double lerp(double t, double from, double to) {
		return from + t * (to - from);
	}

	private static double frac(double v) {
		return v - Math.floor(v);
	}
}
