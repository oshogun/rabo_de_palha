package io.github.oshogun.rabodepalha.rts;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

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
		assertFalse(CutawayRegion.NONE.hidesEntityAt(0, 1000, 0));
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
				changedSectionYs(CutawayRegion.above(40), CutawayRegion.above(41), MIN_SY, MAX_SY));
		assertArrayEquals(new int[] {2},
				changedSectionYs(CutawayRegion.above(41), CutawayRegion.above(40), MIN_SY, MAX_SY));
	}

	@Test
	void stepAcrossSectionEdgeDirtiesTwoLayers() {
		assertArrayEquals(new int[] {2, 3},
				changedSectionYs(CutawayRegion.above(47), CutawayRegion.above(48), MIN_SY, MAX_SY));
		assertArrayEquals(new int[] {2, 3},
				changedSectionYs(CutawayRegion.above(46), CutawayRegion.above(47), MIN_SY, MAX_SY));
		assertArrayEquals(new int[] {1, 2},
				changedSectionYs(CutawayRegion.above(32), CutawayRegion.above(31), MIN_SY, MAX_SY));
	}

	@Test
	void jumpDirtiesOnlyBothBoundaries() {
		// Three sections up: the layers in between go from INSIDE to OUTSIDE and keep their real meshes.
		assertArrayEquals(new int[] {2, 5},
				changedSectionYs(CutawayRegion.above(40), CutawayRegion.above(88), MIN_SY, MAX_SY));
		assertArrayEquals(new int[] {2, 5},
				changedSectionYs(CutawayRegion.above(88), CutawayRegion.above(40), MIN_SY, MAX_SY));
	}

	@Test
	void enterAndExitDirtyExactlyTheBoundaryLayers() {
		for (int level : new int[] {40, 47, 31, -1, -64, 318}) {
			CutawayRegion region = CutawayRegion.above(level);
			int[] boundary = boundaryLayers(region);
			assertArrayEquals(boundary,
					changedSectionYs(CutawayRegion.NONE, region, MIN_SY, MAX_SY), "enter " + level);
			assertArrayEquals(boundary,
					changedSectionYs(region, CutawayRegion.NONE, MIN_SY, MAX_SY), "exit " + level);
		}
	}

	@Test
	void noneToNoneDirtiesNothing() {
		assertArrayEquals(new int[0],
				changedSectionYs(CutawayRegion.NONE, CutawayRegion.NONE, MIN_SY, MAX_SY));
	}

	@Test
	void changedLayersAreClampedToTheWorld() {
		assertArrayEquals(new int[] {3},
				changedSectionYs(CutawayRegion.NONE, CutawayRegion.above(47), 3, 10));
		assertArrayEquals(new int[] {2},
				changedSectionYs(CutawayRegion.NONE, CutawayRegion.above(47), -4, 2));
		assertArrayEquals(new int[0],
				changedSectionYs(CutawayRegion.NONE, CutawayRegion.above(-200), MIN_SY, MAX_SY));
		assertArrayEquals(new int[0],
				changedSectionYs(CutawayRegion.NONE, CutawayRegion.above(1000), MIN_SY, MAX_SY));
		assertArrayEquals(new int[0],
				changedSectionYs(CutawayRegion.NONE, CutawayRegion.above(40), 5, 4));
	}

	@Test
	void insideSetChangesOnlyWhenTheLowestInsideSectionMoves() {
		assertFalse(insideSetChanged(CutawayRegion.above(40), CutawayRegion.above(41)));
		assertFalse(insideSetChanged(CutawayRegion.above(47), CutawayRegion.above(48)));
		assertTrue(insideSetChanged(CutawayRegion.above(46), CutawayRegion.above(47)));
		assertTrue(insideSetChanged(CutawayRegion.above(31), CutawayRegion.above(30)));
		assertTrue(insideSetChanged(CutawayRegion.above(40), CutawayRegion.above(88)));
		assertTrue(insideSetChanged(CutawayRegion.NONE, CutawayRegion.above(40)));
		assertTrue(insideSetChanged(CutawayRegion.above(40), CutawayRegion.NONE));
		assertFalse(insideSetChanged(CutawayRegion.NONE, CutawayRegion.NONE));
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
				assertEquals(differs, insideSetChanged(old, now, -6, 8), a + " -> " + b);
			}
		}
	}

	@Test
	void hidesEntityAtMatchesFeetAboveLevel() {
		for (int level = -64; level <= 80; level += 8) {
			CutawayRegion region = CutawayRegion.above(level);
			for (int feet = level - 3; feet <= level + 3; feet++) {
				assertEquals(RtsViewLevel.feetAboveLevel(feet, level), region.hidesEntityAt(5, feet, -9), level + "/" + feet);
			}
		}
		// A worker standing on the level is shown, one block up is hidden.
		assertFalse(CutawayRegion.above(64).hidesEntityAt(0, 64, 0));
		assertTrue(CutawayRegion.above(64).hidesEntityAt(0, 65, 0));
	}

	@Test
	void equalityByLevel() {
		assertEquals(CutawayRegion.above(40), CutawayRegion.above(40));
		assertEquals(CutawayRegion.above(40).hashCode(), CutawayRegion.above(40).hashCode());
		assertNotEquals(CutawayRegion.above(40), CutawayRegion.above(41));
		assertNotEquals(CutawayRegion.NONE, CutawayRegion.above(0));
		assertEquals(CutawayRegion.NONE, CutawayRegion.NONE);
	}

	@Test
	void unionTakesInsideOverBoundaryOverOutside() {
		CutawayShape.Box room = new CutawayShape.Box(0, 64, 0, 9, Integer.MAX_VALUE, 9);
		CutawayShape.Box block = new CutawayShape.Box(100, 100, 100, 100, 100, 100);
		CutawayRegion region = CutawayRegion.of(40, List.of(room, block));

		// Level 40 makes section 2 BOUNDARY, and the room doesn't reach it.
		assertEquals(Section.BOUNDARY, region.classify(0, 2, 0));
		// Section 3 is INSIDE the level, whatever the room says.
		assertEquals(Section.INSIDE, region.classify(0, 3, 0));
		assertEquals(40, region.level());

		CutawayRegion local = CutawayRegion.of(null, List.of(room, block));
		assertNull(local.level());
		assertEquals(Section.BOUNDARY, local.classify(6, 6, 6));
		assertEquals(Section.BOUNDARY, local.classify(0, 4, 0));
		assertEquals(Section.OUTSIDE, local.classify(-5, 2, -5));
		assertTrue(local.contains(100, 100, 100));
		assertTrue(local.contains(3, 200, 3));
		assertFalse(local.contains(3, 63, 3));
	}

	@Test
	void bubbleKeepsEntitiesVisibleWhileBoxAndLevelHideThem() {
		CutawayShape.Bubble bubble = new CutawayShape.Bubble(0, 64, 0, 5.0, 24, 0.0, 0.0);
		CutawayShape.Box room = new CutawayShape.Box(20, 70, 20, 30, Integer.MAX_VALUE, 30);
		CutawayRegion region = CutawayRegion.of(100, List.of(bubble, room));

		assertTrue(region.contains(0, 64, 0));
		assertFalse(region.hidesEntityAt(0, 64, 0));
		assertTrue(region.hidesEntityAt(25, 70, 25));
		assertFalse(region.hidesEntityAt(25, 69, 25));
		assertTrue(region.hidesEntityAt(0, 101, 0));
	}

	@Test
	void emptyShapeListIsNone() {
		assertEquals(CutawayRegion.NONE, CutawayRegion.of(null, List.of()));
		assertFalse(CutawayRegion.of(null, List.of()).active());
		assertTrue(CutawayRegion.of(null, List.of(new CutawayShape.Above(3))).active());
	}

	@Test
	void equalityByShapes() {
		CutawayShape.Bubble a = new CutawayShape.Bubble(1, 2, 3, 5.0, 24, 0.5, 0.0);
		CutawayShape.Bubble b = new CutawayShape.Bubble(1, 2, 3, 5.0, 24, 0.5, 0.0);
		assertEquals(CutawayRegion.of(40, List.of(a)), CutawayRegion.of(40, List.of(b)));
		assertEquals(CutawayRegion.of(40, List.of(a)).hashCode(), CutawayRegion.of(40, List.of(b)).hashCode());
		assertNotEquals(CutawayRegion.of(40, List.of(a)), CutawayRegion.of(null, List.of(a)));
		assertNotEquals(CutawayRegion.of(40, List.of(a)),
				CutawayRegion.of(40, List.of(new CutawayShape.Bubble(1, 2, 7, 5.0, 24, 0.5, 0.0))));
		assertEquals(CutawayRegion.above(40), CutawayRegion.of(40, List.of()));
	}

	@Test
	void movingTheBubbleWithTheLevelUnchangedDirtiesOnlyBubbleSections() {
		CutawayShape.Bubble from = new CutawayShape.Bubble(8, 50, 8, 5.0, 24, 0.0, 0.0);
		CutawayShape.Bubble to = new CutawayShape.Bubble(40, 50, 8, 5.0, 24, 0.0, 0.0);
		CutawayRegion old = CutawayRegion.of(60, List.of(from));
		CutawayRegion now = CutawayRegion.of(60, List.of(to));

		Set<String> dirty = new HashSet<>();
		CutawayRegion.diff(old, now, WINDOW, (sx, sy, sz) -> {
			assertTrue(dirty.add(sx + "," + sy + "," + sz), "dirtied twice: " + sx + "," + sy + "," + sz);
			assertTrue(from.sectionBounds().contains(sx, sy, sz) || to.sectionBounds().contains(sx, sy, sz),
					"outside both bubbles: " + sx + "," + sy + "," + sz);
		});

		assertFalse(dirty.isEmpty());
		// The level's boundary layer away from the bubbles is left alone.
		assertEquals(Section.BOUNDARY, now.classify(-5, 3, -5));
		assertFalse(dirty.contains("-5,3,-5"));
		// Both the section the bubble left and the one it entered are re-meshed.
		assertTrue(dirty.contains("0,3,0"));
		assertTrue(dirty.contains("2,3,0"));
	}

	@Test
	void togglingTheLevelDirtiesItsBoundaryLayerAcrossTheWindow() {
		CutawayShape.Bubble bubble = new CutawayShape.Bubble(8, 50, 8, 5.0, 24, 0.0, 0.0);
		Set<String> dirty = new HashSet<>();
		boolean flag = CutawayRegion.diff(CutawayRegion.of(null, List.of(bubble)), CutawayRegion.of(40, List.of(bubble)),
				WINDOW, (sx, sy, sz) -> dirty.add(sx + "," + sy + "," + sz));

		assertTrue(flag);
		for (int sz = WINDOW.minZ(); sz <= WINDOW.maxZ(); sz++) {
			for (int sx = WINDOW.minX(); sx <= WINDOW.maxX(); sx++) {
				assertTrue(dirty.contains(sx + ",2," + sz), sx + ",2," + sz);
			}
		}
	}

	@Test
	void identicalRegionsDirtyNothing() {
		CutawayRegion region = CutawayRegion.of(40, List.of(new CutawayShape.Bubble(8, 50, 8, 5.0, 24, 1.0, 0.0)));
		CutawayRegion same = CutawayRegion.of(40, List.of(new CutawayShape.Bubble(8, 50, 8, 5.0, 24, 1.0, 0.0)));
		List<String> dirty = new ArrayList<>();

		assertFalse(CutawayRegion.diff(region, same, WINDOW, (sx, sy, sz) -> dirty.add(sx + "," + sy + "," + sz)));
		assertTrue(dirty.isEmpty());
	}

	@Test
	void sectionGoingFromBoundaryToInsideIsDirtiedAndFlagged() {
		// A box covering exactly section (0, 4, 0)'s inflated span, and one a block short of it.
		CutawayShape.Box exact = new CutawayShape.Box(-1, 63, -1, 16, 80, 16);
		CutawayShape.Box partial = new CutawayShape.Box(0, 63, -1, 16, 80, 16);
		assertEquals(Section.INSIDE, exact.classify(0, 4, 0));
		assertEquals(Section.BOUNDARY, partial.classify(0, 4, 0));
		Set<String> dirty = new HashSet<>();

		assertTrue(CutawayRegion.diff(CutawayRegion.of(null, List.of(partial)), CutawayRegion.of(null, List.of(exact)),
				WINDOW, (sx, sy, sz) -> dirty.add(sx + "," + sy + "," + sz)));
		assertTrue(dirty.contains("0,4,0"));
	}

	@Test
	void diffMatchesBruteForceForRandomRegions() {
		Random random = new Random(29);
		for (int round = 0; round < 150; round++) {
			CutawayRegion old = randomRegion(random);
			CutawayRegion now = random.nextInt(4) == 0 ? old : randomRegion(random);
			Set<String> dirty = new HashSet<>();
			boolean flag = CutawayRegion.diff(old, now, SMALL_WINDOW, (sx, sy, sz) -> dirty.add(sx + "," + sy + "," + sz));
			boolean insideChanged = false;
			for (int sy = SMALL_WINDOW.minY(); sy <= SMALL_WINDOW.maxY(); sy++) {
				for (int sz = SMALL_WINDOW.minZ(); sz <= SMALL_WINDOW.maxZ(); sz++) {
					for (int sx = SMALL_WINDOW.minX(); sx <= SMALL_WINDOW.maxX(); sx++) {
						Section before = old.classify(sx, sy, sz);
						Section after = now.classify(sx, sy, sz);
						insideChanged |= (before == Section.INSIDE) != (after == Section.INSIDE);
						String key = sx + "," + sy + "," + sz;
						if (dirty.contains(key)) {
							assertTrue(before == Section.BOUNDARY || after == Section.BOUNDARY, key);
						} else if (before == Section.BOUNDARY || after == Section.BOUNDARY) {
							// Skipped, so nothing the section's mesh reads may differ.
							assertSameBlocks(old, now, sx, sy, sz, old + " -> " + now + " at " + key);
						}
					}
				}
			}
			assertEquals(insideChanged, flag, old + " -> " + now);
			if (old.equals(now)) {
				assertTrue(dirty.isEmpty());
			}
		}
	}

	private static final CutawayShape.SectionBounds WINDOW = new CutawayShape.SectionBounds(-6, MIN_SY, -6, 6, MAX_SY, 6);
	private static final CutawayShape.SectionBounds SMALL_WINDOW = new CutawayShape.SectionBounds(-2, 2, -2, 2, 6, 2);

	private static CutawayRegion randomRegion(Random random) {
		List<CutawayShape> shapes = new ArrayList<>();
		int count = random.nextInt(3);
		for (int i = 0; i < count; i++) {
			int x = random.nextInt(60) - 30;
			int y = 40 + random.nextInt(50);
			int z = random.nextInt(60) - 30;
			if (random.nextBoolean()) {
				shapes.add(new CutawayShape.Bubble(x, y, z, 2 + random.nextInt(6), 4 + random.nextInt(24),
						(random.nextInt(5) - 2) * 0.5, (random.nextInt(5) - 2) * 0.5));
			} else {
				int maxY = random.nextBoolean() ? Integer.MAX_VALUE : y + random.nextInt(20);
				shapes.add(new CutawayShape.Box(x, y, z, x + random.nextInt(20), maxY, z + random.nextInt(20)));
			}
		}
		return CutawayRegion.of(random.nextInt(3) == 0 ? 50 + random.nextInt(40) : null, shapes);
	}

	private static void assertSameBlocks(CutawayRegion a, CutawayRegion b, int sx, int sy, int sz, String message) {
		for (int y = sy * 16 - 1; y <= sy * 16 + 16; y++) {
			for (int z = sz * 16 - 1; z <= sz * 16 + 16; z++) {
				for (int x = sx * 16 - 1; x <= sx * 16 + 16; x++) {
					if (a.contains(x, y, z) != b.contains(x, y, z)) {
						throw new AssertionError(message + ": block " + x + "," + y + "," + z);
					}
				}
			}
		}
	}

	/** The section Ys of one column, ascending, that {@link CutawayRegion#diff} re-meshes: the #27 layer rule. */
	private static int[] changedSectionYs(CutawayRegion old, CutawayRegion now, int minSectionY, int maxSectionY) {
		if (maxSectionY < minSectionY) {
			return new int[0];
		}
		List<Integer> out = new ArrayList<>();
		CutawayRegion.diff(old, now, new CutawayShape.SectionBounds(0, minSectionY, 0, 0, maxSectionY, 0),
				(sx, sy, sz) -> out.add(sy));
		return out.stream().mapToInt(Integer::intValue).sorted().toArray();
	}

	/** Whether {@link CutawayRegion#diff} reports a changed INSIDE set over the overworld's height. */
	private static boolean insideSetChanged(CutawayRegion old, CutawayRegion now) {
		return insideSetChanged(old, now, MIN_SY, MAX_SY);
	}

	private static boolean insideSetChanged(CutawayRegion old, CutawayRegion now, int minSectionY, int maxSectionY) {
		return CutawayRegion.diff(old, now, new CutawayShape.SectionBounds(0, minSectionY, 0, 0, maxSectionY, 0),
				(sx, sy, sz) -> {
				});
	}

	private static int[] boundaryLayers(CutawayRegion region) {
		return java.util.stream.IntStream.rangeClosed(MIN_SY, MAX_SY)
				.filter(sy -> region.classify(0, sy, 0) == Section.BOUNDARY)
				.toArray();
	}
}
