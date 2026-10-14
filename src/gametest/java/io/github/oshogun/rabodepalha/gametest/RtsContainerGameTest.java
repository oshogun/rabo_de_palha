package io.github.oshogun.rabodepalha.gametest;

import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.LEVEL_0;
import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.LEVEL_2;
import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.assertResult;
import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.assertUntouched;
import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.finish;
import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.layFloor;
import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.rtsPlayer;

import io.github.oshogun.rabodepalha.network.RtsContentsRequestPayload;
import io.github.oshogun.rabodepalha.network.RtsDepositPayload;
import io.github.oshogun.rabodepalha.network.RtsWithdrawPayload;
import io.github.oshogun.rabodepalha.registry.ModEntities;
import io.github.oshogun.rabodepalha.rts.RtsItemPicker;
import io.github.oshogun.rabodepalha.rts.RtsOrders;
import io.github.oshogun.rabodepalha.task.Task;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;
import io.github.oshogun.rabodepalha.worker.task.DepositTask;
import io.github.oshogun.rabodepalha.worker.task.WithdrawTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * Server side of the RTS container orders: {@link RtsOrders#handleDeposit}, {@link RtsOrders#handleWithdraw} and
 * {@link RtsOrders#handleContents}, with mock players from {@link RtsModeGameTest#joinPlayer}.
 */
public class RtsContainerGameTest {
	private static final BlockPos WORKER_A = new BlockPos(2, 1, 2);
	private static final BlockPos WORKER_B = new BlockPos(3, 1, 2);
	private static final BlockPos CONTAINER = new BlockPos(6, 1, 6);
	/** A floor block, not a container. */
	private static final BlockPos FLOOR = new BlockPos(5, 0, 5);
	private static final Identifier DIRT = BuiltInRegistries.ITEM.getKey(Items.DIRT);
	private static final Identifier COBBLESTONE = BuiltInRegistries.ITEM.getKey(Items.COBBLESTONE);

	private static ChestBlockEntity placeChest(GameTestHelper helper, BlockPos rel) {
		helper.setBlock(rel, Blocks.CHEST);
		return helper.getBlockEntity(rel, ChestBlockEntity.class);
	}

	private static List<Integer> ids(WorkerEntity... workers) {
		List<Integer> ids = new ArrayList<>();
		for (WorkerEntity worker : workers) {
			ids.add(worker.getId());
		}
		return ids;
	}

	private static RtsDepositPayload deposit(GameTestHelper helper, BlockPos rel, Optional<Identifier> item,
			WorkerEntity... workers) {
		return new RtsDepositPayload(ids(workers), helper.absolutePos(rel), item, false);
	}

	private static RtsWithdrawPayload withdraw(GameTestHelper helper, BlockPos rel, Identifier item, int count,
			WorkerEntity... workers) {
		return new RtsWithdrawPayload(ids(workers), helper.absolutePos(rel), item, count, false);
	}

	private static RtsContentsRequestPayload contents(GameTestHelper helper, BlockPos rel) {
		return new RtsContentsRequestPayload(helper.absolutePos(rel));
	}

	/** Asserts that {@code worker}'s whole queue is one deposit into {@code pos} of {@code item}. */
	private static void assertOnlyDeposit(GameTestHelper helper, WorkerEntity worker, BlockPos pos, Optional<Identifier> item) {
		List<Task<WorkerEntity>> queue = worker.tasks().snapshot();
		helper.assertTrue(queue.size() == 1 && queue.get(0) instanceof DepositTask d && d.pos().equals(pos)
				&& d.itemId().equals(item), "expected only a deposit " + item + " into " + pos + ", got " + queue);
	}

	private static void assertContentsRejected(GameTestHelper helper, RtsOrders.ContentsResult result, String key) {
		assertResult(helper, result.result(), false, 0, key);
		helper.assertTrue(result.entries().isEmpty(), "rejected request returned entries: " + result.entries());
	}

	@GameTest
	public void depositAllQueuesDepositTask(GameTestHelper helper) {
		layFloor(helper);
		placeChest(helper, CONTAINER);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_A);
		ServerPlayer player = rtsPlayer(helper);

		RtsOrders.Result result = RtsOrders.handleDeposit(player, deposit(helper, CONTAINER, Optional.empty(), worker), LEVEL_2);

		assertResult(helper, result, true, 1, "deposit_applied");
		assertOnlyDeposit(helper, worker, helper.absolutePos(CONTAINER), Optional.empty());
		finish(helper, player);
	}

	@GameTest
	public void depositOneQueuesDepositTask(GameTestHelper helper) {
		layFloor(helper);
		placeChest(helper, CONTAINER);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_A);
		ServerPlayer player = rtsPlayer(helper);

		RtsOrders.Result result = RtsOrders.handleDeposit(player, deposit(helper, CONTAINER, Optional.of(DIRT), worker), LEVEL_2);

		assertResult(helper, result, true, 1, "deposit_applied");
		assertOnlyDeposit(helper, worker, helper.absolutePos(CONTAINER), Optional.of(DIRT));
		finish(helper, player);
	}

	@GameTest
	public void depositOnNonContainerRejected(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_A);
		ServerPlayer player = rtsPlayer(helper);

		RtsOrders.Result result = RtsOrders.handleDeposit(player, deposit(helper, FLOOR, Optional.empty(), worker), LEVEL_2);

		assertResult(helper, result, false, 0, "not_container");
		assertUntouched(helper, worker);
		finish(helper, player);
	}

	@GameTest
	public void contentsSummedByItem(GameTestHelper helper) {
		layFloor(helper);
		ChestBlockEntity chest = placeChest(helper, CONTAINER);
		chest.setItem(0, new ItemStack(Items.DIRT, 4));
		chest.setItem(3, new ItemStack(Items.COBBLESTONE, 3));
		chest.setItem(7, new ItemStack(Items.DIRT, 6));
		ServerPlayer player = rtsPlayer(helper);

		RtsOrders.ContentsResult result = RtsOrders.handleContents(player, contents(helper, CONTAINER), LEVEL_2);

		helper.assertTrue(result.result().ok(), "contents rejected: " + result.result());
		List<RtsItemPicker.Entry> expected = List.of(new RtsItemPicker.Entry(DIRT, 10), new RtsItemPicker.Entry(COBBLESTONE, 3));
		helper.assertTrue(result.entries().equals(expected), "expected " + expected + ", got " + result.entries());
		finish(helper, player);
	}

	/** Totals are not capped at a stack: five full stacks of dirt come back as one entry of 320. */
	@GameTest
	public void contentsTotalExceedsStack(GameTestHelper helper) {
		layFloor(helper);
		ChestBlockEntity chest = placeChest(helper, CONTAINER);
		for (int i = 0; i < 5; i++) {
			chest.setItem(i, new ItemStack(Items.DIRT, 64));
		}
		ServerPlayer player = rtsPlayer(helper);

		RtsOrders.ContentsResult result = RtsOrders.handleContents(player, contents(helper, CONTAINER), LEVEL_2);

		helper.assertTrue(result.result().ok(), "contents rejected: " + result.result());
		List<RtsItemPicker.Entry> expected = List.of(new RtsItemPicker.Entry(DIRT, 320));
		helper.assertTrue(result.entries().equals(expected), "expected " + expected + ", got " + result.entries());
		finish(helper, player);
	}

	@GameTest
	public void contentsRejections(GameTestHelper helper) {
		layFloor(helper);
		placeChest(helper, CONTAINER);
		ServerPlayer player = rtsPlayer(helper);

		assertContentsRejected(helper, RtsOrders.handleContents(player, contents(helper, FLOOR), LEVEL_2), "not_container");
		assertContentsRejected(helper, RtsOrders.handleContents(player, contents(helper, CONTAINER), LEVEL_2, 1.0),
				"target_too_far");
		BlockPos unloaded = CONTAINER.offset(100_000, 0, 100_000);
		assertContentsRejected(helper, RtsOrders.handleContents(player, contents(helper, unloaded), LEVEL_2, 1_000_000.0),
				"not_loaded");
		assertContentsRejected(helper, RtsOrders.handleContents(player, contents(helper, CONTAINER), LEVEL_0),
				"no_permission");
		finish(helper, player);
	}

	@GameTest
	public void contentsNotInRtsRejected(GameTestHelper helper) {
		layFloor(helper);
		placeChest(helper, CONTAINER);
		ServerPlayer player = RtsModeGameTest.joinPlayer(helper);

		assertContentsRejected(helper, RtsOrders.handleContents(player, contents(helper, CONTAINER), LEVEL_2), "not_rts");
		finish(helper, player);
	}

	/** The count is per worker: each selected worker gets its own withdraw of the full count. */
	@GameTest
	public void withdrawQueuesPerWorker(GameTestHelper helper) {
		layFloor(helper);
		ChestBlockEntity chest = placeChest(helper, CONTAINER);
		chest.setItem(0, new ItemStack(Items.DIRT, 20));
		WorkerEntity a = helper.spawn(ModEntities.WORKER, WORKER_A);
		WorkerEntity b = helper.spawn(ModEntities.WORKER, WORKER_B);
		ServerPlayer player = rtsPlayer(helper);
		BlockPos pos = helper.absolutePos(CONTAINER);

		RtsOrders.Result result = RtsOrders.handleWithdraw(player, withdraw(helper, CONTAINER, DIRT, 5, a, b), LEVEL_2);

		assertResult(helper, result, true, 2, "withdraw_applied");
		for (WorkerEntity worker : List.of(a, b)) {
			List<Task<WorkerEntity>> queue = worker.tasks().snapshot();
			helper.assertTrue(queue.size() == 1 && queue.get(0) instanceof WithdrawTask w && w.pos().equals(pos)
					&& w.itemId().equals(DIRT) && w.count() == 5, "expected only a withdraw dirt x5 from " + pos + ", got " + queue);
		}
		finish(helper, player);
	}

	@GameTest
	public void withdrawRejections(GameTestHelper helper) {
		layFloor(helper);
		placeChest(helper, CONTAINER);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_A);
		ServerPlayer player = rtsPlayer(helper);

		assertResult(helper, RtsOrders.handleWithdraw(player, withdraw(helper, CONTAINER, DIRT, 0, worker), LEVEL_2),
				false, 0, "bad_count");
		assertResult(helper, RtsOrders.handleWithdraw(player, withdraw(helper, FLOOR, DIRT, 5, worker), LEVEL_2),
				false, 0, "not_container");
		assertUntouched(helper, worker);
		finish(helper, player);
	}
}
