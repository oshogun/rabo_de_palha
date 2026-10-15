package io.github.oshogun.rabodepalha.worker.task;

import java.util.Optional;
import java.util.function.IntPredicate;

import io.github.oshogun.rabodepalha.task.FailureReason;
import io.github.oshogun.rabodepalha.task.Task;
import io.github.oshogun.rabodepalha.task.TaskOutcome;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Walks into reach of the container at {@link #pos()} and puts every stack of {@link #itemId()} into
 * it, or the whole inventory except damageable items (tools, weapons, armor) if no item is given;
 * naming an item deposits it even if damageable. Whatever does not fit stays with the worker:
 * {@link FailureReason#CONTAINER_FULL} if there was no room, {@link FailureReason#ITEM_REFUSED} if the
 * container's slot rules accept none of the leftover items.
 */
public final class DepositTask implements Task<WorkerEntity> {
	public static final String TYPE_ID = "deposit";

	private final BlockPos pos;
	private final Optional<Identifier> itemId;
	private Approach approach;
	private Item item;
	private TaskOutcome startFailure;

	public DepositTask(BlockPos pos, Optional<Identifier> itemId) {
		this.pos = pos.immutable();
		this.itemId = itemId;
	}

	public BlockPos pos() {
		return pos;
	}

	/** The item to deposit; empty means everything. */
	public Optional<Identifier> itemId() {
		return itemId;
	}

	@Override
	public void start(WorkerEntity worker) {
		startFailure = null;
		item = null;
		if (itemId.isPresent()) {
			Identifier id = itemId.get();
			// ITEM is a defaulted registry (unknown ids resolve to air), so check the key first.
			if (!BuiltInRegistries.ITEM.containsKey(id) || BuiltInRegistries.ITEM.getValue(id) == Items.AIR) {
				startFailure = TaskOutcome.failed(FailureReason.MISSING_ITEM, "unknown item " + id);
				return;
			}
			item = BuiltInRegistries.ITEM.getValue(id);
			if (!worker.hasItem(item)) {
				startFailure = TaskOutcome.failed(FailureReason.MISSING_ITEM, "no " + id + " in inventory");
				return;
			}
		}
		approach = Approach.toWorkOn(pos, Approach.DEFAULT_TIMEOUT);
		approach.start(worker);
	}

	@Override
	public TaskOutcome tick(WorkerEntity worker) {
		if (startFailure != null) {
			return startFailure;
		}
		switch (approach.tick(worker)) {
			case PATHING:
				return TaskOutcome.RUNNING;
			case FAILED:
				return approach.failure();
			case ARRIVED:
				break;
		}

		// Looked up on arrival: the block may have changed while walking.
		Level level = worker.level();
		Container container = Containers.find(level, pos);
		if (container == null) {
			return TaskOutcome.failed(FailureReason.NOT_A_CONTAINER,
					level.getBlockState(pos).getBlock().getName().getString() + " at " + pos.toShortString() + " is not a container");
		}

		SimpleContainer inventory = worker.getInventory();
		int kept = 0;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			// Deposit-all leaves the worker its tools, weapons and armor; naming one still deposits it.
			if (item == null && stack.isDamageableItem()) {
				kept += stack.getCount();
			}
		}
		Moved moved = insertSlots(container, inventory, i -> {
			ItemStack stack = inventory.getItem(i);
			return item != null ? stack.is(item) : !stack.isDamageableItem();
		});
		int deposited = moved.deposited();
		int left = moved.left();
		int refused = moved.refused();
		if (deposited > 0) {
			inventory.setChanged();
			worker.getLookControl().setLookAt(Vec3.atCenterOf(pos));
			worker.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		}

		if (deposited == 0 && left == 0 && refused == 0) {
			// The named item can be lost while walking; only deposit-all treats an empty inventory as done.
			if (item != null) {
				return TaskOutcome.failed(FailureReason.MISSING_ITEM, "no " + itemId.get() + " in inventory");
			}
			return TaskOutcome.succeeded("nothing to deposit" + keptNote(kept));
		}
		if (left > 0) {
			return TaskOutcome.failed(FailureReason.CONTAINER_FULL, "deposited " + deposited + ", " + left + " left"
					+ (refused > 0 ? ", " + refused + " not accepted" : ""));
		}
		if (refused > 0) {
			return TaskOutcome.failed(FailureReason.ITEM_REFUSED, "deposited " + deposited + ", " + refused + " not accepted");
		}
		return TaskOutcome.succeeded("deposited " + deposited + " items" + keptNote(kept));
	}

	/** What {@link #insertSlots} moved and what it left with the worker. */
	record Moved(int deposited, int left, int refused) {
	}

	/**
	 * Moves the non-empty inventory slots that {@code include} accepts into {@code container}, as far as
	 * they fit. Counts what moved, what stayed for lack of room and what the slot rules refused.
	 */
	static Moved insertSlots(Container container, SimpleContainer inventory, IntPredicate include) {
		int deposited = 0;
		int left = 0;
		int refused = 0;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty() || !include.test(i)) {
				continue;
			}
			deposited += Containers.insert(container, stack);
			if (stack.isEmpty()) {
				continue;
			}
			if (Containers.accepts(container, stack)) {
				left += stack.getCount();
			} else {
				refused += stack.getCount();
			}
		}
		return new Moved(deposited, left, refused);
	}

	private static String keptNote(int kept) {
		return kept > 0 ? ", kept " + kept + " tools" : "";
	}

	@Override
	public void stop(WorkerEntity worker, boolean cancelled) {
		if (approach != null) {
			approach.stop(worker);
		}
	}

	@Override
	public String describe() {
		return "deposit " + itemId.map(Identifier::toString).orElse("all") + " into " + pos.toShortString();
	}

	@Override
	public String typeId() {
		return TYPE_ID;
	}
}
