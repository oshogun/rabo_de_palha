package io.github.oshogun.rabodepalha.gametest;

import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.LEVEL_0;
import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.LEVEL_1;
import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.LEVEL_2;
import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.assertResult;
import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.finish;
import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.layFloor;
import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.rtsPlayer;

import io.github.oshogun.rabodepalha.network.RtsRemoveTaskPayload;
import io.github.oshogun.rabodepalha.network.RtsSnapshotPayload;
import io.github.oshogun.rabodepalha.network.RtsWatchPayload;
import io.github.oshogun.rabodepalha.registry.ModEntities;
import io.github.oshogun.rabodepalha.rts.RtsOrders;
import io.github.oshogun.rabodepalha.rts.RtsWatchers;
import io.github.oshogun.rabodepalha.rts.WorkerSnapshot;
import io.github.oshogun.rabodepalha.task.Task;
import io.github.oshogun.rabodepalha.task.TaskRunner;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;
import io.github.oshogun.rabodepalha.worker.task.MoveToTask;
import io.github.oshogun.rabodepalha.worker.task.TaskTypes;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Server side of the RTS side panel: {@link RtsOrders#handleWatch} with {@link RtsWatchers#collect}, and
 * {@link RtsOrders#handleRemoveTask}, with mock players from {@link RtsModeGameTest#joinPlayer}. Watch and
 * collect run in the same tick, so the server's own watcher tick cannot consume the snapshot in between.
 */
public class RtsPanelGameTest {
	private static final BlockPos WORKER_START = new BlockPos(2, 1, 2);
	private static final int UNKNOWN_ID = Integer.MAX_VALUE;

	/** A worker with three queued moves, none started yet. */
	private static WorkerEntity busyWorker(GameTestHelper helper) {
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		worker.tasks().enqueue(new MoveToTask(helper.absolutePos(new BlockPos(7, 1, 7))));
		worker.tasks().enqueue(new MoveToTask(helper.absolutePos(new BlockPos(0, 1, 7))));
		worker.tasks().enqueue(new MoveToTask(helper.absolutePos(new BlockPos(7, 1, 0))));
		return worker;
	}

	private static RtsWatchPayload watch(int... ids) {
		return new RtsWatchPayload(Arrays.stream(ids).boxed().toList());
	}

	private static void assertTasks(GameTestHelper helper, WorkerEntity worker, List<Task<WorkerEntity>> expected) {
		helper.assertTrue(worker.tasks().snapshot().equals(expected),
				"tasks changed: expected " + expected + ", got " + worker.tasks().snapshot());
	}

	/** Asserts {@code snapshot} shows {@code worker}'s current task, queue in order and every inventory slot. */
	private static void assertMatches(GameTestHelper helper, WorkerSnapshot snapshot, WorkerEntity worker) {
		helper.assertTrue(snapshot.entityId() == worker.getId(), "snapshot of " + snapshot.entityId());
		helper.assertTrue(snapshot.name().equals(worker.label()), "name " + snapshot.name());
		helper.assertTrue(snapshot.chunks() == WorkerSnapshot.ChunkStatus.of(worker), "chunks " + snapshot.chunks());

		Optional<Task<WorkerEntity>> current = worker.tasks().current();
		helper.assertTrue(current.isPresent(), "worker has no current task");
		helper.assertTrue(snapshot.current().equals(current.map(TaskTypes::summarize)),
				"current " + snapshot.current() + ", expected " + current);

		List<TaskRunner.Pending<WorkerEntity>> pending = worker.tasks().pendingEntries();
		helper.assertTrue(snapshot.queuedTotal() == pending.size() && snapshot.queued().size() == pending.size(),
				"queued " + snapshot.queued() + " of " + snapshot.queuedTotal() + ", expected " + pending);
		for (int i = 0; i < pending.size(); i++) {
			WorkerSnapshot.QueuedTask queued = snapshot.queued().get(i);
			helper.assertTrue(queued.taskId() == pending.get(i).id()
					&& queued.summary().equals(TaskTypes.summarize(pending.get(i).task())),
					"queued #" + i + " is " + queued + ", expected " + pending.get(i));
		}

		helper.assertTrue(snapshot.inventory().size() == worker.getInventory().getContainerSize(),
				"inventory size " + snapshot.inventory().size());
		for (int i = 0; i < snapshot.inventory().size(); i++) {
			helper.assertTrue(ItemStack.matches(snapshot.inventory().get(i), worker.getInventory().getItem(i)),
					"slot " + i + " is " + snapshot.inventory().get(i) + ", expected " + worker.getInventory().getItem(i));
		}
	}

	// Watch

	@GameTest(maxTicks = 200)
	public void watchSendsSnapshotOfQueueAndInventory(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = busyWorker(helper);
		Task<WorkerEntity> first = worker.tasks().snapshot().get(0);
		worker.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 5));
		worker.getInventory().setItem(3, new ItemStack(Items.OAK_LOG, 12));
		ServerPlayer player = rtsPlayer(helper);

		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(worker.tasks().current().orElse(null) == first,
						"first move not running yet: " + worker.tasks().current()))
				.thenExecute(() -> {
					RtsOrders.Result result = RtsOrders.handleWatch(player, watch(worker.getId()), LEVEL_2);
					helper.assertTrue(result.ok() && result.applied() == 1, "watch rejected: " + result);
					helper.assertTrue(RtsWatchers.watched(player).equals(List.of(worker.getId())),
							"watching " + RtsWatchers.watched(player));

					Optional<RtsSnapshotPayload> payload = RtsWatchers.collect(player);
					helper.assertTrue(payload.isPresent(), "no snapshot");
					helper.assertTrue(payload.get().gone().isEmpty(), "gone " + payload.get().gone());
					helper.assertTrue(payload.get().snapshots().size() == 1, "snapshots " + payload.get().snapshots());
					assertMatches(helper, payload.get().snapshots().get(0), worker);
					helper.assertTrue(payload.get().snapshots().get(0).queued().size() == 2, "expected two queued moves");
					// Snapshots are sent on change: nothing changed since the last collect.
					helper.assertTrue(RtsWatchers.collect(player).isEmpty(), "unchanged worker sent again");
				})
				.thenExecute(() -> RtsModeGameTest.leave(player))
				.thenSucceed();
	}

	/** Some ids are live: the watch goes ahead and the unknown ones are reported as gone. */
	@GameTest
	public void watchReportsUnknownAmongLiveAsGone(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = busyWorker(helper);
		ServerPlayer player = rtsPlayer(helper);

		RtsOrders.Result result = RtsOrders.handleWatch(player, watch(worker.getId(), UNKNOWN_ID), LEVEL_2);

		helper.assertTrue(result.ok() && result.applied() == 1, "watch rejected: " + result);
		helper.assertTrue(RtsWatchers.watched(player).equals(List.of(worker.getId())),
				"watching " + RtsWatchers.watched(player));
		Optional<RtsSnapshotPayload> payload = RtsWatchers.collect(player);
		helper.assertTrue(payload.isPresent() && payload.get().gone().equals(List.of(UNKNOWN_ID)),
				"expected the unknown id as gone, got " + payload);
		finish(helper, player);
	}

	@GameTest
	public void watchRejectedUnknownWorker(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = busyWorker(helper);
		ServerPlayer player = rtsPlayer(helper);
		helper.assertTrue(RtsOrders.handleWatch(player, watch(worker.getId()), LEVEL_2).ok(), "first watch rejected");

		assertResult(helper, RtsOrders.handleWatch(player, watch(UNKNOWN_ID), LEVEL_2), false, 0, "unknown_worker");
		// The rejected watch also ends the previous one.
		helper.assertTrue(RtsWatchers.watched(player).isEmpty(), "still watching " + RtsWatchers.watched(player));
		helper.assertTrue(RtsWatchers.collect(player).isEmpty(), "snapshot sent after a rejected watch");
		finish(helper, player);
	}

	@GameTest
	public void watchRejectedWithoutPermission(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = busyWorker(helper);
		ServerPlayer player = rtsPlayer(helper);

		assertResult(helper, RtsOrders.handleWatch(player, watch(worker.getId()), LEVEL_0), false, 0, "no_permission");
		assertResult(helper, RtsOrders.handleWatch(player, watch(worker.getId()), LEVEL_1), false, 0, "no_permission");
		// The mock player is not an op, so its own permissions are rejected as well.
		assertResult(helper, RtsOrders.handleWatch(player, watch(worker.getId())), false, 0, "no_permission");
		helper.assertTrue(RtsWatchers.watched(player).isEmpty(), "watching " + RtsWatchers.watched(player));
		finish(helper, player);
	}

	@GameTest
	public void watchRejectedOutsideRts(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = busyWorker(helper);
		ServerPlayer player = RtsModeGameTest.joinPlayer(helper);

		assertResult(helper, RtsOrders.handleWatch(player, watch(worker.getId()), LEVEL_2), false, 0, "not_rts");
		helper.assertTrue(RtsWatchers.watched(player).isEmpty(), "watching " + RtsWatchers.watched(player));
		finish(helper, player);
	}

	// Remove

	@GameTest
	public void removeDropsExactlyThatTask(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = busyWorker(helper);
		List<TaskRunner.Pending<WorkerEntity>> before = worker.tasks().pendingEntries();
		helper.assertTrue(before.size() == 3, "expected three queued tasks, got " + before);
		ServerPlayer player = rtsPlayer(helper);
		RtsRemoveTaskPayload middle = new RtsRemoveTaskPayload(worker.getId(), before.get(1).id());

		assertResult(helper, RtsOrders.handleRemoveTask(player, middle, LEVEL_2), true, 1, "task_removed");
		assertTasks(helper, worker, List.of(before.get(0).task(), before.get(2).task()));
		List<TaskRunner.Pending<WorkerEntity>> after = worker.tasks().pendingEntries();
		helper.assertTrue(after.equals(List.of(before.get(0), before.get(2))), "ids changed: " + after);

		// The same task again is no longer queued.
		assertResult(helper, RtsOrders.handleRemoveTask(player, middle, LEVEL_2), false, 0, "task_gone");
		assertTasks(helper, worker, List.of(before.get(0).task(), before.get(2).task()));
		finish(helper, player);
	}

	@GameTest
	public void removeRejectedWithoutPermission(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = busyWorker(helper);
		List<Task<WorkerEntity>> before = worker.tasks().snapshot();
		RtsRemoveTaskPayload payload = new RtsRemoveTaskPayload(worker.getId(), worker.tasks().pendingEntries().get(1).id());
		ServerPlayer player = rtsPlayer(helper);

		assertResult(helper, RtsOrders.handleRemoveTask(player, payload, LEVEL_0), false, 0, "no_permission");
		assertResult(helper, RtsOrders.handleRemoveTask(player, payload, LEVEL_1), false, 0, "no_permission");
		assertResult(helper, RtsOrders.handleRemoveTask(player, payload), false, 0, "no_permission");
		assertTasks(helper, worker, before);
		finish(helper, player);
	}

	@GameTest
	public void removeRejectedOutsideRts(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = busyWorker(helper);
		List<Task<WorkerEntity>> before = worker.tasks().snapshot();
		RtsRemoveTaskPayload payload = new RtsRemoveTaskPayload(worker.getId(), worker.tasks().pendingEntries().get(1).id());
		ServerPlayer player = RtsModeGameTest.joinPlayer(helper);

		assertResult(helper, RtsOrders.handleRemoveTask(player, payload, LEVEL_2), false, 0, "not_rts");
		assertTasks(helper, worker, before);
		finish(helper, player);
	}

	@GameTest
	public void removeRejectedUnknownWorker(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = busyWorker(helper);
		List<Task<WorkerEntity>> before = worker.tasks().snapshot();
		long taskId = worker.tasks().pendingEntries().get(1).id();
		ServerPlayer player = rtsPlayer(helper);

		assertResult(helper, RtsOrders.handleRemoveTask(player, new RtsRemoveTaskPayload(UNKNOWN_ID, taskId), LEVEL_2),
				false, 0, "unknown_worker");
		assertTasks(helper, worker, before);
		finish(helper, player);
	}
}
