package io.github.oshogun.rabodepalha.worker.task;

import io.github.oshogun.rabodepalha.task.FailureReason;
import io.github.oshogun.rabodepalha.task.Task;
import io.github.oshogun.rabodepalha.task.TaskOutcome;
import io.github.oshogun.rabodepalha.worker.Usables;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Walks into reach of {@link #pos()}, looks at the block there, swings and uses it: toggles a lever, presses
 * a button, or toggles a door, trapdoor or fence gate that opens by hand ({@link Usables}). An already pressed
 * button succeeds without a second press; any other block fails with {@link FailureReason#NOT_USABLE}.
 */
public final class UseTask implements Task<WorkerEntity> {
	public static final String TYPE_ID = "use";

	private final BlockPos pos;
	private Approach approach;

	public UseTask(BlockPos pos) {
		this.pos = pos.immutable();
	}

	public BlockPos pos() {
		return pos;
	}

	@Override
	public void start(WorkerEntity worker) {
		approach = Approach.toWorkOn(pos, Approach.DEFAULT_TIMEOUT);
		approach.start(worker);
	}

	@Override
	public TaskOutcome tick(WorkerEntity worker) {
		switch (approach.tick(worker)) {
			case PATHING:
				return TaskOutcome.RUNNING;
			case FAILED:
				return approach.failure();
			case ARRIVED:
				break;
		}
		return act(worker);
	}

	private TaskOutcome act(WorkerEntity worker) {
		Level level = worker.level();
		BlockPos at = Usables.canonicalPos(level, pos);
		String name = level.getBlockState(at).getBlock().getName().getString();
		if (!Usables.isUsable(level.getBlockState(at))) {
			return TaskOutcome.failed(FailureReason.NOT_USABLE, name + " at " + pos.toShortString() + " is not usable");
		}
		worker.getLookControl().setLookAt(Vec3.atCenterOf(at));
		worker.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		return switch (Usables.use(level, worker, at)) {
			case USED -> TaskOutcome.succeeded("used " + name + " at " + pos.toShortString());
			case ALREADY_PRESSED -> TaskOutcome.succeeded(name + " at " + pos.toShortString() + " was already pressed");
			case NOT_USABLE -> TaskOutcome.failed(FailureReason.NOT_USABLE,
					name + " at " + pos.toShortString() + " is not usable");
		};
	}

	@Override
	public void stop(WorkerEntity worker, boolean cancelled) {
		if (approach != null) {
			approach.stop(worker);
		}
	}

	@Override
	public String describe() {
		return "use " + pos.toShortString();
	}

	@Override
	public String typeId() {
		return TYPE_ID;
	}
}
