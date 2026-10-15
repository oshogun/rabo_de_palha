package io.github.oshogun.rabodepalha.worker;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Opens the doors and gates on a worker's path as it reaches them, and closes the ones it opened
 * once it is through, like {@code InteractWithDoor} does for villagers.
 *
 * <p>Only doors this worker opened itself are tracked and closed; a door found open is left alone.
 * A tracked door stays open while the worker is in it or about to use it, while any entity stands
 * in it, or while another worker within {@link #OTHER_WORKER_RANGE} blocks is coming through.
 * Tracking outlives the task, so a cancelled order still closes its doors once nothing blocks them.
 * Not persisted: workers that unload leave their doors open.
 */
public final class WorkerDoors {
	/** Horizontal distance from the worker to a door cell's centre at which the worker opens it. */
	public static final double OPEN_RANGE = 2.0;
	/** Range within which another worker heading through a door keeps it open (as vanilla). */
	public static final double OTHER_WORKER_RANGE = 2.0;

	/** Canonical positions ({@link Openables#canonicalPos}) of the doors and gates this worker opened. */
	private final Set<BlockPos> opened = new HashSet<>();

	public Set<BlockPos> opened() {
		return Collections.unmodifiableSet(opened);
	}

	/** Opens the doors and gates just ahead on the path, then closes the ones left behind. */
	public void tick(ServerLevel level, WorkerEntity worker) {
		openAhead(level, worker);
		closeBehind(level, worker);
	}

	/**
	 * Closes every tracked door that is still open, unless another entity stands in it or another
	 * worker is coming through, then forgets them all. Used when the worker is destroyed.
	 */
	public void closeAllNow(ServerLevel level, WorkerEntity worker) {
		for (BlockPos pos : opened) {
			if (!level.isLoaded(pos)) {
				continue;
			}
			BlockState state = level.getBlockState(pos);
			if (!Openables.isOpenable(state) || Openables.isClosedOpenable(state)) {
				continue;
			}
			boolean doorway = Openables.isOpenableDoor(state);
			if (!entityInDoorway(level, worker, pos, doorway) && !otherWorkerComing(level, worker, pos)) {
				Openables.open(level, worker, pos, false);
			}
		}
		opened.clear();
	}

	/** Forgets the tracked doors without touching the world. */
	public void forget() {
		opened.clear();
	}

	/**
	 * Whether a door this worker opened must stay open: the worker is in it or about to use it,
	 * an entity stands in it, or another worker is coming through.
	 */
	public static boolean shouldKeepOpen(boolean selfInDoorway, boolean onOwnPath, boolean entityInDoorway,
			boolean otherWorkerComing) {
		return selfInDoorway || onOwnPath || entityInDoorway || otherWorkerComing;
	}

	/**
	 * Whether {@code path} is about to pass through or has just left the door at {@code pos}: its
	 * next node, the node after, or its previous node is in a door cell.
	 */
	public static boolean pathUsesDoor(@Nullable Path path, BlockPos pos, boolean lookAhead) {
		if (path == null || path.isDone()) {
			return false;
		}
		int next = path.getNextNodeIndex();
		if (isDoorCell(path.getNode(next).asBlockPos(), pos)) {
			return true;
		}
		if (lookAhead && next + 1 < path.getNodeCount() && isDoorCell(path.getNode(next + 1).asBlockPos(), pos)) {
			return true;
		}
		Node previous = path.getPreviousNode();
		return previous != null && isDoorCell(previous.asBlockPos(), pos);
	}

	/** Whether {@code cell} is the door at canonical {@code pos} or its upper half. */
	static boolean isDoorCell(BlockPos cell, BlockPos pos) {
		return cell.equals(pos) || cell.equals(pos.above());
	}

	private void openAhead(ServerLevel level, WorkerEntity worker) {
		Path path = worker.getNavigation().getPath();
		if (path == null || path.isDone()) {
			return;
		}
		int next = path.getNextNodeIndex();
		for (int i = next; i <= next + 1 && i < path.getNodeCount(); i++) {
			BlockPos node = path.getNode(i).asBlockPos();
			tryOpen(level, worker, node);
			tryOpen(level, worker, node.above());
		}
	}

	private void tryOpen(ServerLevel level, WorkerEntity worker, BlockPos cell) {
		if (!level.isLoaded(cell)) {
			return;
		}
		BlockState state = level.getBlockState(cell);
		if (!Openables.isClosedOpenable(state) || !withinOpenRange(worker.position(), cell)) {
			return;
		}
		BlockPos pos = Openables.canonicalPos(state, cell);
		Openables.open(level, worker, pos, true);
		opened.add(pos.immutable());
	}

	static boolean withinOpenRange(Vec3 position, BlockPos cell) {
		double dx = position.x - (cell.getX() + 0.5);
		double dz = position.z - (cell.getZ() + 0.5);
		return dx * dx + dz * dz <= OPEN_RANGE * OPEN_RANGE && Math.abs(Math.floor(position.y) - cell.getY()) <= 1;
	}

	private void closeBehind(ServerLevel level, WorkerEntity worker) {
		Path ownPath = worker.getNavigation().getPath();
		Iterator<BlockPos> it = opened.iterator();
		while (it.hasNext()) {
			BlockPos pos = it.next();
			if (!level.isLoaded(pos)) {
				it.remove();
				continue;
			}
			BlockState state = level.getBlockState(pos);
			if (!Openables.isOpenable(state) || Openables.isClosedOpenable(state)) {
				it.remove();
				continue;
			}
			boolean doorway = Openables.isOpenableDoor(state);
			AABB cells = doorCells(pos, doorway);
			boolean keep = shouldKeepOpen(
					worker.getBoundingBox().intersects(cells),
					pathUsesDoor(ownPath, pos, true),
					entityInDoorway(level, worker, pos, doorway),
					otherWorkerComing(level, worker, pos));
			if (!keep) {
				Openables.open(level, worker, pos, false);
				it.remove();
			}
		}
	}

	private static AABB doorCells(BlockPos pos, boolean door) {
		return door ? new AABB(pos).expandTowards(0, 1, 0) : new AABB(pos);
	}

	/** Whether an entity other than {@code worker} stands in the door's cells. */
	private static boolean entityInDoorway(ServerLevel level, WorkerEntity worker, BlockPos pos, boolean door) {
		return !level.getEntities(worker, doorCells(pos, door), EntitySelector.NO_SPECTATORS).isEmpty();
	}

	/** Whether another worker within {@link #OTHER_WORKER_RANGE} has the door as its next or previous node. */
	private static boolean otherWorkerComing(ServerLevel level, WorkerEntity worker, BlockPos pos) {
		Vec3 center = Vec3.atCenterOf(pos);
		return !level.getEntitiesOfClass(WorkerEntity.class, new AABB(center, center).inflate(OTHER_WORKER_RANGE),
				other -> other != worker && other.isAlive()
						&& pos.closerToCenterThan(other.position(), OTHER_WORKER_RANGE)
						&& pathUsesDoor(other.getNavigation().getPath(), pos, false))
				.isEmpty();
	}
}
