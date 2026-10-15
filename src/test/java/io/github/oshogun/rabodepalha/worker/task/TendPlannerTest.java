package io.github.oshogun.rabodepalha.worker.task;

import static io.github.oshogun.rabodepalha.worker.task.TendPlanner.Column.DRY_DIRT;
import static io.github.oshogun.rabodepalha.worker.task.TendPlanner.Column.EMPTY_FARMLAND;
import static io.github.oshogun.rabodepalha.worker.task.TendPlanner.Column.GROWING;
import static io.github.oshogun.rabodepalha.worker.task.TendPlanner.Column.OTHER;
import static io.github.oshogun.rabodepalha.worker.task.TendPlanner.Column.RIPE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.github.oshogun.rabodepalha.worker.task.TendPlanner.Column;
import io.github.oshogun.rabodepalha.worker.task.TendPlanner.Sink;
import io.github.oshogun.rabodepalha.worker.task.TendPlanner.Slot;

import org.junit.jupiter.api.Test;

class TendPlannerTest {
	private static final List<Column> ALL = List.of(GROWING, RIPE, EMPTY_FARMLAND, DRY_DIRT, OTHER);

	@Test
	void tillComesFirstWithHoe() {
		assertEquals(new TendPlanner.Till(3), TendPlanner.next(ALL, true, true, true, Sink.OK));
	}

	@Test
	void plantWhenNoHoe() {
		assertEquals(new TendPlanner.Plant(2), TendPlanner.next(ALL, true, false, true, Sink.OK));
	}

	@Test
	void harvestWhenNoSeedsAndRoom() {
		assertEquals(new TendPlanner.Harvest(1), TendPlanner.next(ALL, false, false, true, Sink.NONE));
	}

	@Test
	void firstRipeInListOrderWins() {
		List<Column> columns = List.of(OTHER, RIPE, RIPE);

		assertEquals(new TendPlanner.Harvest(1), TendPlanner.next(columns, true, true, true, Sink.NONE));
	}

	@Test
	void depositWhenNoRoomAndSinkOk() {
		assertEquals(new TendPlanner.Deposit(), TendPlanner.next(List.of(RIPE), true, true, false, Sink.OK));
	}

	@Test
	void waitWhenNoRoomAndSinkUnusable() {
		for (Sink sink : List.of(Sink.NONE, Sink.FULL, Sink.NOT_A_CONTAINER)) {
			assertEquals(new TendPlanner.Wait(), TendPlanner.next(List.of(RIPE), true, true, false, sink));
		}
	}

	@Test
	void noDepositWhenNothingRipe() {
		assertEquals(new TendPlanner.Wait(), TendPlanner.next(List.of(GROWING, OTHER), true, true, false, Sink.OK));
	}

	@Test
	void waitWhenNothingToDo() {
		assertEquals(new TendPlanner.Wait(), TendPlanner.next(List.of(GROWING, OTHER), true, true, true, Sink.OK));
		assertEquals(new TendPlanner.Wait(), TendPlanner.next(List.of(EMPTY_FARMLAND, DRY_DIRT), false, false, true, Sink.OK));
	}

	@Test
	void replantingContinuesWithoutRoom() {
		assertEquals(new TendPlanner.Plant(1), TendPlanner.next(List.of(RIPE, EMPTY_FARMLAND), true, false, false, Sink.NONE));
	}

	@Test
	void problemKeys() {
		List<Column> ripe = List.of(RIPE);

		assertEquals(Optional.of("no_container"), TendPlanner.problem(ripe, false, Sink.NONE));
		assertEquals(Optional.of("container_full"), TendPlanner.problem(ripe, false, Sink.FULL));
		assertEquals(Optional.of("not_a_container"), TendPlanner.problem(ripe, false, Sink.NOT_A_CONTAINER));
		assertEquals(Optional.empty(), TendPlanner.problem(ripe, false, Sink.OK));
	}

	@Test
	void noProblemWithRoomOrNothingRipe() {
		assertEquals(Optional.empty(), TendPlanner.problem(List.of(RIPE), true, Sink.NONE));
		assertEquals(Optional.empty(), TendPlanner.problem(List.of(GROWING), false, Sink.NONE));
	}

	@Test
	void keepsToolsAndOneStackPerPlantedSeed() {
		List<Slot> slots = List.of(
				new Slot(false, "minecraft:wheat_seeds"),
				new Slot(true, null),
				new Slot(false, "minecraft:wheat_seeds"),
				Slot.EMPTY,
				new Slot(false, "minecraft:carrot"),
				new Slot(false, "minecraft:beetroot_seeds"),
				new Slot(false, "minecraft:carrot"));

		Set<Integer> keep = TendPlanner.keepSlots(slots, Set.of("minecraft:wheat_seeds", "minecraft:carrot"));

		assertEquals(Set.of(0, 1, 4), keep);
	}
}
