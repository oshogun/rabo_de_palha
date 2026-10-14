package io.github.oshogun.rabodepalha.rts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class RtsCameraMathTest {
	private static final double EPS = 1e-9;

	@Test
	void forwardAtYawZeroMovesSouth() {
		RtsCameraMath.Pan pan = RtsCameraMath.panVector(1.0, 0.0, 0.0f, 2.0);

		assertEquals(0.0, pan.x(), EPS);
		assertEquals(2.0, pan.z(), EPS);
	}

	@Test
	void rightAtYawZeroMovesWest() {
		RtsCameraMath.Pan pan = RtsCameraMath.panVector(0.0, 1.0, 0.0f, 1.0);

		assertEquals(-1.0, pan.x(), EPS);
		assertEquals(0.0, pan.z(), EPS);
	}

	@Test
	void forwardAtYawNinetyMovesWest() {
		RtsCameraMath.Pan pan = RtsCameraMath.panVector(1.0, 0.0, 90.0f, 1.0);

		assertEquals(-1.0, pan.x(), EPS);
		assertEquals(0.0, pan.z(), EPS);
	}

	@Test
	void diagonalIsNotFaster() {
		RtsCameraMath.Pan pan = RtsCameraMath.panVector(1.0, 1.0, 37.0f, 3.0);

		assertEquals(3.0, Math.hypot(pan.x(), pan.z()), EPS);
	}

	@Test
	void noInputOrNoSpeedGivesZero() {
		assertTrue(RtsCameraMath.panVector(0.0, 0.0, 45.0f, 2.0).isZero());
		assertTrue(RtsCameraMath.panVector(1.0, 0.0, 45.0f, 0.0).isZero());
	}

	@Test
	void edgeScrollCorners() {
		int margin = RtsCameraMath.EDGE_MARGIN_PX;

		assertEquals(new RtsCameraMath.PanInput(1.0, -1.0), RtsCameraMath.edgeScroll(0, 0, 800, 600, margin));
		assertEquals(new RtsCameraMath.PanInput(-1.0, 1.0), RtsCameraMath.edgeScroll(799, 599, 800, 600, margin));
	}

	@Test
	void edgeScrollCentreAndOutside() {
		int margin = RtsCameraMath.EDGE_MARGIN_PX;

		assertEquals(RtsCameraMath.PanInput.NONE, RtsCameraMath.edgeScroll(400, 300, 800, 600, margin));
		assertEquals(new RtsCameraMath.PanInput(0.0, -1.0), RtsCameraMath.edgeScroll(-50, 300, 800, 600, margin));
		assertEquals(new RtsCameraMath.PanInput(-1.0, 0.0), RtsCameraMath.edgeScroll(400, 900, 800, 600, margin));
	}

	@Test
	void edgeScrollOnEmptyScreenIsNone() {
		assertEquals(RtsCameraMath.PanInput.NONE, RtsCameraMath.edgeScroll(0, 0, 0, 0, 8));
	}

	@Test
	void panInputPlusClamps() {
		RtsCameraMath.PanInput sum = new RtsCameraMath.PanInput(1.0, -1.0).plus(new RtsCameraMath.PanInput(1.0, -1.0));

		assertEquals(new RtsCameraMath.PanInput(1.0, -1.0), sum);
	}

	@Test
	void clampZoomBounds() {
		assertEquals(6.0, RtsCameraMath.clampZoom(1.0), EPS);
		assertEquals(80.0, RtsCameraMath.clampZoom(500.0), EPS);
		assertEquals(30.0, RtsCameraMath.clampZoom(30.0), EPS);
		assertEquals(RtsCameraMath.DEFAULT_HEIGHT, RtsCameraMath.clampZoom(Double.NaN), EPS);
	}

	@Test
	void wheelUpZoomsIn() {
		assertEquals(20.0, RtsCameraMath.zoom(24.0, 1.0), EPS);
		assertEquals(28.0, RtsCameraMath.zoom(24.0, -1.0), EPS);
		assertEquals(RtsCameraMath.MIN_HEIGHT, RtsCameraMath.zoom(8.0, 10.0), EPS);
	}

	@Test
	void panSpeedGrowsWithHeightAndIsCapped() {
		assertEquals(RtsCameraMath.MIN_PAN_SPEED, RtsCameraMath.panSpeedForHeight(RtsCameraMath.MIN_HEIGHT), EPS);
		assertEquals(24.0 * RtsCameraMath.PAN_SPEED_PER_HEIGHT, RtsCameraMath.panSpeedForHeight(24.0), EPS);
		assertEquals(RtsCameraMath.MAX_PAN_SPEED, RtsCameraMath.panSpeedForHeight(RtsCameraMath.MAX_HEIGHT), EPS);
		assertEquals(RtsCameraMath.MAX_PAN_SPEED, RtsCameraMath.panSpeedForHeight(10_000.0), EPS);
	}

	@Test
	void wrapYawRange() {
		assertEquals(-170.0f, RtsCameraMath.wrapYaw(190.0f), 1e-4f);
		assertEquals(170.0f, RtsCameraMath.wrapYaw(-190.0f), 1e-4f);
		assertEquals(-180.0f, RtsCameraMath.wrapYaw(180.0f), 1e-4f);
		assertEquals(45.0f, RtsCameraMath.wrapYaw(45.0f + 720.0f), 1e-4f);
	}

	private static final double TICK = 0.05;
	private static final double ZOOM = 24.0;

	/** Seeds the filter at {@code ground} and runs {@code ticks} ticks against a fixed sample. */
	private static RtsCameraMath.HeightState run(RtsCameraMath.HeightState state, double reference, double highest,
			int ticks) {
		for (int i = 0; i < ticks; i++) {
			state = RtsCameraMath.filterHeight(state, reference, highest, ZOOM, TICK, false);
		}
		return state;
	}

	private static RtsCameraMath.HeightState seeded(double ground) {
		return RtsCameraMath.filterHeight(RtsCameraMath.HeightState.UNSET, ground, ground, ZOOM, TICK, false);
	}

	/** Heights for the given offsets over flat ground at {@code flat}, raised to {@code tall} inside the box. */
	private static double[] terrain(List<RtsCameraMath.Offset> offsets, double flat, double tall, double minX,
			double maxX, double minZ, double maxZ) {
		double[] heights = new double[offsets.size()];
		for (int i = 0; i < offsets.size(); i++) {
			RtsCameraMath.Offset o = offsets.get(i);
			boolean inside = o.dx() >= minX && o.dx() <= maxX && o.dz() >= minZ && o.dz() <= maxZ;
			heights[i] = inside ? tall : flat;
		}
		return heights;
	}

	private static double[] weights(List<RtsCameraMath.Offset> offsets) {
		return offsets.stream().mapToDouble(RtsCameraMath.Offset::weight).toArray();
	}

	@Test
	void singleTallColumnBarelyMovesReference() {
		List<RtsCameraMath.Offset> offsets = RtsCameraMath.sampleOffsets(ZOOM, 0.0f, RtsCameraMath.DEFAULT_PITCH);
		double[] column = terrain(offsets, 64.0, 90.0, 0.0, 0.0, 0.0, 0.0);
		double[] tree = terrain(offsets, 64.0, 72.0, -2.0, 2.0, -2.0, 2.0);

		double columnRef = RtsCameraMath.weightedPercentile(column, weights(offsets), RtsCameraMath.SAMPLE_PERCENTILE);
		double treeRef = RtsCameraMath.weightedPercentile(tree, weights(offsets), RtsCameraMath.SAMPLE_PERCENTILE);
		assertEquals(64.0, columnRef, EPS);
		assertEquals(64.0, treeRef, EPS);

		// High enough zoom that the clearance above the column does not bind.
		double zoom = 40.0;
		RtsCameraMath.HeightState state = RtsCameraMath.filterHeight(RtsCameraMath.HeightState.UNSET, 64.0, 64.0, zoom,
				TICK, false);
		double startY = state.y();
		for (int i = 0; i < 100; i++) {
			state = RtsCameraMath.filterHeight(state, columnRef, 90.0, zoom, TICK, false);
			assertTrue(state.y() >= 90.0 + RtsCameraMath.MIN_CLEARANCE);
		}
		assertTrue(Math.abs(state.y() - startY) < 1.0);
	}

	@Test
	void stepUpRaisesCamera() {
		RtsCameraMath.HeightState state = seeded(64.0);
		for (int i = 0; i < 10; i++) {
			state = RtsCameraMath.filterHeight(state, 74.0, 74.0, ZOOM, TICK, false);
			assertTrue(state.y() >= 74.0 + RtsCameraMath.MIN_CLEARANCE);
		}
		assertEquals(74.0 + ZOOM, state.y(), 0.5);
	}

	@Test
	void dipLowersSlowly() {
		RtsCameraMath.HeightState start = seeded(64.0);
		double startY = start.y();
		double drop = startY - (50.0 + ZOOM);

		RtsCameraMath.HeightState state = run(start, 50.0, 50.0, 1);
		assertTrue(startY - state.y() < 1.0);
		state = run(state, 50.0, 50.0, 9);
		assertTrue(startY - state.y() < drop / 2.0);
		state = run(state, 50.0, 50.0, 90);
		assertEquals(50.0 + ZOOM, state.y(), 0.5);
	}

	@Test
	void smallBumpInsideDeadZoneDoesNotMoveCamera() {
		RtsCameraMath.HeightState start = seeded(64.0);

		RtsCameraMath.HeightState state = run(start, 65.0, 64.0, 20);

		assertEquals(start.ground(), state.ground(), EPS);
		assertEquals(start.y(), state.y(), EPS);
	}

	@Test
	void clearanceAlwaysKept() {
		double[][] samples = {
			{64, 64}, {64, 100}, {90, 95}, {40, 120}, {Double.NaN, 70}, {64, 64}, {30, 30}, {200, 205}, {64, 150},
		};
		for (boolean locked : new boolean[] {false, true}) {
			RtsCameraMath.HeightState state = seeded(64.0);
			for (double[] sample : samples) {
				for (int i = 0; i < 5; i++) {
					state = RtsCameraMath.filterHeight(state, sample[0], sample[1], RtsCameraMath.MIN_HEIGHT, TICK,
							locked);
					assertTrue(state.y() >= sample[1] + RtsCameraMath.MIN_CLEARANCE,
							"y " + state.y() + " below clearance over " + sample[1] + " locked=" + locked);
				}
			}
		}
	}

	@Test
	void unloadedReferenceKeepsLastHeight() {
		RtsCameraMath.HeightState start = seeded(64.0);

		RtsCameraMath.HeightState state = run(start, Double.NaN, Double.NaN, 20);

		assertEquals(64.0, state.ground(), EPS);
		assertEquals(start.y(), state.y(), EPS);
		assertEquals(RtsCameraMath.HeightState.UNSET,
				RtsCameraMath.filterHeight(RtsCameraMath.HeightState.UNSET, Double.NaN, Double.NaN, ZOOM, TICK, false));
	}

	@Test
	void lockedKeepsTarget() {
		RtsCameraMath.HeightState state = seeded(64.0);
		for (int i = 0; i < 40; i++) {
			state = RtsCameraMath.filterHeight(state, 50.0, 50.0, ZOOM, TICK, true);
		}

		assertEquals(64.0, state.ground(), EPS);
		assertEquals(64.0 + ZOOM, state.y(), EPS);
	}

	/** Runs {@code ticks} ticks over flat ground at {@code ground} with the given zoom height. */
	private static RtsCameraMath.HeightState runZoom(RtsCameraMath.HeightState state, double ground, double highest,
			double zoomHeight, int ticks, boolean locked) {
		for (int i = 0; i < ticks; i++) {
			state = RtsCameraMath.filterHeight(state, ground, highest, zoomHeight, TICK, locked);
		}
		return state;
	}

	@Test
	void zoomInReachesTargetQuickly() {
		double target = ZOOM / 2.0;

		RtsCameraMath.HeightState state = runZoom(seeded(64.0), 64.0, 64.0, target, 4, false);

		assertEquals(64.0 + target, state.y(), 0.1 * target);
	}

	@Test
	void zoomOutReachesTargetQuickly() {
		double target = ZOOM * 2.0;

		RtsCameraMath.HeightState state = runZoom(seeded(64.0), 64.0, 64.0, target, 4, false);

		assertEquals(64.0 + target, state.y(), 0.1 * target);
	}

	@Test
	void zoomDoesNotTouchGroundSmoothing() {
		RtsCameraMath.HeightState start = seeded(64.0);

		RtsCameraMath.HeightState state = runZoom(start, 64.0, 64.0, ZOOM / 2.0, 1, false);

		assertEquals(start.ground(), state.ground(), EPS);
		assertEquals(start.base(), state.base(), EPS);
		assertEquals(0.0, state.baseVelocity(), EPS);
	}

	@Test
	void clearanceHoldsWhenZoomingInOverTallBlock() {
		double highest = 64.0 + 20.0;
		for (boolean locked : new boolean[] {false, true}) {
			RtsCameraMath.HeightState state = seeded(64.0);
			for (int i = 0; i < 40; i++) {
				state = RtsCameraMath.filterHeight(state, 64.0, highest, RtsCameraMath.MIN_HEIGHT, TICK, locked);
				assertTrue(state.y() >= highest + RtsCameraMath.MIN_CLEARANCE,
						"y " + state.y() + " below clearance, locked=" + locked);
			}
		}
	}

	@Test
	void dipStillSlowWhileZoomed() {
		RtsCameraMath.HeightState start = seeded(64.0);

		RtsCameraMath.HeightState state = runZoom(start, 50.0, 50.0, ZOOM / 2.0, 10, false);

		assertTrue(64.0 - state.base() < 14.0 / 2.0);
		assertEquals(ZOOM / 2.0, state.zoom(), 0.1);
	}

	@Test
	void smoothDampIsTimeBased() {
		RtsCameraMath.Damped half = RtsCameraMath.smoothDamp(100.0, 0.0, 80.0, 1.0, 0.025);
		RtsCameraMath.Damped twoHalves = RtsCameraMath.smoothDamp(half.value(), half.velocity(), 80.0, 1.0, 0.025);
		RtsCameraMath.Damped whole = RtsCameraMath.smoothDamp(100.0, 0.0, 80.0, 1.0, 0.05);

		assertEquals(whole.value(), twoHalves.value(), 1e-3);
		assertEquals(whole.velocity(), twoHalves.velocity(), 1e-2);
	}

	@Test
	void smoothDampDoesNotOvershoot() {
		RtsCameraMath.Damped damped = RtsCameraMath.smoothDamp(70.0, 50.0, 80.0, 0.12, 0.25);

		assertTrue(damped.value() <= 80.0);
	}

	@Test
	void largeDtIsClamped() {
		RtsCameraMath.HeightState start = seeded(64.0);

		RtsCameraMath.HeightState huge = RtsCameraMath.filterHeight(start, 30.0, 30.0, ZOOM, 10.0, false);
		RtsCameraMath.HeightState capped = RtsCameraMath.filterHeight(start, 30.0, 30.0, ZOOM,
				RtsCameraMath.MAX_STEP_DT, false);

		assertEquals(capped.y(), huge.y(), EPS);
		assertTrue(huge.y() > 30.0 + ZOOM + 10.0);
	}

	@Test
	void sampleRadiusGrowsWithHeightAndIsCapped() {
		assertEquals(RtsCameraMath.MIN_SAMPLE_RADIUS, RtsCameraMath.sampleRadius(RtsCameraMath.MIN_HEIGHT), EPS);
		assertEquals(24.0 * RtsCameraMath.SAMPLE_RADIUS_PER_HEIGHT, RtsCameraMath.sampleRadius(24.0), EPS);
		assertEquals(RtsCameraMath.MAX_SAMPLE_RADIUS, RtsCameraMath.sampleRadius(RtsCameraMath.MAX_HEIGHT), EPS);
		assertTrue(RtsCameraMath.sampleRadius(30.0) > RtsCameraMath.sampleRadius(20.0));
	}

	@Test
	void sampleOffsetsIncludeLookAtArea() {
		List<RtsCameraMath.Offset> offsets = RtsCameraMath.sampleOffsets(ZOOM, 0.0f, RtsCameraMath.DEFAULT_PITCH);
		double distance = RtsCameraMath.lookAtDistance(ZOOM, RtsCameraMath.DEFAULT_PITCH);

		assertEquals(ZOOM / Math.tan(Math.toRadians(60.0)), distance, EPS);
		assertTrue(offsets.stream().anyMatch(o -> o.dx() == 0.0 && o.dz() == 0.0 && o.nearCamera()));
		assertTrue(offsets.stream().anyMatch(o -> !o.nearCamera() && o.weight() == RtsCameraMath.LOOK_AT_WEIGHT
				&& Math.abs(o.dx()) < EPS && Math.abs(o.dz() - distance) < EPS));
		double lookAtMeanZ = offsets.stream().filter(o -> !o.nearCamera()).mapToDouble(RtsCameraMath.Offset::dz)
				.average().orElseThrow();
		assertEquals(distance, lookAtMeanZ, 1e-6);
		assertTrue(offsets.size() < 300);
	}

	@Test
	void weightedPercentileIgnoresNaN() {
		double[] values = {Double.NaN, 1.0, 2.0, 3.0, 100.0};
		double[] weights = {50.0, 1.0, 1.0, 1.0, 1.0};

		assertEquals(3.0, RtsCameraMath.weightedPercentile(values, weights, 0.75), EPS);
		assertEquals(100.0, RtsCameraMath.weightedPercentile(values, weights, 1.0), EPS);
		assertEquals(1.0, RtsCameraMath.weightedPercentile(values, weights, 0.0), EPS);
		assertTrue(Double.isNaN(RtsCameraMath.weightedPercentile(new double[] {Double.NaN}, new double[] {1.0}, 0.5)));
		assertEquals(100.0, RtsCameraMath.maxFinite(values), EPS);
		assertTrue(Double.isNaN(RtsCameraMath.maxFinite(new double[] {Double.NaN})));
	}

	@Test
	void clampPitchBounds() {
		assertEquals(30.0, RtsCameraMath.clampPitch(10.0), EPS);
		assertEquals(30.0, RtsCameraMath.clampPitch(30.0), EPS);
		assertEquals(45.0, RtsCameraMath.clampPitch(45.0), EPS);
		assertEquals(90.0, RtsCameraMath.clampPitch(90.0), EPS);
		assertEquals(90.0, RtsCameraMath.clampPitch(120.0), EPS);
		assertEquals(RtsCameraMath.DEFAULT_PITCH, RtsCameraMath.clampPitch(Double.NaN), EPS);
	}

	@Test
	void tiltAddsAndClamps() {
		assertEquals(65.0, RtsCameraMath.tilt(60.0f, 5.0), EPS);
		assertEquals(55.0, RtsCameraMath.tilt(60.0f, -5.0), EPS);
		assertEquals(90.0, RtsCameraMath.tilt(88.0f, 5.0), EPS);
		assertEquals(30.0, RtsCameraMath.tilt(32.0f, -5.0), EPS);
	}

	@Test
	void lookAtPointMatchesLookAtDistance() {
		double d = 24.0 / Math.tan(Math.toRadians(60.0));
		RtsCameraMath.Pan south = RtsCameraMath.lookAtPoint(10.0, 20.0, 24.0, 0.0f, 60.0f);
		RtsCameraMath.Pan west = RtsCameraMath.lookAtPoint(10.0, 20.0, 24.0, 90.0f, 60.0f);

		assertEquals(10.0, south.x(), EPS);
		assertEquals(20.0 + d, south.z(), EPS);
		assertEquals(10.0 - d, west.x(), EPS);
		assertEquals(20.0, west.z(), EPS);
	}

	@Test
	void orbitPositionRoundTrips() {
		float[] pitches = {30.0f, 45.0f, 60.0f, 90.0f};
		double[] heights = {6.0, 24.0, 80.0};
		for (float yaw = -180.0f; yaw <= 180.0f; yaw += 45.0f) {
			for (float pitch : pitches) {
				for (double height : heights) {
					RtsCameraMath.Pan cam = RtsCameraMath.orbitPosition(5.0, -7.0, height, yaw, pitch);
					RtsCameraMath.Pan pivot = RtsCameraMath.lookAtPoint(cam.x(), cam.z(), height, yaw, pitch);

					assertEquals(5.0, pivot.x(), 1e-6);
					assertEquals(-7.0, pivot.z(), 1e-6);
				}
			}
		}
	}

	@Test
	void orbitAtStraightDownIsRotateInPlace() {
		RtsCameraMath.Pan shift = RtsCameraMath.orbitShift(80.0, 0.0f, 90.0f, 80.0, 135.0f, 90.0f);

		assertEquals(0.0, shift.x(), EPS);
		assertEquals(0.0, shift.z(), EPS);
	}

	@Test
	void orbitShiftIdentityIsZero() {
		assertTrue(RtsCameraMath.orbitShift(24.0, 30.0f, 60.0f, 24.0, 30.0f, 60.0f).isZero());
	}

	@Test
	void rotateKeepsPivotFixed() {
		assertPivotFixed(24.0, 10.0f, 45.0f, 24.0, 100.0f, 45.0f);
	}

	@Test
	void tiltKeepsPivotFixed() {
		assertPivotFixed(40.0, -30.0f, 60.0f, 40.0, -30.0f, 30.0f);
	}

	@Test
	void zoomKeepsPivotFixed() {
		assertPivotFixed(24.0, 70.0f, 35.0f, 60.0, 70.0f, 35.0f);
	}

	/** Seeds the filter at {@code ground} and lets it settle there. */
	private static RtsCameraMath.HeightState settled(double ground) {
		return run(seeded(ground), ground, ground, 40);
	}

	@Test
	void levelStepDownOfOneMovesCamera() {
		RtsCameraMath.HeightState start = settled(64.0);

		RtsCameraMath.HeightState rebased = RtsCameraMath.rebaseGround(start, 63.0);
		assertEquals(start.y(), rebased.y(), EPS);
		assertEquals(63.0, rebased.ground(), EPS);

		RtsCameraMath.HeightState state = run(rebased, 63.0, 63.0, 10);
		assertEquals(63.0 + ZOOM, state.y(), 0.05);
		assertEquals(0.0, state.level(), 0.05);
	}

	@Test
	void levelStepDownOfTenIsQuick() {
		RtsCameraMath.HeightState state = RtsCameraMath.rebaseGround(settled(64.0), 54.0);

		state = run(state, 54.0, 54.0, 6);

		assertEquals(54.0 + ZOOM, state.y(), 1.0);
	}

	@Test
	void levelStepUpOfTenIsQuick() {
		RtsCameraMath.HeightState state = RtsCameraMath.rebaseGround(settled(64.0), 74.0);

		state = run(state, 74.0, 74.0, 6);

		assertEquals(74.0 + ZOOM, state.y(), 1.0);
	}

	@Test
	void terrainBumpAtFixedLevelKeepsDeadZone() {
		RtsCameraMath.HeightState start = settled(64.0);

		RtsCameraMath.HeightState state = run(start, 65.0, 64.0, 40);

		assertEquals(start.ground(), state.ground(), EPS);
		assertEquals(start.y(), state.y(), EPS);
		assertEquals(0.0, state.level(), EPS);
	}

	@Test
	void levelChangeKeepsClearance() {
		RtsCameraMath.HeightState state = RtsCameraMath.rebaseGround(settled(64.0), 40.0);
		for (int i = 0; i < 40; i++) {
			state = RtsCameraMath.filterHeight(state, 40.0, 64.0, ZOOM, TICK, false);
			assertTrue(state.y() >= 64.0 + RtsCameraMath.MIN_CLEARANCE, "y " + state.y() + " below clearance");
		}
	}

	@Test
	void rebaseIgnoresNaNAndUnset() {
		RtsCameraMath.HeightState start = settled(64.0);

		assertEquals(start, RtsCameraMath.rebaseGround(start, Double.NaN));
		assertEquals(RtsCameraMath.HeightState.UNSET, RtsCameraMath.rebaseGround(RtsCameraMath.HeightState.UNSET, 50.0));
	}

	@Test
	void heldLevelKeyDescendsSmoothly() {
		RtsCameraMath.HeightState state = settled(64.0);
		double ground = 64.0;
		double lastY = state.y();
		for (int step = 0; step < 20; step++) {
			ground -= 1.0;
			state = RtsCameraMath.rebaseGround(state, ground);
			for (int i = 0; i < 2; i++) {
				state = RtsCameraMath.filterHeight(state, ground, ground, ZOOM, TICK, false);
				assertTrue(state.y() <= lastY + EPS, "camera rose while descending");
				lastY = state.y();
			}
		}
		state = run(state, ground, ground, 10);

		assertEquals(ground + ZOOM, state.y(), 0.05);
	}

	@Test
	void levelChangeThroughColumnTopConverges() {
		// Floor tops at 31 and 11, with air between them.
		RtsViewLevel.SolidTest column = y -> y == 30 || y <= 10;
		double upper = RtsViewLevel.columnTop(40, -64, RtsViewLevel.SCAN_LIMIT, column);
		double lower = RtsViewLevel.columnTop(25, -64, RtsViewLevel.SCAN_LIMIT, column);
		assertEquals(31.0, upper, EPS);
		assertEquals(11.0, lower, EPS);

		RtsCameraMath.HeightState state = RtsCameraMath.rebaseGround(settled(upper), lower);
		state = run(state, lower, lower, 20);

		assertEquals(lower + ZOOM, state.y(), 0.05);
		assertEquals(lower, state.ground(), EPS);
	}

	private static void assertPivotFixed(double h0, float y0, float p0, double h1, float y1, float p1) {
		double x = 12.0;
		double z = -3.0;
		RtsCameraMath.Pan before = RtsCameraMath.lookAtPoint(x, z, h0, y0, p0);
		RtsCameraMath.Pan shift = RtsCameraMath.orbitShift(h0, y0, p0, h1, y1, p1);
		RtsCameraMath.Pan after = RtsCameraMath.lookAtPoint(x + shift.x(), z + shift.z(), h1, y1, p1);

		assertEquals(before.x(), after.x(), 1e-6);
		assertEquals(before.z(), after.z(), 1e-6);
	}
}
