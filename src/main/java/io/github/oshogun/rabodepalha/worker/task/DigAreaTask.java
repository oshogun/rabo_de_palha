package io.github.oshogun.rabodepalha.worker.task;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.oshogun.rabodepalha.task.FailureReason;
import io.github.oshogun.rabodepalha.task.Task;
import io.github.oshogun.rabodepalha.task.TaskOutcome;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Clears the cuboid between {@link #from()} and {@link #to()}, one {@link MineTask} per block: top
 * layer down, nearest block first within a layer. Air and blocks holding a fluid (water, lava,
 * waterlogged blocks) are left alone, and so is the block the worker stands on until it has stepped
 * off it; each block is mined from beside it, never from on top. Blocks that can't be mined (unbreakable, no tool) are skipped and listed when the job ends.
 * Blocks it can't get to are retried after other blocks were dug; the job fails with
 * {@link FailureReason#UNREACHABLE} when it dug nothing and {@link FailureReason#STUCK} when it dug
 * some but blocks are left. Only the corners are persisted: after a load the area is scanned again.
 */
public final class DigAreaTask implements Task<WorkerEntity> {
	public static final String TYPE_ID = "dig_area";
	/** Largest area, in blocks, a single task digs; every pick scans the whole area. */
	public static final int MAX_VOLUME = 4096;
	/** Sub-tasks that end without any work (unbreakable, no tool) are run back to back up to this many per tick. */
	private static final int MAX_PICKS_PER_TICK = 32;
	private static final int MAX_LISTED = 8;

	private record Skip(String block, FailureReason reason) {
	}

	private final BlockPos from;
	private final BlockPos to;
	private final BlockPos min;
	private final BlockPos max;

	private Task<WorkerEntity> sub;
	/** Block the current sub-task mines; null while the sub-task moves the worker off its block. */
	private BlockPos subTarget;
	private final Map<BlockPos, Skip> skipped = new LinkedHashMap<>();
	/** Blocks that could not be reached; retried once other blocks were dug since they failed. */
	private final Set<BlockPos> deferred = new LinkedHashSet<>();
	private int mined;
	private boolean progressSinceDefer;
	private boolean relocatedSinceProgress;
	private BlockPos relocateFrom;
	private Deque<BlockPos> relocateSpots = new ArrayDeque<>();
	private TaskOutcome.Failed lastApproachFailure;

	public DigAreaTask(BlockPos from, BlockPos to) {
		this.from = from.immutable();
		this.to = to.immutable();
		this.min = DigOrder.min(from, to);
		this.max = DigOrder.max(from, to);
	}

	public BlockPos from() {
		return from;
	}

	public BlockPos to() {
		return to;
	}

	public long volume() {
		return DigOrder.volume(from, to);
	}

	@Override
	public void start(WorkerEntity worker) {
		sub = null;
		subTarget = null;
		skipped.clear();
		deferred.clear();
		mined = 0;
		progressSinceDefer = false;
		relocatedSinceProgress = false;
		relocateFrom = null;
		relocateSpots = new ArrayDeque<>();
		lastApproachFailure = null;
	}

	@Override
	public TaskOutcome tick(WorkerEntity worker) {
		for (int i = 0; i < MAX_PICKS_PER_TICK; i++) {
			if (sub == null) {
				TaskOutcome next = startNext(worker);
				if (next != null) {
					return next;
				}
			}
			TaskOutcome outcome = sub.tick(worker);
			if (!outcome.isTerminal() || !endSub(worker, outcome)) {
				return TaskOutcome.RUNNING;
			}
		}
		return TaskOutcome.RUNNING;
	}

	/** Starts the next sub-task and returns null, or returns the outcome when there is nothing left to start. */
	private TaskOutcome startNext(WorkerEntity worker) {
		Level level = worker.level();
		BlockPos standingOn = worker.getOnPos();
		Optional<BlockPos> target = DigOrder.next(min, max, worker.blockPosition(), standingOn, pos -> isCandidate(level, pos));
		if (target.isPresent()) {
			startSub(worker, MineTask.fromBeside(target.get()), target.get());
			return null;
		}
		if (!deferred.isEmpty() && progressSinceDefer) {
			// The area changed since these failed, so a way to them may have opened up.
			deferred.clear();
			progressSinceDefer = false;
			return TaskOutcome.RUNNING;
		}
		boolean standingLeft = isInArea(standingOn) && isCandidate(level, standingOn);
		if (standingLeft && !relocatedSinceProgress) {
			relocatedSinceProgress = true;
			relocateFrom = standingOn.immutable();
			relocateSpots = new ArrayDeque<>(relocateSpots(worker, relocateFrom));
			if (startRelocate(worker)) {
				return null;
			}
		}
		if (deferred.isEmpty() && !standingLeft) {
			return TaskOutcome.succeeded("dug " + mined + " blocks" + skippedNote());
		}
		if (mined == 0) {
			return TaskOutcome.failed(FailureReason.UNREACHABLE, "could not reach any block in " + area()
					+ lastFailureNote() + skippedNote());
		}
		List<BlockPos> left = new ArrayList<>(deferred);
		if (standingLeft) {
			left.add(standingOn.immutable());
		}
		return TaskOutcome.failed(FailureReason.STUCK, "dug " + mined + " blocks, " + left.size() + " left unreachable: "
				+ listed(left.stream().map(BlockPos::toShortString).toList()) + lastFailureNote() + skippedNote());
	}

	/** Stand spots from which {@code block} can be dug, far enough that moving to them takes the worker off it. */
	private static List<BlockPos> relocateSpots(WorkerEntity worker, BlockPos block) {
		Vec3 position = worker.position();
		return StandSpots.candidates(worker.level(), block, position, worker.getEyeHeight()).stream()
				.filter(spot -> !spot.below().equals(block)
						&& Vec3.atBottomCenterOf(spot).distanceTo(position) > Approach.MOVE_REACH)
				.toList();
	}

	private boolean startRelocate(WorkerEntity worker) {
		BlockPos spot = relocateSpots.poll();
		if (spot == null) {
			return false;
		}
		startSub(worker, new MoveToTask(spot), null);
		return true;
	}

	private void startSub(WorkerEntity worker, Task<WorkerEntity> task, BlockPos target) {
		sub = task;
		subTarget = target;
		sub.start(worker);
	}

	/**
	 * Stops the ended sub-task and records its outcome. Returns true when it ended without doing any
	 * work, so the next block can be started in the same tick.
	 */
	private boolean endSub(WorkerEntity worker, TaskOutcome outcome) {
		Task<WorkerEntity> ended = sub;
		BlockPos target = subTarget;
		sub = null;
		subTarget = null;
		ended.stop(worker, false);
		if (target == null) {
			if (!(outcome instanceof TaskOutcome.Succeeded) || worker.getOnPos().equals(relocateFrom)) {
				startRelocate(worker);
			}
			return false;
		}
		if (outcome instanceof TaskOutcome.Succeeded) {
			mined++;
			progress();
			return false;
		}
		if (!(outcome instanceof TaskOutcome.Failed failed)) {
			return false;
		}
		switch (failed.reason()) {
			case UNBREAKABLE, NO_TOOL -> {
				String name = worker.level().getBlockState(target).getBlock().getName().getString();
				skipped.put(target, new Skip(name, failed.reason()));
				return true;
			}
			case AIR -> {
				progress();
				return true;
			}
			case TARGET_CHANGED -> {
				// Someone else dug it, or it turned into another block: look at it again after other blocks.
				if (worker.level().getBlockState(target).isAir()) {
					progress();
				} else {
					deferred.add(target);
				}
				return false;
			}
			default -> {
				deferred.add(target);
				lastApproachFailure = failed;
				return false;
			}
		}
	}

	private void progress() {
		progressSinceDefer = true;
		relocatedSinceProgress = false;
	}

	private boolean isInArea(BlockPos pos) {
		return pos.getX() >= min.getX() && pos.getX() <= max.getX()
				&& pos.getY() >= min.getY() && pos.getY() <= max.getY()
				&& pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
	}

	private boolean isCandidate(Level level, BlockPos pos) {
		if (skipped.containsKey(pos) || deferred.contains(pos)) {
			return false;
		}
		BlockState state = level.getBlockState(pos);
		return !state.isAir() && state.getFluidState().isEmpty();
	}

	private String skippedNote() {
		if (skipped.isEmpty()) {
			return "";
		}
		return "; skipped: " + listed(skipped.entrySet().stream()
				.map(e -> e.getValue().block() + " @ " + e.getKey().toShortString() + " (" + e.getValue().reason() + ")")
				.toList());
	}

	private String lastFailureNote() {
		return lastApproachFailure == null ? "" : " (" + lastApproachFailure.reason() + ": " + lastApproachFailure.detail() + ")";
	}

	private static String listed(List<String> items) {
		String shown = items.stream().limit(MAX_LISTED).collect(Collectors.joining(", "));
		return items.size() > MAX_LISTED ? shown + " and " + (items.size() - MAX_LISTED) + " more" : shown;
	}

	private String area() {
		return min.toShortString() + " to " + max.toShortString();
	}

	@Override
	public void stop(WorkerEntity worker, boolean cancelled) {
		if (sub != null) {
			Task<WorkerEntity> running = sub;
			sub = null;
			subTarget = null;
			running.stop(worker, cancelled);
		}
	}

	@Override
	public String describe() {
		return "dig area " + area();
	}

	@Override
	public String typeId() {
		return TYPE_ID;
	}
}
