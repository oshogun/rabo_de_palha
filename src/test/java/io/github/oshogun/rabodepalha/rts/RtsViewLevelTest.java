package io.github.oshogun.rabodepalha.rts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RtsViewLevelTest {
	private static final double EPS = 1e-9;
	private static final int MIN_Y = -64;
	private static final int LIMIT = RtsViewLevel.SCAN_LIMIT;

	@Test
	void solidColumnTopIsJustAboveLevel() {
		assertEquals(65.0, RtsViewLevel.columnTop(64, MIN_Y, LIMIT, y -> y <= 70), EPS);
	}

	@Test
	void levelOnSolidBlockGivesThatBlocksTop() {
		assertEquals(41.0, RtsViewLevel.columnTop(40, MIN_Y, LIMIT, y -> y == 40), EPS);
	}

	@Test
	void caveUnderOverhangGivesCaveFloor() {
		// Rock overhang 60..70, cave air 50..59, cave floor at 49 and below.
		RtsViewLevel.SolidTest cave = y -> y <= 49 || (y >= 60 && y <= 70);

		assertEquals(50.0, RtsViewLevel.columnTop(55, MIN_Y, LIMIT, cave), EPS);
		assertEquals(71.0, RtsViewLevel.columnTop(75, MIN_Y, LIMIT, cave), EPS);
	}

	@Test
	void roomUnderRoofGivesFloorTop() {
		// Roof at 80, room air 71..79, floor at 70 on solid ground.
		RtsViewLevel.SolidTest house = y -> y == 80 || y <= 70;

		assertEquals(71.0, RtsViewLevel.columnTop(75, MIN_Y, LIMIT, house), EPS);
		assertEquals(81.0, RtsViewLevel.columnTop(90, MIN_Y, LIMIT, house), EPS);
	}

	@Test
	void nothingSolidWithinLimitIsNaN() {
		assertTrue(Double.isNaN(RtsViewLevel.columnTop(100, MIN_Y, LIMIT, y -> y <= 0)));
		// The scan covers exactly LIMIT blocks: level down to level - LIMIT + 1.
		assertEquals(100 - LIMIT + 2, RtsViewLevel.columnTop(100, MIN_Y, LIMIT, y -> y == 100 - LIMIT + 1), EPS);
		assertTrue(Double.isNaN(RtsViewLevel.columnTop(100, MIN_Y, LIMIT, y -> y == 100 - LIMIT)));
	}

	@Test
	void scanStopsAtMinY() {
		assertTrue(Double.isNaN(RtsViewLevel.columnTop(-40, MIN_Y, LIMIT, y -> y < MIN_Y)));
		assertEquals(MIN_Y + 1, RtsViewLevel.columnTop(-40, MIN_Y, LIMIT, y -> y == MIN_Y), EPS);
	}

	@Test
	void startsUndergroundBelowHeightmapSurface() {
		// Standing on the surface: the top block is 63, feet in 64.
		assertFalse(RtsViewLevel.startsUnderground(64, 64));
		assertFalse(RtsViewLevel.startsUnderground(90, 64));
		// Under a roof whose top block is 79.
		assertTrue(RtsViewLevel.startsUnderground(71, 80));
		// In a cave far below the surface.
		assertTrue(RtsViewLevel.startsUnderground(-20, 64));
	}

	@Test
	void workerOnTheLevelFloorStaysPickable() {
		// Floor top block at 63: the level is the feet block 64, the column top is 64, and a worker there has feet in 64.
		int level = 64;
		assertEquals(64.0, RtsViewLevel.columnTop(level, MIN_Y, LIMIT, y -> y <= 63), EPS);
		assertFalse(RtsViewLevel.feetAboveLevel(64, level));
		assertFalse(RtsViewLevel.feetAboveLevel(50, level));
		assertTrue(RtsViewLevel.feetAboveLevel(65, level));
	}

	@Test
	void repeatStepFiresOnPressThenRepeats() {
		assertTrue(RtsViewLevel.repeatStep(0));
		for (int t = 1; t < RtsViewLevel.REPEAT_DELAY; t++) {
			assertFalse(RtsViewLevel.repeatStep(t), "tick " + t);
		}
		assertTrue(RtsViewLevel.repeatStep(RtsViewLevel.REPEAT_DELAY));
		assertFalse(RtsViewLevel.repeatStep(RtsViewLevel.REPEAT_DELAY + 1));
		assertTrue(RtsViewLevel.repeatStep(RtsViewLevel.REPEAT_DELAY + RtsViewLevel.REPEAT_INTERVAL));
		assertTrue(RtsViewLevel.repeatStep(RtsViewLevel.REPEAT_DELAY + 5 * RtsViewLevel.REPEAT_INTERVAL));
	}

	@Test
	void clampLevelToWorldRange() {
		assertEquals(-64, RtsViewLevel.clampLevel(-100, -64, 320));
		assertEquals(319, RtsViewLevel.clampLevel(400, -64, 320));
		assertEquals(319, RtsViewLevel.clampLevel(320, -64, 320));
		assertEquals(12, RtsViewLevel.clampLevel(12, -64, 320));
	}

	@Test
	void depthBelowSurface() {
		assertEquals(13.0, RtsViewLevel.depthBelowSurface(64.0, 50), EPS);
		assertEquals(0.0, RtsViewLevel.depthBelowSurface(64.0, 63), EPS);
		assertEquals(-6.0, RtsViewLevel.depthBelowSurface(64.0, 69), EPS);
		assertTrue(Double.isNaN(RtsViewLevel.depthBelowSurface(Double.NaN, 10)));
	}
}
