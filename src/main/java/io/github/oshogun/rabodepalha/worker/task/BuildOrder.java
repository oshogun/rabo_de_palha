package io.github.oshogun.rabodepalha.worker.task;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.core.BlockPos;

/** World-free build order for {@code BuildTask}: free-standing blocks before attached ones, each group bottom-up. */
final class BuildOrder {
	/** A blueprint entry to place; {@code attached} if it hangs on a neighbour (torch, ladder, door, ...). */
	record Entry(BlockPos pos, boolean attached) {
	}

	private static final Comparator<Entry> ORDER = Comparator.comparing(Entry::attached)
			.thenComparingInt(entry -> entry.pos().getY())
			.thenComparingInt(entry -> entry.pos().getX())
			.thenComparingInt(entry -> entry.pos().getZ());

	private BuildOrder() {
	}

	/**
	 * {@code entries} in placing order: non-attached entries first, then attached ones; within each group
	 * by lower y, then lower x, then lower z. Returns a new list; {@code entries} is not changed.
	 */
	static List<Entry> order(List<Entry> entries) {
		List<Entry> ordered = new ArrayList<>(entries);
		ordered.sort(ORDER);
		return ordered;
	}
}
