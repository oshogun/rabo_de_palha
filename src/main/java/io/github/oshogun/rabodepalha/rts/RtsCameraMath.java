package io.github.oshogun.rabodepalha.rts;

/**
 * Pure maths for the RTS camera: panning, edge scroll, zoom and rotation. It has no Minecraft
 * dependencies, so the client code stays thin and the numbers can be unit-tested.
 *
 * <p>Angles follow Minecraft's convention: yaw 0 faces +Z (south) and grows clockwise seen from
 * above, so yaw 90 faces -X (west). Pitch is positive when looking down.
 */
public final class RtsCameraMath {
	/** Lowest camera height above the ground, in blocks. */
	public static final double MIN_HEIGHT = 6.0;
	/** Highest camera height above the ground, in blocks. */
	public static final double MAX_HEIGHT = 80.0;
	/** Camera height used when entering RTS mode. */
	public static final double DEFAULT_HEIGHT = 24.0;
	/** Blocks of height changed per mouse-wheel notch. */
	public static final double ZOOM_STEP = 4.0;
	/** Downward pitch of the angled view, in degrees. */
	public static final float DEFAULT_PITCH = 60.0f;
	/** Degrees of yaw turned per client tick while Q or E is held. */
	public static final float ROTATE_STEP_DEG = 4.0f;
	/** Degrees of yaw turned per pixel of middle-drag. */
	public static final float DRAG_ROTATE_DEG_PER_PIXEL = 0.3f;
	/** Width of the screen border, in pixels, that triggers edge scroll. */
	public static final int EDGE_MARGIN_PX = 8;

	/** Pan speed bounds, in blocks per tick. The upper bound is the client-side UX cap. */
	public static final double MIN_PAN_SPEED = 0.4;
	public static final double MAX_PAN_SPEED = 4.0;
	/** Pan speed per block of camera height, so a higher camera covers ground faster. */
	public static final double PAN_SPEED_PER_HEIGHT = 0.06;

	private RtsCameraMath() {
	}

	/** A horizontal world-space displacement. */
	public record Pan(double x, double z) {
		public static final Pan ZERO = new Pan(0.0, 0.0);

		public boolean isZero() {
			return x == 0.0 && z == 0.0;
		}
	}

	/** Screen-relative pan input: {@code forward} moves up the screen, {@code right} to the right. */
	public record PanInput(double forward, double right) {
		public static final PanInput NONE = new PanInput(0.0, 0.0);

		/** Adds two inputs, clamping each axis to [-1, 1]. */
		public PanInput plus(PanInput other) {
			return new PanInput(clamp(forward + other.forward, -1.0, 1.0), clamp(right + other.right, -1.0, 1.0));
		}
	}

	/**
	 * Turns screen-relative input into a world-space displacement for a camera facing {@code yawDeg}.
	 * Input longer than 1 (a diagonal) is normalised so diagonals are not faster.
	 */
	public static Pan panVector(double forward, double right, float yawDeg, double speed) {
		double len = Math.sqrt(forward * forward + right * right);
		if (len == 0.0 || speed <= 0.0) {
			return Pan.ZERO;
		}
		if (len > 1.0) {
			forward /= len;
			right /= len;
		}
		double yaw = Math.toRadians(yawDeg);
		double sin = Math.sin(yaw);
		double cos = Math.cos(yaw);
		// Facing direction is (-sin, cos); its right-hand side is (-cos, -sin).
		double x = (-sin * forward - cos * right) * speed;
		double z = (cos * forward - sin * right) * speed;
		return new Pan(x, z);
	}

	/**
	 * Edge-scroll input for a cursor at ({@code mouseX}, {@code mouseY}) on a screen of the given size,
	 * with y growing downwards. Each axis is -1, 0 or 1. A cursor outside the screen counts as on the
	 * nearest edge; an empty screen yields no input.
	 */
	public static PanInput edgeScroll(double mouseX, double mouseY, int width, int height, int margin) {
		if (width <= 0 || height <= 0) {
			return PanInput.NONE;
		}
		double right = 0.0;
		if (mouseX < margin) {
			right = -1.0;
		} else if (mouseX >= width - margin) {
			right = 1.0;
		}
		double forward = 0.0;
		if (mouseY < margin) {
			forward = 1.0;
		} else if (mouseY >= height - margin) {
			forward = -1.0;
		}
		return new PanInput(forward, right);
	}

	/** Clamps a camera height to [{@link #MIN_HEIGHT}, {@link #MAX_HEIGHT}]. */
	public static double clampZoom(double height) {
		if (Double.isNaN(height)) {
			return DEFAULT_HEIGHT;
		}
		return clamp(height, MIN_HEIGHT, MAX_HEIGHT);
	}

	/** Height after scrolling the wheel by {@code wheelDelta} notches; scrolling up zooms in. */
	public static double zoom(double height, double wheelDelta) {
		return clampZoom(height - wheelDelta * ZOOM_STEP);
	}

	/** Pan speed in blocks per tick for a camera at {@code height}, capped at {@link #MAX_PAN_SPEED}. */
	public static double panSpeedForHeight(double height) {
		return clamp(clampZoom(height) * PAN_SPEED_PER_HEIGHT, MIN_PAN_SPEED, MAX_PAN_SPEED);
	}

	/** Wraps a yaw into [-180, 180). */
	public static float wrapYaw(float yawDeg) {
		float wrapped = yawDeg % 360.0f;
		if (wrapped >= 180.0f) {
			wrapped -= 360.0f;
		} else if (wrapped < -180.0f) {
			wrapped += 360.0f;
		}
		return wrapped;
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}
}
