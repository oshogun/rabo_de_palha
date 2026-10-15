package io.github.oshogun.rabodepalha.gametest;

import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.LEVEL_2;
import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.assertResult;
import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.rtsPlayer;

import io.github.oshogun.rabodepalha.network.RtsOrderPayload;
import io.github.oshogun.rabodepalha.network.RtsStopPayload;
import io.github.oshogun.rabodepalha.registry.ModEntities;
import io.github.oshogun.rabodepalha.rts.RtsOrderKind;
import io.github.oshogun.rabodepalha.rts.RtsOrders;
import io.github.oshogun.rabodepalha.task.Task;
import io.github.oshogun.rabodepalha.task.TaskOutcome;
import io.github.oshogun.rabodepalha.task.TaskOutcome.Cancelled.Cause;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;
import io.github.oshogun.rabodepalha.worker.task.TendFarmTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The tend_farm task, driven through {@code /rdp tend}, {@code /rdp clear} and the RTS stop and move orders. The
 * field is a row of three wet farmland columns set into the stone floor, the plants on top; the worker starts in
 * a corner and the produce chest stands at the far side.
 */
public class WorkerTendGameTest {
	private static final BlockPos WORKER_START = new BlockPos(1, 1, 1);
	private static final BlockPos FIELD_FROM = new BlockPos(3, 0, 3);
	private static final BlockPos FIELD_TO = new BlockPos(5, 0, 3);
	private static final BlockPos WATER = new BlockPos(4, 0, 5);
	private static final BlockPos CHEST = new BlockPos(6, 1, 1);
	/** A floor block; the appended move targets the air above it. */
	private static final BlockPos MOVE_CLICKED = new BlockPos(1, 0, 6);
	private static final BlockState WET_FARMLAND = Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE,
			FarmlandBlock.MAX_MOISTURE);
	/** Long enough for three scans of a field with nothing to do. */
	private static final int THREE_ROUNDS = TendFarmTask.WAIT_TICKS * 3 + 20;

	private static List<BlockPos> field() {
		List<BlockPos> field = new ArrayList<>();
		for (int x = FIELD_FROM.getX(); x <= FIELD_TO.getX(); x++) {
			field.add(new BlockPos(x, FIELD_FROM.getY(), FIELD_FROM.getZ()));
		}
		return field;
	}

	/** Lays the floor and wet farmland, with {@code plants[i]} (or nothing, when null) on column {@code i}. */
	private static void layField(GameTestHelper helper, BlockState... plants) {
		WorkerTaskGameTest.layFloor(helper);
		helper.setBlock(WATER, Blocks.WATER);
		List<BlockPos> field = field();
		for (int i = 0; i < field.size(); i++) {
			helper.setBlock(field.get(i), WET_FARMLAND);
			if (plants[i] != null) {
				helper.setBlock(field.get(i).above(), plants[i]);
			}
		}
	}

	private static BlockState wheat(int age) {
		return ((CropBlock) Blocks.WHEAT).getStateForAge(age);
	}

	private static WorkerEntity spawnWorker(GameTestHelper helper, List<TaskOutcome> outcomes) {
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		worker.tasks().addListener((task, outcome) -> outcomes.add(outcome));
		return worker;
	}

	/** Runs {@code /rdp tend} on the field for {@code worker}, with the chest when {@code chest} is set. */
	private static void tend(GameTestHelper helper, WorkerEntity worker, boolean chest) {
		String command = "rdp tend " + WorkerCommandGameTest.coords(helper.absolutePos(FIELD_FROM)) + " "
				+ WorkerCommandGameTest.coords(helper.absolutePos(FIELD_TO))
				+ (chest ? " " + WorkerCommandGameTest.coords(helper.absolutePos(CHEST)) : "") + " " + worker.getStringUUID();
		RecordingCommandSource rec = new RecordingCommandSource();
		int result = WorkerCommandGameTest.run(helper, WorkerCommandGameTest.sourceAt(helper, rec, worker), command);
		helper.assertTrue(result == 1, "expected result 1, got " + result);
	}

	private static void assertTending(GameTestHelper helper, WorkerEntity worker) {
		helper.assertTrue(worker.tasks().current().orElse(null) instanceof TendFarmTask,
				"not tending: " + worker.tasks().current());
	}

	private static void assertOnlyTending(GameTestHelper helper, WorkerEntity worker) {
		List<Task<WorkerEntity>> queue = worker.tasks().snapshot();
		helper.assertTrue(queue.size() == 1 && queue.get(0) instanceof TendFarmTask, "expected only tend_farm, got " + queue);
	}

	/** Asserts the plant on {@code ground} is wheat that is not ripe: planted, or harvested and replanted. */
	private static void assertReplanted(GameTestHelper helper, BlockPos ground) {
		helper.assertBlockPresent(Blocks.FARMLAND, ground);
		helper.assertBlockPresent(Blocks.WHEAT, ground.above());
		helper.assertTrue(helper.getBlockState(ground.above()).getValue(CropBlock.AGE) < CropBlock.MAX_AGE,
				"wheat at " + ground.above() + " is still ripe");
	}

	private static void fill(Container container, Item item) {
		for (int i = 0; i < container.getContainerSize(); i++) {
			container.setItem(i, new ItemStack(item, 64));
		}
	}

	private static int count(Container container, Item item) {
		int total = 0;
		for (int i = 0; i < container.getContainerSize(); i++) {
			if (container.getItem(i).is(item)) {
				total += container.getItem(i).getCount();
			}
		}
		return total;
	}

	/** How many system chat lines sent to {@code joined} so far carry tend notice {@code key}; consumes them all. */
	private static int notices(RtsModeGameTest.JoinedPlayer joined, String key) {
		joined.channel().flushOutbound();
		int found = 0;
		Object message;
		while ((message = joined.channel().readOutbound()) != null) {
			if (message instanceof ClientboundSystemChatPacket chat
					&& RtsOrderGameTest.keys(chat.content()).contains(TendFarmTask.NOTICE_PREFIX + key)) {
				found++;
			}
		}
		return found;
	}

	/** Re-tills dried dirt, plants empty farmland and harvests and replants ripe wheat, then keeps going. */
	@GameTest(maxTicks = 600)
	public void oneRoundHarvestsAndReplants(GameTestHelper helper) {
		layField(helper, wheat(CropBlock.MAX_AGE), null, null);
		List<BlockPos> field = field();
		helper.setBlock(field.get(2), Blocks.DIRT);
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.IRON_HOE));
		worker.insertOrDrop(new ItemStack(Items.WHEAT_SEEDS, 3));
		tend(helper, worker, false);

		helper.succeedWhen(() -> {
			for (BlockPos ground : field) {
				assertReplanted(helper, ground);
			}
			helper.assertTrue(worker.countItem(Items.WHEAT) == 1, "expected 1 wheat, got " + worker.countItem(Items.WHEAT));
			helper.assertItemEntityNotPresent(Items.WHEAT);
			helper.assertTrue(outcomes.isEmpty(), "tending ended: " + outcomes);
			assertOnlyTending(helper, worker);
		});
	}

	/** No empty slot: the stone goes into the chest, the hoe and the seed stack stay, and the wheat is harvested. */
	@GameTest(maxTicks = 800)
	public void fullInventoryDepositsKeepingSeedsAndHoe(GameTestHelper helper) {
		BlockState ripe = wheat(CropBlock.MAX_AGE);
		layField(helper, ripe, ripe, ripe);
		helper.setBlock(CHEST, Blocks.CHEST);
		ChestBlockEntity chest = helper.getBlockEntity(CHEST, ChestBlockEntity.class);
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.getInventory().setItem(0, new ItemStack(Items.IRON_HOE));
		worker.getInventory().setItem(1, new ItemStack(Items.WHEAT_SEEDS, 16));
		for (int i = 2; i < WorkerEntity.INVENTORY_SIZE; i++) {
			worker.getInventory().setItem(i, new ItemStack(Items.STONE, 64));
		}
		int stone = (WorkerEntity.INVENTORY_SIZE - 2) * 64;
		tend(helper, worker, true);

		helper.succeedWhen(() -> {
			helper.assertTrue(count(chest, Items.STONE) == stone, "expected " + stone + " stone in the chest, got " + count(chest, Items.STONE));
			helper.assertTrue(worker.countItem(Items.STONE) == 0, "stone left in the inventory");
			helper.assertTrue(count(chest, Items.IRON_HOE) == 0 && worker.countItem(Items.IRON_HOE) == 1, "the hoe was deposited");
			helper.assertTrue(count(chest, Items.WHEAT_SEEDS) == 0, "the seeds were deposited");
			// One seed per replant may come from the stack instead of the drops.
			helper.assertTrue(worker.countItem(Items.WHEAT_SEEDS) >= 13, "seeds lost: " + worker.countItem(Items.WHEAT_SEEDS));
			for (BlockPos ground : field()) {
				assertReplanted(helper, ground);
			}
			helper.assertTrue(worker.countItem(Items.WHEAT) == 3, "expected 3 wheat, got " + worker.countItem(Items.WHEAT));
			helper.assertTrue(outcomes.isEmpty(), "tending ended: " + outcomes);
			assertTending(helper, worker);
		});
	}

	/** Nothing ripe, nothing to plant or till: the worker stands still, keeps its items and never paths. */
	@GameTest(maxTicks = 600)
	public void waitsWithoutEffectWhileNothingIsRipe(GameTestHelper helper) {
		BlockState young = wheat(0);
		layField(helper, young, young, young);
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.IRON_HOE));
		worker.insertOrDrop(new ItemStack(Items.WHEAT_SEEDS, 5));
		tend(helper, worker, false);
		Vec3[] start = new Vec3[1];

		helper.startSequence()
				.thenWaitUntil(() -> assertTending(helper, worker))
				.thenExecute(() -> start[0] = worker.position())
				.thenIdle(THREE_ROUNDS)
				.thenExecute(() -> {
					helper.assertTrue(worker.position().distanceTo(start[0]) < 0.5, "the worker moved: " + start[0] + " -> " + worker.position());
					helper.assertTrue(worker.getNavigation().isDone(), "the worker is pathing while waiting");
					helper.assertTrue(worker.countItem(Items.WHEAT_SEEDS) == 5, "seeds used: " + worker.countItem(Items.WHEAT_SEEDS));
					helper.assertTrue(worker.getInventory().getItem(0).getDamageValue() == 0, "the hoe was used");
					for (BlockPos ground : field()) {
						helper.assertBlockPresent(Blocks.FARMLAND, ground);
						helper.assertBlockPresent(Blocks.WHEAT, ground.above());
					}
					helper.assertTrue(outcomes.isEmpty(), "tending ended: " + outcomes);
					assertOnlyTending(helper, worker);
				})
				.thenSucceed();
	}

	/** Saved and loaded before it ran: the copy tends the same field into the same chest. */
	@GameTest(maxTicks = 600)
	public void resumesAfterReload(GameTestHelper helper) {
		layField(helper, wheat(CropBlock.MAX_AGE), null, null);
		helper.setBlock(CHEST, Blocks.CHEST);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		tend(helper, worker, true);

		WorkerEntity loaded = WorkerTaskGameTest.roundTrip(helper, worker);
		// The copy has the same UUID, so the original must go before the copy can be added.
		worker.discard();
		helper.assertTrue(helper.getLevel().addFreshEntity(loaded), "could not add the reloaded worker");
		List<Task<WorkerEntity>> queue = loaded.tasks().snapshot();
		helper.assertTrue(queue.size() == 1 && queue.get(0) instanceof TendFarmTask t
				&& t.from().equals(helper.absolutePos(FIELD_FROM)) && t.to().equals(helper.absolutePos(FIELD_TO))
				&& t.container().equals(Optional.of(helper.absolutePos(CHEST))), "expected the same tend_farm, got " + queue);
		List<TaskOutcome> outcomes = new ArrayList<>();
		loaded.tasks().addListener((task, outcome) -> outcomes.add(outcome));

		helper.succeedWhen(() -> {
			assertReplanted(helper, field().get(0));
			helper.assertTrue(loaded.countItem(Items.WHEAT) == 1, "expected 1 wheat, got " + loaded.countItem(Items.WHEAT));
			helper.assertTrue(outcomes.isEmpty(), "tending ended: " + outcomes);
			assertOnlyTending(helper, loaded);
		});
	}

	/** The RTS Stop order ends tending for good: cancelled once, not reported, and it does not come back. */
	@GameTest(maxTicks = 400)
	public void stopEndsIt(GameTestHelper helper) {
		BlockState young = wheat(0);
		layField(helper, young, young, young);
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		ServerPlayer player = rtsPlayer(helper);
		tend(helper, worker, false);

		helper.startSequence()
				.thenWaitUntil(() -> assertTending(helper, worker))
				.thenExecute(() -> {
					assertResult(helper, RtsOrders.handleStop(player, new RtsStopPayload(List.of(worker.getId())), LEVEL_2),
							true, 1, "stopped");
					helper.assertTrue(worker.tasks().isIdle(), "worker still has tasks: " + worker.tasks().snapshot());
					helper.assertTrue(outcomes.equals(List.of(TaskOutcome.cancelled(Cause.CLEARED))),
							"expected one Cancelled(CLEARED), got " + outcomes);
					helper.assertFalse(WorkerEntity.notifiesCommanders(outcomes.get(0)), "the stop would be reported");
				})
				.thenIdle(TendFarmTask.WAIT_TICKS + 10)
				.thenExecute(() -> {
					helper.assertTrue(worker.tasks().isIdle(), "tending came back: " + worker.tasks().snapshot());
					helper.assertTrue(outcomes.size() == 1, "more outcomes: " + outcomes);
				})
				.thenExecute(() -> RtsModeGameTest.leave(player))
				.thenSucceed();
	}

	/** {@code /rdp clear} ends tending like Stop. */
	@GameTest(maxTicks = 400)
	public void clearEndsIt(GameTestHelper helper) {
		BlockState young = wheat(0);
		layField(helper, young, young, young);
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		tend(helper, worker, false);

		helper.startSequence()
				.thenWaitUntil(() -> assertTending(helper, worker))
				.thenExecute(() -> {
					WorkerCommandGameTest.run(helper, WorkerCommandGameTest.sourceAt(helper, new RecordingCommandSource(), worker),
							"rdp clear " + worker.getStringUUID());
					helper.assertTrue(worker.tasks().isIdle(), "worker still has tasks: " + worker.tasks().snapshot());
					helper.assertTrue(outcomes.equals(List.of(TaskOutcome.cancelled(Cause.CLEARED))),
							"expected one Cancelled(CLEARED), got " + outcomes);
				})
				.thenIdle(TendFarmTask.WAIT_TICKS + 10)
				.thenExecute(() -> helper.assertTrue(worker.tasks().isIdle(), "tending came back: " + worker.tasks().snapshot()))
				.thenSucceed();
	}

	/**
	 * A Shift-appended move while tending runs first; tending is suspended, not ended, and resumes after it with
	 * the same task.
	 */
	@GameTest(maxTicks = 600)
	public void shiftAppendRunsFirstThenTendingResumes(GameTestHelper helper) {
		BlockState young = wheat(0);
		layField(helper, young, young, young);
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		ServerPlayer player = rtsPlayer(helper);
		tend(helper, worker, false);
		Object[] tending = new Object[1];
		BlockPos target = helper.absolutePos(MOVE_CLICKED.above());

		helper.startSequence()
				.thenWaitUntil(() -> assertTending(helper, worker))
				.thenExecute(() -> {
					tending[0] = worker.tasks().current().orElseThrow();
					RtsOrderPayload move = new RtsOrderPayload(List.of(worker.getId()), RtsOrderKind.MOVE,
							helper.absolutePos(MOVE_CLICKED), Direction.UP, Optional.empty(), true);
					RtsOrders.Result result = RtsOrders.handle(player, move, LEVEL_2);
					helper.assertTrue(result.ok() && result.applied() == 1, "move not appended: " + result);
				})
				.thenWaitUntil(() -> helper.assertTrue(!outcomes.isEmpty(), "the appended move has not finished"))
				.thenExecute(() -> {
					helper.assertTrue(outcomes.size() == 1 && outcomes.get(0) instanceof TaskOutcome.Succeeded,
							"expected only the move to succeed, got " + outcomes);
					helper.assertTrue(worker.blockPosition().closerThan(target, 2.0), "the move did not run: " + worker.blockPosition());
				})
				.thenWaitUntil(() -> helper.assertTrue(worker.tasks().current().orElse(null) == tending[0],
						"tending did not resume: " + worker.tasks().current()))
				.thenExecute(() -> {
					assertOnlyTending(helper, worker);
					helper.assertTrue(outcomes.size() == 1, "tending was ended: " + outcomes);
				})
				.thenExecute(() -> RtsModeGameTest.leave(player))
				.thenSucceed();
	}

	/** No chest and no room: ripe wheat is left standing and the commander hears about it once in three rounds. */
	@GameTest(maxTicks = 600)
	public void missingContainerIsReportedOnce(GameTestHelper helper) {
		layField(helper, wheat(CropBlock.MAX_AGE), null, null);
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		fill(worker.getInventory(), Items.STONE);
		RtsModeGameTest.JoinedPlayer joined = RtsModeGameTest.joinPlayerWithChannel(helper);
		worker.addCommander(joined.player().getUUID());
		tend(helper, worker, false);

		helper.startSequence()
				.thenWaitUntil(() -> assertTending(helper, worker))
				.thenIdle(THREE_ROUNDS)
				.thenExecute(() -> {
					int reported = notices(joined, "no_container");
					helper.assertTrue(reported == 1, "expected one no_container notice, got " + reported);
					helper.assertBlockProperty(field().get(0).above(), CropBlock.AGE, CropBlock.MAX_AGE);
					helper.assertTrue(worker.countItem(Items.STONE) == WorkerEntity.INVENTORY_SIZE * 64, "inventory changed");
					helper.assertTrue(outcomes.isEmpty(), "tending ended: " + outcomes);
				})
				.thenExecute(() -> RtsModeGameTest.leave(joined.player()))
				.thenSucceed();
	}

	/** A full chest and no room: reported once in three rounds, and nothing is harvested or moved. */
	@GameTest(maxTicks = 600)
	public void fullContainerIsReportedOnce(GameTestHelper helper) {
		layField(helper, wheat(CropBlock.MAX_AGE), null, null);
		helper.setBlock(CHEST, Blocks.CHEST);
		ChestBlockEntity chest = helper.getBlockEntity(CHEST, ChestBlockEntity.class);
		fill(chest, Items.DIRT);
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		fill(worker.getInventory(), Items.STONE);
		RtsModeGameTest.JoinedPlayer joined = RtsModeGameTest.joinPlayerWithChannel(helper);
		worker.addCommander(joined.player().getUUID());
		tend(helper, worker, true);

		helper.startSequence()
				.thenWaitUntil(() -> assertTending(helper, worker))
				.thenIdle(THREE_ROUNDS)
				.thenExecute(() -> {
					int reported = notices(joined, "container_full");
					helper.assertTrue(reported == 1, "expected one container_full notice, got " + reported);
					helper.assertBlockProperty(field().get(0).above(), CropBlock.AGE, CropBlock.MAX_AGE);
					helper.assertTrue(count(chest, Items.STONE) == 0, "stone went into the full chest");
					helper.assertTrue(worker.countItem(Items.STONE) == WorkerEntity.INVENTORY_SIZE * 64, "inventory changed");
					helper.assertTrue(outcomes.isEmpty(), "tending ended: " + outcomes);
				})
				.thenExecute(() -> RtsModeGameTest.leave(joined.player()))
				.thenSucceed();
	}
}
