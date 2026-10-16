package io.github.oshogun.rabodepalha.worker.task;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.oshogun.rabodepalha.task.FailureReason;
import io.github.oshogun.rabodepalha.task.TaskOutcome;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Sub-state-machine that walks a worker until it is "in reach" of a target, shared by tasks.
 * Not persisted: tasks restart from scratch after load, so a fresh Approach is built in start().
 *
 * <p>With {@link #withScaffold}, an Approach that finds no reachable stand spot pillars up or bridges a gap
 * with throwaway blocks ({@link ScaffoldPlanner}, {@link Scaffolding}). The placed blocks live in the
 * worker's {@link io.github.oshogun.rabodepalha.worker.WorkerScaffold}, not here, so they are cleaned up
 * once {@link #stop} releases them, even after a reload.
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
	/** Whether to scaffold when no stand spot is reachable; {@link #reserved} is never spent on it. */
	private final boolean scaffold;
	private final Item reserved;

	private List<BlockPos> candidates = List.of();
	private int candidateIndex;
	private int repaths;
	private int elapsed;
	private double bestDistance;
	private int lastImprovement;
	/** The path whose progress {@link #bestNodeIndex} measures; a new path restarts the count. */
	private Path trackedPath;
	private int bestNodeIndex;
	private boolean pathPending;
	private State state = State.PATHING;
	private TaskOutcome.Failed failure;
	/** The scaffold being built, or null; while set, it drives the worker instead of the navigation. */
	private Scaffolding scaffolding;
	/** Only one scaffold attempt per start. */
	private boolean scaffolded;
	/** Whether this claimed the worker's scaffold record and must release it in {@link #stop}. */
	private boolean claimedScaffold;

	private Approach(BlockPos target, boolean standAt, boolean offTarget, List<BlockPos> keepClear, int timeoutTicks) {
		this(target, standAt, offTarget, keepClear, timeoutTicks, false, Items.AIR);
	}

	private Approach(BlockPos target, boolean standAt, boolean offTarget, List<BlockPos> keepClear, int timeoutTicks,
			boolean scaffold, Item reserved) {
		this.target = target.immutable();
		this.standAt = standAt;
		this.offTarget = offTarget;
		this.keepClear = List.copyOf(keepClear);
		this.timeoutTicks = timeoutTicks;
		this.scaffold = scaffold;
		this.reserved = reserved;
	}

	/**
	 * A copy that pillars or bridges with scaffold blocks when no stand spot is reachable, never spending
	 * the last unit of {@code reserved} ({@code Items.AIR} for none). {@link #toStandAt} never scaffolds.
	 */
	public Approach withScaffold(Item reserved) {
		return new Approach(target, standAt, offTarget, keepClear, timeoutTicks, !standAt, reserved);
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
		trackedPath = null;
		bestNodeIndex = 0;
		failure = null;
		pathPending = false;
		scaffolding = null;
		scaffolded = false;
		releaseScaffold(worker);
		state = State.PATHING;
		if (inReach(worker)) {
			state = State.ARRIVED;
			return;
		}
		candidates = spotCandidates(worker);
		if (!canPath(worker)) {
			// Freshly spawned/loaded or falling: navigation refuses to path until on ground.
			pathPending = true;
		} else if (!followNextReachablePath(worker)) {
			scaffoldOrFail(worker);
		}
	}

	public State tick(WorkerEntity worker) {
		if (state != State.PATHING) {
			return state;
		}
		if (scaffolding != null) {
			return tickScaffolding(worker);
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
					return scaffoldOrFail(worker);
				}
			}
			return state;
		}
		if (worker.ironDoors().takeRefusal()) {
			// The switch did not open the door: it is now remembered as impassable, so repath around it.
			// Not counted as a repath; each refusal adds a door to the memory, which bounds this.
			bestDistance = distance(worker);
			lastImprovement = elapsed;
			if (!followNextReachablePath(worker)) {
				return fail(FailureReason.UNREACHABLE, ironDoorDetail(worker.ironDoors().lastRefused()));
			}
			return state;
		}
		double distance = distance(worker);
		if (distance < bestDistance - MIN_IMPROVEMENT) {
			bestDistance = distance;
			lastImprovement = elapsed;
		} else if (advancedAlongPath(worker)) {
			// A detour to a ladder, or a climb straight up under the target, moves along the path
			// without getting closer; that is still progress.
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
				return failNoPath(worker);
			}
		}
		return state;
	}

	public void stop(WorkerEntity worker) {
		worker.getNavigation().stop();
		releaseScaffold(worker);
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

	private List<BlockPos> spotCandidates(WorkerEntity worker) {
		return standAt
				? List.of(target)
				: StandSpots.candidates(worker.level(), target, worker.position(), worker.getEyeHeight()).stream()
						.filter(spot -> !offTarget || !spot.below().equals(target))
						// The worker is under two blocks tall: its body fills the spot and the cell above.
						.filter(spot -> !keepClear.contains(spot) && !keepClear.contains(spot.above()))
						.toList();
	}

	private double distance(WorkerEntity worker) {
		return standAt
				? worker.position().distanceTo(Vec3.atBottomCenterOf(target))
				: worker.getEyePosition().distanceTo(Vec3.atCenterOf(target));
	}

	/** Mirrors WorkerNavigation#canUpdatePath; createPath returns null otherwise. */
	private static boolean canPath(WorkerEntity worker) {
		return worker.onGround() || worker.isInLiquid() || worker.isPassenger() || worker.onClimbable();
	}

	/** True when the worker reached a further node of the path it follows since the last call. */
	private boolean advancedAlongPath(WorkerEntity worker) {
		Path path = worker.getNavigation().getPath();
		if (path == null) {
			trackedPath = null;
			return false;
		}
		int index = path.getNextNodeIndex();
		if (path != trackedPath) {
			trackedPath = path;
			bestNodeIndex = index;
			return false;
		}
		if (index > bestNodeIndex) {
			bestNodeIndex = index;
			return true;
		}
		return false;
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

	/**
	 * Builds the scaffold while it runs. Elapsed, stuck, repath and iron door handling are paused: the worker
	 * is mostly mid-air or standing still, which they would take for being stuck.
	 */
	private State tickScaffolding(WorkerEntity worker) {
		if (inReach(worker)) {
			// Also stops a pillar early once a jump brings the eye in reach.
			worker.getNavigation().stop();
			state = State.ARRIVED;
			return state;
		}
		Scaffolding.State result = scaffolding.tick(worker);
		if (scaffolding.claimed()) {
			claimedScaffold = true;
		}
		switch (result) {
			case FAILED -> {
				state = State.FAILED;
				failure = scaffolding.failure();
			}
			case DONE -> {
				scaffolding = null;
				if (inReach(worker)) {
					state = State.ARRIVED;
					return state;
				}
				// A bridge that reached resumed ground: walk on from there.
				candidates = spotCandidates(worker);
				candidateIndex = 0;
				bestDistance = distance(worker);
				lastImprovement = elapsed;
				trackedPath = null;
				if (!canPath(worker)) {
					pathPending = true;
				} else if (!followNextReachablePath(worker)) {
					return failNoPath(worker);
				}
			}
			case RUNNING -> {
			}
		}
		return state;
	}

	/**
	 * With scaffolding on, starts the cheapest scaffold plan whose start the worker can get to; otherwise,
	 * or with no plan, fails as unreachable. Plans without scaffold blocks fail with a hint instead.
	 */
	private State scaffoldOrFail(WorkerEntity worker) {
		if (!scaffold || scaffolded) {
			return failNoPath(worker);
		}
		scaffolded = true;
		List<ScaffoldPlanner.Plan> plans = scaffoldPlans(worker);
		if (plans.isEmpty()) {
			return failNoPath(worker);
		}
		if (!worker.hasScaffoldBlock(reserved)) {
			return fail(FailureReason.UNREACHABLE,
					"no path to " + target.toShortString() + "; scaffold blocks (#rabodepalha:scaffold_blocks) would help");
		}
		PathNavigation navigation = worker.getNavigation();
		for (ScaffoldPlanner.Plan plan : plans) {
			BlockPos start = plan.start();
			if (worker.blockPosition().equals(start) && worker.onGround()) {
				scaffolding = new Scaffolding(plan, reserved);
				return state;
			}
			Path path = navigation.createPath(start, 0);
			if (path != null && path.canReach()) {
				scaffolding = new Scaffolding(plan, reserved);
				return state;
			}
		}
		return failNoPath(worker);
	}

	/** Pillars, then bridges, fewest blocks first; a pillar wins a tie. */
	private List<ScaffoldPlanner.Plan> scaffoldPlans(WorkerEntity worker) {
		Set<BlockPos> forbidden = new HashSet<>(keepClear);
		forbidden.add(target);
		if (offTarget) {
			forbidden.add(target.above());
		}
		ScaffoldPlanner.Cells cells = ScaffoldPlanner.Cells.of(worker.level());
		List<ScaffoldPlanner.Plan> plans = new ArrayList<>();
		plans.addAll(ScaffoldPlanner.pillars(cells, target, worker.position(), worker.getEyeHeight(), forbidden::contains));
		plans.addAll(ScaffoldPlanner.bridges(cells, target, worker.position(), worker.getEyeHeight(), forbidden::contains));
		plans.sort(Comparator.comparingInt(plan -> plan.cells().size()));
		return plans;
	}

	private void releaseScaffold(WorkerEntity worker) {
		if (claimedScaffold) {
			worker.scaffold().release();
			claimedScaffold = false;
		}
	}

	/** Fails as unreachable, naming the refused iron door when that is likely what blocks the way. */
	private State failNoPath(WorkerEntity worker) {
		BlockPos door = worker.ironDoors().lastRefused();
		if (door != null && worker.ironDoors().isRefused(door, worker.level().getGameTime())) {
			return fail(FailureReason.UNREACHABLE, ironDoorDetail(door));
		}
		return fail(FailureReason.UNREACHABLE, "no path to " + target.toShortString());
	}

	private static String ironDoorDetail(BlockPos door) {
		return "no way through the iron door at " + door.toShortString();
	}

	private State fail(FailureReason reason, String detail) {
		state = State.FAILED;
		failure = TaskOutcome.failed(reason, detail);
		return state;
	}
}
