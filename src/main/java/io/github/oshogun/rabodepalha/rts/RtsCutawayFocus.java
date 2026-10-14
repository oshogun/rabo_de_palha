package io.github.oshogun.rabodepalha.rts;

import org.jspecify.annotations.Nullable;

/**
 * Pure rules that keep the moving cutaway shapes steady, so they re-mesh rarely (docs/design/cutaway.md §4
 * and §7): the bubble's anchor snaps to a coarse grid with hysteresis, and the camera yaw and pitch are
 * quantized. It has no Minecraft dependencies, so it can be unit-tested.
 */
public final class RtsCutawayFocus {
	/** The anchor grid, in blocks. */
	public static final int GRID = 4;
	/** How far past the grid cell the raw point may move before the anchor follows, in blocks. */
	public static final int HYSTERESIS = 2;
	/** How many facings the camera yaw is quantized to. */
	public static final int FACINGS = 8;
	/** Extra degrees the yaw must pass a facing's edge before the facing changes. */
	public static final float YAW_HYSTERESIS_DEG = 5.0f;
	/** The bubble's horizontal lean per block of height, from looking straight down to shallow views. */
	static final double[] SLOPES = {0.0, 0.5, 1.0, 2.0};
	/** Bubble radius, in blocks. */
	public static final double RADIUS = 5.0;
	/** Bubble height above its anchor, in blocks. */
	public static final int HEIGHT = 24;

	/** Which side of a box the camera is on, per facing: -1 the min side, 1 the max side, 0 neither. */
	private static final int[] CAMERA_SIDE_X = {0, 1, 1, 1, 0, -1, -1, -1};
	private static final int[] CAMERA_SIDE_Z = {-1, -1, 0, 1, 1, 1, 0, -1};
	private static final double DIAGONAL = Math.sqrt(0.5);

	private RtsCutawayFocus() {
	}

	/** A debounced block position a moving shape is built around. */
	public record Anchor(int x, int y, int z) {
	}

	/**
	 * The anchor for the raw block {@code rawX, rawY, rawZ}. It keeps {@code prev} while the raw point stays
	 * within {@link #GRID} blocks of it ({@link #HYSTERESIS} past its grid cell) on x and z, and while
	 * {@code prev} is not below the raw point and within two grid steps above it; otherwise it jumps to the
	 * raw point snapped to the grid. Y snaps up, so the anchor is never below the raw block: the bubble never
	 * hides the block under the cursor.
	 */
	public static Anchor anchor(@Nullable Anchor prev, int rawX, int rawY, int rawZ) {
		if (prev != null && Math.abs(rawX - prev.x) <= GRID / 2 + HYSTERESIS
				&& Math.abs(rawZ - prev.z) <= GRID / 2 + HYSTERESIS
				&& prev.y >= rawY && prev.y - rawY < 2 * GRID) {
			return prev;
		}
		return new Anchor(snap(rawX), Math.floorDiv(rawY + GRID - 1, GRID) * GRID, snap(rawZ));
	}

	private static int snap(int v) {
		return Math.floorDiv(v + GRID / 2, GRID) * GRID;
	}

	/**
	 * The camera yaw quantized to one of {@link #FACINGS} facings, 0 for yaw 0 (looking south, +z) and
	 * counting with the yaw in 45-degree steps. It keeps {@code prevFacing} (-1 for none) until the yaw is
	 * {@link #YAW_HYSTERESIS_DEG} past its edge.
	 */
	public static int quantizeYaw(int prevFacing, float yawDeg) {
		float step = 360.0f / FACINGS;
		if (prevFacing >= 0 && prevFacing < FACINGS
				&& Math.abs(wrapDeg(yawDeg - prevFacing * step)) <= step / 2 + YAW_HYSTERESIS_DEG) {
			return prevFacing;
		}
		return Math.floorMod(Math.round(yawDeg / step), FACINGS);
	}

	private static float wrapDeg(float deg) {
		float d = deg % 360.0f;
		if (d >= 180.0f) {
			d -= 360.0f;
		} else if (d < -180.0f) {
			d += 360.0f;
		}
		return d;
	}

	/**
	 * How far the bubble leans toward the camera per block of height, for a camera pitched
	 * {@code pitchDeg} below the horizon: cot(pitch), snapped to the nearest of a few steps so tilting
	 * rarely re-meshes. Shallower views lean more.
	 */
	public static double quantizeSlope(float pitchDeg) {
		double pitch = Math.toRadians(Math.max(1.0f, Math.min(90.0f, pitchDeg)));
		double raw = Math.cos(pitch) / Math.sin(pitch);
		double best = SLOPES[0];
		for (double slope : SLOPES) {
			if (Math.abs(slope - raw) < Math.abs(best - raw)) {
				best = slope;
			}
		}
		return best;
	}

	/** The side of a box the camera is on along x for {@code facing}: -1 the min side, 1 the max side, 0 neither. */
	public static int cameraSideX(int facing) {
		return CAMERA_SIDE_X[Math.floorMod(facing, FACINGS)];
	}

	/** The side of a box the camera is on along z for {@code facing}. */
	public static int cameraSideZ(int facing) {
		return CAMERA_SIDE_Z[Math.floorMod(facing, FACINGS)];
	}

	/** The see-through bubble from {@code anchor} up, leaning toward a camera at {@code facing} by {@code slope}. */
	public static CutawayShape.Bubble bubbleFor(Anchor anchor, int facing, double slope) {
		int sx = cameraSideX(facing);
		int sz = cameraSideZ(facing);
		double unit = sx != 0 && sz != 0 ? DIAGONAL : 1.0;
		return new CutawayShape.Bubble(anchor.x(), anchor.y(), anchor.z(), RADIUS, HEIGHT, sx * unit * slope,
				sz * unit * slope);
	}
}
