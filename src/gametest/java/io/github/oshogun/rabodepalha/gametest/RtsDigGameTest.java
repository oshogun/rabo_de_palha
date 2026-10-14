package io.github.oshogun.rabodepalha.gametest;

import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.LEVEL_2;
import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.assertResult;
import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.assertUntouched;
import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.finish;
import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.layFloor;
import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.rtsPlayer;

import io.github.oshogun.rabodepalha.network.RtsDigPayload;
import io.github.oshogun.rabodepalha.registry.ModEntities;
import io.github.oshogun.rabodepalha.rts.RtsOrders;
import io.github.oshogun.rabodepalha.task.Task;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;
import io.github.oshogun.rabodepalha.worker.task.DigAreaTask;
import io.github.oshogun.rabodepalha.worker.task.MoveToTask;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server side of the RTS dig order: {@link RtsOrders#handleDig(ServerPlayer, RtsDigPayload,
 * net.minecraft.server.permissions.PermissionSet)} with mock players, reusing the {@link RtsOrderGameTest}
 * helpers. Messages are checked by their translation keys.
 */
public class RtsDigGameTest {
	private static final BlockPos WORKER_A = new BlockPos(1, 1, 6);
	private static final BlockPos WORKER_B = new BlockPos(6, 1, 1);
	/** Two opposite floor corners of a 4×4×1 area, given max-first to check that the server normalises them. */
	private static final BlockPos CORNER_A = new BlockPos(5, 0, 5);
	private static final BlockPos CORNER_B = new BlockPos(2, 0, 2);

	private static RtsDigPayload dig(BlockPos from, BlockPos to, boolean append, WorkerEntity... workers) {
		List<Integer> ids = new ArrayList<>();
		for (WorkerEntity worker : workers) {
			ids.add(worker.getId());
		}
		return new RtsDigPayload(ids, from, to, append);
	}

	private static BlockPos min(BlockPos a, BlockPos b) {
		return new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
	}

	private static BlockPos max(BlockPos a, BlockPos b) {
		return new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
	}

	private static boolean isDig(Task<WorkerEntity> task, BlockPos min, BlockPos max) {
		return task instanceof DigAreaTask d && d.from().equals(min) && d.to().equals(max);
	}

	@GameTest
	public void digQueuesOnEachWorker(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity a = helper.spawn(ModEntities.WORKER, WORKER_A);
		WorkerEntity b = helper.spawn(ModEntities.WORKER, WORKER_B);
		ServerPlayer player = rtsPlayer(helper);
		BlockPos low = helper.absolutePos(CORNER_B);
		BlockPos high = helper.absolutePos(CORNER_A);
		// absolutePos may rotate the test, so normalise the expected corners as well.
		BlockPos expectedMin = min(low, high);
		BlockPos expectedMax = max(low, high);

		RtsOrders.Result result = RtsOrders.handleDig(player, dig(high, low, false, a, b), LEVEL_2);

		assertResult(helper, result, true, 2, "dig_replaced");
		List<Task<WorkerEntity>> queueA = a.tasks().snapshot();
		List<Task<WorkerEntity>> queueB = b.tasks().snapshot();
		helper.assertTrue(queueA.size() == 1 && isDig(queueA.get(0), expectedMin, expectedMax),
				"expected only a dig " + expectedMin + ".." + expectedMax + ", got " + queueA);
		helper.assertTrue(queueB.size() == 1 && isDig(queueB.get(0), expectedMin, expectedMax),
				"expected only a dig " + expectedMin + ".." + expectedMax + ", got " + queueB);
		helper.assertTrue(queueA.get(0) != queueB.get(0), "workers share one task instance");
		helper.assertTrue(a.commanders().contains(player.getUUID()), "player not recorded as commander");
		finish(helper, player);
	}

	@GameTest
	public void digOverVolumeRejected(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_A);
		ServerPlayer player = rtsPlayer(helper);
		BlockPos from = helper.absolutePos(new BlockPos(0, 0, 0));
		// 17×16×16 = 4352 > 4096.
		BlockPos to = from.offset(16, -15, 15);

		RtsOrders.Result result = RtsOrders.handleDig(player, dig(from, to, false, worker), LEVEL_2);

		assertResult(helper, result, false, 0, "area_too_large");
		assertUntouched(helper, worker);
		finish(helper, player);
	}

	/** Uses the overload with explicit limits, so the area stays inside the loaded test area. */
	@GameTest
	public void digTooFarRejected(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_A);
		ServerPlayer player = rtsPlayer(helper);
		RtsDigPayload payload = dig(helper.absolutePos(CORNER_A), helper.absolutePos(CORNER_B), false, worker);

		RtsOrders.Result result = RtsOrders.handleDig(player, payload, LEVEL_2, 1.0, RtsOrders.MAX_WORKER_DISTANCE);

		assertResult(helper, result, false, 0, "target_too_far");
		assertUntouched(helper, worker);
		finish(helper, player);
	}

	@GameTest
	public void digNotLoadedRejected(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_A);
		ServerPlayer player = rtsPlayer(helper);
		BlockPos from = helper.absolutePos(CORNER_A).offset(100_000, 0, 100_000);
		BlockPos to = from.offset(2, 0, 2);

		RtsOrders.Result result = RtsOrders.handleDig(player, dig(from, to, false, worker), LEVEL_2, 1_000_000.0,
				RtsOrders.MAX_WORKER_DISTANCE);

		assertResult(helper, result, false, 0, "not_loaded");
		assertUntouched(helper, worker);
		finish(helper, player);
	}

	@GameTest
	public void digAppendKeepsQueue(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_A);
		ServerPlayer player = rtsPlayer(helper);
		MoveToTask first = new MoveToTask(helper.absolutePos(new BlockPos(6, 1, 6)));
		worker.tasks().enqueue(first);
		BlockPos from = helper.absolutePos(CORNER_A);
		BlockPos to = helper.absolutePos(CORNER_B);
		BlockPos min = min(from, to);
		BlockPos max = max(from, to);

		RtsOrders.Result appended = RtsOrders.handleDig(player, dig(from, to, true, worker), LEVEL_2);
		assertResult(helper, appended, true, 1, "dig_queued");
		List<Task<WorkerEntity>> queue = worker.tasks().snapshot();
		helper.assertTrue(queue.size() == 2 && queue.get(0) == first && isDig(queue.get(1), min, max),
				"expected the dig appended, got " + queue);

		RtsOrders.Result replaced = RtsOrders.handleDig(player, dig(from, to, false, worker), LEVEL_2);
		assertResult(helper, replaced, true, 1, "dig_replaced");
		List<Task<WorkerEntity>> after = worker.tasks().snapshot();
		helper.assertTrue(after.size() == 1 && isDig(after.get(0), min, max), "expected only the dig, got " + after);
		finish(helper, player);
	}
}
