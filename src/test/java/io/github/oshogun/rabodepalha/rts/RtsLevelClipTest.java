package io.github.oshogun.rabodepalha.rts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class RtsLevelClipTest {
	private static final int NO_LEVEL = Integer.MAX_VALUE;

	private static String key(int x, int y, int z) {
		return x + "," + y + "," + z;
	}

	/** Traverses the ray against the given solid blocks and returns the key of the first one hit. */
	private static String hit(double sx, double sy, double sz, double ex, double ey, double ez, int level,
			Set<String> solid) {
		return RtsLevelClip.traverse(sx, sy, sz, ex, ey, ez, level, (x, y, z) -> {
			String k = key(x, y, z);
			return solid.contains(k) ? k : null;
		});
	}

	/** Every block the ray visits, in order, with nothing solid. */
	private static List<String> visited(double sx, double sy, double sz, double ex, double ey, double ez,
			int level) {
		List<String> out = new ArrayList<>();
		RtsLevelClip.traverse(sx, sy, sz, ex, ey, ez, level, (x, y, z) -> {
			out.add(key(x, y, z));
			return null;
		});
		return out;
	}

	@Test
	void roofAboveLevelIsSkippedAndFloorHit() {
		Set<String> house = Set.of(key(0, 15, 0), key(0, 5, 0));

		assertEquals(key(0, 5, 0), hit(0.5, 20.5, 0.5, 0.5, 0.0, 0.5, 10, house));
	}

	@Test
	void noLevelHitsRoof() {
		Set<String> house = Set.of(key(0, 15, 0), key(0, 5, 0));

		assertEquals(key(0, 15, 0), hit(0.5, 20.5, 0.5, 0.5, 0.0, 0.5, NO_LEVEL, house));
	}

	@Test
	void startInsideSolidAboveLevelPassesThrough() {
		Set<String> rock = Set.of(key(0, 20, 0), key(0, 19, 0), key(0, 5, 0));

		assertEquals(key(0, 5, 0), hit(0.5, 20.5, 0.5, 0.5, 0.0, 0.5, 10, rock));
		// Without a level the start voxel itself is visited and hit.
		assertEquals(key(0, 20, 0), hit(0.5, 20.5, 0.5, 0.5, 0.0, 0.5, NO_LEVEL, rock));
	}

	@Test
	void visitorNeverCalledAboveLevel() {
		List<String> seen = new ArrayList<>();
		RtsLevelClip.traverse(0.3, 30.7, -2.2, 7.9, -4.1, 5.6, 8, (x, y, z) -> {
			assertTrue(y <= 8, "visited y " + y);
			seen.add(key(x, y, z));
			return null;
		});

		assertTrue(!seen.isEmpty());
	}

	@Test
	void diagonalVisitsVoxelsInRayOrder() {
		assertEquals(List.of(key(0, 0, 0), key(1, 0, 0), key(2, 0, 0), key(2, 0, 1)),
				visited(0.5, 0.5, 0.2, 2.5, 0.5, 1.2, NO_LEVEL));
	}

	@Test
	void longDiagonalIsFaceConnected() {
		List<String> path = visited(-3.3, 12.6, 4.1, 9.8, -2.4, -6.7, NO_LEVEL);

		assertEquals(key(-4, 12, 4), path.get(0));
		assertEquals(key(9, -3, -7), path.get(path.size() - 1));
		for (int i = 1; i < path.size(); i++) {
			int[] a = parse(path.get(i - 1));
			int[] b = parse(path.get(i));
			int moved = Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]);
			assertEquals(1, moved, path.get(i - 1) + " -> " + path.get(i));
		}
	}

	@Test
	void axisAlignedRayWalksOneAxis() {
		assertEquals(List.of(key(0, 5, 0), key(0, 4, 0), key(0, 3, 0), key(0, 2, 0), key(0, 1, 0), key(0, 0, 0)),
				visited(0.5, 5.5, 0.5, 0.5, 0.5, 0.5, NO_LEVEL));
		assertEquals(List.of(key(-2, 7, 3), key(-1, 7, 3), key(0, 7, 3)),
				visited(-1.5, 7.5, 3.5, 0.5, 7.5, 3.5, NO_LEVEL));
	}

	@Test
	void exactBoundariesVisitEachBlockOnce() {
		// Both ends are nudged outward as vanilla does, so an end exactly on y=0 also touches y=-1.
		assertEquals(List.of(key(1, 3, 0), key(1, 2, 0), key(1, 1, 0), key(1, 0, 0), key(1, -1, 0)),
				visited(1.0, 3.0, 0.5, 1.0, 0.0, 0.5, NO_LEVEL));
		assertEquals(List.of(key(1, 2, 0), key(1, 1, 0), key(1, 0, 0), key(1, -1, 0)),
				visited(1.0, 3.0, 0.5, 1.0, 0.0, 0.5, 2));
	}

	@Test
	void missReturnsNull() {
		assertNull(hit(0.5, 20.5, 0.5, 0.5, 0.0, 0.5, 10, Set.of(key(0, 15, 0))));
		assertNull(hit(0.5, 20.5, 0.5, 0.5, 0.0, 0.5, NO_LEVEL, Set.of(key(1, 5, 0))));
		assertNull(hit(0.5, 0.5, 0.5, 0.5, 0.5, 0.5, NO_LEVEL, Set.of(key(0, 0, 0))));
	}

	private static int[] parse(String key) {
		String[] parts = key.split(",");
		return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
	}
}
