package io.github.oshogun.rabodepalha.rts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RtsCutawayFocusTest {
	@Test
	void anchorSnapsToTheGrid() {
		RtsCutawayFocus.Anchor anchor = RtsCutawayFocus.anchor(null, 13, 65, -7);

		assertEquals(0, Math.floorMod(anchor.x(), RtsCutawayFocus.GRID));
		assertEquals(0, Math.floorMod(anchor.z(), RtsCutawayFocus.GRID));
		assertEquals(12, anchor.x());
		assertEquals(-8, anchor.z());
		// Y snaps up, never below the raw cell.
		assertEquals(68, anchor.y());
		assertEquals(64, RtsCutawayFocus.anchor(null, 0, 64, 0).y());
	}

	@Test
	void anchorStaysForMovesOfFourOrLess() {
		RtsCutawayFocus.Anchor anchor = RtsCutawayFocus.anchor(null, 8, 64, 8);
		for (int d = -4; d <= 4; d++) {
			assertSame(anchor, RtsCutawayFocus.anchor(anchor, 8 + d, 64, 8));
			assertSame(anchor, RtsCutawayFocus.anchor(anchor, 8, 64, 8 + d));
		}
	}

	@Test
	void anchorJumpsForMovesOverFour() {
		RtsCutawayFocus.Anchor anchor = RtsCutawayFocus.anchor(null, 8, 64, 8);

		RtsCutawayFocus.Anchor east = RtsCutawayFocus.anchor(anchor, 13, 64, 8);
		assertNotEquals(anchor, east);
		assertEquals(12, east.x());
		RtsCutawayFocus.Anchor north = RtsCutawayFocus.anchor(anchor, 8, 64, 3);
		assertEquals(4, north.z());
	}

	@Test
	void anchorNeverStaysBelowTheRawCell() {
		RtsCutawayFocus.Anchor anchor = RtsCutawayFocus.anchor(null, 8, 64, 8);
		// Climbing one block puts the cursor above the anchor: it must follow, or the bubble would drill.
		RtsCutawayFocus.Anchor climbed = RtsCutawayFocus.anchor(anchor, 8, 65, 8);
		assertTrue(climbed.y() >= 65);
		// Going down a little keeps it; far down follows.
		assertSame(anchor, RtsCutawayFocus.anchor(anchor, 8, 60, 8));
		assertNotEquals(anchor, RtsCutawayFocus.anchor(anchor, 8, 56, 8));
	}

	@Test
	void yawQuantizesToEightFacings() {
		assertEquals(0, RtsCutawayFocus.quantizeYaw(-1, 0.0f));
		assertEquals(0, RtsCutawayFocus.quantizeYaw(-1, 360.0f));
		assertEquals(1, RtsCutawayFocus.quantizeYaw(-1, 44.0f));
		assertEquals(2, RtsCutawayFocus.quantizeYaw(-1, 90.0f));
		assertEquals(4, RtsCutawayFocus.quantizeYaw(-1, 180.0f));
		assertEquals(4, RtsCutawayFocus.quantizeYaw(-1, -180.0f));
		assertEquals(6, RtsCutawayFocus.quantizeYaw(-1, -90.0f));
		assertEquals(7, RtsCutawayFocus.quantizeYaw(-1, -40.0f));
	}

	@Test
	void yawKeepsItsFacingInsideTheHysteresisBand() {
		// Facing 0 covers -22.5..22.5; with hysteresis it holds to 27.5.
		assertEquals(0, RtsCutawayFocus.quantizeYaw(0, 25.0f));
		assertEquals(0, RtsCutawayFocus.quantizeYaw(0, -27.0f));
		assertEquals(1, RtsCutawayFocus.quantizeYaw(0, 29.0f));
		assertEquals(1, RtsCutawayFocus.quantizeYaw(1, 19.0f));
		assertEquals(0, RtsCutawayFocus.quantizeYaw(1, 16.0f));
		// Across the wrap.
		assertEquals(7, RtsCutawayFocus.quantizeYaw(7, 0.0f + 360.0f - 45.0f + 25.0f));
		assertEquals(0, RtsCutawayFocus.quantizeYaw(7, 360.0f + 1.0f));
	}

	@Test
	void slopeStepsAreMonotonic() {
		double previous = Double.MAX_VALUE;
		for (float pitch = 5.0f; pitch <= 90.0f; pitch += 0.5f) {
			double slope = RtsCutawayFocus.quantizeSlope(pitch);
			assertTrue(slope <= previous, "pitch " + pitch);
			previous = slope;
		}
		assertEquals(0.0, RtsCutawayFocus.quantizeSlope(90.0f));
		assertEquals(1.0, RtsCutawayFocus.quantizeSlope(45.0f));
		assertEquals(2.0, RtsCutawayFocus.quantizeSlope(10.0f));
	}
}
