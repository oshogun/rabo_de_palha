package io.github.oshogun.rabodepalha.rts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.ToIntBiFunction;

import org.junit.jupiter.api.Test;

import io.github.oshogun.rabodepalha.rts.RtsIrrigation.Column;
import io.github.oshogun.rabodepalha.rts.RtsIrrigation.Plan;
import io.github.oshogun.rabodepalha.rts.RtsIrrigation.Water;

class RtsIrrigationTest {
	private static final int Y = 64;

	@Test
	void hydratesMatchesIsNearWater() {
		for (int dx = -5; dx <= 5; dx++) {
			for (int dz = -5; dz <= 5; dz++) {
				for (int dy = -2; dy <= 2; dy++) {
					boolean expected = Math.abs(dx) <= 4 && Math.abs(dz) <= 4 && (dy == 0 || dy == 1);
					assertEquals(expected, RtsIrrigation.hydrates(10 + dx, Y + dy, 20 + dz, 10, Y, 20),
							"offset " + dx + "," + dy + "," + dz);
				}
			}
		}
	}

	@Test
	void waterOneBlockUpHydratesButOneBlockDownDoesNot() {
		assertTrue(RtsIrrigation.hydrates(4, Y + 1, 4, 0, Y, 0));
		assertFalse(RtsIrrigation.hydrates(4, Y - 1, 4, 0, Y, 0));
		assertFalse(RtsIrrigation.hydrates(0, Y + 2, 0, 0, Y, 0));
	}

	@Test
	void nineByNineFlatFieldNeedsOneCentredHole() {
		RtsField field = flat(0, 0, 8, 8);
		Plan plan = RtsIrrigation.plan(field, columns(field, (x, z) -> Y, (x, z) -> true), List.of());

		assertEquals(List.of(new Column(4, 4, Y, true)), plan.holes());
		assertEquals(80, plan.wet().size());
		assertTrue(plan.dry().isEmpty());
		assertFalse(plan.wet().contains(RtsIrrigation.key(4, 4)));
	}

	@Test
	void twentyByTwentyFieldIsAllWetWithNineHoles() {
		RtsField field = flat(0, 0, 19, 19);
		Plan plan = RtsIrrigation.plan(field, columns(field, (x, z) -> Y, (x, z) -> true), List.of());

		assertEquals(9, plan.holes().size());
		assertEquals(400 - 9, plan.wet().size());
		assertTrue(plan.dry().isEmpty());
		assertNoAdjacentHoles(plan);
	}

	@Test
	void lShapedFieldGetsOneHolePerSquare() {
		RtsField field = flat(0, 0, 17, 17);
		// the quadrant x >= 9, z >= 9 is not part of the field
		List<Column> columns = columns(field, (x, z) -> Y, (x, z) -> true);
		columns.removeIf(c -> c.x() >= 9 && c.z() >= 9);
		Plan plan = RtsIrrigation.plan(field, columns, List.of());

		assertEquals(3, plan.holes().size());
		assertEquals(columns.size() - 3, plan.wet().size());
		assertTrue(plan.dry().isEmpty());
	}

	@Test
	void stepUpBesideTheHoleStaysDryUntilAnotherHoleCoversIt() {
		RtsField field = flat(0, 0, 8, 8);
		// the row z = 8 is one block higher than the rest and cannot hold water
		Plan plan = RtsIrrigation.plan(field,
				columns(field, (x, z) -> z == 8 ? Y + 1 : Y, (x, z) -> z != 8), List.of());

		assertEquals(List.of(new Column(4, 4, Y, true)), plan.holes());
		assertEquals(9, plan.dry().size());
		for (int x = 0; x <= 8; x++) {
			assertTrue(plan.dry().contains(RtsIrrigation.key(x, 8)));
		}

		Plan covered = RtsIrrigation.plan(field,
				columns(field, (x, z) -> z == 8 ? Y + 1 : Y, (x, z) -> true), List.of());
		assertEquals(2, covered.holes().size());
		assertTrue(covered.dry().isEmpty());
	}

	@Test
	void stepDownBesideTheHoleIsWet() {
		RtsField field = flat(0, 0, 8, 8);
		Plan plan = RtsIrrigation.plan(field,
				columns(field, (x, z) -> z == 8 ? Y - 1 : Y, (x, z) -> true), List.of());

		assertEquals(List.of(new Column(4, 4, Y, true)), plan.holes());
		assertTrue(plan.dry().isEmpty());
		assertTrue(plan.wet().contains(RtsIrrigation.key(0, 8)));
	}

	@Test
	void fieldAlreadyNearWaterNeedsNoHole() {
		RtsField field = flat(0, 0, 7, 7);
		Plan plan = RtsIrrigation.plan(field, columns(field, (x, z) -> Y, (x, z) -> true),
				List.of(new Water(3, Y, -1), new Water(3, Y, 8)));

		assertTrue(plan.holes().isEmpty());
		assertEquals(64, plan.wet().size());
		assertTrue(plan.dry().isEmpty());
	}

