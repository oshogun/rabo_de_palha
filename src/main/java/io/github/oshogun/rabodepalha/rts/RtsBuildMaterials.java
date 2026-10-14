package io.github.oshogun.rabodepalha.rts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * What a blueprint needs against what the selected workers carry, for the RTS materials panel.
 * {@link io.github.oshogun.rabodepalha.worker.task.BuildTask} checks each worker on its own, so a
 * worker is ready only if it alone carries everything, and items split across workers do not add up.
 * It needs no world, so it can be unit-tested.
 */
public final class RtsBuildMaterials {
	private RtsBuildMaterials() {
	}

	/** One needed item: how many the blueprint takes and the most any single worker carries. */
	public record Row(Item item, int needed, int bestHave) {
		public boolean missing() {
			return bestHave < needed;
		}
	}

	/** The rows in {@code needed}'s iteration order, and how many of the checked workers carry everything. */
	public record Report(List<Row> rows, int workersReady, int workersChecked) {
		public Report {
			rows = List.copyOf(rows);
		}
	}

	public static Report compare(Map<Item, Integer> needed, List<List<ItemStack>> perWorkerInventories) {
		List<Map<Item, Integer>> counts = new ArrayList<>(perWorkerInventories.size());
		for (List<ItemStack> inventory : perWorkerInventories) {
			Map<Item, Integer> count = new HashMap<>();
			for (ItemStack stack : inventory) {
				if (!stack.isEmpty()) {
					count.merge(stack.getItem(), stack.getCount(), Integer::sum);
				}
			}
			counts.add(count);
		}

		List<Row> rows = new ArrayList<>(needed.size());
		for (Map.Entry<Item, Integer> entry : needed.entrySet()) {
			int best = 0;
			for (Map<Item, Integer> count : counts) {
				best = Math.max(best, count.getOrDefault(entry.getKey(), 0));
			}
			rows.add(new Row(entry.getKey(), entry.getValue(), best));
		}

		int ready = 0;
		for (Map<Item, Integer> count : counts) {
			boolean hasAll = true;
			for (Map.Entry<Item, Integer> entry : needed.entrySet()) {
				if (count.getOrDefault(entry.getKey(), 0) < entry.getValue()) {
					hasAll = false;
					break;
				}
			}
			if (hasAll) {
				ready++;
			}
		}
		return new Report(rows, ready, counts.size());
	}
}
