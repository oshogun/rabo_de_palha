package io.github.oshogun.rabodepalha.rts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.github.oshogun.rabodepalha.rts.QueuedOrderMarkers.Limits;
import io.github.oshogun.rabodepalha.rts.QueuedOrderMarkers.Marker;
import io.github.oshogun.rabodepalha.rts.QueuedOrderMarkers.Markers;
import io.github.oshogun.rabodepalha.rts.QueuedOrderMarkers.Segment;
import io.github.oshogun.rabodepalha.rts.QueuedOrderMarkers.Shape;
import io.github.oshogun.rabodepalha.worker.task.TaskSummary;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class QueuedOrderMarkersTest {
	private static final Vec3 CAMERA = new Vec3(0, 10, 0);
	private static final Vec3 WORKER = new Vec3(0.5, 1, 0.5);
	private static final BlockPos A = new BlockPos(3, 0, 4);
	private static final BlockPos B = new BlockPos(-2, 1, 5);
	private static final BlockPos C = new BlockPos(6, 2, -1);
	private static final Limits NO_CAPS = new Limits(100, 1000, 1000, 10_000);

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	// Shapes

	@Test
	void moveIsAColumnOverTheTarget() {
		Marker marker = only(task("move_to", A));
		assertEquals(RtsTaskKind.MOVE, marker.kind());
		assertEquals(Shape.COLUMN, marker.shape());
		assertEquals(new AABB(3.4, 0, 4.4, 3.6, 2.5, 4.6), round(marker.bounds()));
		assertEquals(Vec3.atCenterOf(A), marker.anchor());
	}

	@Test
	void blockKindsAreTheTargetBlock() {
		for (String key : List.of("mine", "place", "deposit", "deposit_item", "withdraw", "use")) {
			Marker marker = only(task(key, A));
			assertEquals(Shape.BLOCK, marker.shape(), key);
			assertEquals(new AABB(A), marker.bounds(), key);
			assertEquals(Vec3.atCenterOf(A), marker.anchor(), key);
		}
		assertEquals(RtsTaskKind.MINE, only(task("mine", A)).kind());
		assertEquals(RtsTaskKind.PLACE, only(task("place", A)).kind());
		assertEquals(RtsTaskKind.DEPOSIT, only(task("deposit_item", A)).kind());
		assertEquals(RtsTaskKind.WITHDRAW, only(task("withdraw", A)).kind());
		assertEquals(RtsTaskKind.USE, only(task("use", A)).kind());
	}

	@Test
	void digIsABoxBetweenCornersInAnyOrder() {
		Marker marker = only(task("dig_area", new BlockPos(5, 3, 1), new BlockPos(2, 0, 4)));
		assertEquals(RtsTaskKind.DIG, marker.kind());
		assertEquals(Shape.BOX, marker.shape());
		assertEquals(new AABB(2, 0, 1, 6, 4, 5), marker.bounds());
		assertEquals(new Vec3(4, 2, 3), marker.anchor());
	}

	@Test
	void harvestIsABoxBetweenCorners() {
		Marker marker = only(task("harvest_area", new BlockPos(5, 3, 1), new BlockPos(2, 0, 4)));
		assertEquals(RtsTaskKind.HARVEST, marker.kind());
		assertEquals(Shape.BOX, marker.shape());
		assertEquals(new AABB(2, 0, 1, 6, 4, 5), marker.bounds());
	}

	@Test
	void tendIsTheFieldBoxWithTheContainerOutlined() {
		Marker marker = only(repeating("tend_farm", new BlockPos(5, 3, 1), new BlockPos(2, 0, 4), C));
		assertEquals(RtsTaskKind.TEND, marker.kind());
		assertEquals(Shape.BOX, marker.shape());
		assertEquals(new AABB(2, 0, 1, 6, 4, 5), marker.bounds());
		assertEquals(C, marker.outline());
		assertTrue(marker.repeating());
	}

	@Test
	void tendWithoutAContainerOutlinesNothing() {
		Marker marker = only(repeating("tend_farm", new BlockPos(5, 3, 1), new BlockPos(2, 0, 4)));
		assertEquals(Shape.BOX, marker.shape());
		assertNull(marker.outline());
	}

	@Test
	void oneShotOrdersAreNotRepeatingAndOutlineNothing() {
		Marker marker = only(task("harvest_area", A, B));
		assertFalse(marker.repeating());
		assertNull(marker.outline());
	}

	@Test
	void queuedRepeatingOrderKeepsItsFlagBehindAnAppendedOrder() {
		Markers markers = build(worker(WORKER, task("mine", A), repeating("tend_farm", B, C)));
		List<Marker> list = markers.markers();
		assertEquals(List.of(false, true), list.stream().map(Marker::repeating).toList());
		assertFalse(list.get(1).current());
	}

	@Test
	void buildIsItsFootprint() {
		BlockPos origin = new BlockPos(4, 1, 4);
		Marker marker = only(task("build", origin, new BlockPos(1, 1, 4), new BlockPos(4, 3, 5)));
		assertEquals(RtsTaskKind.BUILD, marker.kind());
		assertEquals(Shape.BOX, marker.shape());
		assertEquals(new AABB(1, 1, 4, 5, 4, 6), marker.bounds());
	}

	@Test
	void buildWithOnlyAnOriginIsAColumn() {
		Marker marker = only(task("build", A));
		assertEquals(RtsTaskKind.BUILD, marker.kind());
		assertEquals(Shape.COLUMN, marker.shape());
		assertEquals(Vec3.atCenterOf(A), marker.anchor());
	}

	// Ordering

	@Test
	void currentFirstThenQueueInOrder() {
		Markers markers = build(worker(WORKER, task("mine", A), task("move_to", B), task("place", C)));
		List<Marker> list = markers.markers();
		assertEquals(3, list.size());
		assertEquals(List.of(RtsTaskKind.MINE, RtsTaskKind.MOVE, RtsTaskKind.PLACE), list.stream().map(Marker::kind).toList());
		assertEquals(List.of(0, 1, 2), list.stream().map(Marker::order).toList());
		assertEquals(List.of(true, false, false), list.stream().map(Marker::current).toList());
		assertEquals(RtsOrderColors.line(RtsTaskKind.MINE), list.get(0).argb());
		assertEquals(RtsOrderColors.dimmed(RtsOrderColors.line(RtsTaskKind.MOVE)), list.get(1).argb());
		assertEquals(RtsOrderColors.dimmed(RtsOrderColors.line(RtsTaskKind.PLACE)), list.get(2).argb());

		List<Segment> segments = markers.segments();
		assertEquals(3, segments.size());
		assertSegment(segments.get(0), WORKER, Vec3.atCenterOf(A));
		assertSegment(segments.get(1), Vec3.atCenterOf(A), Vec3.atCenterOf(B));
		assertSegment(segments.get(2), Vec3.atCenterOf(B), Vec3.atCenterOf(C));
		assertEquals(List.of(true, false, false), segments.stream().map(Segment::current).toList());
		assertEquals(list.get(1).argb(), segments.get(1).argb());
	}

	@Test
	void noCurrentTaskMeansNothingIsCurrent() {
		WorkerSnapshot snapshot = snapshot(Optional.empty(), List.of(task("mine", A), task("place", B)));
		Markers markers = QueuedOrderMarkers.build(List.of(new QueuedOrderMarkers.Worker(WORKER, snapshot)), CAMERA, NO_CAPS);
		assertEquals(2, markers.markers().size());
		assertTrue(markers.markers().stream().noneMatch(Marker::current));
		assertTrue(markers.segments().stream().noneMatch(Segment::current));
		assertSegment(markers.segments().get(0), WORKER, Vec3.atCenterOf(A));
	}

	@Test
	void tasksWithoutTargetsOrOfOtherKindAreSkippedWithoutBreakingTheChain() {
		Markers markers = build(worker(WORKER, task("mine", A), task("place"), task("unknown", C), task("move_to", B)));
		assertEquals(List.of(RtsTaskKind.MINE, RtsTaskKind.MOVE), markers.markers().stream().map(Marker::kind).toList());
		assertEquals(List.of(0, 3), markers.markers().stream().map(Marker::order).toList());
		assertEquals(2, markers.segments().size());
		assertSegment(markers.segments().get(1), Vec3.atCenterOf(A), Vec3.atCenterOf(B));
	}

	@Test
	void idleWorkerGivesNothing() {
		WorkerSnapshot idle = snapshot(Optional.empty(), List.of());
		Markers markers = QueuedOrderMarkers.build(List.of(new QueuedOrderMarkers.Worker(WORKER, idle)), CAMERA, NO_CAPS);
		assertTrue(markers.markers().isEmpty());
		assertTrue(markers.segments().isEmpty());
	}

	@Test
	void workersKeepTheirOwnChains() {
		Vec3 other = new Vec3(10.5, 1, 10.5);
		Markers markers = build(worker(WORKER, task("mine", A)), worker(other, task("move_to", B), task("place", C)));
		assertEquals(List.of(0, 1, 1), markers.markers().stream().map(Marker::workerIndex).toList());
		assertSegment(markers.segments().get(1), other, Vec3.atCenterOf(B));
		assertTrue(markers.segments().get(1).current());
	}

	// Caps

	@Test
	void workersPastTheCapAreSkipped() {
		List<QueuedOrderMarkers.Worker> workers = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			workers.add(worker(WORKER, task("mine", A.offset(i, 0, 0))));
		}
		Markers markers = QueuedOrderMarkers.build(workers, CAMERA, new Limits(3, 1000, 1000, 10_000));
		assertEquals(List.of(0, 1, 2), markers.markers().stream().map(Marker::workerIndex).toList());
	}

	@Test
	void totalCapTruncatesTheTailInOrder() {
		Markers markers = QueuedOrderMarkers.build(List.of(
				worker(WORKER, task("mine", A), task("move_to", B), task("place", C)),
				worker(WORKER, task("mine", B), task("move_to", C))), CAMERA, new Limits(100, 4, 1000, 10_000));
		List<Marker> list = markers.markers();
		assertEquals(4, list.size());
		assertEquals(List.of(0, 0, 0, 1), list.stream().map(Marker::workerIndex).toList());
		assertEquals(List.of(0, 1, 2, 0), list.stream().map(Marker::order).toList());
		assertEquals(4, markers.segments().size());
	}

	@Test
	void perWorkerCapKeepsTheFirstTasks() {
		Markers markers = QueuedOrderMarkers.build(List.of(
				worker(WORKER, task("mine", A), task("move_to", B), task("place", C)),
				worker(WORKER, task("mine", B), task("move_to", C), task("place", A))), CAMERA, new Limits(100, 1000, 2, 10_000));
		assertEquals(List.of(0, 1, 0, 1), markers.markers().stream().map(Marker::order).toList());
		assertEquals(List.of(0, 0, 1, 1), markers.markers().stream().map(Marker::workerIndex).toList());
	}

	@Test
	void defaultLimitsCoverAFullSnapshot() {
		assertEquals(1 + WorkerSnapshot.MAX_QUEUED, Limits.DEFAULT.maxMarkersPerWorker());
		List<TaskSummary> tasks = new ArrayList<>();
		for (int i = 0; i <= WorkerSnapshot.MAX_QUEUED; i++) {
			tasks.add(task("mine", new BlockPos(i, 0, 0)));
		}
		Markers markers = QueuedOrderMarkers.build(List.of(worker(WORKER, tasks.toArray(TaskSummary[]::new))), CAMERA,
				Limits.DEFAULT);
		assertEquals(1 + WorkerSnapshot.MAX_QUEUED, markers.markers().size());
		assertTrue(Limits.DEFAULT.maxMarkers() < Limits.DEFAULT.maxWorkers() * Limits.DEFAULT.maxMarkersPerWorker(),
				"the total cap bounds a full selection");
	}

	@Test
	void farMarkersAndTheirLinesAreDroppedButLaterOnesKept() {
		BlockPos far = new BlockPos(500, 0, 0);
		Markers markers = QueuedOrderMarkers.build(List.of(worker(WORKER, task("mine", A), task("move_to", far),
				task("place", B), task("mine", C))), CAMERA, new Limits(100, 1000, 1000, 50));
		assertEquals(List.of(0, 2, 3), markers.markers().stream().map(Marker::order).toList());
		// worker→A, then nothing into or out of the far move, then B→C.
		assertEquals(2, markers.segments().size());
		assertSegment(markers.segments().get(0), WORKER, Vec3.atCenterOf(A));
		assertSegment(markers.segments().get(1), Vec3.atCenterOf(B), Vec3.atCenterOf(C));
	}

	@Test
	void distanceIsToTheNearestPointOfTheBounds() {
		// The dig box reaches to within 10 blocks of the camera although its centre is much farther.
		Markers markers = QueuedOrderMarkers.build(List.of(worker(WORKER,
				task("dig_area", new BlockPos(10, 10, 0), new BlockPos(200, 10, 0)))), CAMERA, new Limits(100, 1000, 1000, 20));
		assertEquals(1, markers.markers().size());
		assertEquals(0, QueuedOrderMarkers.distanceSqr(new AABB(0, 0, 0, 1, 1, 1), new Vec3(0.5, 0.5, 0.5)));
		assertEquals(4, QueuedOrderMarkers.distanceSqr(new AABB(0, 0, 0, 1, 1, 1), new Vec3(3, 0.5, 0.5)));
	}

	@Test
	void farWorkerDrawsNoLineToItsFirstMarker() {
		Vec3 farWorker = new Vec3(500, 1, 0);
		Markers markers = QueuedOrderMarkers.build(List.of(worker(farWorker, task("mine", A), task("place", B))), CAMERA,
				new Limits(100, 1000, 1000, 50));
		assertEquals(2, markers.markers().size());
		assertEquals(1, markers.segments().size());
		assertSegment(markers.segments().get(0), Vec3.atCenterOf(A), Vec3.atCenterOf(B));
		assertFalse(markers.segments().get(0).current());
	}

	// Helpers

	private static Marker only(TaskSummary current) {
		Markers markers = build(worker(WORKER, current));
		assertEquals(1, markers.markers().size());
		assertEquals(1, markers.segments().size());
		assertTrue(markers.markers().get(0).current());
		return markers.markers().get(0);
	}

	private static Markers build(QueuedOrderMarkers.Worker... workers) {
		return QueuedOrderMarkers.build(List.of(workers), CAMERA, NO_CAPS);
	}

	/** A worker whose first task is current and the rest queued. */
	private static QueuedOrderMarkers.Worker worker(Vec3 position, TaskSummary... tasks) {
		List<TaskSummary> list = List.of(tasks);
		return new QueuedOrderMarkers.Worker(position, snapshot(Optional.of(list.get(0)), list.subList(1, list.size())));
	}

	private static WorkerSnapshot snapshot(Optional<TaskSummary> current, List<TaskSummary> queued) {
		List<WorkerSnapshot.QueuedTask> entries = new ArrayList<>();
		for (int i = 0; i < queued.size(); i++) {
			entries.add(new WorkerSnapshot.QueuedTask(i + 1L, queued.get(i)));
		}
		return new WorkerSnapshot(1, Component.literal("worker"), current, entries, entries.size(), List.of(),
				WorkerSnapshot.ChunkStatus.LOADED);
	}

	private static TaskSummary task(String key, BlockPos... targets) {
		return new TaskSummary("rabodepalha.task." + key, List.of(), List.of(targets));
	}

	private static TaskSummary repeating(String key, BlockPos... targets) {
		return new TaskSummary("rabodepalha.task." + key, List.of(), List.of(targets), true);
	}

	private static void assertSegment(Segment segment, Vec3 from, Vec3 to) {
		assertEquals(from, segment.from());
		assertEquals(to, segment.to());
	}

	/** Rounds the bounds to 1e-9, so floating-point noise in the column's width does not fail equality. */
	private static AABB round(AABB box) {
		return new AABB(r(box.minX), r(box.minY), r(box.minZ), r(box.maxX), r(box.maxY), r(box.maxZ));
	}

	private static double r(double value) {
		return Math.round(value * 1e9) / 1e9;
	}
}
