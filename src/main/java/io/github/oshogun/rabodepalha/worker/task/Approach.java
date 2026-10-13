package io.github.oshogun.rabodepalha.worker.task;

import java.util.List;

import io.github.oshogun.rabodepalha.task.FailureReason;
import io.github.oshogun.rabodepalha.task.TaskOutcome;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Sub-state-machine that walks a worker until it is "in reach" of a target, shared by tasks.
 * Not persisted: tasks restart from scratch after load, so a fresh Approach is built in start().
 */
public final class Approach {
	public static final int DEFAULT_TIMEOUT = 1200;
	public static final int STUCK_TICKS = 100;
	public static final int MAX_REPATHS = 2;
	public static final double MOVE_REACH = 1.5;
	private static final double MIN_IMPROVEMENT = 0.05;
	private static final double SPEED = 1.0;

	public enum State { PATHING, ARRIVED, FAILED }

	private final BlockPos target;
	private final boolean standAt;
	private final boolean offTarget;
	private final int timeoutTicks;
	/** Cells the worker's body must not overlap when it arrives (or stand in, for picked spots). */
	private final List<BlockPos> keepClear;

	private List<BlockPos> candidates = List.of();
	private int candidateIndex;
	private int repaths;
	private int elapsed;
	private double bestDistance;
	private int lastImprovement;
	private boolean pathPending;
	private State state = State.PATHING;
	private TaskOutcome.Failed failure;

	private Approach(BlockPos target, boolean standAt, boolean offTarget, List<BlockPos> keepClear, int timeoutTicks) {
		this.target = target.immutable();
		this.standAt = standAt;
		this.offTarget = offTarget;
		this.keepClear = List.copyOf(keepClear);
		this.timeoutTicks = timeoutTicks;
	}

	/** Reach = eye within {@link StandSpots#REACH} of the target's center (for mining/placing). */
	public static Approach toWorkOn(BlockPos target, int timeoutTicks) {
		return new Approach(target, false, false, List.of(), timeoutTicks);
	}

	/**
	 * Like {@link #toWorkOn}, but never arrives while the worker's body overlaps one of {@code keepClear}
	 * and does not pick spots where it would, so the worker does not stand where it is about to place.
	 */
	public static Approach toWorkOnClearOf(BlockPos target, List<BlockPos> keepClear, int timeoutTicks) {
		return new Approach(target, false, false, keepClear, timeoutTicks);
	}

	/**
	 * Like {@link #toWorkOn}, but never arrives while standing on the target and does not pick the
	 * spot above it, so mining the target can't pull the floor from under the worker.
	 */
	public static Approach toWorkOnFromBeside(BlockPos target, int timeoutTicks) {
		return new Approach(target, false, true, List.of(), timeoutTicks);
	}

	/** Reach = within {@link #MOVE_REACH} of the target's bottom center (for moving). */
	public static Approach toStandAt(BlockPos target, int timeoutTicks) {
		return new Approach(target, true, false, List.of(), timeoutTicks);
	}

	public void start(WorkerEntity worker) {
		elapsed = 0;
		repaths = 0;
		candidateIndex = 0;
		lastImprovement = 0;
		bestDistance = distance(worker);
		failure = null;
		pathPending = false;
		state = State.PATHING;
		if (inReach(worker)) {
			state = State.ARRIVED;
			return;
		}
		candidates = standAt
				? List.of(target)
				: StandSpots.candidates(worker.level(), target, worker.position(), worker.getEyeHeight()).stream()
						.filter(spot -> !offTarget || !spot.below().equals(target))
						// The worker is under two blocks tall: its body fills the spot and the cell above.
						.filter(spot -> !keepClear.contains(spot) && !keepClear.contains(spot.above()))
						.toList();
		if (!canPath(worker)) {
			// Freshly spawned/loaded or falling: navigation refuses to path until on ground.
			pathPending = true;
		} else if (!followNextReachablePath(worker)) {
			fail(FailureReason.UNREACHABLE, "no path to " + target.toShortString());
		}
	}

	public State tick(WorkerEntity worker) {
		if (state != State.PATHING) {
			return state;
		}
		elapsed++;
		if (inReach(worker)) {
			worker.getNavigation().stop();
			state = State.ARRIVED;
			return state;
		}
		if (elapsed > timeoutTicks) {
			return fail(FailureReason.TIMEOUT, "not in reach after " + timeoutTicks + " ticks");
		}
		if (pathPending) {
			if (canPath(worker)) {
				pathPending = false;
				lastImprovement = elapsed;
				if (!followNextReachablePath(worker)) {
					return fail(FailureReason.UNREACHABLE, "no path to " + target.toShortString());
				}
			}
			return state;
		}
		double distance = distance(worker);
		if (distance < bestDistance - MIN_IMPROVEMENT) {
			bestDistance = distance;
			lastImprovement = elapsed;
		} else if (elapsed - lastImprovement > STUCK_TICKS) {
			return fail(FailureReason.STUCK, "no progress for " + STUCK_TICKS + " ticks");
		}
		PathNavigation navigation = worker.getNavigation();
		if ((navigation.isDone() || navigation.isStuck()) && canPath(worker)) {
			if (repaths >= MAX_REPATHS) {
				return fail(FailureReason.UNREACHABLE, "gave up after " + MAX_REPATHS + " repaths");
			}
			repaths++;
			// The current spot did not get us in reach; for MoveTo it is the only candidate, so retry it.
			if (!standAt) {
				candidateIndex++;
			}
			if (!followNextReachablePath(worker)) {
				return fail(FailureReason.UNREACHABLE, "no path to " + target.toShortString());
			}
		}
		return state;
	}

	public void stop(WorkerEntity worker) {
		worker.getNavigation().stop();
	}

	public State state() {
		return state;
	}

	/** The failure outcome, or null unless {@link #state()} is {@link State#FAILED}. */
	public TaskOutcome.Failed failure() {
		return failure;
	}

	public boolean inReach(WorkerEntity worker) {
		if (offTarget && worker.getOnPos().equals(target)) {
			return false;
		}
		for (BlockPos cell : keepClear) {
			if (worker.getBoundingBox().intersects(new AABB(cell))) {
				return false;
			}
		}
		return standAt
				? worker.position().distanceTo(Vec3.atBottomCenterOf(target)) <= MOVE_REACH
				: worker.getEyePosition().distanceTo(Vec3.atCenterOf(target)) <= StandSpots.REACH;
	}

	private double distance(WorkerEntity worker) {
		return standAt
				? worker.position().distanceTo(Vec3.atBottomCenterOf(target))
				: worker.getEyePosition().distanceTo(Vec3.atCenterOf(target));
	}

	/** Mirrors GroundPathNavigation#canUpdatePath; createPath returns null otherwise. */
	private static boolean canPath(WorkerEntity worker) {
		return worker.onGround() || worker.isInLiquid() || worker.isPassenger();
	}

	/** Follows a complete path to the current candidate or, failing that, to the next ones. */
	private boolean followNextReachablePath(WorkerEntity worker) {
		PathNavigation navigation = worker.getNavigation();
		while (candidateIndex < candidates.size()) {
			Path path = navigation.createPath(candidates.get(candidateIndex), 0);
			if (path != null && path.canReach()) {
				navigation.moveTo(path, SPEED);
				return true;
			}
			candidateIndex++;
		}
		return false;
	}

	private State fail(FailureReason reason, String detail) {
		state = State.FAILED;
		failure = TaskOutcome.failed(reason, detail);
		return state;
	}
}
