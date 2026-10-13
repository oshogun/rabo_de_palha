package io.github.oshogun.rabodepalha.worker.task;

import io.github.oshogun.rabodepalha.task.Task;
import io.github.oshogun.rabodepalha.task.TaskOutcome;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;

import net.minecraft.core.BlockPos;

/** Walks the worker to stand at {@link #pos()}. */
public final class MoveToTask implements Task<WorkerEntity> {
	public static final String TYPE_ID = "move_to";

	private final BlockPos pos;
	private final int timeoutTicks;
	private Approach approach;

	public MoveToTask(BlockPos pos) {
		this(pos, Approach.DEFAULT_TIMEOUT);
	}

	public MoveToTask(BlockPos pos, int timeoutTicks) {
		this.pos = pos.immutable();
		this.timeoutTicks = timeoutTicks;
	}

	public BlockPos pos() {
		return pos;
	}

	public int timeoutTicks() {
		return timeoutTicks;
	}

	@Override
	public void start(WorkerEntity worker) {
		approach = Approach.toStandAt(pos, timeoutTicks);
		approach.start(worker);
	}

	@Override
	public TaskOutcome tick(WorkerEntity worker) {
		return switch (approach.tick(worker)) {
			case PATHING -> TaskOutcome.RUNNING;
			case ARRIVED -> TaskOutcome.succeeded("arrived at " + pos.toShortString());
			case FAILED -> approach.failure();
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
		return "move to " + pos.toShortString();
	}

	@Override
	public String typeId() {
		return TYPE_ID;
	}
}
