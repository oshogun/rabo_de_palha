package io.github.oshogun.rabodepalha.rts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import io.github.oshogun.rabodepalha.rts.CutawayRegion.Section;
import org.junit.jupiter.api.Test;

class CutawayShapeTest {
	@Test
	void classifyAgreesWithContainsForRandomShapes() {
		Random random = new Random(2629);
		for (int round = 0; round < 300; round++) {
			CutawayShape shape = randomShape(random);
			// Sections around the shape, negative coordinates included.
			CutawayShape.SectionBounds near = shape.sectionBounds();
			int sx0 = near.minX() - 1;
			int sy0 = near.minY() - 1;
			int sz0 = near.minZ() - 1;
			for (int sy = sy0; sy <= Math.min(near.maxY() + 1, sy0 + 6); sy++) {
				for (int sz = sz0; sz <= Math.min(near.maxZ() + 1, sz0 + 5); sz++) {
					for (int sx = sx0; sx <= Math.min(near.maxX() + 1, sx0 + 5); sx++) {
						checkSection(shape, sx, sy, sz);
					}
				}
			}
		}
	}

	@Test
	void sectionBoundsCoverEveryTouchedSection() {
		Random random = new Random(7);
		for (int round = 0; round < 200; round++) {
			CutawayShape shape = randomShape(random);
			CutawayShape.SectionBounds bounds = shape.sectionBounds();
			for (int sy = bounds.minY() - 2; sy <= Math.min(bounds.maxY(), bounds.minY() + 8) + 2; sy++) {
				for (int sz = bounds.minZ() - 2; sz <= bounds.maxZ() + 2; sz++) {
					for (int sx = bounds.minX() - 2; sx <= bounds.maxX() + 2; sx++) {
						if (!bounds.contains(sx, sy, sz)) {
							assertEquals(Section.OUTSIDE, shape.classify(sx, sy, sz), shape + " at " + sx + "," + sy + "," + sz);
						}
					}
				}
			}
		}
	}

	@Test
	void verticalBubbleKeepsTheFloorAndHidesTheColumnAbove() {
		CutawayShape.Bubble bubble = RtsCutawayFocus.bubbleFor(new RtsCutawayFocus.Anchor(10, 64, -20), 0, 0.0);

		assertTrue(bubble.contains(10, 64, -20));
		assertTrue(bubble.contains(10, 64 + RtsCutawayFocus.HEIGHT - 1, -20));
		assertFalse(bubble.contains(10, 64 + RtsCutawayFocus.HEIGHT, -20));
		// The floor block under the anchor stays.
		assertFalse(bubble.contains(10, 63, -20));
		// Within the radius around the column, at every height.
		assertTrue(bubble.contains(15, 64, -20));
		assertTrue(bubble.contains(10, 80, -25));
		assertTrue(bubble.contains(13, 70, -16));
		assertFalse(bubble.contains(16, 64, -20));
		assertFalse(bubble.contains(14, 70, -16));
		assertFalse(bubble.contains(10, 80, -26));
	}

	@Test
	void slantedBubbleLeansTowardTheCameraForEveryFacing() {
		RtsCutawayFocus.Anchor anchor = new RtsCutawayFocus.Anchor(0, 64, 0);
		for (int facing = 0; facing < RtsCutawayFocus.FACINGS; facing++) {
			CutawayShape.Bubble bubble = RtsCutawayFocus.bubbleFor(anchor, facing, 1.0);
			int sideX = RtsCutawayFocus.cameraSideX(facing);
			int sideZ = RtsCutawayFocus.cameraSideZ(facing);
			double unit = sideX != 0 && sideZ != 0 ? Math.sqrt(0.5) : 1.0;
			for (int dy = 0; dy < RtsCutawayFocus.HEIGHT; dy += 4) {
				// The disc centre at height dy is slope * dy toward the camera.
				int cx = (int) Math.round(sideX * unit * dy);
				int cz = (int) Math.round(sideZ * unit * dy);
				assertTrue(bubble.contains(cx, 64 + dy, cz), "facing " + facing + " dy " + dy);
				if (dy >= 12) {
					// Far up, the anchor column itself has been left behind.
					assertFalse(bubble.contains(0, 64 + dy, 0), "facing " + facing + " dy " + dy);
					// And the bubble never leans away from the camera.
					assertFalse(bubble.contains(-cx, 64 + dy, -cz), "facing " + facing + " dy " + dy);
				}
			}
		}
	}

