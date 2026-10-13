package io.github.oshogun.rabodepalha.worker.task;

import java.util.List;

import io.github.oshogun.rabodepalha.task.FailureReason;
import io.github.oshogun.rabodepalha.task.Task;
import io.github.oshogun.rabodepalha.task.TaskOutcome;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Walks into reach of {@link #pos()} and breaks the block there at player speed with the best
 * tool in the inventory. Drops go to the inventory; what does not fit is dropped at the feet.
 * Ignores mobGriefing.
 */
public final class MineTask implements Task<WorkerEntity> {
	public static final String TYPE_ID = "mine";
	private static final int SWING_INTERVAL = 5;

	private final BlockPos pos;
	private Approach approach;
	private BlockState target;
	private TaskOutcome startFailure;
	private float progress;
	private int lastStage;
	private int miningTicks;

	public MineTask(BlockPos pos) {
		this.pos = pos.immutable();
	}

	public BlockPos pos() {
		return pos;
	}

	@Override
	public void start(WorkerEntity worker) {
		Level level = worker.level();
		target = level.getBlockState(pos);
		progress = 0.0F;
		lastStage = -1;
		miningTicks = 0;
		startFailure = checkMineable(worker, level);
		if (startFailure == null) {
			approach = Approach.toWorkOn(pos, Approach.DEFAULT_TIMEOUT);
			approach.start(worker);
		}
	}

	private TaskOutcome checkMineable(WorkerEntity worker, Level level) {
		if (target.isAir()) {
			return TaskOutcome.failed(FailureReason.AIR, "nothing to mine at " + pos.toShortString());
		}
		if (target.getDestroySpeed(level, pos) < 0.0F) {
			return TaskOutcome.failed(FailureReason.UNBREAKABLE, target.getBlock().getName().getString() + " is unbreakable");
		}
		if (target.requiresCorrectToolForDrops() && !worker.bestToolFor(target).isCorrectToolForDrops(target)) {
			return TaskOutcome.failed(FailureReason.NO_TOOL, "no tool for " + target.getBlock().getName().getString());
		}
		return null;
	}

	@Override
	public TaskOutcome tick(WorkerEntity worker) {
		if (startFailure != null) {
			return startFailure;
		}
		Level level = worker.level();
		if (level.getBlockState(pos) != target) {
			return TaskOutcome.failed(FailureReason.TARGET_CHANGED, "block at " + pos.toShortString() + " changed");
		}
		switch (approach.tick(worker)) {
			case PATHING:
				return TaskOutcome.RUNNING;
			case FAILED:
				return approach.failure();
			case ARRIVED:
				break;
		}

		worker.getLookControl().setLookAt(Vec3.atCenterOf(pos));
		if (miningTicks++ % SWING_INTERVAL == 0) {
			worker.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		}
		ItemStack tool = worker.bestToolFor(target);
		progress += destroyProgress(worker, level, tool);
		if (progress < 1.0F) {
			int stage = (int) (progress * 10.0F);
			if (stage != lastStage) {
				lastStage = stage;
				level.destroyBlockProgress(worker.getId(), pos, stage);
			}
			return TaskOutcome.RUNNING;
		}
		return breakBlock(worker, level, tool);
	}

	/** Hand copy of Block#getDestroyProgress + Player#getDestroySpeed, which both need a Player. */
	private float destroyProgress(WorkerEntity worker, Level level, ItemStack tool) {
		float hardness = target.getDestroySpeed(level, pos);
		if (hardness == 0.0F) {
			return 1.0F;
		}
		float speed = tool.getDestroySpeed(target);
		if (speed > 1.0F && worker.getAttributes().hasAttribute(Attributes.MINING_EFFICIENCY)) {
			speed += (float) worker.getAttributeValue(Attributes.MINING_EFFICIENCY);
		}
		if (!worker.onGround()) {
			speed /= 5.0F;
		}
		boolean correct = !target.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(target);
		return speed / hardness / (correct ? 30 : 100);
	}

	private TaskOutcome breakBlock(WorkerEntity worker, Level level, ItemStack tool) {
		boolean correct = !target.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(target);
		List<ItemStack> drops = correct && level instanceof ServerLevel serverLevel
				? Block.getDrops(target, serverLevel, pos, level.getBlockEntity(pos), worker, tool)
				: List.of();
		boolean hardBlock = target.getDestroySpeed(level, pos) != 0.0F;
		// No world drops; destroyBlock still plays the break particles and sound.
		level.destroyBlock(pos, false, worker);
		if (hardBlock && tool.isDamageableItem()) {
			tool.hurtAndBreak(1, worker, EquipmentSlot.MAINHAND);
			worker.getInventory().setChanged();
		}

		int dropped = 0;
		for (ItemStack drop : drops) {
			dropped += worker.insertOrDrop(drop).getCount();
		}
		String name = target.getBlock().getName().getString();
		return TaskOutcome.succeeded(dropped > 0
				? "mined " + name + "; inventory full, " + dropped + " items dropped at feet"
				: "mined " + name);
	}

	@Override
	public void stop(WorkerEntity worker, boolean cancelled) {
		worker.level().destroyBlockProgress(worker.getId(), pos, -1);
		if (approach != null) {
			approach.stop(worker);
		}
	}

	@Override
	public String describe() {
		return "mine " + pos.toShortString();
	}

	@Override
	public String typeId() {
		return TYPE_ID;
	}
}
