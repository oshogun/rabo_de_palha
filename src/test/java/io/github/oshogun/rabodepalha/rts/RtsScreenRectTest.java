package io.github.oshogun.rabodepalha.rts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RtsScreenRectTest {
	@Test
	void cornersInAnyOrderNormalise() {
		RtsScreenRect expected = new RtsScreenRect(10, 20, 30, 40);

		assertEquals(expected, RtsScreenRect.fromCorners(10, 20, 30, 40));
		assertEquals(expected, RtsScreenRect.fromCorners(30, 40, 10, 20));
		assertEquals(expected, RtsScreenRect.fromCorners(30, 20, 10, 40));
		assertEquals(expected, RtsScreenRect.fromCorners(10, 40, 30, 20));
		assertEquals(20.0, expected.width());
		assertEquals(20.0, expected.height());
	}

	@Test
	void containsIsInclusiveOnEdges() {
		RtsScreenRect rect = RtsScreenRect.fromCorners(10, 20, 30, 40);

		assertTrue(rect.contains(10, 20));
		assertTrue(rect.contains(30, 40));
		assertTrue(rect.contains(20, 30));
		assertFalse(rect.contains(9.9, 30));
		assertFalse(rect.contains(20, 40.1));
	}

	@Test
	void dragThreshold() {
		double t = RtsScreenRect.DRAG_THRESHOLD_PX;

		assertFalse(RtsScreenRect.isDrag(100, 100, 100, 100));
		assertFalse(RtsScreenRect.isDrag(100, 100, 100 + t, 100 - t));
		assertTrue(RtsScreenRect.isDrag(100, 100, 100 + t + 1, 100));
		assertTrue(RtsScreenRect.isDrag(100, 100, 100, 100 - t - 1));
	}
}
