package io.github.oshogun.rabodepalha.rts;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.oshogun.rabodepalha.rts.CutawayRegion.Section;
import org.junit.jupiter.api.Test;

class CutawayRegionTest {
	// Overworld section range: blocks -64..319.
	private static final int MIN_SY = -4;
	private static final int MAX_SY = 19;

	@Test
	void levelStaysVisibleAndAboveIsHidden() {
		CutawayRegion region = CutawayRegion.above(64);

		assertTrue(region.active());
		assertFalse(region.contains(0, 64, 0));
		assertFalse(region.contains(0, 10, 0));
		assertTrue(region.contains(0, 65, 0));
		assertTrue(region.contains(-300, 200, 7));
	}

	@Test
	void noneHidesNothing() {
		assertFalse(CutawayRegion.NONE.active());
		assertFalse(CutawayRegion.NONE.contains(0, 1000, 0));
		assertFalse(CutawayRegion.NONE.contains(0, -64, 0));
		for (int sy = MIN_SY; sy <= MAX_SY; sy++) {
			assertEquals(Section.OUTSIDE, CutawayRegion.NONE.classify(0, sy, 0), "sy " + sy);
		}
		assertFalse(CutawayRegion.NONE.hidesFeet(1000));
	}

	@Test
	void levelAtSectionTopBlockCutsItAndTheInflatedLayerAbove() {
		// Section 2 spans blocks 32..47; section 3 reads block 47 for face culling.
		CutawayRegion region = CutawayRegion.above(47);

		assertEquals(Section.OUTSIDE, region.classify(0, 1, 0));
		assertEquals(Section.BOUNDARY, region.classify(0, 2, 0));
		assertEquals(Section.BOUNDARY, region.classify(0, 3, 0));
		assertEquals(Section.INSIDE, region.classify(0, 4, 0));
	}

	@Test
	void levelAtSectionBottomBlock() {
		CutawayRegion region = CutawayRegion.above(32);

		assertEquals(Section.OUTSIDE, region.classify(0, 1, 0));
		assertEquals(Section.BOUNDARY, region.classify(0, 2, 0));
		assertEquals(Section.INSIDE, region.classify(0, 3, 0));
	}

	@Test
	void levelJustBelowSectionIsBoundaryThroughInflation() {
		// Level 31 is the block just below section 2: section 2 lies entirely above it, but reads block 31.
		CutawayRegion region = CutawayRegion.above(31);
		assertEquals(Section.BOUNDARY, region.classify(0, 1, 0));
		assertEquals(Section.BOUNDARY, region.classify(0, 2, 0));
		assertEquals(Section.INSIDE, region.classify(0, 3, 0));

		// One lower and section 2 no longer touches the visible part.
		CutawayRegion lower = CutawayRegion.above(30);
		assertEquals(Section.BOUNDARY, lower.classify(0, 1, 0));
		assertEquals(Section.INSIDE, lower.classify(0, 2, 0));
	}

	@Test
	void classifyNegativeY() {
		// Level -1 is the top block of section -1; section 0 reads it.
		CutawayRegion region = CutawayRegion.above(-1);
		assertEquals(Section.OUTSIDE, region.classify(0, -2, 0));
		assertEquals(Section.BOUNDARY, region.classify(0, -1, 0));
		assertEquals(Section.BOUNDARY, region.classify(0, 0, 0));
		assertEquals(Section.INSIDE, region.classify(0, 1, 0));

		CutawayRegion bottom = CutawayRegion.above(-64);
		assertEquals(Section.OUTSIDE, bottom.classify(0, -5, 0));
		assertEquals(Section.BOUNDARY, bottom.classify(0, -4, 0));
		assertEquals(Section.INSIDE, bottom.classify(0, -3, 0));
	}

	@Test
	void classifyIgnoresColumn() {
		CutawayRegion region = CutawayRegion.above(40);
		assertEquals(region.classify(0, 2, 0), region.classify(-17, 2, 99));
		assertEquals(region.classify(0, 5, 0), region.classify(1000, 5, -1000));
	}

	@Test
	void stepInsideOneSectionDirtiesOneLayer() {
		assertArrayEquals(new int[] {2},
				CutawayRegion.changedSectionYs(CutawayRegion.above(40), CutawayRegion.above(41), MIN_SY, MAX_SY));
		assertArrayEquals(new int[] {2},
				CutawayRegion.changedSectionYs(CutawayRegion.above(41), CutawayRegion.above(40), MIN_SY, MAX_SY));
	}

	@Test
	void stepAcrossSectionEdgeDirtiesTwoLayers() {
		assertArrayEquals(new int[] {2, 3},
				CutawayRegion.changedSectionYs(CutawayRegion.above(47), CutawayRegion.above(48), MIN_SY, MAX_SY));
		assertArrayEquals(new int[] {2, 3},
				CutawayRegion.changedSectionYs(CutawayRegion.above(46), CutawayRegion.above(47), MIN_SY, MAX_SY));
		assertArrayEquals(new int[] {1, 2},
				CutawayRegion.changedSectionYs(CutawayRegion.above(32), CutawayRegion.above(31), MIN_SY, MAX_SY));
	}

