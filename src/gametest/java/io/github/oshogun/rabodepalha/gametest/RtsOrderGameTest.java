package io.github.oshogun.rabodepalha.gametest;

import io.github.oshogun.rabodepalha.RaboDePalha;
import io.github.oshogun.rabodepalha.network.RtsBuildPayload;
import io.github.oshogun.rabodepalha.network.RtsOrderPayload;
import io.github.oshogun.rabodepalha.registry.ModEntities;
import io.github.oshogun.rabodepalha.rts.BlueprintFootprint;
import io.github.oshogun.rabodepalha.rts.RtsMode;
import io.github.oshogun.rabodepalha.rts.RtsOrderKind;
import io.github.oshogun.rabodepalha.rts.RtsOrders;
import io.github.oshogun.rabodepalha.rts.RtsTaskKind;
import io.github.oshogun.rabodepalha.rts.WorkerSnapshot;
import io.github.oshogun.rabodepalha.task.Task;
import io.github.oshogun.rabodepalha.task.TaskOutcome;
import io.github.oshogun.rabodepalha.task.TaskOutcome.Cancelled.Cause;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;
import io.github.oshogun.rabodepalha.worker.task.MineTask;
import io.github.oshogun.rabodepalha.worker.task.MoveToTask;
import io.github.oshogun.rabodepalha.worker.task.PlaceTask;
import io.github.oshogun.rabodepalha.worker.task.TaskSummary;
import io.github.oshogun.rabodepalha.worker.task.UseTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Server side of RTS orders: {@link RtsOrders#handle(ServerPlayer, RtsOrderPayload, PermissionSet)} with
 * mock players from {@link RtsModeGameTest#joinPlayer}. The server has no mod language file, so messages
 * are checked by their translation keys.
 */
public class RtsOrderGameTest {
	static final PermissionSet LEVEL_0 = LevelBasedPermissionSet.forLevel(PermissionLevel.ALL);
	static final PermissionSet LEVEL_1 = LevelBasedPermissionSet.MODERATOR;
	static final PermissionSet LEVEL_2 = LevelBasedPermissionSet.GAMEMASTER;
	private static final BlockPos WORKER_START = new BlockPos(2, 1, 2);
	/** A floor block; MOVE targets the air above it. */
	private static final BlockPos CLICKED = new BlockPos(5, 0, 5);

	static void layFloor(GameTestHelper helper) {
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
			}
		}
	}

	/** A non-op mock player already in RTS mode. */
	static ServerPlayer rtsPlayer(GameTestHelper helper) {
		ServerPlayer player = RtsModeGameTest.joinPlayer(helper);
		helper.assertTrue(RtsMode.enter(player), "enter refused");
		return player;
	}

	private static RtsOrderPayload order(GameTestHelper helper, RtsOrderKind kind, BlockPos clicked, boolean append,
			WorkerEntity... workers) {
		List<Integer> ids = new ArrayList<>();
		for (WorkerEntity worker : workers) {
			ids.add(worker.getId());
		}
		return new RtsOrderPayload(ids, kind, helper.absolutePos(clicked), Direction.UP, Optional.empty(), append);
	}

	/** Every translation key in {@code message}, its siblings and its component arguments. */
	static List<String> keys(Component message) {
		List<String> keys = new ArrayList<>();
		collectKeys(message, keys);
		return keys;
	}

	private static void collectKeys(Component message, List<String> keys) {
		if (message.getContents() instanceof TranslatableContents translatable) {
			keys.add(translatable.getKey());
			for (Object arg : translatable.getArgs()) {
				if (arg instanceof Component component) {
					collectKeys(component, keys);
				}
			}
		}
		for (Component sibling : message.getSiblings()) {
			collectKeys(sibling, keys);
		}
	}

	static void assertResult(GameTestHelper helper, RtsOrders.Result result, boolean ok, int applied, String key) {
		helper.assertTrue(result.ok() == ok, "expected ok=" + ok + ", got " + result);
		helper.assertTrue(result.applied() == applied, "expected " + applied + " applied, got " + result);
		helper.assertTrue(keys(result.message()).contains("rabodepalha.rts.order." + key),
				"message lacks " + key + ": " + keys(result.message()));
	}

	static void assertUntouched(GameTestHelper helper, WorkerEntity worker) {
		helper.assertTrue(worker.tasks().isIdle(), "worker got tasks: " + worker.tasks().snapshot());
		helper.assertTrue(worker.commanders().isEmpty(), "worker got commanders: " + worker.commanders());
	}

	private static boolean isMove(Task<WorkerEntity> task, BlockPos pos) {
		return task instanceof MoveToTask m && m.pos().equals(pos);
	}

	/** Asserts that {@code worker}'s whole queue is one move_to {@code pos}. */
	private static void assertOnlyMove(GameTestHelper helper, WorkerEntity worker, BlockPos pos) {
		List<Task<WorkerEntity>> queue = worker.tasks().snapshot();
		helper.assertTrue(queue.size() == 1 && isMove(queue.get(0), pos), "expected only a move_to " + pos + ", got " + queue);
	}

	static void finish(GameTestHelper helper, ServerPlayer player) {
		RtsModeGameTest.leave(player);
		helper.succeed();
	}

	@GameTest
	public void levelZeroAndOneRejected(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		ServerPlayer player = rtsPlayer(helper);
		RtsOrderPayload payload = order(helper, RtsOrderKind.MOVE, CLICKED, false, worker);

		assertResult(helper, RtsOrders.handle(player, payload, LEVEL_0), false, 0, "no_permission");
		assertResult(helper, RtsOrders.handle(player, payload, LEVEL_1), false, 0, "no_permission");
		// The mock player is not an op, so its own permissions are rejected as well.
		assertResult(helper, RtsOrders.handle(player, payload), false, 0, "no_permission");
		assertUntouched(helper, worker);
		finish(helper, player);
	}

	@GameTest
	public void notInRtsRejected(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		ServerPlayer player = RtsModeGameTest.joinPlayer(helper);

		RtsOrders.Result result = RtsOrders.handle(player, order(helper, RtsOrderKind.MOVE, CLICKED, false, worker), LEVEL_2);

		assertResult(helper, result, false, 0, "not_rts");
		assertUntouched(helper, worker);
		finish(helper, player);
	}

	@GameTest
	public void emptyOrderRejected(GameTestHelper helper) {
		layFloor(helper);
		ServerPlayer player = rtsPlayer(helper);

		RtsOrders.Result result = RtsOrders.handle(player, order(helper, RtsOrderKind.MOVE, CLICKED, false), LEVEL_2);

		assertResult(helper, result, false, 0, "no_workers");
		finish(helper, player);
	}

	@GameTest
	public void unknownWorkerRejected(GameTestHelper helper) {
		layFloor(helper);
		Pig pig = helper.spawn(EntityTypes.PIG, WORKER_START);
		ServerPlayer player = rtsPlayer(helper);
		RtsOrderPayload payload = new RtsOrderPayload(List.of(Integer.MAX_VALUE, pig.getId()), RtsOrderKind.MOVE,
				helper.absolutePos(CLICKED), Direction.UP, Optional.empty(), false);

		RtsOrders.Result result = RtsOrders.handle(player, payload, LEVEL_2);

		assertResult(helper, result, false, 0, "unknown_worker");
		helper.assertFalse(keys(result.message()).contains("rabodepalha.rts.order.too_far"), "unexpected too_far: " + result);
		finish(helper, player);
	}

	@GameTest
	public void mixedValidAndUnknown(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		ServerPlayer player = rtsPlayer(helper);
		RtsOrderPayload payload = new RtsOrderPayload(List.of(worker.getId(), Integer.MAX_VALUE), RtsOrderKind.MOVE,
				helper.absolutePos(CLICKED), Direction.UP, Optional.empty(), false);

		RtsOrders.Result result = RtsOrders.handle(player, payload, LEVEL_2);

		// Applied to the valid worker, and the message says one was rejected.
		assertResult(helper, result, false, 1, "partial");
		helper.assertTrue(result.message().getContents() instanceof TranslatableContents t && t.getArgs().length == 4
				&& t.getArgs()[1].equals(1) && t.getArgs()[2].equals(1) && t.getArgs()[3].equals(0),
				"expected 1 rejected (1 unknown, 0 too far): " + result.message());
		assertOnlyMove(helper, worker, helper.absolutePos(CLICKED.above()));
		finish(helper, player);
	}

	@GameTest
	public void targetTooFarRejected(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		ServerPlayer player = rtsPlayer(helper);
		BlockPos far = CLICKED.offset((int) RtsOrders.MAX_TARGET_DISTANCE + 16, 0, 0);

		RtsOrders.Result result = RtsOrders.handle(player, order(helper, RtsOrderKind.MOVE, far, false, worker), LEVEL_2);

		assertResult(helper, result, false, 0, "target_too_far");
		assertUntouched(helper, worker);
		finish(helper, player);
	}

	/** Uses the overload with explicit limits, so the far worker stays inside the loaded test area. */
	@GameTest
	public void workerTooFarRejected(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity near = helper.spawn(ModEntities.WORKER, new BlockPos(5, 1, 4));
		WorkerEntity far = helper.spawn(ModEntities.WORKER, new BlockPos(0, 1, 0));
		ServerPlayer player = rtsPlayer(helper);
		RtsOrderPayload payload = order(helper, RtsOrderKind.MOVE, CLICKED, false, near, far);

		RtsOrders.Result result = RtsOrders.handle(player, payload, LEVEL_2, RtsOrders.MAX_TARGET_DISTANCE, 3.0);

		assertResult(helper, result, false, 1, "partial");
		helper.assertTrue(keys(result.message()).contains("rabodepalha.rts.order.replaced"), "no applied part: " + result);
		assertOnlyMove(helper, near, helper.absolutePos(CLICKED.above()));
		assertUntouched(helper, far);

		RtsOrders.Result none = RtsOrders.handle(player, order(helper, RtsOrderKind.MOVE, CLICKED, false, far), LEVEL_2,
				RtsOrders.MAX_TARGET_DISTANCE, 3.0);
		assertResult(helper, none, false, 0, "too_far");
		assertUntouched(helper, far);
		finish(helper, player);
	}

	@GameTest
	public void moveEnqueuesAbove(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		ServerPlayer player = rtsPlayer(helper);

		RtsOrders.Result result = RtsOrders.handle(player, order(helper, RtsOrderKind.MOVE, CLICKED, false, worker), LEVEL_2);

		assertResult(helper, result, true, 1, "replaced");
		assertOnlyMove(helper, worker, helper.absolutePos(CLICKED.above()));
		finish(helper, player);
	}

	@GameTest
	public void mineEnqueues(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		ServerPlayer player = rtsPlayer(helper);

		RtsOrders.Result air = RtsOrders.handle(player, order(helper, RtsOrderKind.MINE, CLICKED.above(), false, worker), LEVEL_2);
		assertResult(helper, air, false, 0, "air");
		assertUntouched(helper, worker);

		RtsOrders.Result result = RtsOrders.handle(player, order(helper, RtsOrderKind.MINE, CLICKED, false, worker), LEVEL_2);
		assertResult(helper, result, true, 1, "replaced");
		List<Task<WorkerEntity>> queue = worker.tasks().snapshot();
		BlockPos target = helper.absolutePos(CLICKED);
		helper.assertTrue(queue.size() == 1 && queue.get(0) instanceof MineTask m && m.pos().equals(target),
				"expected only a mine " + target + ", got " + queue);
		finish(helper, player);
	}

	@GameTest
	public void useWithoutPermissionOrOutsideRtsRejected(GameTestHelper helper) {
		layFloor(helper);
		helper.setBlock(CLICKED.above(), Blocks.LEVER.defaultBlockState()
				.setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR));
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		ServerPlayer player = RtsModeGameTest.joinPlayer(helper);
		RtsOrderPayload payload = order(helper, RtsOrderKind.USE, CLICKED.above(), false, worker);

		assertResult(helper, RtsOrders.handle(player, payload, LEVEL_0), false, 0, "no_permission");
		assertResult(helper, RtsOrders.handle(player, payload, LEVEL_2), false, 0, "not_rts");
		assertUntouched(helper, worker);
		finish(helper, player);
	}

	@GameTest
	public void useEnqueuesOnlyOnUsableBlocks(GameTestHelper helper) {
		layFloor(helper);
		BlockPos lever = CLICKED.above();
		helper.setBlock(lever, Blocks.LEVER.defaultBlockState()
				.setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR));
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		ServerPlayer player = rtsPlayer(helper);

		RtsOrders.Result stone = RtsOrders.handle(player, order(helper, RtsOrderKind.USE, CLICKED, false, worker), LEVEL_2);
		assertResult(helper, stone, false, 0, "not_usable");
		assertUntouched(helper, worker);

		RtsOrders.Result result = RtsOrders.handle(player, order(helper, RtsOrderKind.USE, lever, false, worker), LEVEL_2);
		assertResult(helper, result, true, 1, "replaced");
		List<Task<WorkerEntity>> queue = worker.tasks().snapshot();
		BlockPos target = helper.absolutePos(lever);
		helper.assertTrue(queue.size() == 1 && queue.get(0) instanceof UseTask u && u.pos().equals(target),
				"expected only a use " + target + ", got " + queue);
		List<TaskSummary> tasks = snapshotTasks(worker);
		helper.assertTrue(tasks.size() == 1 && RtsTaskKind.of(tasks.get(0)) == RtsTaskKind.USE, "unexpected tasks " + tasks);
		finish(helper, player);
	}

	@GameTest
	public void useOnDoorUpperHalfTargetsLowerHalf(GameTestHelper helper) {
		layFloor(helper);
		BlockState door = Blocks.OAK_DOOR.defaultBlockState();
		BlockPos lower = CLICKED.above();
		helper.setBlock(lower, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
		helper.setBlock(lower.above(), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		ServerPlayer player = rtsPlayer(helper);

		RtsOrders.Result result = RtsOrders.handle(player, order(helper, RtsOrderKind.USE, lower.above(), false, worker),
				LEVEL_2);
		assertResult(helper, result, true, 1, "replaced");
		List<Task<WorkerEntity>> queue = worker.tasks().snapshot();
		BlockPos target = helper.absolutePos(lower);
		helper.assertTrue(queue.size() == 1 && queue.get(0) instanceof UseTask u && u.pos().equals(target),
				"expected only a use " + target + ", got " + queue);
		finish(helper, player);
	}

	@GameTest
	public void placeUsesItemId(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		ServerPlayer player = rtsPlayer(helper);
		// The player's own inventory no longer matters: the block comes from the picked item id.
		player.getInventory().setItem(0, new ItemStack(Items.DIRT));
		BlockPos clicked = helper.absolutePos(CLICKED);

		List<Optional<Identifier>> bads = List.of(Optional.empty(), Optional.of(RaboDePalha.id("no_such_item")),
				Optional.of(BuiltInRegistries.ITEM.getKey(Items.STICK)));
		for (Optional<Identifier> item : bads) {
			RtsOrderPayload bad = new RtsOrderPayload(List.of(worker.getId()), RtsOrderKind.PLACE, clicked, Direction.UP, item, false);
			assertResult(helper, RtsOrders.handle(player, bad, LEVEL_2), false, 0, "no_block");
		}
		assertUntouched(helper, worker);

		RtsOrderPayload payload = new RtsOrderPayload(List.of(worker.getId()), RtsOrderKind.PLACE, clicked, Direction.UP,
				Optional.of(BuiltInRegistries.ITEM.getKey(Items.STONE)), false);
		RtsOrders.Result result = RtsOrders.handle(player, payload, LEVEL_2);

		assertResult(helper, result, true, 1, "replaced");
		List<Task<WorkerEntity>> queue = worker.tasks().snapshot();
		helper.assertTrue(queue.size() == 1 && queue.get(0) instanceof PlaceTask p && p.pos().equals(clicked.above())
						&& p.state() == Blocks.STONE.defaultBlockState(),
				"expected only a place stone at " + clicked.above() + ", got " + queue);
		finish(helper, player);
	}

	@GameTest
	public void replaceVsAppend(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		ServerPlayer player = rtsPlayer(helper);
		MoveToTask first = new MoveToTask(helper.absolutePos(new BlockPos(1, 1, 6)));
		MoveToTask second = new MoveToTask(helper.absolutePos(new BlockPos(6, 1, 1)));
		BlockPos ordered = helper.absolutePos(CLICKED.above());
		worker.tasks().enqueue(first);
		worker.tasks().enqueue(second);

		RtsOrders.Result appended = RtsOrders.handle(player, order(helper, RtsOrderKind.MOVE, CLICKED, true, worker), LEVEL_2);
		assertResult(helper, appended, true, 1, "queued");
		List<Task<WorkerEntity>> queue = worker.tasks().snapshot();
		helper.assertTrue(queue.size() == 3 && queue.get(0) == first && queue.get(1) == second && isMove(queue.get(2), ordered),
				"expected the order appended, got " + queue);

		RtsOrders.Result replaced = RtsOrders.handle(player, order(helper, RtsOrderKind.MOVE, CLICKED, false, worker), LEVEL_2);
		assertResult(helper, replaced, true, 1, "replaced");
		assertOnlyMove(helper, worker, ordered);
		helper.assertTrue(worker.tasks().current().isEmpty() || isMove(worker.tasks().current().get(), ordered),
				"unexpected current task " + worker.tasks().current());
		finish(helper, player);
	}

	@GameTest
	public void commanderRemembered(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		ServerPlayer player = rtsPlayer(helper);

		RtsOrders.handle(player, order(helper, RtsOrderKind.MOVE, CLICKED, false, worker), LEVEL_2);

		helper.assertTrue(worker.commanders().contains(player.getUUID()), "player not recorded as commander: " + worker.commanders());
		finish(helper, player);
	}

	/** Chat lines about cancelled tasks among {@code lines}. */
	private static List<String> cancelledLines(List<String> lines) {
		return lines.stream().filter(line -> line.contains("cancelled")).toList();
	}

	@GameTest(maxTicks = 200)
	public void replaceOrderSupersedesRunningTaskQuietly(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		List<TaskOutcome> outcomes = new ArrayList<>();
		worker.tasks().addListener((task, outcome) -> outcomes.add(outcome));
		RtsModeGameTest.JoinedPlayer joined = RtsModeGameTest.joinPlayerWithChannel(helper);
		ServerPlayer player = joined.player();
		helper.assertTrue(RtsMode.enter(player), "enter refused");
		// Already a commander, so a "cancelled" report would reach this player.
		worker.addCommander(player.getUUID());
		MoveToTask old = new MoveToTask(helper.absolutePos(new BlockPos(6, 1, 0)));
		worker.tasks().enqueue(old);
		BlockPos ordered = helper.absolutePos(CLICKED.above());

		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(worker.tasks().current().orElse(null) == old && outcomes.isEmpty(),
						"old move not running yet: " + worker.tasks().current() + ", " + outcomes))
				.thenExecute(() -> {
					joined.drainSystemChat();
					RtsOrders.Result result = RtsOrders.handle(player, order(helper, RtsOrderKind.MOVE, CLICKED, false, worker), LEVEL_2);
					assertResult(helper, result, true, 1, "replaced");
					helper.assertTrue(outcomes.equals(List.of(TaskOutcome.cancelled(Cause.SUPERSEDED))),
							"expected one Cancelled(SUPERSEDED), got " + outcomes);
					helper.assertFalse(WorkerEntity.notifiesCommanders(outcomes.get(0)), "superseded task would be reported");
					assertOnlyMove(helper, worker, ordered);
					List<String> cancelled = cancelledLines(joined.drainSystemChat());
					helper.assertTrue(cancelled.isEmpty(), "commander was told about the replace: " + cancelled);
				})
				.thenExecute(() -> RtsModeGameTest.leave(player))
				.thenSucceed();
	}

	@GameTest(maxTicks = 200)
	public void removedWorkerReportsCancelledToCommander(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		List<TaskOutcome> outcomes = new ArrayList<>();
		worker.tasks().addListener((task, outcome) -> outcomes.add(outcome));
		RtsModeGameTest.JoinedPlayer joined = RtsModeGameTest.joinPlayerWithChannel(helper);
		ServerPlayer player = joined.player();
		helper.assertTrue(RtsMode.enter(player), "enter refused");
		assertResult(helper, RtsOrders.handle(player, order(helper, RtsOrderKind.MOVE, CLICKED, false, worker), LEVEL_2),
				true, 1, "replaced");

		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(worker.tasks().current().isPresent() && outcomes.isEmpty(),
						"move not running yet: " + worker.tasks().current() + ", " + outcomes))
				.thenExecute(() -> {
					joined.drainSystemChat();
					worker.discard();
					helper.assertTrue(outcomes.equals(List.of(TaskOutcome.cancelled(Cause.REMOVED))),
							"expected one Cancelled(REMOVED), got " + outcomes);
					List<String> cancelled = cancelledLines(joined.drainSystemChat());
					helper.assertTrue(cancelled.size() == 1 && cancelled.get(0).endsWith("-> cancelled (worker removed)"),
							"expected one removal report, got " + cancelled);
				})
				.thenExecute(() -> RtsModeGameTest.leave(player))
				.thenSucceed();
	}

	/** The worker's current task, then its queue, as the snapshot the RTS client draws markers from. */
	private static List<TaskSummary> snapshotTasks(WorkerEntity worker) {
		WorkerSnapshot snapshot = WorkerSnapshot.of(worker);
		List<TaskSummary> tasks = new ArrayList<>();
		snapshot.current().ifPresent(tasks::add);
		snapshot.queued().forEach(queued -> tasks.add(queued.summary()));
		return tasks;
	}

	@GameTest(maxTicks = 400)
	public void queuedOrdersSnapshotCarriesTargets(GameTestHelper helper) {
		layFloor(helper);
		BlockPos mined = new BlockPos(4, 1, 4);
		helper.setBlock(mined, Blocks.STONE);
		Identifier blueprint = WorkerTaskGameTest.blueprint(helper, "rts_order_markers", Map.of(
				new BlockPos(0, 0, 0), Blocks.COBBLESTONE.defaultBlockState(),
				new BlockPos(1, 0, 2), Blocks.COBBLESTONE.defaultBlockState()));
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		worker.insertOrDrop(new ItemStack(Items.WOODEN_PICKAXE));
		ServerPlayer player = rtsPlayer(helper);
		BlockPos minePos = helper.absolutePos(mined);
		BlockPos placePos = helper.absolutePos(CLICKED.above());
		BlockPos origin = helper.absolutePos(new BlockPos(5, 1, 2));

		// Shift-queued, the way the command bar sends them.
		assertResult(helper, RtsOrders.handle(player, order(helper, RtsOrderKind.MINE, mined, true, worker), LEVEL_2),
				true, 1, "queued");
		RtsOrderPayload place = new RtsOrderPayload(List.of(worker.getId()), RtsOrderKind.PLACE, helper.absolutePos(CLICKED),
				Direction.UP, Optional.of(BuiltInRegistries.ITEM.getKey(Items.STONE)), true);
		assertResult(helper, RtsOrders.handle(player, place, LEVEL_2), true, 1, "queued");
		RtsBuildPayload build = new RtsBuildPayload(List.of(worker.getId()), origin, blueprint, Rotation.CLOCKWISE_90, true);
		assertResult(helper, RtsOrders.handleBuild(player, build, LEVEL_2, RtsOrders.MAX_TARGET_DISTANCE,
				RtsOrders.MAX_WORKER_DISTANCE), true, 1, "build_queued");

		List<TaskSummary> tasks = snapshotTasks(worker);
		helper.assertTrue(tasks.stream().map(RtsTaskKind::of).toList()
				.equals(List.of(RtsTaskKind.MINE, RtsTaskKind.PLACE, RtsTaskKind.BUILD)), "unexpected tasks " + tasks);
		helper.assertTrue(tasks.get(0).targets().equals(List.of(minePos)), "mine targets " + tasks.get(0).targets());
		helper.assertTrue(tasks.get(1).targets().equals(List.of(placePos)), "place targets " + tasks.get(1).targets());
		BoundingBox footprint = BlueprintFootprint.box(origin, new Vec3i(2, 1, 3), Rotation.CLOCKWISE_90);
		List<BlockPos> expected = List.of(origin, new BlockPos(footprint.minX(), footprint.minY(), footprint.minZ()),
				new BlockPos(footprint.maxX(), footprint.maxY(), footprint.maxZ()));
		helper.assertTrue(tasks.get(2).targets().equals(expected),
				"build targets " + tasks.get(2).targets() + ", expected " + expected);

		helper.startSequence()
				.thenWaitUntil(() -> helper.assertBlockPresent(Blocks.AIR, mined))
				.thenWaitUntil(() -> {
					List<TaskSummary> after = snapshotTasks(worker);
					helper.assertTrue(after.stream().noneMatch(task -> RtsTaskKind.of(task) == RtsTaskKind.MINE),
							"finished mine still in the snapshot: " + after);
				})
				.thenExecute(() -> RtsModeGameTest.leave(player))
				.thenSucceed();
	}
}