	@Test
	void cameraSideMatchesTheYawLookDirection() {
		for (int facing = 0; facing < RtsCutawayFocus.FACINGS; facing++) {
			double yaw = Math.toRadians(facing * 45.0);
			// Minecraft looks along (-sin yaw, cos yaw); the camera sits on the other side.
			assertEquals((int) Math.signum(Math.round(Math.sin(yaw) * 10)), RtsCutawayFocus.cameraSideX(facing), "x " + facing);
			assertEquals((int) Math.signum(Math.round(-Math.cos(yaw) * 10)), RtsCutawayFocus.cameraSideZ(facing), "z " + facing);
		}
	}

	@Test
	void bubbleOverAHillsideNeverHidesTheHoveredBlock() {
		// Hovering the top of block (5, 70, 5) on a slope: the bubble starts in the cell above it.
		for (int facing = 0; facing < RtsCutawayFocus.FACINGS; facing++) {
			for (double slope : RtsCutawayFocus.SLOPES) {
				RtsCutawayFocus.Anchor anchor = RtsCutawayFocus.anchor(null, 5, 71, 5);
				CutawayShape.Bubble bubble = RtsCutawayFocus.bubbleFor(anchor, facing, slope);
				for (int dx = -6; dx <= 6; dx++) {
					for (int dz = -6; dz <= 6; dz++) {
						assertFalse(bubble.contains(5 + dx, 70, 5 + dz));
					}
				}
			}
		}
	}

	@Test
	void boxClassifiesOnTheInflatedSpan() {
		CutawayShape.Box box = new CutawayShape.Box(-1, -1, -1, 16, 16, 16);
		assertEquals(Section.INSIDE, box.classify(0, 0, 0));
		assertEquals(Section.BOUNDARY, box.classify(1, 0, 0));
		assertEquals(Section.BOUNDARY, box.classify(-1, 0, 0));
		assertEquals(Section.OUTSIDE, box.classify(2, 0, 0));
		assertEquals(Section.OUTSIDE, box.classify(0, -2, 0));

		CutawayShape.Box roof = new CutawayShape.Box(0, 70, 0, 4, Integer.MAX_VALUE, 4);
		assertTrue(roof.contains(2, 1000, 2));
		assertEquals(Section.BOUNDARY, roof.classify(0, 30, 0));
		assertEquals(Integer.MAX_VALUE, roof.sectionBounds().maxY());
	}

	@Test
	void aboveMatchesTheViewLevelRule() {
		CutawayShape.Above above = new CutawayShape.Above(40);
		assertFalse(above.contains(0, 40, 0));
		assertTrue(above.contains(0, 41, 0));
		assertTrue(above.hidesEntities());
		assertEquals(2, above.sectionBounds().minY());
		assertEquals(Section.OUTSIDE, above.classify(0, 1, 0));
		assertEquals(Section.BOUNDARY, above.classify(0, 2, 0));
	}

	private static void checkSection(CutawayShape shape, int sx, int sy, int sz) {
		Section section = shape.classify(sx, sy, sz);
		boolean any = false;
		boolean all = true;
		for (int y = sy * 16 - 1; y <= sy * 16 + 16; y++) {
			for (int z = sz * 16 - 1; z <= sz * 16 + 16; z++) {
				for (int x = sx * 16 - 1; x <= sx * 16 + 16; x++) {
					boolean in = shape.contains(x, y, z);
					any |= in;
					all &= in;
				}
			}
		}
		String where = shape + " at " + sx + "," + sy + "," + sz;
		if (section == Section.INSIDE) {
			assertTrue(all, "INSIDE but not all hidden: " + where);
		} else if (section == Section.OUTSIDE) {
			assertFalse(any, "OUTSIDE but some hidden: " + where);
		}
		// BOUNDARY may be conservative, but a mixed section must be BOUNDARY.
		if (any && !all) {
			assertEquals(Section.BOUNDARY, section, "mixed section: " + where);
		}
	}

	private static CutawayShape randomShape(Random random) {
		int x = random.nextInt(80) - 40;
		int y = random.nextInt(80) - 40;
		int z = random.nextInt(80) - 40;
		if (random.nextInt(3) == 0) {
			int maxY = random.nextInt(4) == 0 ? Integer.MAX_VALUE : y + random.nextInt(40);
			return new CutawayShape.Box(x, y, z, x + random.nextInt(40), maxY, z + random.nextInt(40));
		}
		// Radii up to 24 so some bubbles swallow whole sections.
		double radius = 1 + random.nextInt(24) + (random.nextBoolean() ? 0.5 : 0.0);
		return new CutawayShape.Bubble(x, y, z, radius, 1 + random.nextInt(40), (random.nextInt(9) - 4) * 0.25,
				(random.nextInt(9) - 4) * 0.25);
	}
}