	@Test
	void waterOneBlockUpAlsoCountsAsNearby() {
		RtsField field = flat(0, 0, 7, 7);
		Plan plan = RtsIrrigation.plan(field, columns(field, (x, z) -> Y, (x, z) -> true),
				List.of(new Water(3, Y + 1, -1), new Water(3, Y + 1, 8)));

		assertTrue(plan.holes().isEmpty());
	}

	@Test
	void waterBelowTheFieldDoesNotCount() {
		RtsField field = flat(0, 0, 8, 8);
		Plan plan = RtsIrrigation.plan(field, columns(field, (x, z) -> Y, (x, z) -> true),
				List.of(new Water(4, Y - 1, -1), new Water(4, Y - 1, 9)));

		assertEquals(List.of(new Column(4, 4, Y, true)), plan.holes());
	}

	@Test
	void waterBesideHalfTheFieldLeavesHolesOnlyInTheOtherHalf() {
		RtsField field = flat(0, 0, 17, 8);
		// a river along x = -1 keeps x 0..3 wet
		List<Water> river = new ArrayList<>();
		for (int z = -1; z <= 9; z++) {
			river.add(new Water(-1, Y, z));
		}
		Plan plan = RtsIrrigation.plan(field, columns(field, (x, z) -> Y, (x, z) -> true), river);

		assertTrue(plan.dry().isEmpty());
		assertFalse(plan.holes().isEmpty());
		for (Column hole : plan.holes()) {
			assertTrue(hole.x() >= 4, "hole at " + hole);
		}
		assertTrue(plan.holes().size() <= 2);
	}

	@Test
	void holeMovesOffACentreThatCannotHoldWater() {
		RtsField field = flat(0, 0, 8, 8);
		Plan plan = RtsIrrigation.plan(field,
				columns(field, (x, z) -> Y, (x, z) -> !(x == 4 && z == 4)), List.of());

		Column first = plan.holes().get(0);
		assertTrue(Math.abs(first.x() - 4) + Math.abs(first.z() - 4) == 1, "first hole at " + first);
		assertTrue(plan.holes().stream().allMatch(Column::holdsWater));
		assertTrue(plan.dry().isEmpty());
		assertNoAdjacentHoles(plan);
	}

	@Test
	void tileWithNoValidSpotIsSkippedAndLeftDry() {
		RtsField field = flat(0, 0, 8, 8);
		Plan plan = RtsIrrigation.plan(field, columns(field, (x, z) -> Y, (x, z) -> false), List.of());

		assertTrue(plan.holes().isEmpty());
		assertTrue(plan.wet().isEmpty());
		assertEquals(81, plan.dry().size());
	}

	@Test
	void holesAreNeverAdjacent() {
		RtsField field = flat(0, 0, 31, 13);
		// only a sparse checkerboard can hold water, so the planner must pick around it
		Plan plan = RtsIrrigation.plan(field,
				columns(field, (x, z) -> Y + (x / 5) % 2, (x, z) -> (x + z) % 2 == 0), List.of());

		assertNoAdjacentHoles(plan);
		assertTrue(plan.holes().stream().allMatch(Column::holdsWater));
	}

	@Test
	void planIsOrderedAndDeterministic() {
		RtsField field = flat(0, 0, 19, 19);
		List<Column> columns = columns(field, (x, z) -> Y, (x, z) -> true);
		Plan plan = RtsIrrigation.plan(field, columns, List.of());
		List<Column> reversed = new ArrayList<>(columns);
		java.util.Collections.reverse(reversed);

		assertEquals(plan, RtsIrrigation.plan(field, reversed, List.of()));
		for (int i = 1; i < plan.holes().size(); i++) {
			Column a = plan.holes().get(i - 1);
			Column b = plan.holes().get(i);
			assertTrue(a.z() < b.z() || a.z() == b.z() && a.x() < b.x());
		}
	}

	private static RtsField flat(int minX, int minZ, int maxX, int maxZ) {
		return RtsField.of(minX, Y, minZ, maxX, Y, maxZ);
	}

	private static List<Column> columns(RtsField field, ToIntBiFunction<Integer, Integer> surface,
			BiPredicate<Integer, Integer> holds) {
		List<Column> columns = new ArrayList<>();
		for (int z = field.minZ(); z <= field.maxZ(); z++) {
			for (int x = field.minX(); x <= field.maxX(); x++) {
				columns.add(new Column(x, z, surface.applyAsInt(x, z), holds.test(x, z)));
			}
		}
		return columns;
	}

	private static void assertNoAdjacentHoles(Plan plan) {
		List<Column> holes = plan.holes();
		for (int i = 0; i < holes.size(); i++) {
			for (int j = i + 1; j < holes.size(); j++) {
				Column a = holes.get(i);
				Column b = holes.get(j);
				assertFalse(Math.abs(a.x() - b.x()) <= 1 && Math.abs(a.z() - b.z()) <= 1,
						"adjacent holes " + a + " and " + b);
			}
		}
	}
}