	@Test
	void jumpDirtiesOnlyBothBoundaries() {
		// Three sections up: the layers in between go from INSIDE to OUTSIDE and keep their real meshes.
		assertArrayEquals(new int[] {2, 5},
				CutawayRegion.changedSectionYs(CutawayRegion.above(40), CutawayRegion.above(88), MIN_SY, MAX_SY));
		assertArrayEquals(new int[] {2, 5},
				CutawayRegion.changedSectionYs(CutawayRegion.above(88), CutawayRegion.above(40), MIN_SY, MAX_SY));
	}

	@Test
	void enterAndExitDirtyExactlyTheBoundaryLayers() {
		for (int level : new int[] {40, 47, 31, -1, -64, 318}) {
			CutawayRegion region = CutawayRegion.above(level);
			int[] boundary = boundaryLayers(region);
			assertArrayEquals(boundary,
					CutawayRegion.changedSectionYs(CutawayRegion.NONE, region, MIN_SY, MAX_SY), "enter " + level);
			assertArrayEquals(boundary,
					CutawayRegion.changedSectionYs(region, CutawayRegion.NONE, MIN_SY, MAX_SY), "exit " + level);
		}
	}

	@Test
	void noneToNoneDirtiesNothing() {
		assertArrayEquals(new int[0],
				CutawayRegion.changedSectionYs(CutawayRegion.NONE, CutawayRegion.NONE, MIN_SY, MAX_SY));
	}

	@Test
	void changedLayersAreClampedToTheWorld() {
		assertArrayEquals(new int[] {3},
				CutawayRegion.changedSectionYs(CutawayRegion.NONE, CutawayRegion.above(47), 3, 10));
		assertArrayEquals(new int[] {2},
				CutawayRegion.changedSectionYs(CutawayRegion.NONE, CutawayRegion.above(47), -4, 2));
		assertArrayEquals(new int[0],
				CutawayRegion.changedSectionYs(CutawayRegion.NONE, CutawayRegion.above(-200), MIN_SY, MAX_SY));
		assertArrayEquals(new int[0],
				CutawayRegion.changedSectionYs(CutawayRegion.NONE, CutawayRegion.above(1000), MIN_SY, MAX_SY));
		assertArrayEquals(new int[0],
				CutawayRegion.changedSectionYs(CutawayRegion.NONE, CutawayRegion.above(40), 5, 4));
	}

	@Test
	void insideSetChangesOnlyWhenTheLowestInsideSectionMoves() {
		assertFalse(CutawayRegion.insideSetChanged(CutawayRegion.above(40), CutawayRegion.above(41)));
		assertFalse(CutawayRegion.insideSetChanged(CutawayRegion.above(47), CutawayRegion.above(48)));
		assertTrue(CutawayRegion.insideSetChanged(CutawayRegion.above(46), CutawayRegion.above(47)));
		assertTrue(CutawayRegion.insideSetChanged(CutawayRegion.above(31), CutawayRegion.above(30)));
		assertTrue(CutawayRegion.insideSetChanged(CutawayRegion.above(40), CutawayRegion.above(88)));
		assertTrue(CutawayRegion.insideSetChanged(CutawayRegion.NONE, CutawayRegion.above(40)));
		assertTrue(CutawayRegion.insideSetChanged(CutawayRegion.above(40), CutawayRegion.NONE));
		assertFalse(CutawayRegion.insideSetChanged(CutawayRegion.NONE, CutawayRegion.NONE));
	}

	@Test
	void insideSetChangedMatchesClassification() {
		for (int a = -70; a <= 100; a++) {
			for (int b = a - 20; b <= a + 20; b++) {
				CutawayRegion old = CutawayRegion.above(a);
				CutawayRegion now = CutawayRegion.above(b);
				boolean differs = false;
				for (int sy = -6; sy <= 8; sy++) {
					differs |= (old.classify(0, sy, 0) == Section.INSIDE) != (now.classify(0, sy, 0) == Section.INSIDE);
				}
				assertEquals(differs, CutawayRegion.insideSetChanged(old, now), a + " -> " + b);
			}
		}
	}

	@Test
	void hidesFeetMatchesFeetAboveLevel() {
		for (int level = -64; level <= 80; level += 8) {
			CutawayRegion region = CutawayRegion.above(level);
			for (int feet = level - 3; feet <= level + 3; feet++) {
				assertEquals(RtsViewLevel.feetAboveLevel(feet, level), region.hidesFeet(feet), level + "/" + feet);
			}
		}
		// A worker standing on the level is shown, one block up is hidden.
		assertFalse(CutawayRegion.above(64).hidesFeet(64));
		assertTrue(CutawayRegion.above(64).hidesFeet(65));
	}

	@Test
	void equalityByLevel() {
		assertEquals(CutawayRegion.above(40), CutawayRegion.above(40));
		assertEquals(CutawayRegion.above(40).hashCode(), CutawayRegion.above(40).hashCode());
		assertNotEquals(CutawayRegion.above(40), CutawayRegion.above(41));
		assertNotEquals(CutawayRegion.NONE, CutawayRegion.above(0));
		assertEquals(CutawayRegion.NONE, CutawayRegion.NONE);
	}

	private static int[] boundaryLayers(CutawayRegion region) {
		return java.util.stream.IntStream.rangeClosed(MIN_SY, MAX_SY)
				.filter(sy -> region.classify(0, sy, 0) == Section.BOUNDARY)
				.toArray();
	}
}
