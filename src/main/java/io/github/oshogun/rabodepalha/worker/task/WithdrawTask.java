package io.github.oshogun.rabodepalha.worker.task;

import io.github.oshogun.rabodepalha.task.FailureReason;
import io.github.oshogun.rabodepalha.task.Task;
import io.github.oshogun.rabodepalha.task.TaskOutcome;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Walks into reach of the container at {@link #pos()} and takes {@link #count()} of {@link #itemId()}
 * from it. Each stack only leaves the container as far as it fits in the worker's inventory, so
 * nothing is dropped or lost, whatever its components; a short
 * container or a full inventory fails the task, keeping what was taken.
 */
public final class WithdrawTask implements Task<WorkerEntity> {
	public static final String TYPE_ID = "withdraw";

	private final BlockPos pos;
	private final Identifier itemId;
	private final int count;
	private Approach approach;
	private Item item;
	private TaskOutcome startFailure;

	public WithdrawTask(BlockPos pos, Identifier itemId, int count) {
		if (count < 1) {
			throw new IllegalArgumentException("count must be at least 1, got " + count);
		}
		this.pos = pos.immutable();
		this.itemId = itemId;
		this.count = count;
	}

	public BlockPos pos() {
		return pos;
	}

	public Identifier itemId() {
		return itemId;
	}

	public int count() {
		return count;
	}

	@Override
	public void start(WorkerEntity worker) {
		startFailure = null;
		// ITEM is a defaulted registry (unknown ids resolve to air), so check the key first.
		if (!BuiltInRegistries.ITEM.containsKey(itemId) || BuiltInRegistries.ITEM.getValue(itemId) == Items.AIR) {
			startFailure = TaskOutcome.failed(FailureReason.MISSING_ITEM, "unknown item " + itemId);
			return;
		}
		item = BuiltInRegistries.ITEM.getValue(itemId);
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

		int available = Containers.count(container, item);
		if (available == 0) {
			return TaskOutcome.failed(FailureReason.ITEM_NOT_FOUND, "no " + itemId + " in container");
		}
		int taken = Containers.transfer(container, item, count, worker.getInventory());
		if (taken == 0) {
			return TaskOutcome.failed(FailureReason.INVENTORY_FULL, "took 0 of " + count);
		}
		worker.getLookControl().setLookAt(Vec3.atCenterOf(pos));
		worker.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		if (taken < count) {
			FailureReason reason = available < count ? FailureReason.ITEM_NOT_FOUND : FailureReason.INVENTORY_FULL;
			return TaskOutcome.failed(reason, "took " + taken + " of " + count);
		}
		return TaskOutcome.succeeded("took " + taken + " " + itemId);
	}

	@Override
	public void stop(WorkerEntity worker, boolean cancelled) {
		if (approach != null) {
			approach.stop(worker);
		}
	}

	@Override
	public String describe() {
		return "withdraw " + count + " " + itemId + " from " + pos.toShortString();
	}

	@Override
	public String typeId() {
		return TYPE_ID;
	}
}
