package io.github.oshogun.rabodepalha.rts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

/**
 * Items the RTS panel offers to pick from: the selected workers' inventories, combined by item id.
 * Entries carry a plain count rather than an ItemStack, so totals may exceed the max stack size.
 */
public final class RtsItemPicker {
	private RtsItemPicker() {
	}

	/** An item and its total count; the count may exceed the item's max stack size. */
	public record Entry(Identifier item, int count) {
		public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
				Identifier.STREAM_CODEC, Entry::item,
				ByteBufCodecs.VAR_INT, Entry::count,
				Entry::new);

		public Entry {
			if (count < 1) {
				throw new IllegalArgumentException("count must be >= 1: " + count);
			}
		}
	}

	/** Sums the stacks of every inventory by item id, skipping empty stacks, in first-seen order. */
	public static List<Entry> combine(List<List<ItemStack>> inventories) {
		Map<Identifier, Integer> totals = new LinkedHashMap<>();
		for (List<ItemStack> inventory : inventories) {
			for (ItemStack stack : inventory) {
				if (!stack.isEmpty()) {
					totals.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()), stack.getCount(), Integer::sum);
				}
			}
		}
		List<Entry> entries = new ArrayList<>(totals.size());
		totals.forEach((item, count) -> entries.add(new Entry(item, count)));
		return entries;
	}

	/** Whether the id names a registered block item, i.e. something a PLACE order can use. */
	public static boolean isBlock(Identifier item) {
		return BuiltInRegistries.ITEM.getOptional(item).map(i -> i instanceof BlockItem).orElse(false);
	}
}
