package io.github.oshogun.rabodepalha.worker;

import io.github.oshogun.rabodepalha.task.TaskOutcome;
import io.github.oshogun.rabodepalha.worker.task.StandSpots;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Gets a worker through the closed iron doors on its path with the switch {@link IronDoorSwitches} guessed
 * opens them: presses the button or flips the lever once in reach, or just walks onto a pressure plate, then
 * waits up to {@link IronDoorSwitches#WAIT_TICKS} for the door to open and walks through.
 *
 * <p>A door that closes again before the worker is through is pressed again, up to
 * {@link IronDoorSwitches#MAX_PRESSES} times. A door the switch doesn't open is refused: remembered as
 * impassable for {@link IronDoorSwitches#REFUSE_TICKS}, the path is stopped and {@link #takeRefusal} tells the
 * task to repath. A lever the worker flipped is flipped back from the far side when a lever there suggests the
 * door is meant to be closed from both sides; otherwise the door is left open and a note is added to the task's
 * outcome. Not persisted.
 */
public final class WorkerIronDoors {
	/** Horizontal distance from a pressure plate's centre at which the worker counts as stepping on it. */
	public static final double PLATE_RANGE = 1.5;

	private enum Phase {
		/** Walking up to the switch. */
		APPROACHING,
		/** Switch used, waiting for the door to open. */
		WAITING,
		/** The door opened; walking through. */
		PASSING
	}

	/** The door the worker is currently getting through. */
	private static final class Passage {
		final BlockPos door;
		final Direction.Axis axis;
		final int side;
		final IronDoorSwitches.Switch sw;
		Phase phase = Phase.APPROACHING;
		int presses;
		long deadline;
		boolean leverFlipped;
		boolean leverWasOff;

		Passage(BlockPos door, Direction.Axis axis, int side, IronDoorSwitches.Switch sw) {
			this.door = door;
			this.axis = axis;
			this.side = side;
			this.sw = sw;
		}
	}

	/** Canonical positions of refused doors, with the game time the refusal expires. */
	private final Map<BlockPos, Long> refused = new HashMap<>();
	private final List<String> notes = new ArrayList<>();
	@Nullable
	private Passage passage;
	private boolean refusalPending;
	@Nullable
	private BlockPos lastRefused;

	/** Whether the door at canonical {@code pos} is remembered as impassable at {@code gameTime}. */
	public boolean isRefused(BlockPos pos, long gameTime) {
		Long until = refused.get(pos);
		return until != null && gameTime < until;
	}

	/** Whether a door was refused since the last call; clears the flag. */
	public boolean takeRefusal() {
		boolean pending = refusalPending;
		refusalPending = false;
		return pending;
	}

	/** The canonical position of the last refused door, or null if none was refused. */
	@Nullable
	public BlockPos lastRefused() {
		return lastRefused;
	}

	/** Appends the pending notes (e.g. a door left open) to {@code outcome}'s text and clears them. */
	public TaskOutcome annotate(TaskOutcome outcome) {
		if (notes.isEmpty()) {
			return outcome;
		}
		String joined = String.join("; ", notes);
		notes.clear();
		if (outcome instanceof TaskOutcome.Succeeded succeeded) {
			return TaskOutcome.succeeded(append(succeeded.note(), joined));
		}
		if (outcome instanceof TaskOutcome.Failed failed) {
			return TaskOutcome.failed(failed.reason(), append(failed.detail(), joined));
		}
		return outcome;
	}

	private static String append(String text, String note) {
		return text.isEmpty() ? note : text + "; " + note;
	}

	/** Forgets the passage, refusals and notes without touching the world. */
	public void forget() {
		refused.clear();
		notes.clear();
		passage = null;
		refusalPending = false;
		lastRefused = null;
	}

	public void tick(ServerLevel level, WorkerEntity worker) {
		long now = level.getGameTime();
		refused.values().removeIf(until -> until <= now);
		Path path = worker.getNavigation().getPath();
		if (passage == null) {
			passage = findPassage(level, worker, path, now);
			if (passage == null) {
				return;
			}
		}
		Passage p = passage;
		if (isThrough(worker, p)) {
			finishThrough(level, worker, p);
			passage = null;
			return;
		}
		BlockState state = level.isLoaded(p.door) ? level.getBlockState(p.door) : null;
		if (state == null || !IronDoorSwitches.isIronDoor(state)) {
			passage = null;
			return;
		}
		// The path moves past the door node while the worker is still in the doorway; that isn't abandoning it.
		if (!WorkerDoors.pathUsesDoor(path, p.door, true) && !worker.getBoundingBox().intersects(doorCells(p.door))) {
			abandon(level, worker, p);
			passage = null;
			return;
		}
		boolean open = state.getValue(DoorBlock.OPEN);
		switch (p.phase) {
			case APPROACHING -> {
				if (open) {
					p.phase = Phase.PASSING;
				} else if (press(level, worker, p)) {
					p.phase = Phase.WAITING;
					p.deadline = now + IronDoorSwitches.WAIT_TICKS;
				}
			}
			case WAITING -> {
				if (open) {
					p.phase = Phase.PASSING;
				} else if (now >= p.deadline) {
					refuse(level, worker, p, now);
				}
			}
			case PASSING -> {
				if (!open && IronDoorSwitches.sideOf(p.door, p.axis, worker.getX(), worker.getZ()) != -p.side) {
					if (p.presses >= IronDoorSwitches.MAX_PRESSES) {
						refuse(level, worker, p, now);
					} else if (repress(level, worker, p)) {
						p.phase = Phase.WAITING;
						p.deadline = now + IronDoorSwitches.WAIT_TICKS;
					}
				}
			}
		}
	}

	@Nullable
	private Passage findPassage(ServerLevel level, WorkerEntity worker, @Nullable Path path, long now) {
		if (path == null || path.isDone()) {
			return null;
		}
		int next = path.getNextNodeIndex();
		for (int i = next; i <= next + 1 && i < path.getNodeCount(); i++) {
			BlockPos node = path.getNode(i).asBlockPos();
			for (BlockPos cell : new BlockPos[] {node, node.above()}) {
				if (!level.isLoaded(cell)) {
					continue;
				}
				BlockState state = level.getBlockState(cell);
				if (!IronDoorSwitches.isIronDoor(state) || state.getValue(DoorBlock.OPEN)) {
					continue;
				}
				BlockPos door = Openables.canonicalPos(state, cell).immutable();
				if (isRefused(door, now)) {
					continue;
				}
				Direction.Axis axis = IronDoorSwitches.passageAxis(state);
				int side = IronDoorSwitches.sideOf(door, axis, worker.getX(), worker.getZ());
				List<IronDoorSwitches.Switch> switches = IronDoorSwitches.switchesOnSide(door, axis, side,
						level::getBlockState);
				if (!switches.isEmpty()) {
					return new Passage(door, axis, side, switches.get(0));
				}
			}
		}
		return null;
	}

	/** Uses the passage's switch if the worker can; returns whether it did (or stepped on the plate). */
	private static boolean press(ServerLevel level, WorkerEntity worker, Passage p) {
		if (p.sw.kind() == IronDoorSwitches.Kind.PLATE) {
			Vec3 center = Vec3.atBottomCenterOf(p.sw.pos());
			double dx = worker.getX() - center.x;
			double dz = worker.getZ() - center.z;
			if (dx * dx + dz * dz > PLATE_RANGE * PLATE_RANGE) {
				return false;
			}
			p.presses++;
			return true;
		}
		if (!inReach(worker, p.sw.pos())) {
			return false;
		}
		BlockState state = level.getBlockState(p.sw.pos());
		if (!Usables.isSwitch(state)) {
			return false;
		}
		if (p.sw.kind() == IronDoorSwitches.Kind.LEVER && !p.leverFlipped) {
			p.leverWasOff = !state.getValue(LeverBlock.POWERED);
			p.leverFlipped = true;
		}
		use(level, worker, p.sw.pos());
		p.presses++;
		return true;
	}

	/** Uses a button or waits on a plate again after the door closed; a lever is left as it is. */
	private static boolean repress(ServerLevel level, WorkerEntity worker, Passage p) {
		if (p.sw.kind() == IronDoorSwitches.Kind.LEVER) {
			p.presses++;
			return true;
		}
		return press(level, worker, p);
	}

	private void refuse(ServerLevel level, WorkerEntity worker, Passage p, long now) {
		refused.put(p.door, now + IronDoorSwitches.REFUSE_TICKS);
		restoreLever(level, worker, p);
		refusalPending = true;
		lastRefused = p.door;
		worker.getNavigation().stop();
		passage = null;
	}

	/** The worker is clear of the door cells and on the far side. */
	private static boolean isThrough(WorkerEntity worker, Passage p) {
		return !worker.getBoundingBox().intersects(doorCells(p.door))
				&& IronDoorSwitches.sideOf(p.door, p.axis, worker.getX(), worker.getZ()) == -p.side;
	}

	private void finishThrough(ServerLevel level, WorkerEntity worker, Passage p) {
		if (!p.leverFlipped || !leverDiffers(level, p)) {
			return;
		}
		List<IronDoorSwitches.Switch> farLevers = IronDoorSwitches
				.switchesOnSide(p.door, p.axis, -p.side, level::getBlockState).stream()
				.filter(s -> s.kind() == IronDoorSwitches.Kind.LEVER)
				.toList();
		if (!farLevers.isEmpty()) {
			if (inReach(worker, p.sw.pos())) {
				restoreLever(level, worker, p);
			} else {
				BlockPos far = farLevers.get(0).pos();
				use(level, worker, far);
				if (doorOpen(level, p.door)) {
					use(level, worker, far);
				}
			}
		}
		if (doorOpen(level, p.door)) {
			notes.add(leftOpenNote(p.door));
		}
	}

	/** The path stopped using the door before the worker got through. */
	private void abandon(ServerLevel level, WorkerEntity worker, Passage p) {
		if (!p.leverFlipped || !leverDiffers(level, p)) {
			return;
		}
		if (inReach(worker, p.sw.pos())) {
			restoreLever(level, worker, p);
		}
		if (doorOpen(level, p.door)) {
			notes.add(leftOpenNote(p.door));
		}
	}

	/** Flips the worker's lever back to how it found it, if it flipped it and it is still flipped. */
	private static void restoreLever(ServerLevel level, WorkerEntity worker, Passage p) {
		if (p.leverFlipped && leverDiffers(level, p)) {
			use(level, worker, p.sw.pos());
		}
	}

	private static boolean leverDiffers(ServerLevel level, Passage p) {
		BlockState state = level.getBlockState(p.sw.pos());
		return state.getBlock() instanceof LeverBlock && state.getValue(LeverBlock.POWERED) == p.leverWasOff;
	}

	private static void use(ServerLevel level, WorkerEntity worker, BlockPos pos) {
		worker.getLookControl().setLookAt(Vec3.atCenterOf(pos));
		worker.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		Usables.use(level, worker, pos);
	}

	private static boolean inReach(WorkerEntity worker, BlockPos pos) {
		return worker.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) <= StandSpots.REACH;
	}

	private static boolean doorOpen(ServerLevel level, BlockPos door) {
		BlockState state = level.getBlockState(door);
		return state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.OPEN);
	}

	private static AABB doorCells(BlockPos door) {
		return new AABB(door).expandTowards(0, 1, 0);
	}

	static String leftOpenNote(BlockPos door) {
		return "left the iron door at " + door.getX() + " " + door.getY() + " " + door.getZ() + " open";
	}
}
