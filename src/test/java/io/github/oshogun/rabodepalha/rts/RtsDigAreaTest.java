package io.github.oshogun.rabodepalha.rts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RtsDigAreaTest {
	/** The volume limit of {@code DigAreaTask.MAX_VOLUME}. */
	private static final long LIMIT = 4096;

	@Test
	void cornersInAnyOrderGiveTheSameCuboidWithTheHigherCornerOnTop() {
		RtsDigArea expected = new RtsDigArea(1, 63, 2, 5, 65, 9);

		assertEquals(expected, RtsDigArea.of(1, 65, 2, 5, 64, 9, 3));
		assertEquals(expected, RtsDigArea.of(5, 64, 9, 1, 65, 2, 3));
		assertEquals(expected, RtsDigArea.of(5, 65, 2, 1, 64, 9, 3));
		assertEquals(expected, RtsDigArea.of(1, 64, 9, 5, 65, 2, 3));
	}

	@Test
	void depthOneIsTheTopLayerOnly() {
		RtsDigArea area = RtsDigArea.of(0, 70, 0, 3, 60, 3, 1);

		assertEquals(1, area.sizeY());
		assertEquals(70, area.maxY());
		assertEquals(70, area.minY());
	}

	@Test
	void largeDepthReachesDown() {
		RtsDigArea area = RtsDigArea.of(0, 80, 0, 0, 80, 0, 100);

		assertEquals(80, area.maxY());
		assertEquals(80 - 99, area.minY());
		assertEquals(100, area.sizeY());
	}

	@Test
	void depthIsClamped() {
		assertEquals(RtsDigArea.MAX_DEPTH, RtsDigArea.of(0, 100, 0, 0, 100, 0, 1000).sizeY());
		assertEquals(1, RtsDigArea.of(0, 100, 0, 0, 100, 0, 0).sizeY());
		assertEquals(1, RtsDigArea.of(0, 100, 0, 0, 100, 0, -5).sizeY());
		assertEquals(1, RtsDigArea.clampDepth(0));
		assertEquals(384, RtsDigArea.clampDepth(385));
		assertEquals(42, RtsDigArea.clampDepth(42));
	}

	@Test
	void volumeLimit() {
		RtsDigArea over = RtsDigArea.of(0, 64, 0, 16, 64, 15, 16);
		RtsDigArea exact = RtsDigArea.of(0, 64, 0, 15, 64, 15, 16);

		assertEquals(17 * 16 * 16, over.volume());
		assertTrue(over.exceeds(LIMIT));
		assertEquals(LIMIT, exact.volume());
		assertFalse(exact.exceeds(LIMIT));
	}

	@Test
	void volumeDoesNotOverflow() {
		RtsDigArea huge = RtsDigArea.between(-100000, 0, -100000, 100000, 383, 100000);

		assertEquals(200001L * 384 * 200001L, huge.volume());
		assertTrue(huge.exceeds(LIMIT));
	}

	@Test
	void betweenIsOrderIndependent() {
		RtsDigArea expected = new RtsDigArea(-3, 10, 4, 7, 20, 8);

		assertEquals(expected, RtsDigArea.between(-3, 10, 4, 7, 20, 8));
		assertEquals(expected, RtsDigArea.between(7, 20, 8, -3, 10, 4));
		assertEquals(expected, RtsDigArea.between(7, 10, 4, -3, 20, 8));
	}

	@Test
	void labelIsXByZByDepth() {
		RtsDigArea area = RtsDigArea.of(0, 64, 0, 11, 64, 7, 3);

		assertEquals("12×8×3 = 288", area.label());
	}

	@Test
	void initialDepthReachesTheLowerCorner() {
		assertEquals(1, RtsDigArea.initialDepth(64, 64));
		assertEquals(5, RtsDigArea.initialDepth(60, 64));
		assertEquals(5, RtsDigArea.initialDepth(64, 60));

		RtsDigArea area = RtsDigArea.of(0, 64, 0, 0, 60, 0, RtsDigArea.initialDepth(64, 60));
		assertEquals(60, area.minY());
		assertEquals(64, area.maxY());
	}

	@Test
	void farthestCornerUsesTheOuterFaces() {
		RtsDigArea block = new RtsDigArea(0, 0, 0, 0, 0, 0);

		// The outer faces of block (0,0,0) span 0..1, so the far corner from (0,0,0) is (1,1,1).
		assertEquals(3.0, block.farthestCornerDistanceSqr(0, 0, 0), 1e-9);
		assertEquals(3.0, block.farthestCornerDistanceSqr(1, 1, 1), 1e-9);
		assertEquals(3 * 1.5 * 1.5, block.farthestCornerDistanceSqr(-0.5, -0.5, -0.5), 1e-9);

		RtsDigArea area = new RtsDigArea(0, 0, 0, 9, 0, 0);
		assertEquals(10 * 10 + 1 + 1, area.farthestCornerDistanceSqr(0, 0, 0), 1e-9);
	}

	@Test
	void centerIsBetweenTheOuterFaces() {
		RtsDigArea area = new RtsDigArea(0, 60, -4, 3, 63, 3);

		assertEquals(2.0, area.centerX());
		assertEquals(62.0, area.centerY());
		assertEquals(0.0, area.centerZ());
	}
}
