package io.github.oshogun.rabodepalha.worker.task;

import java.util.List;

import io.github.oshogun.rabodepalha.task.FailureReason;
import io.github.oshogun.rabodepalha.task.Task;
import io.github.oshogun.rabodepalha.task.TaskOutcome;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Walks into reach of {@link #pos()} and places {@link #state()} there, properties included,
 * consuming one matching item from the inventory.
 *
 * <p>Two-part blocks are placed whole or not at all. For blocks with a
 * {@link BlockStateProperties#DOUBLE_BLOCK_HALF} (doors, tall plants) the lower half goes at
 * {@code pos} and the upper half above it; a requested upper half is normalized to the lower half
 * at {@code pos}. For beds the foot goes at {@code pos} and the head in the bed's facing direction.
 */
public final class PlaceTask implements Task<WorkerEntity> {
	public static final String TYPE_ID = "place";

	/** One block of the placement: {@code name} is how chat messages refer to it. */
	record Part(String name, BlockPos pos, BlockState state) {
	}

	private final BlockPos pos;
	private final BlockState state;
	private Approach approach;
	private Block block;
	private Item item;
	private TaskOutcome startFailure;

	public PlaceTask(BlockPos pos, BlockState state) {
		this.pos = pos.immutable();
		this.state = state;
	}

	/** Places the default state of {@code block}. */
	public static PlaceTask of(BlockPos pos, Block block) {
		return new PlaceTask(pos, block.defaultBlockState());
	}

	public BlockPos pos() {
		return pos;
	}

	public BlockState state() {
		return state;
	}

	@Override
	public void start(WorkerEntity worker) {
		startFailure = null;
		block = state.getBlock();
		item = block.asItem();
		if (state.isAir() || item == Items.AIR) {
			startFailure = TaskOutcome.failed(FailureReason.MISSING_ITEM, stateString() + " has no item");
			return;
		}
		if (!worker.hasItem(item)) {
			startFailure = missingItem();
			return;
		}
		// Stay out of the cells being filled, so the worker never obstructs its own placement.
		approach = Approach.toWorkOnClearOf(pos, parts().stream().map(Part::pos).toList(), Approach.DEFAULT_TIMEOUT);
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
		if (!worker.hasItem(item)) {
			return missingItem();
		}
		List<Part> parts = parts();
		// Validate every part before touching the world, so a failure never leaves half a block.
		for (Part part : parts) {
			if (level.isOutsideBuildHeight(part.pos()) || !level.getBlockState(part.pos()).canBeReplaced()) {
				return failed(FailureReason.NOT_REPLACEABLE, part, "is not replaceable");
			}
			// Entities count as obstructions; the approach keeps the worker itself out of the way.
			if (!level.isUnobstructed(part.state(), part.pos(), CollisionContext.empty())) {
				return failed(FailureReason.OBSTRUCTED, part, "is obstructed");
			}
		}
		// Only the primary part: the others' survival depends on the primary already being there.
		Part primary = parts.get(0);
		if (!primary.state().canSurvive(level, primary.pos())) {
			return failed(FailureReason.CANNOT_SURVIVE, primary, "cannot survive there");
		}

		worker.getLookControl().setLookAt(Vec3.atCenterOf(pos));
		worker.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		BlockState replaced = level.getBlockState(primary.pos());
		if (!level.setBlock(primary.pos(), primary.state(), Block.UPDATE_ALL)) {
			return failed(FailureReason.OBSTRUCTED, primary, "could not be set");
		}
		for (Part part : parts.subList(1, parts.size())) {
			if (!level.setBlock(part.pos(), part.state(), Block.UPDATE_ALL)) {
				level.setBlock(primary.pos(), replaced, Block.UPDATE_ALL);
				return failed(FailureReason.OBSTRUCTED, part, "could not be set");
			}
		}
		worker.consumeOne(item);
		SoundType sound = state.getSoundType();
		level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
		return TaskOutcome.succeeded("placed " + block.getName().getString());
	}

	private List<Part> parts() {
		return parts(pos, state);
	}

	/**
	 * The blocks that placing {@code state} at {@code pos} sets, primary first: two parts for
	 * doors, tall plants and beds, otherwise one.
	 */
	static List<Part> parts(BlockPos pos, BlockState state) {
		if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
			return List.of(
					new Part("lower half", pos, state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER)),
					new Part("upper half", pos.above(), state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)));
		}
		if (state.hasProperty(BlockStateProperties.BED_PART) && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
			return List.of(
					new Part("foot", pos, state.setValue(BlockStateProperties.BED_PART, BedPart.FOOT)),
					new Part("head", pos.relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING)), state.setValue(BlockStateProperties.BED_PART, BedPart.HEAD)));
		}
		return List.of(new Part("block", pos, state));
	}

	private TaskOutcome failed(FailureReason reason, Part part, String problem) {
		return TaskOutcome.failed(reason,
				"cannot place " + stateString() + ": " + part.name() + " at " + part.pos().toShortString() + " " + problem);
	}

	private TaskOutcome missingItem() {
		return TaskOutcome.failed(FailureReason.MISSING_ITEM, "no " + BuiltInRegistries.BLOCK.getKey(block) + " in inventory");
	}

	private String stateString() {
		return BlockStateParser.serialize(state);
	}

	@Override
	public void stop(WorkerEntity worker, boolean cancelled) {
		if (approach != null) {
			approach.stop(worker);
		}
	}

	@Override
	public String describe() {
		return "place " + stateString() + " at " + pos.toShortString();
	}

	@Override
	public String typeId() {
		return TYPE_ID;
	}
}
