package io.github.oshogun.rabodepalha.worker.task;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Item movement into and out of vanilla containers, as a player with the container open would do it:
 * slot rules ({@link Container#canPlaceItem}) and stack limits apply, face-based
 * {@code WorldlyContainer} rules do not.
 */
final class Containers {
	private Containers() {
	}

	/**
	 * The container of the block entity at {@code pos}, with both halves of a double chest combined, or
	 * null if there is none. Entity containers (minecart chests) and block-only containers (composter)
	 * do not count.
	 */
	static @Nullable Container find(Level level, BlockPos pos) {
		if (!(level.getBlockEntity(pos) instanceof Container container)) {
			return null;
		}
		BlockState state = level.getBlockState(pos);
		if (state.getBlock() instanceof ChestBlock chest) {
			// Ignore a cat or block on the lid: the worker does not need to open it visibly.
			Container combined = ChestBlock.getContainer(chest, state, level, pos, true);
			return combined != null ? combined : container;
		}
		return container;
	}

	/**
	 * Moves as much of {@code stack} into {@code container} as fits, filling matching stacks first and
	 * empty slots second. Shrinks {@code stack} by the amount moved.
	 *
	 * @return the number of items moved
	 */
	static int insert(Container container, ItemStack stack) {
		int moved = 0;
		for (int pass = 0; pass < 2 && !stack.isEmpty(); pass++) {
			boolean emptySlots = pass == 1;
			for (int i = 0; i < container.getContainerSize() && !stack.isEmpty(); i++) {
				ItemStack slot = container.getItem(i);
				if (slot.isEmpty() != emptySlots || !container.canPlaceItem(i, stack)) {
					continue;
				}
				if (!emptySlots && !ItemStack.isSameItemSameComponents(slot, stack)) {
					continue;
				}
				int limit = Math.min(container.getMaxStackSize(stack), stack.getMaxStackSize());
				int n = Math.min(stack.getCount(), limit - slot.getCount());
				if (n <= 0) {
					continue;
				}
				if (emptySlots) {
					container.setItem(i, stack.split(n));
				} else {
					slot.grow(n);
					stack.shrink(n);
				}
				moved += n;
			}
		}
		if (moved > 0) {
			container.setChanged();
		}
		return moved;
	}

	static int count(Container container, Item item) {
		return container.countItem(item);
	}

	/**
	 * Moves up to {@code max} of {@code item}, whatever its components, from {@code from} into
	 * {@code to} with {@link #insert}. Each slot only loses what {@code to} actually accepted, so
	 * nothing is lost when a stack does not fit.
	 *
	 * @return the number of items moved
	 */
	static int transfer(Container from, Item item, int max, Container to) {
		int moved = 0;
		for (int i = 0; i < from.getContainerSize() && moved < max; i++) {
			ItemStack slot = from.getItem(i);
			if (!slot.is(item)) {
				continue;
			}
			int inserted = insert(to, slot.copyWithCount(Math.min(max - moved, slot.getCount())));
			slot.shrink(inserted);
			moved += inserted;
		}
		if (moved > 0) {
			from.setChanged();
		}
		return moved;
	}
}
