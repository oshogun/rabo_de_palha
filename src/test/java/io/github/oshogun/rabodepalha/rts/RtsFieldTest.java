package io.github.oshogun.rabodepalha.rts;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalInt;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class RtsFieldTest {
	@Test
	void cornersInAnyOrderGiveTheSameField() {
		RtsField expected = new RtsField(1, 2, 5, 9, 64 - RtsField.WINDOW, 66 + RtsField.WINDOW);

		assertEquals(expected, RtsField.of(1, 66, 2, 5, 64, 9));
		assertEquals(expected, RtsField.of(5, 64, 9, 1, 66, 2));
		assertEquals(expected, RtsField.of(5, 66, 2, 1, 64, 9));
		assertEquals(expected, RtsField.of(1, 64, 9, 5, 66, 2));
	}

	@Test
	void windowReachesThreeBelowTheLowerCornerAndThreeAboveTheHigher() {
		RtsField field = RtsField.of(0, 70, 0, 4, 65, 4);

		assertEquals(62, field.bottomY());
		assertEquals(73, field.topY());
		assertEquals(5, field.rise());
	}

	@Test
	void flatFieldHasNoRise() {
		assertEquals(0, RtsField.of(0, 64, 0, 9, 64, 9).rise());
	}

	@Test
	void surfaceOnFlatGroundIsTheTopBlock() {
		assertEquals(OptionalInt.of(64), RtsField.surfaceY(61, 67, y -> y <= 64));
	}

	@Test
	void surfaceFollowsStepsAndDipsWithinTheWindow() {
		assertEquals(OptionalInt.of(66), RtsField.surfaceY(61, 67, y -> y <= 66));
		assertEquals(OptionalInt.of(61), RtsField.surfaceY(61, 67, y -> y <= 61));
		assertEquals(OptionalInt.of(67), RtsField.surfaceY(61, 67, y -> y <= 67));
	}

	@Test
	void surfaceSkipsAFloatingBlockAboveTheWindow() {
		assertEquals(OptionalInt.of(63), RtsField.surfaceY(61, 67, y -> y <= 63 || y == 70));
	}

	@Test
	void surfaceUnderAnOverhangIsTheHighestOpenTop() {
		// ground up to 62, air 63..64, a ledge at 65, open above
		assertEquals(OptionalInt.of(65), RtsField.surfaceY(61, 67, y -> y <= 62 || y == 65));
	}

	@Test
	void buriedColumnHasNoSurface() {
		assertEquals(OptionalInt.empty(), RtsField.surfaceY(61, 67, y -> y <= 80));
	}

	@Test
	void columnWithNoGroundHasNoSurface() {
		assertEquals(OptionalInt.empty(), RtsField.surfaceY(61, 67, y -> y <= 50));
		assertEquals(OptionalInt.empty(), RtsField.surfaceY(61, 67, y -> false));
	}

	@Test
	void capIsOnColumns() {
		assertFalse(RtsField.of(0, 64, 0, 31, 64, 31).exceeds());
		assertEquals(1024, RtsField.of(0, 64, 0, 31, 64, 31).columns());
		RtsField over = RtsField.of(0, 64, 0, 1024, 64, 0);
		assertEquals(1025, over.columns());
		assertTrue(over.exceeds());
		assertFalse(RtsField.of(0, 64, 0, 1023, 64, 0).exceeds());
	}

	@Test
	void capIsOnRise() {
		assertFalse(RtsField.of(0, 64, 0, 3, 80, 3).exceeds());
		assertTrue(RtsField.of(0, 64, 0, 3, 81, 3).exceeds());
	}

	@Test
	void columnsDoNotOverflow() {
		RtsField huge = RtsField.of(-30_000_000, 64, -30_000_000, 30_000_000, 64, 30_000_000);

		assertEquals(60_000_001L * 60_000_001L, huge.columns());
		assertTrue(huge.exceeds());
	}

	@Test
	void columnOrderIsSerpentine() {
		List<int[]> order = RtsField.of(0, 64, 0, 2, 64, 1).columnOrder();

		assertEquals(6, order.size());
		assertArrayEquals(new int[] {0, 0}, order.get(0));
		assertArrayEquals(new int[] {1, 0}, order.get(1));
		assertArrayEquals(new int[] {2, 0}, order.get(2));
		assertArrayEquals(new int[] {2, 1}, order.get(3));
		assertArrayEquals(new int[] {1, 1}, order.get(4));
		assertArrayEquals(new int[] {0, 1}, order.get(5));
	}

	@Test
	void columnOrderCoversEveryColumnOnce() {
		RtsField field = RtsField.of(-3, 64, 5, 4, 66, 9);
		List<int[]> order = field.columnOrder();

		assertEquals(field.columns(), order.size());
		assertEquals(field.columns(), order.stream().map(c -> c[0] + "," + c[1]).collect(Collectors.toSet()).size());
	}

	@Test
	void labelShowsSizeAndColumns() {
		assertEquals("12×8 = 96", RtsField.of(0, 64, 0, 11, 64, 7).label());
	}
}
