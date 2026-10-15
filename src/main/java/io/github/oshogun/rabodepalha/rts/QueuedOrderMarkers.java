package io.github.oshogun.rabodepalha.rts;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import io.github.oshogun.rabodepalha.worker.task.TaskSummary;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Turns the selected workers' snapshots into the markers drawn for their current and queued tasks: a column
 * for a move, a block for mine, place, deposit, withdraw and use, a box for a dig area and a build's footprint.
 * Lines join each worker to its current target and then its queued targets in order. The current task gets
 * its kind's full colour, queued ones a dimmed one ({@link RtsOrderColors}).
 *
 * <p>The output is capped by {@link Limits}: workers past {@code maxWorkers} are skipped, markers past
 * {@code maxMarkers} are dropped from the tail, and a marker farther than {@code maxDistance} from the camera
 * is dropped along with the lines to and from it, while later markers in range are kept. It needs no client
 * classes, so it can be unit-tested.
 */
public final class QueuedOrderMarkers {
	/** Half the width of a move column. */
	private static final double COLUMN_HALF_WIDTH = 0.1;
	private static final double COLUMN_HEIGHT = 2.5;

	public enum Shape {
		COLUMN,
		BLOCK,
		BOX
	}

	/** A selected worker: where its lines start and its latest snapshot. */
	public record Worker(Vec3 position, WorkerSnapshot snapshot) {
	}

	/**
	 * @param maxWorkers workers drawn, in selection order
	 * @param maxMarkers markers drawn in total
	 * @param maxMarkersPerWorker markers drawn per worker
	 * @param maxDistance how far from the camera a marker is drawn, to the nearest point of its bounds
	 */
	public record Limits(int maxWorkers, int maxMarkers, int maxMarkersPerWorker, double maxDistance) {
		/** The current task and every queued one a snapshot carries. */
		public static final Limits DEFAULT = new Limits(8, 64, 1 + WorkerSnapshot.MAX_QUEUED, 96.0);
	}

	/**
	 * One task's marker.
	 *
	 * @param bounds the world-space box drawn
	 * @param anchor where lines join the marker
	 * @param workerIndex the worker's index in the input
	 * @param order the task's position in the worker's list: 0 for the current task, then the queue in order
	 */
	public record Marker(RtsTaskKind kind, Shape shape, AABB bounds, Vec3 anchor, int argb, boolean current,
			int workerIndex, int order) {
	}

	/** A line between two consecutive points of a worker's chain, in the colour of the marker it leads to. */
	public record Segment(Vec3 from, Vec3 to, int argb, boolean current) {
	}

	public record Markers(List<Marker> markers, List<Segment> segments) {
		public static final Markers EMPTY = new Markers(List.of(), List.of());

		public Markers {
			markers = List.copyOf(markers);
			segments = List.copyOf(segments);
		}
	}

	private QueuedOrderMarkers() {
	}

	public static Markers build(List<Worker> workers, Vec3 camera, Limits limits) {
		List<Marker> markers = new ArrayList<>();
		List<Segment> segments = new ArrayList<>();
		double maxDistanceSqr = limits.maxDistance() * limits.maxDistance();
		int workerCount = Math.min(workers.size(), Math.max(0, limits.maxWorkers()));
		for (int w = 0; w < workerCount && markers.size() < limits.maxMarkers(); w++) {
			Worker worker = workers.get(w);
			List<TaskSummary> tasks = new ArrayList<>();
			worker.snapshot().current().ifPresent(tasks::add);
			for (WorkerSnapshot.QueuedTask queued : worker.snapshot().queued()) {
				tasks.add(queued.summary());
			}
			boolean hasCurrent = worker.snapshot().current().isPresent();
			// Where the next line starts; null after a dropped point, so no line runs to or from it.
			Vec3 previous = worker.position().distanceToSqr(camera) <= maxDistanceSqr ? worker.position() : null;
			int emitted = 0;
			for (int order = 0; order < tasks.size(); order++) {
				if (markers.size() >= limits.maxMarkers() || emitted >= limits.maxMarkersPerWorker()) {
					break;
				}
				TaskSummary summary = tasks.get(order);
				RtsTaskKind kind = RtsTaskKind.of(summary);
				Placed placed = place(kind, summary.targets());
				if (placed == null) {
					// Nothing to draw; the chain carries on from the previous point.
					continue;
				}
				if (distanceSqr(placed.bounds(), camera) > maxDistanceSqr) {
					previous = null;
					continue;
				}
				boolean current = hasCurrent && order == 0;
				int argb = current ? RtsOrderColors.line(kind) : RtsOrderColors.dimmed(RtsOrderColors.line(kind));
				markers.add(new Marker(kind, placed.shape(), placed.bounds(), placed.anchor(), argb, current, w, order));
				emitted++;
				if (previous != null) {
					segments.add(new Segment(previous, placed.anchor(), argb, current));
				}
				previous = placed.anchor();
			}
		}
		return new Markers(markers, segments);
	}

	private record Placed(Shape shape, AABB bounds, Vec3 anchor) {
	}

	/** The shape drawn for a task of {@code kind} with {@code targets}, or null when it has nothing to draw. */
	private static @Nullable Placed place(RtsTaskKind kind, List<BlockPos> targets) {
		if (targets.isEmpty()) {
			return null;
		}
		BlockPos first = targets.get(0);
		return switch (kind) {
			case MOVE -> column(first);
			case MINE, PLACE, DEPOSIT, WITHDRAW, USE -> block(first);
			case DIG -> targets.size() >= 2 ? box(targets.get(0), targets.get(1)) : block(first);
			case BUILD -> targets.size() >= 3 ? box(targets.get(1), targets.get(2)) : column(first);
			case OTHER -> null;
		};
	}

	private static Placed column(BlockPos pos) {
		Vec3 centre = Vec3.atCenterOf(pos);
		AABB bounds = new AABB(centre.x - COLUMN_HALF_WIDTH, pos.getY(), centre.z - COLUMN_HALF_WIDTH,
				centre.x + COLUMN_HALF_WIDTH, pos.getY() + COLUMN_HEIGHT, centre.z + COLUMN_HALF_WIDTH);
		return new Placed(Shape.COLUMN, bounds, centre);
	}

	private static Placed block(BlockPos pos) {
		return new Placed(Shape.BLOCK, new AABB(pos), Vec3.atCenterOf(pos));
	}

	/** The blocks between two corners in any order, inclusive. */
	private static Placed box(BlockPos a, BlockPos b) {
		AABB bounds = new AABB(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
				Math.max(a.getX(), b.getX()) + 1, Math.max(a.getY(), b.getY()) + 1, Math.max(a.getZ(), b.getZ()) + 1);
		return new Placed(Shape.BOX, bounds, bounds.getCenter());
	}

	/** The squared distance from {@code point} to the nearest point of {@code box}; 0 inside it. */
	static double distanceSqr(AABB box, Vec3 point) {
		double dx = Math.max(0, Math.max(box.minX - point.x, point.x - box.maxX));
		double dy = Math.max(0, Math.max(box.minY - point.y, point.y - box.maxY));
		double dz = Math.max(0, Math.max(box.minZ - point.z, point.z - box.maxZ));
		return dx * dx + dy * dy + dz * dz;
	}
}
