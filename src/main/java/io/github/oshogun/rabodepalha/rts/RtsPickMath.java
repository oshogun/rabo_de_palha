package io.github.oshogun.rabodepalha.rts;

/**
 * Pure perspective maths for picking with the mouse in RTS mode: the world-space ray under the cursor,
 * and the screen position of a world point. It has no Minecraft dependencies, so it can be unit-tested.
 *
 * <p>It follows Minecraft's camera conventions: yaw 0 faces +Z and yaw 90 faces -X, pitch is positive
 * when looking down, and the FOV is the vertical one, in degrees. Screen coordinates are pixels from
 * the top-left corner, with y growing downwards. Both methods share one camera basis, so a point
 * projected to a pixel lies on the ray cast from that pixel.
 */
public final class RtsPickMath {
	/** Points at or nearer than this depth, in blocks along the view direction, are not projected. */
	public static final double NEAR_EPSILON = 0.05;

	private RtsPickMath() {
	}

	/** A position on screen, in pixels. */
	public record Projected(double x, double y) {
	}

	/** Camera axes in world space: forward, right and up, each a unit vector. */
	private record Basis(double[] forward, double[] right, double[] up) {
		static Basis of(float yawDeg, float pitchDeg) {
			double yaw = Math.toRadians(yawDeg);
			double pitch = Math.toRadians(pitchDeg);
			double sinYaw = Math.sin(yaw);
			double cosYaw = Math.cos(yaw);
			double sinPitch = Math.sin(pitch);
			double cosPitch = Math.cos(pitch);
			double[] forward = {-sinYaw * cosPitch, -sinPitch, cosYaw * cosPitch};
			double[] right = {-cosYaw, 0.0, -sinYaw};
			// up = right x forward
			double[] up = {
					right[1] * forward[2] - right[2] * forward[1],
					right[2] * forward[0] - right[0] * forward[2],
					right[0] * forward[1] - right[1] * forward[0]};
			return new Basis(forward, right, up);
		}
	}

	/**
	 * The unit world-space direction of the ray from the camera eye through the cursor at
	 * ({@code cursorX}, {@code cursorY}) on a screen of {@code screenW} by {@code screenH} pixels.
	 */
	public static double[] cursorRayDir(double cursorX, double cursorY, double screenW, double screenH,
			double fovYDeg, float yawDeg, float pitchDeg) {
		Basis basis = Basis.of(yawDeg, pitchDeg);
		double tanHalf = Math.tan(Math.toRadians(fovYDeg) / 2.0);
		double aspect = screenW / screenH;
		double ndcX = cursorX / screenW * 2.0 - 1.0;
		double ndcY = 1.0 - cursorY / screenH * 2.0;
		double sx = ndcX * tanHalf * aspect;
		double sy = ndcY * tanHalf;
		double[] dir = new double[3];
		for (int i = 0; i < 3; i++) {
			dir[i] = basis.forward[i] + basis.right[i] * sx + basis.up[i] * sy;
		}
		double len = Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]);
		for (int i = 0; i < 3; i++) {
			dir[i] /= len;
		}
		return dir;
	}

	/**
	 * The screen pixel of a world point given relative to the camera eye, or {@code null} when the point
	 * is behind the camera or too close to it. The result can lie outside the screen.
	 */
	public static Projected project(double relX, double relY, double relZ, double screenW, double screenH,
			double fovYDeg, float yawDeg, float pitchDeg) {
		Basis basis = Basis.of(yawDeg, pitchDeg);
		double depth = dot(basis.forward, relX, relY, relZ);
		if (depth <= NEAR_EPSILON) {
			return null;
		}
		double tanHalf = Math.tan(Math.toRadians(fovYDeg) / 2.0);
		double aspect = screenW / screenH;
		double ndcX = dot(basis.right, relX, relY, relZ) / depth / (tanHalf * aspect);
		double ndcY = dot(basis.up, relX, relY, relZ) / depth / tanHalf;
		return new Projected((ndcX + 1.0) / 2.0 * screenW, (1.0 - ndcY) / 2.0 * screenH);
	}

	private static double dot(double[] axis, double x, double y, double z) {
		return axis[0] * x + axis[1] * y + axis[2] * z;
	}
}
