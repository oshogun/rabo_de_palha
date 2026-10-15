package io.github.oshogun.rabodepalha.worker.task;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * World-free decisions for the tend-farm loop: what to do next given the field, the inventory and the
 * container, which problem (if any) to report, and which inventory slots survive a deposit.
 */
final class TendPlanner {
	private TendPlanner() {
	}

	/** What one field column holds, as far as tending is concerned. */
	enum Column {
		/** A ripe plant: harvest and replant it. */
		RIPE,
		/** Farmland with air above: plant a seed. */
		EMPTY_FARMLAND,
		/** Dirt that was farmland and dried back: till it again. */
		DRY_DIRT,
		/** A plant that is not ripe yet. */
		GROWING,
		/** Anything else: left alone. */
		OTHER
	}

	/** The state of the optional container produce goes to. */
	enum Sink {
		/** No container was given. */
		NONE,
		/** The container exists and has room. */
		OK,
		/** The container exists but is full. */
		FULL,
		/** The given block is not a container. */
		NOT_A_CONTAINER
	}

	/** The next step of the loop. Column indices refer to the list passed to {@link #next}. */
	sealed interface Action {
	}

	record Harvest(int index) implements Action {
	}

	record Plant(int index) implements Action {
	}

	record Till(int index) implements Action {
	}

	record Deposit() implements Action {
	}

	record Wait() implements Action {
	}

	/**
	 * The next action. Priority: till dried dirt (needs a hoe), plant empty farmland (needs seeds), harvest a
	 * ripe plant (needs room for the drops), deposit when there is no room, something is ripe and the
	 * container takes items, otherwise wait. Within a kind, the first column in list order wins.
	 */
	static Action next(List<Column> columns, boolean hasSeed, boolean hasHoe, boolean roomForDrops, Sink sink) {
		if (hasHoe) {
			int dry = columns.indexOf(Column.DRY_DIRT);
			if (dry >= 0) {
				return new Till(dry);
			}
		}
		if (hasSeed) {
			int empty = columns.indexOf(Column.EMPTY_FARMLAND);
			if (empty >= 0) {
				return new Plant(empty);
			}
		}
		int ripe = columns.indexOf(Column.RIPE);
		if (ripe >= 0) {
			if (roomForDrops) {
				return new Harvest(ripe);
			}
			if (sink == Sink.OK) {
				return new Deposit();
			}
		}
		return new Wait();
	}

	/**
	 * The notice key to report when ripe plants are left because the drops have nowhere to go: the inventory
	 * has no room and the container is missing, full or not a container. Empty when nothing is skipped.
	 */
	static Optional<String> problem(List<Column> columns, boolean roomForDrops, Sink sink) {
		if (roomForDrops || !columns.contains(Column.RIPE)) {
			return Optional.empty();
		}
		return switch (sink) {
			case NONE -> Optional.of("no_container");
			case FULL -> Optional.of("container_full");
			case NOT_A_CONTAINER -> Optional.of("not_a_container");
			case OK -> Optional.empty();
		};
	}

	/** One inventory slot as seen by {@link #keepSlots}: a tool, or a seed with its item id, or neither. */
	record Slot(boolean isTool, String seedId) {
		static final Slot EMPTY = new Slot(false, null);
	}

	/**
	 * Indices of the slots a deposit leaves in the inventory: every tool, and the first stack of each seed
	 * id in {@code plantedSeeds}. Every other slot is deposited.
	 */
	static Set<Integer> keepSlots(List<Slot> slots, Set<String> plantedSeeds) {
		Set<Integer> keep = new HashSet<>();
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < slots.size(); i++) {
			Slot slot = slots.get(i);
			if (slot.isTool()) {
				keep.add(i);
			} else if (slot.seedId() != null && plantedSeeds.contains(slot.seedId()) && seen.add(slot.seedId())) {
				keep.add(i);
			}
		}
		return keep;
	}
}
