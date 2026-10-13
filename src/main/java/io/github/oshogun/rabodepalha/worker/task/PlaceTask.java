package io.github.oshogun.rabodepalha.worker.task;

import io.github.oshogun.rabodepalha.task.FailureReason;
import io.github.oshogun.rabodepalha.task.Task;
import io.github.oshogun.rabodepalha.task.TaskOutcome;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Walks into reach of {@link #pos()} and places the default state of {@link #blockId()} there,
 * consuming one matching item from the inventory. The block is kept as an id so the task stays
 * codec-friendly; it is resolved in {@link #start}.
 */
public final class PlaceTask implements Task<WorkerEntity> {
	public static final String TYPE_ID = "place";

	private final BlockPos pos;
	private final Identifier blockId;
	private Approach approach;
	private Block block;
	private Item item;
	private TaskOutcome startFailure;

	public PlaceTask(BlockPos pos, Identifier blockId) {
		this.pos = pos.immutable();
		this.blockId = blockId;
	}

	public BlockPos pos() {
		return pos;
	}

	public Identifier blockId() {
		return blockId;
	}

	@Override
	public void start(WorkerEntity worker) {
		startFailure = null;
		// BLOCK is a defaulted registry (unknown ids resolve to air), so check the key first.
		if (!BuiltInRegistries.BLOCK.containsKey(blockId)) {
			startFailure = TaskOutcome.failed(FailureReason.MISSING_ITEM, "unknown block " + blockId);
			return;
		}
		block = BuiltInRegistries.BLOCK.getValue(blockId);
		item = block.asItem();
		if (item == Items.AIR) {
			startFailure = TaskOutcome.failed(FailureReason.MISSING_ITEM, blockId + " has no item");
			return;
		}
		if (!worker.hasItem(item)) {
			startFailure = missingItem();
			return;
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

		Level level = worker.level();
		BlockState state = block.defaultBlockState();
		if (!worker.hasItem(item)) {
			return missingItem();
		}
		if (!level.getBlockState(pos).canBeReplaced()) {
			return TaskOutcome.failed(FailureReason.NOT_REPLACEABLE, "block at " + pos.toShortString() + " is not replaceable");
		}
		if (!state.canSurvive(level, pos)) {
			return TaskOutcome.failed(FailureReason.CANNOT_SURVIVE, blockId + " cannot survive at " + pos.toShortString());
		}
		// Entities (the worker included) count as obstructions.
		if (!level.isUnobstructed(state, pos, CollisionContext.empty())) {
			return TaskOutcome.failed(FailureReason.OBSTRUCTED, pos.toShortString() + " is obstructed");
		}

		worker.getLookControl().setLookAt(Vec3.atCenterOf(pos));
		worker.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		level.setBlock(pos, state, Block.UPDATE_ALL);
		worker.consumeOne(item);
		SoundType sound = state.getSoundType();
		level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
		return TaskOutcome.succeeded("placed " + block.getName().getString());
	}

	private TaskOutcome missingItem() {
		return TaskOutcome.failed(FailureReason.MISSING_ITEM, "no " + blockId + " in inventory");
	}

	@Override
	public void stop(WorkerEntity worker, boolean cancelled) {
		if (approach != null) {
			approach.stop(worker);
		}
	}

	@Override
	public String describe() {
		return "place " + blockId + " at " + pos.toShortString();
	}

	@Override
	public String typeId() {
		return TYPE_ID;
	}
}
