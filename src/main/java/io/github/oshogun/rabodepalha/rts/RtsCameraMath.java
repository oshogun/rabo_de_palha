package io.github.oshogun.rabodepalha.rts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure maths for the RTS camera: panning, edge scroll, zoom, rotation and the height filter. It has no Minecraft
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
	/** Shallowest pitch the camera can tilt to, in degrees. */
	public static final float MIN_PITCH = 30.0f;
	/** Steepest pitch the camera can tilt to: straight down. */
	public static final float MAX_PITCH = 90.0f;
	/** Degrees of pitch tilted per client tick while a tilt key is held. */
	public static final float TILT_STEP_DEG = 2.0f;
	/** Degrees of pitch tilted per pixel of vertical middle-drag. */
	public static final float DRAG_TILT_DEG_PER_PIXEL = 0.3f;
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

	/**
	 * Clamps a pitch to [{@link #MIN_PITCH}, {@link #MAX_PITCH}]. Positive pitch looks down and 90 is
	 * straight down; NaN gives {@link #DEFAULT_PITCH}.
	 */
	public static float clampPitch(double pitchDeg) {
		if (Double.isNaN(pitchDeg)) {
			return DEFAULT_PITCH;
		}
		return (float) clamp(pitchDeg, MIN_PITCH, MAX_PITCH);
	}

	/** Pitch after tilting by {@code deltaDeg}; positive tilts towards straight down. */
	public static float tilt(float pitchDeg, double deltaDeg) {
		return clampPitch(pitchDeg + deltaDeg);
	}

	/*
	 * Orbit. Rotating, tilting and zooming keep the ground point at the screen centre (the pivot) fixed
	 * and move the camera around it, as RTS games do. Rotating in place would swing the view across
	 * lookAtDistance (about 140 blocks at 30 degrees and height 80); at 90 degrees the two coincide.
	 * The pivot lies on a flat plane at the camera's ground reference, {@code height} below it, so it
	 * only approximates the true screen-centre point on uneven terrain. Pitches are clamped here, so
	 * results are always finite.
	 */

	/** Ground point at the screen centre for a camera at ({@code camX}, {@code camZ}), on flat ground. */
	public static Pan lookAtPoint(double camX, double camZ, double height, float yawDeg, float pitchDeg) {
		double d = lookAtDistance(height, clampPitch(pitchDeg));
		double yaw = Math.toRadians(yawDeg);
		return new Pan(camX - Math.sin(yaw) * d, camZ + Math.cos(yaw) * d);
	}

	/** Camera position whose screen centre looks at ({@code pivotX}, {@code pivotZ}); inverse of {@link #lookAtPoint}. */
	public static Pan orbitPosition(double pivotX, double pivotZ, double height, float yawDeg, float pitchDeg) {
		double d = lookAtDistance(height, clampPitch(pitchDeg));
		double yaw = Math.toRadians(yawDeg);
		return new Pan(pivotX + Math.sin(yaw) * d, pivotZ - Math.cos(yaw) * d);
	}

	/**
	 * Camera displacement that keeps the pivot fixed when the pose changes from (height0, yaw0, pitch0)
	 * to (height1, yaw1, pitch1). It does not depend on the absolute position, so it can be accumulated.
	 */
	public static Pan orbitShift(double height0, float yaw0, float pitch0, double height1, float yaw1,
			float pitch1) {
		Pan p0 = lookAtPoint(0.0, 0.0, height0, yaw0, pitch0);
		Pan p1 = lookAtPoint(0.0, 0.0, height1, yaw1, pitch1);
		return new Pan(p0.x() - p1.x(), p0.z() - p1.z());
	}

	/** Radius of the ground sample, in blocks per block of camera height. */
	public static final double SAMPLE_RADIUS_PER_HEIGHT = 0.5;
	/** Smallest ground sample radius, in blocks. */
	public static final double MIN_SAMPLE_RADIUS = 4.0;
	/** Largest ground sample radius, in blocks. */
	public static final double MAX_SAMPLE_RADIUS = 24.0;
	/** Weighted percentile of the sampled heights used as the ground reference. */
	public static final double SAMPLE_PERCENTILE = 0.9;
	/** Weight of samples around the look-at point, relative to 1 for samples around the camera. */
	public static final double LOOK_AT_WEIGHT = 2.0;

	/** Minimum clearance, in blocks, kept above the highest sampled block near the camera. */
	public static final double MIN_CLEARANCE = 3.0;
	/** Ground reference changes up to this many blocks are ignored. */
	public static final double HEIGHT_DEAD_ZONE = 1.5;
	/** Smoothing time, in seconds, when the camera rises. */
	public static final double RISE_SMOOTH_TIME = 0.12;
	/** Smoothing time, in seconds, when the camera descends. */
	public static final double FALL_SMOOTH_TIME = 1.0;
	/** Longest time step, in seconds, the height filter integrates at once (guards against lag spikes). */
	public static final double MAX_STEP_DT = 0.25;

	/** A ground sample offset from the camera column, with its weight in the percentile. */
	public record Offset(double dx, double dz, double weight, boolean nearCamera) {
	}

	/**
	 * Smoothed camera height state. {@code ground} is the ground reference the camera currently follows;
	 * NaN fields mean the filter has not been seeded yet.
	 */
	public record HeightState(double y, double velocity, double ground) {
		public static final HeightState UNSET = new HeightState(Double.NaN, 0.0, Double.NaN);

		public boolean isSet() {
			return !Double.isNaN(y);
		}
	}

	/** Result of one {@link #smoothDamp} step. */
	public record Damped(double value, double velocity) {
	}

	/** Ground sample radius for a camera at {@code height}: grows with height, within bounds. */
	public static double sampleRadius(double height) {
		return clamp(clampZoom(height) * SAMPLE_RADIUS_PER_HEIGHT, MIN_SAMPLE_RADIUS, MAX_SAMPLE_RADIUS);
	}

	/** Grid step between samples for a sample {@code radius}, so the sample count stays bounded. */
	public static int sampleStep(double radius) {
		return Math.max(2, (int) Math.round(radius / 6.0));
	}

	/** Horizontal distance from the camera column to the point it looks at on flat ground. */
	public static double lookAtDistance(double height, float pitchDeg) {
		double tan = Math.tan(Math.toRadians(pitchDeg));
		return tan <= 0.0 ? 0.0 : height / tan;
	}

	/**
	 * Sample offsets for a camera at {@code height} facing {@code yawDeg}: a disc around the camera
	 * column with weight 1, plus a disc of the same size around the look-at point with weight
	 * {@link #LOOK_AT_WEIGHT}.
	 */
	public static List<Offset> sampleOffsets(double height, float yawDeg, float pitchDeg) {
		double radius = sampleRadius(height);
		int step = sampleStep(radius);
		double distance = lookAtDistance(clampZoom(height), pitchDeg);
		double yaw = Math.toRadians(yawDeg);
		double centreX = -Math.sin(yaw) * distance;
		double centreZ = Math.cos(yaw) * distance;
		List<Offset> offsets = new ArrayList<>();
		addDisc(offsets, 0.0, 0.0, radius, step, 1.0, true);
		addDisc(offsets, centreX, centreZ, radius, step, LOOK_AT_WEIGHT, false);
		return offsets;
	}

	private static void addDisc(List<Offset> out, double cx, double cz, double radius, int step, double weight,
			boolean nearCamera) {
		int n = (int) Math.floor(radius / step);
		double r2 = radius * radius;
		for (int i = -n; i <= n; i++) {
			for (int j = -n; j <= n; j++) {
				double dx = (double) i * step;
				double dz = (double) j * step;
				if (dx * dx + dz * dz <= r2) {
					out.add(new Offset(cx + dx, cz + dz, weight, nearCamera));
				}
			}
		}
	}

	/**
	 * Weighted percentile {@code p} in [0, 1] of {@code values}: the smallest value whose cumulative
	 * weight reaches {@code p} of the total. NaN values and non-positive weights are skipped; NaN when
	 * nothing is left.
	 */
	public static double weightedPercentile(double[] values, double[] weights, double p) {
		List<double[]> valid = new ArrayList<>();
		double total = 0.0;
		for (int i = 0; i < values.length; i++) {
			if (!Double.isNaN(values[i]) && weights[i] > 0.0) {
				valid.add(new double[] {values[i], weights[i]});
				total += weights[i];
			}
		}
		if (valid.isEmpty()) {
			return Double.NaN;
		}
		valid.sort(Comparator.comparingDouble(v -> v[0]));
		double threshold = clamp(p, 0.0, 1.0) * total;
		double cumulative = 0.0;
		for (double[] v : valid) {
			cumulative += v[1];
			if (cumulative >= threshold) {
				return v[0];
			}
		}
		return valid.get(valid.size() - 1)[0];
	}

	/** Largest non-NaN value, or NaN when there is none. */
	public static double maxFinite(double[] values) {
		double max = Double.NaN;
		for (double v : values) {
			if (!Double.isNaN(v) && (Double.isNaN(max) || v > max)) {
				max = v;
			}
		}
		return max;
	}

	/**
	 * One step of a critically damped spring from {@code current} towards {@code target}, which settles
	 * in about {@code smoothTime} seconds without overshooting. The result depends on elapsed time, not
	 * on how it is split into steps.
	 */
	public static Damped smoothDamp(double current, double velocity, double target, double smoothTime, double dt) {
		if (dt <= 0.0) {
			return new Damped(current, velocity);
		}
		double omega = 2.0 / Math.max(1e-4, smoothTime);
		double x = omega * dt;
		double exp = 1.0 / (1.0 + x + 0.48 * x * x + 0.235 * x * x * x);
		double change = current - target;
		double temp = (velocity + omega * change) * dt;
		double newVelocity = (velocity - omega * temp) * exp;
		double output = target + (change + temp) * exp;
		if ((target - current > 0.0) == (output > target)) {
			output = target;
			newVelocity = 0.0;
		}
		return new Damped(output, newVelocity);
	}

	/**
	 * Advances the camera height filter by {@code dt} seconds.
	 *
	 * @param prev       state from the previous tick, or {@link HeightState#UNSET}
	 * @param reference  ground reference from the sample, NaN when nothing sampled was loaded
	 * @param highest    highest sampled block near the camera, NaN when unknown
	 * @param zoomHeight camera height above the ground reference
	 * @param locked     whether the ground reference is frozen; the clearance still applies
	 */
	public static HeightState filterHeight(HeightState prev, double reference, double highest, double zoomHeight,
			double dt, boolean locked) {
		double step = Double.isNaN(dt) ? 0.0 : clamp(dt, 0.0, MAX_STEP_DT);
		if (!prev.isSet()) {
			if (Double.isNaN(reference)) {
				return HeightState.UNSET;
			}
			return withClearance(reference + zoomHeight, 0.0, reference, highest);
		}
		double ground = prev.ground();
		if (!locked && !Double.isNaN(reference) && Math.abs(reference - ground) > HEIGHT_DEAD_ZONE) {
			ground = reference;
		}
		double target = ground + zoomHeight;
		double smoothTime = target > prev.y() ? RISE_SMOOTH_TIME : FALL_SMOOTH_TIME;
		Damped damped = smoothDamp(prev.y(), prev.velocity(), target, smoothTime, step);
		return withClearance(damped.value(), damped.velocity(), ground, highest);
	}

	private static HeightState withClearance(double y, double velocity, double ground, double highest) {
		if (!Double.isNaN(highest) && y < highest + MIN_CLEARANCE) {
			return new HeightState(highest + MIN_CLEARANCE, Math.max(velocity, 0.0), ground);
		}
		return new HeightState(y, velocity, ground);
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}
}
