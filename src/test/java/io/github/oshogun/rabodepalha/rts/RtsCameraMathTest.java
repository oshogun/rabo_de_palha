package io.github.oshogun.rabodepalha.rts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
