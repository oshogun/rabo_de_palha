package io.github.oshogun.rabodepalha.rts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where to dig water holes so a field stays wet. Farmland is wet when water is within {@link #RANGE}
 * blocks horizontally, at its own level or one block above, as {@code FarmlandBlock.isNearWater} scans
 * {@code pos.offset(-4, 0, -4)} to {@code pos.offset(4, 1, 4)}. One hole therefore keeps a
 * {@link #SPACING}×{@link #SPACING} square wet. It holds no Minecraft types so it can be unit-tested.
 */
public final class RtsIrrigation {
	/** How far water reaches horizontally to keep farmland wet. */
	public static final int RANGE = 4;
	/** The side of the square one hole keeps wet, and the spacing of the hole grid. */
	public static final int SPACING = 2 * RANGE + 1;

	private static final Comparator<Column> BY_Z_THEN_X =
			Comparator.comparingInt(Column::z).thenComparingInt(Column::x);

	private RtsIrrigation() {
	}

	/**
	 * A column of the field. {@code y} is its surface: farmland would sit there, and so would the water
	 * if the column were dug out. {@code holdsWater} tells whether a hole there would keep its water.
	 */
	public record Column(int x, int z, int y, boolean holdsWater) {
		public long key() {
			return RtsIrrigation.key(x, z);
		}
	}

	/** A water block already in the world. */
	public record Water(int x, int y, int z) {
	}

	/**
	 * The holes to dig, ordered by z then x, and the field's other columns split into those kept wet,
	 * by the holes or by water already there, and those left dry. Columns are keyed by {@link #key}.
	 */
	public record Plan(List<Column> holes, Set<Long> wet, Set<Long> dry) {
	}

	/** Packs a column's x and z into one key. */
	public static long key(int x, int z) {
		return ((long) x << 32) | (z & 0xFFFFFFFFL);
	}

	/** Whether water at {@code (wx, wy, wz)} keeps farmland at {@code (fx, fy, fz)} wet. */
	public static boolean hydrates(int wx, int wy, int wz, int fx, int fy, int fz) {
		int dy = wy - fy;
		return Math.abs(wx - fx) <= RANGE && Math.abs(wz - fz) <= RANGE && (dy == 0 || dy == 1);
	}

	/**
	 * Plans the fewest holes, on a regular grid where it can, that keep every column of the field wet.
	 * Columns already kept wet by {@code existing} water need no hole. Each {@link #SPACING}-wide tile
	 * of the field that still has dry columns gets the hole that wets the most of them, nearest its
	 * centre on ties, so a bad spot moves the hole aside and a tile with no good spot gets none. A
	 * greedy pass then adds holes for whatever is still dry, until no spot helps. Holes are never next
	 * to each other, diagonals included. Columns outside the field are ignored.
	 */
	public static Plan plan(RtsField field, List<Column> columns, List<Water> existing) {
		List<Column> inField = new ArrayList<>();
		for (Column column : columns) {
			if (column.x() >= field.minX() && column.x() <= field.maxX()
					&& column.z() >= field.minZ() && column.z() <= field.maxZ()) {
				inField.add(column);
			}
		}
		inField.sort(BY_Z_THEN_X);

		Map<Long, Column> byKey = new HashMap<>();
		for (Column column : inField) {
			byKey.put(column.key(), column);
		}

		Set<Long> wet = new LinkedHashSet<>();
		Set<Long> needs = new LinkedHashSet<>();
		for (Column column : inField) {
			if (wetByExisting(column, existing)) {
				wet.add(column.key());
			} else {
				needs.add(column.key());
			}
		}

		List<Column> holes = new ArrayList<>();
		Set<Long> holeKeys = new HashSet<>();

		int tilesX = (field.sizeX() + SPACING - 1) / SPACING;
		int tilesZ = (field.sizeZ() + SPACING - 1) / SPACING;
		for (int tz = 0; tz < tilesZ; tz++) {
			int startZ = field.minZ() + tz * SPACING;
			int endZ = Math.min(startZ + SPACING - 1, field.maxZ());
			int centreZ = Math.min(startZ + RANGE, field.maxZ());
			for (int tx = 0; tx < tilesX; tx++) {
				int startX = field.minX() + tx * SPACING;
				int endX = Math.min(startX + SPACING - 1, field.maxX());
				int centreX = Math.min(startX + RANGE, field.maxX());

				Column best = null;
				int bestCover = 0;
				long bestDistance = Long.MAX_VALUE;
				for (int z = startZ; z <= endZ; z++) {
					for (int x = startX; x <= endX; x++) {
						Column candidate = byKey.get(key(x, z));
						if (candidate == null || !usable(candidate, holes)) {
							continue;
						}
						int cover = cover(candidate, byKey, needs);
						long dx = x - centreX;
						long dz = z - centreZ;
						long distance = dx * dx + dz * dz;
						if (cover > bestCover || cover > 0 && cover == bestCover && distance < bestDistance) {
							best = candidate;
							bestCover = cover;
							bestDistance = distance;
						}
					}
				}
				if (best != null) {
					dig(best, byKey, needs, wet, holes, holeKeys);
				}
			}
		}

		while (!needs.isEmpty()) {
			Column best = null;
			int bestCover = 0;
			for (Column candidate : inField) {
				if (!usable(candidate, holes)) {
					continue;
				}
				int cover = cover(candidate, byKey, needs);
				if (cover > bestCover) {
					best = candidate;
					bestCover = cover;
				}
			}
			if (best == null) {
				break;
			}
			dig(best, byKey, needs, wet, holes, holeKeys);
		}

		holes.sort(BY_Z_THEN_X);
		wet.removeAll(holeKeys);
		return new Plan(Collections.unmodifiableList(holes), Collections.unmodifiableSet(wet),
				Collections.unmodifiableSet(needs));
	}

	private static boolean wetByExisting(Column column, List<Water> existing) {
		for (Water water : existing) {
			if (hydrates(water.x(), water.y(), water.z(), column.x(), column.y(), column.z())) {
				return true;
			}
		}
		return false;
	}

	/** Whether a hole may go in this column: it holds water and touches no other hole. */
	private static boolean usable(Column candidate, List<Column> holes) {
		if (!candidate.holdsWater()) {
			return false;
		}
		for (Column hole : holes) {
			if (Math.abs(hole.x() - candidate.x()) <= 1 && Math.abs(hole.z() - candidate.z()) <= 1) {
				return false;
			}
		}
		return true;
	}

	/** How many dry columns a hole here would deal with, the hole itself included. */
	private static int cover(Column hole, Map<Long, Column> byKey, Set<Long> needs) {
		int count = 0;
		for (int dz = -RANGE; dz <= RANGE; dz++) {
			for (int dx = -RANGE; dx <= RANGE; dx++) {
				long key = key(hole.x() + dx, hole.z() + dz);
				if (!needs.contains(key)) {
					continue;
				}
				Column column = byKey.get(key);
				if (dx == 0 && dz == 0
						|| hydrates(hole.x(), hole.y(), hole.z(), column.x(), column.y(), column.z())) {
					count++;
				}
			}
		}
		return count;
	}

	private static void dig(Column hole, Map<Long, Column> byKey, Set<Long> needs, Set<Long> wet,
			List<Column> holes, Set<Long> holeKeys) {
		holes.add(hole);
		holeKeys.add(hole.key());
		needs.remove(hole.key());
		for (int dz = -RANGE; dz <= RANGE; dz++) {
			for (int dx = -RANGE; dx <= RANGE; dx++) {
				long key = key(hole.x() + dx, hole.z() + dz);
				Column column = byKey.get(key);
				if (column != null && needs.contains(key)
						&& hydrates(hole.x(), hole.y(), hole.z(), column.x(), column.y(), column.z())) {
					needs.remove(key);
					wet.add(key);
				}
			}
		}
	}
}
