package io.github.oshogun.rabodepalha.client.rts;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import io.github.oshogun.rabodepalha.rts.RtsItemPicker;
import io.github.oshogun.rabodepalha.rts.WorkerSnapshot;

/**
 * The item picked in the RTS panel, which PLACE and Deposit use. The pick persists across selection changes,
 * even when the selected workers no longer carry the item, and is forgotten on leaving RTS mode.
 */
public final class RtsItemPickClient {
	private static Optional<Identifier> picked = Optional.empty();

	private RtsItemPickClient() {
	}

	/** The picked item, if any. */
	public static Optional<Identifier> picked() {
		return picked;
	}

	/** Picks {@code item}, or unpicks it when it is already the picked item. */
	public static void pick(Identifier item) {
		picked = picked.filter(item::equals).isPresent() ? Optional.empty() : Optional.of(item);
	}

	/** Forgets the pick. */
	public static void clear() {
		picked = Optional.empty();
	}

	/** The watched workers' inventories combined by item id, in the order the panel's item grid shows them. */
	public static List<RtsItemPicker.Entry> combined() {
		List<List<ItemStack>> inventories = new ArrayList<>();
		for (int id : RtsSnapshotsClient.watched()) {
			WorkerSnapshot snapshot = RtsSnapshotsClient.get(id);
			if (snapshot != null) {
				inventories.add(snapshot.inventory());
			}
		}
		return RtsItemPicker.combine(inventories);
	}
}
