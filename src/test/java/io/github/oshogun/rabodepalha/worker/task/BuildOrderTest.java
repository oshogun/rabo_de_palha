package io.github.oshogun.rabodepalha.worker.task;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import io.github.oshogun.rabodepalha.worker.task.BuildOrder.Entry;

class BuildOrderTest {
	private static Entry solid(int x, int y, int z) {
		return new Entry(new BlockPos(x, y, z), false);
	}

	private static Entry attached(int x, int y, int z) {
		return new Entry(new BlockPos(x, y, z), true);
	}

	@Test
	void bottomUp() {
		List<Entry> ordered = BuildOrder.order(List.of(solid(0, 2, 0), solid(0, 0, 0), solid(0, 1, 0)));

		assertEquals(List.of(solid(0, 0, 0), solid(0, 1, 0), solid(0, 2, 0)), ordered);
	}

	@Test
	void attachedAfterSolids() {
		// The torch sits low, but still waits until every free-standing block is placed.
		List<Entry> ordered = BuildOrder.order(List.of(attached(0, 0, 0), solid(0, 3, 0), attached(1, 1, 0), solid(0, 1, 0)));

		assertEquals(List.of(solid(0, 1, 0), solid(0, 3, 0), attached(0, 0, 0), attached(1, 1, 0)), ordered);
	}

	@Test
	void tiesGoToLowerXThenZ() {
		List<Entry> ordered = BuildOrder.order(List.of(solid(1, 0, 0), solid(0, 0, 2), solid(0, 0, 1), solid(-1, 0, 5)));

		assertEquals(List.of(solid(-1, 0, 5), solid(0, 0, 1), solid(0, 0, 2), solid(1, 0, 0)), ordered);
	}

	@Test
	void inputIsNotChanged() {
		List<Entry> input = new ArrayList<>(List.of(solid(0, 1, 0), solid(0, 0, 0)));

		BuildOrder.order(input);

		assertEquals(List.of(solid(0, 1, 0), solid(0, 0, 0)), input);
	}

	@Test
	void emptyInput() {
		assertEquals(List.of(), BuildOrder.order(List.of()));
	}
}
