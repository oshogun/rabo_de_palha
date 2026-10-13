package io.github.oshogun.rabodepalha.rts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RtsPickMathTest {
	private static final double EPS = 1e-6;
	private static final double W = 1280;
	private static final double H = 720;
	private static final double FOV = 70.0;
	private static final float[][] ANGLES = {
			{0.0f, 0.0f}, {90.0f, 0.0f}, {37.0f, RtsCameraMath.DEFAULT_PITCH}, {-150.0f, 30.0f},
			{180.0f, RtsCameraMath.DEFAULT_PITCH}};

	private static double[] forward(float yawDeg, float pitchDeg) {
		double yaw = Math.toRadians(yawDeg);
		double pitch = Math.toRadians(pitchDeg);
		return new double[] {-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch)};
	}

	@Test
	void centreRayIsForward() {
		for (float[] a : ANGLES) {
			double[] dir = RtsPickMath.cursorRayDir(W / 2, H / 2, W, H, FOV, a[0], a[1]);
			double[] expected = forward(a[0], a[1]);

			for (int i = 0; i < 3; i++) {
				assertEquals(expected[i], dir[i], EPS);
			}
		}
	}

	@Test
	void forwardPointProjectsToCentre() {
		for (float[] a : ANGLES) {
			double[] f = forward(a[0], a[1]);
			RtsPickMath.Projected p = RtsPickMath.project(f[0] * 10, f[1] * 10, f[2] * 10, W, H, FOV, a[0], a[1]);

			assertNotNull(p);
			assertEquals(W / 2, p.x(), EPS);
			assertEquals(H / 2, p.y(), EPS);
		}
	}

	@Test
	void rayThenProjectRoundTrips() {
		double[][] pixels = {{0, 0}, {W - 1, H - 1}, {100, 600}, {1000, 50}, {640, 10}};
		for (float[] a : ANGLES) {
			for (double[] px : pixels) {
				double[] dir = RtsPickMath.cursorRayDir(px[0], px[1], W, H, FOV, a[0], a[1]);
				RtsPickMath.Projected p = RtsPickMath.project(dir[0] * 25, dir[1] * 25, dir[2] * 25, W, H, FOV,
						a[0], a[1]);

				assertNotNull(p);
				assertEquals(px[0], p.x(), 1e-4);
				assertEquals(px[1], p.y(), 1e-4);
			}
		}
	}

	@Test
	void topOfScreenRayPointsAboveForward() {
		double[] dir = RtsPickMath.cursorRayDir(W / 2, 0, W, H, FOV, 0.0f, 0.0f);

		assertTrue(dir[1] > 0.0);
		assertEquals(Math.tan(Math.toRadians(FOV / 2)), dir[1] / dir[2], EPS);
	}

	@Test
	void pointBehindCameraIsRejected() {
		double[] f = forward(37.0f, RtsCameraMath.DEFAULT_PITCH);

		assertNull(RtsPickMath.project(-f[0] * 5, -f[1] * 5, -f[2] * 5, W, H, FOV, 37.0f, RtsCameraMath.DEFAULT_PITCH));
		assertNull(RtsPickMath.project(0, 0, 0, W, H, FOV, 37.0f, RtsCameraMath.DEFAULT_PITCH));
	}

	@Test
	void pointToTheRightProjectsRightOfCentre() {
		// At yaw 0 the camera faces +Z, and its right-hand side is -X.
		RtsPickMath.Projected p = RtsPickMath.project(-2, 0, 10, W, H, FOV, 0.0f, 0.0f);

		assertNotNull(p);
		assertTrue(p.x() > W / 2);
		assertEquals(H / 2, p.y(), EPS);
	}

	@Test
	void pointBelowProjectsBelowCentre() {
		RtsPickMath.Projected p = RtsPickMath.project(0, -2, 10, W, H, FOV, 0.0f, 0.0f);

		assertNotNull(p);
		assertTrue(p.y() > H / 2);
	}
}
