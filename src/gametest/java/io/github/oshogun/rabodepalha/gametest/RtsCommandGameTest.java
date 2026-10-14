package io.github.oshogun.rabodepalha.gametest;

import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.LEVEL_0;
import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.LEVEL_1;
import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.LEVEL_2;
import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.assertResult;
import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.finish;
import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.layFloor;
import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.rtsPlayer;

import io.github.oshogun.rabodepalha.network.RtsSpawnPayload;
import io.github.oshogun.rabodepalha.network.RtsStopPayload;
import io.github.oshogun.rabodepalha.registry.ModEntities;
import io.github.oshogun.rabodepalha.rts.RtsMode;
import io.github.oshogun.rabodepalha.rts.RtsOrders;
import io.github.oshogun.rabodepalha.task.Task;
import io.github.oshogun.rabodepalha.task.TaskOutcome;
import io.github.oshogun.rabodepalha.task.TaskOutcome.Cancelled.Cause;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;
import io.github.oshogun.rabodepalha.worker.task.MoveToTask;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Server side of the RTS command bar's Stop and Spawn orders: {@link RtsOrders#handleStop} and
 * {@link RtsOrders#handleSpawn} with mock players from {@link RtsModeGameTest#joinPlayer}.
 */
public class RtsCommandGameTest {
	private static final BlockPos WORKER_START = new BlockPos(2, 1, 2);
	/** A floor block; Spawn puts the worker on top of it. */
	private static final BlockPos CLICKED = new BlockPos(5, 0, 5);

	private static RtsStopPayload stop(WorkerEntity... workers) {
		List<Integer> ids = new ArrayList<>();
		for (WorkerEntity worker : workers) {
			ids.add(worker.getId());
		}
		return new RtsStopPayload(ids);
	}

	/** A worker with two queued moves, none started yet. */
	private static WorkerEntity busyWorker(GameTestHelper helper) {
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		worker.tasks().enqueue(new MoveToTask(helper.absolutePos(new BlockPos(6, 1, 0))));
		worker.tasks().enqueue(new MoveToTask(helper.absolutePos(new BlockPos(0, 1, 6))));
		return worker;
	}

	private static void assertTasks(GameTestHelper helper, WorkerEntity worker, List<Task<WorkerEntity>> expected) {
		helper.assertTrue(worker.tasks().snapshot().equals(expected),
				"tasks changed: expected " + expected + ", got " + worker.tasks().snapshot());
	}

	/** Alive workers in and around the test structure. */
	private static List<WorkerEntity> workers(GameTestHelper helper) {
		AABB box = new AABB(Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO)),
				Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(8, 4, 8)))).inflate(4);
		return helper.getLevel().getEntitiesOfClass(WorkerEntity.class, box, WorkerEntity::isAlive);
	}

	private static void assertNoWorker(GameTestHelper helper) {
		helper.assertTrue(workers(helper).isEmpty(), "unexpected workers: " + workers(helper));
	}

	private static RtsSpawnPayload spawnOn(GameTestHelper helper, BlockPos clicked) {
		return new RtsSpawnPayload(helper.absolutePos(clicked));
	}

	// Stop

	@GameTest(maxTicks = 200)
	public void stopClearsQueueWithCleared(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = busyWorker(helper);
		Task<WorkerEntity> first = worker.tasks().snapshot().get(0);
		List<TaskOutcome> outcomes = new ArrayList<>();
		worker.tasks().addListener((task, outcome) -> outcomes.add(outcome));
		ServerPlayer player = rtsPlayer(helper);
		worker.addCommander(player.getUUID());

		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(worker.tasks().current().orElse(null) == first && outcomes.isEmpty(),
						"first move not running yet: " + worker.tasks().current() + ", " + outcomes))
				.thenExecute(() -> {
					RtsOrders.Result result = RtsOrders.handleStop(player, stop(worker), LEVEL_2);
					assertResult(helper, result, true, 1, "stopped");
					helper.assertTrue(worker.tasks().isIdle(), "worker still has tasks: " + worker.tasks().snapshot());
					helper.assertTrue(outcomes.equals(List.of(TaskOutcome.cancelled(Cause.CLEARED))),
							"expected one Cancelled(CLEARED), got " + outcomes);
					helper.assertFalse(WorkerEntity.notifiesCommanders(outcomes.get(0)), "cleared task would be reported");
				})
				.thenExecute(() -> RtsModeGameTest.leave(player))
				.thenSucceed();
	}

	/** Stop has no distance limit: the worker stays in the loaded test structure while the player moves far away. */
	@GameTest(maxTicks = 200)
	public void stopWorkerBeyond128Blocks(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = busyWorker(helper);
		Task<WorkerEntity> first = worker.tasks().snapshot().get(0);
		List<TaskOutcome> outcomes = new ArrayList<>();
		worker.tasks().addListener((task, outcome) -> outcomes.add(outcome));
		ServerPlayer player = rtsPlayer(helper);

		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(worker.tasks().current().orElse(null) == first && outcomes.isEmpty(),
						"first move not running yet: " + worker.tasks().current() + ", " + outcomes))
				.thenExecute(() -> {
					// setPos only, so chunk tracking does not change before the order is handled.
					player.setPos(player.position().add(128 + 72, 0, 0));
					helper.assertTrue(player.position().distanceTo(worker.position()) > 128, "player not far enough");
					helper.assertTrue(RtsMode.isActive(player), "player left RTS mode");
					helper.assertTrue(helper.getLevel().isLoaded(worker.blockPosition()), "worker chunk unloaded");

					assertResult(helper, RtsOrders.handleStop(player, stop(worker), LEVEL_2), true, 1, "stopped");
					helper.assertTrue(worker.tasks().isIdle(), "worker still has tasks: " + worker.tasks().snapshot());
					helper.assertTrue(outcomes.equals(List.of(TaskOutcome.cancelled(Cause.CLEARED))),
							"expected one Cancelled(CLEARED), got " + outcomes);
				})
				.thenExecute(() -> RtsModeGameTest.leave(player))
				.thenSucceed();
	}

	/**
	 * Moves the worker into an unloaded chunk. The server may stop resolving the worker's id at all, so either
	 * rejection reason is accepted.
	 */
	@GameTest
	public void stopRejectedWorkerNotLoaded(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = busyWorker(helper);
		List<Task<WorkerEntity>> before = worker.tasks().snapshot();
		ServerPlayer player = rtsPlayer(helper);
		worker.setPos(worker.position().add(10_000, 0, 0));
		helper.assertFalse(helper.getLevel().isLoaded(worker.blockPosition()), "worker chunk loaded");

		RtsOrders.Result result = RtsOrders.handleStop(player, stop(worker), LEVEL_2);

		helper.assertTrue(!result.ok() && result.applied() == 0, "expected rejection, got " + result);
		List<String> keys = RtsOrderGameTest.keys(result.message());
		helper.assertTrue(keys.contains("rabodepalha.rts.order.worker_not_loaded")
				|| keys.contains("rabodepalha.rts.order.unknown_worker"), "unexpected message: " + keys);
		assertTasks(helper, worker, before);
		worker.discard();
		finish(helper, player);
	}

	@GameTest
	public void stopRejectedWithoutPermission(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = busyWorker(helper);
		List<Task<WorkerEntity>> before = worker.tasks().snapshot();
		ServerPlayer player = rtsPlayer(helper);

		assertResult(helper, RtsOrders.handleStop(player, stop(worker), LEVEL_0), false, 0, "no_permission");
		assertResult(helper, RtsOrders.handleStop(player, stop(worker), LEVEL_1), false, 0, "no_permission");
		// The mock player is not an op, so its own permissions are rejected as well.
		assertResult(helper, RtsOrders.handleStop(player, stop(worker)), false, 0, "no_permission");
		assertTasks(helper, worker, before);
		finish(helper, player);
	}

	@GameTest
	public void stopRejectedOutsideRts(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = busyWorker(helper);
		List<Task<WorkerEntity>> before = worker.tasks().snapshot();
		ServerPlayer player = RtsModeGameTest.joinPlayer(helper);

		assertResult(helper, RtsOrders.handleStop(player, stop(worker), LEVEL_2), false, 0, "not_rts");
		assertTasks(helper, worker, before);
		finish(helper, player);
	}

	@GameTest
	public void stopUnknownWorker(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = busyWorker(helper);
		List<Task<WorkerEntity>> before = worker.tasks().snapshot();
		ServerPlayer player = rtsPlayer(helper);

		RtsOrders.Result result = RtsOrders.handleStop(player, new RtsStopPayload(List.of(Integer.MAX_VALUE)), LEVEL_2);
		assertResult(helper, result, false, 0, "unknown_worker");
		assertResult(helper, RtsOrders.handleStop(player, stop(), LEVEL_2), false, 0, "no_workers");
		assertTasks(helper, worker, before);
		finish(helper, player);
	}

	// Spawn

	@GameTest
	public void spawnCreatesWorkerAtClickedBlock(GameTestHelper helper) {
		layFloor(helper);
		ServerPlayer player = rtsPlayer(helper);
		BlockPos spot = helper.absolutePos(CLICKED.above());

		RtsOrders.Result result = RtsOrders.handleSpawn(player, spawnOn(helper, CLICKED), LEVEL_2);

		assertResult(helper, result, true, 1, "spawned");
		List<WorkerEntity> workers = workers(helper);
		helper.assertTrue(workers.size() == 1, "expected one worker, got " + workers);
		WorkerEntity worker = workers.get(0);
		helper.assertTrue(worker.blockPosition().equals(spot), "worker at " + worker.blockPosition() + ", expected " + spot);
		helper.assertTrue(worker.commanders().contains(player.getUUID()), "player not recorded as commander: " + worker.commanders());
		finish(helper, player);
	}

	@GameTest
	public void spawnRejectedNoRoom(GameTestHelper helper) {
		layFloor(helper);
		helper.setBlock(CLICKED.above(), Blocks.STONE);
		ServerPlayer player = rtsPlayer(helper);

		assertResult(helper, RtsOrders.handleSpawn(player, spawnOn(helper, CLICKED), LEVEL_2), false, 0, "no_room");
		assertNoWorker(helper);
		finish(helper, player);
	}

	@GameTest
	public void spawnRejectedWithoutPermission(GameTestHelper helper) {
		layFloor(helper);
		ServerPlayer player = rtsPlayer(helper);

		assertResult(helper, RtsOrders.handleSpawn(player, spawnOn(helper, CLICKED), LEVEL_0), false, 0, "no_permission");
		assertResult(helper, RtsOrders.handleSpawn(player, spawnOn(helper, CLICKED), LEVEL_1), false, 0, "no_permission");
		assertResult(helper, RtsOrders.handleSpawn(player, spawnOn(helper, CLICKED)), false, 0, "no_permission");
		assertNoWorker(helper);
		finish(helper, player);
	}

	@GameTest
	public void spawnRejectedOutsideRts(GameTestHelper helper) {
		layFloor(helper);
		ServerPlayer player = RtsModeGameTest.joinPlayer(helper);

		assertResult(helper, RtsOrders.handleSpawn(player, spawnOn(helper, CLICKED), LEVEL_2), false, 0, "not_rts");
		assertNoWorker(helper);
		finish(helper, player);
	}

	@GameTest
	public void spawnTargetTooFar(GameTestHelper helper) {
		layFloor(helper);
		ServerPlayer player = rtsPlayer(helper);
		BlockPos far = CLICKED.offset((int) RtsOrders.MAX_TARGET_DISTANCE + 16, 0, 0);

		assertResult(helper, RtsOrders.handleSpawn(player, spawnOn(helper, far), LEVEL_2), false, 0, "target_too_far");
		assertNoWorker(helper);
		finish(helper, player);
	}

	/** Uses the overload with an explicit limit, so the distance check passes and the chunk check fails. */
	@GameTest
	public void spawnNotLoaded(GameTestHelper helper) {
		layFloor(helper);
		ServerPlayer player = rtsPlayer(helper);
		BlockPos far = CLICKED.offset(10_000, 0, 0);

		RtsOrders.Result result = RtsOrders.handleSpawn(player, spawnOn(helper, far), LEVEL_2, 1.0E6);

		assertResult(helper, result, false, 0, "not_loaded");
		assertNoWorker(helper);
		finish(helper, player);
	}
}
