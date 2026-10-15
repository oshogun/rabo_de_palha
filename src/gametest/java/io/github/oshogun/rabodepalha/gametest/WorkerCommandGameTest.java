package io.github.oshogun.rabodepalha.gametest;

import io.github.oshogun.rabodepalha.command.WorkerCommands;
import io.github.oshogun.rabodepalha.registry.ModEntities;
import io.github.oshogun.rabodepalha.task.Task;
import io.github.oshogun.rabodepalha.task.TaskOutcome;
import io.github.oshogun.rabodepalha.task.TaskOutcome.Cancelled.Cause;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;
import io.github.oshogun.rabodepalha.worker.task.BuildTask;
import io.github.oshogun.rabodepalha.worker.task.DepositTask;
import io.github.oshogun.rabodepalha.worker.task.DigAreaTask;
import io.github.oshogun.rabodepalha.worker.task.MineTask;
import io.github.oshogun.rabodepalha.worker.task.MoveToTask;
import io.github.oshogun.rabodepalha.worker.task.PlaceTask;
import io.github.oshogun.rabodepalha.worker.task.UseTask;
import io.github.oshogun.rabodepalha.worker.task.WithdrawTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Runs {@code /rdp} through the server's command dispatcher. Workers are selected by UUID, or the
 * source stands right next to the worker, so workers from tests running alongside are never hit.
 */
public class WorkerCommandGameTest {
	private static final PermissionSet LEVEL_0 = LevelBasedPermissionSet.forLevel(PermissionLevel.ALL);
	private static final PermissionSet LEVEL_1 = LevelBasedPermissionSet.MODERATOR;
	private static final PermissionSet LEVEL_2 = LevelBasedPermissionSet.GAMEMASTER;
	private static final BlockPos WORKER_START = new BlockPos(2, 1, 2);
	private static final BlockPos TARGET = new BlockPos(5, 1, 5);

	private static void layFloor(GameTestHelper helper) {
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
			}
		}
	}

	private static CommandSourceStack source(GameTestHelper helper, RecordingCommandSource rec, Vec3 pos, PermissionSet permissions) {
		return helper.getLevel().getServer().createCommandSourceStack()
				.withSource(rec)
				.withLevel(helper.getLevel())
				.withPosition(pos)
				.withPermission(permissions);
	}

	/** Like {@link #source}, but issued by {@code player}, so the command sees a player source. */
	private static CommandSourceStack playerSource(GameTestHelper helper, RecordingCommandSource rec, Vec3 pos,
			PermissionSet permissions, ServerPlayer player) {
		// withEntity keeps the position and permissions already set.
		return source(helper, rec, pos, permissions).withEntity(player);
	}

	/** A level-2 source standing on {@code worker}, so it is the nearest worker. */
	static CommandSourceStack sourceAt(GameTestHelper helper, RecordingCommandSource rec, WorkerEntity worker) {
		return source(helper, rec, worker.position(), LEVEL_2);
	}

	static int run(GameTestHelper helper, CommandSourceStack source, String command) {
		try {
			return helper.getLevel().getServer().getCommands().getDispatcher().execute(command, source);
		} catch (CommandSyntaxException e) {
			helper.fail("/" + command + " failed: " + e.getMessage());
			return 0;
		}
	}

	/** Runs {@code command}, fails the test if it does not throw, and returns the error's raw message. */
	private static String expectFailure(GameTestHelper helper, CommandSourceStack source, String command) {
		try {
			helper.getLevel().getServer().getCommands().getDispatcher().execute(command, source);
		} catch (CommandSyntaxException e) {
			return e.getRawMessage().getString();
		}
		helper.fail("/" + command + " did not fail");
		return "";
	}

	static String coords(BlockPos pos) {
		return pos.getX() + " " + pos.getY() + " " + pos.getZ();
	}

	private static List<WorkerEntity> workersAt(GameTestHelper helper, Vec3 pos) {
		return helper.getLevel().getEntitiesOfClass(WorkerEntity.class, AABB.ofSize(pos, 1, 1, 1), WorkerEntity::isAlive);
	}

	private static List<Task<WorkerEntity>> queueOf(WorkerEntity worker) {
		return worker.tasks().snapshot();
	}

	static void assertFeedback(GameTestHelper helper, RecordingCommandSource rec, String expected) {
		helper.assertTrue(rec.anyContains(expected), "feedback lacks \"" + expected + "\": " + rec.messages());
	}

	@GameTest
	public void spawnAtPosition(GameTestHelper helper) {
		layFloor(helper);
		BlockPos target = helper.absolutePos(new BlockPos(3, 1, 3));
		RecordingCommandSource rec = new RecordingCommandSource();
		CommandSourceStack source = source(helper, rec, helper.absoluteVec(new Vec3(1.5, 1, 1.5)), LEVEL_2);

		int result = run(helper, source, "rdp spawn " + coords(target));

		helper.assertTrue(result == 1, "expected result 1, got " + result);
		List<WorkerEntity> spawned = workersAt(helper, Vec3.atBottomCenterOf(target));
		helper.assertTrue(spawned.size() == 1, "expected one worker at the target, got " + spawned);
		assertFeedback(helper, rec, "Spawned");
		assertFeedback(helper, rec, " " + spawned.get(0).getId() + " @ " + coords(target));
		helper.succeed();
	}

	@GameTest
	public void spawnAtSourceWithoutPos(GameTestHelper helper) {
		layFloor(helper);
		Vec3 pos = Vec3.atBottomCenterOf(helper.absolutePos(WORKER_START));
		RecordingCommandSource rec = new RecordingCommandSource();

		int result = run(helper, source(helper, rec, pos, LEVEL_2), "rdp spawn");

		helper.assertTrue(result == 1, "expected result 1, got " + result);
		helper.assertTrue(workersAt(helper, pos).size() == 1, "no worker at the source position");
		assertFeedback(helper, rec, "Spawned");
		helper.succeed();
	}

	@GameTest
	public void listIncludesWorker(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		RecordingCommandSource rec = new RecordingCommandSource();

		// "list" covers the whole dimension, so workers from other tests may be counted too.
		int result = run(helper, sourceAt(helper, rec, worker), "rdp list");

		helper.assertTrue(result >= 1, "expected at least 1 worker, got " + result);
		assertFeedback(helper, rec, "worker(s):");
		assertFeedback(helper, rec, " " + worker.getId() + " @ " + coords(worker.blockPosition()) + " - idle, 0 queued, chunks not loaded");
		helper.succeed();
	}

	@GameTest
	public void gotoQueuesMoveTask(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		BlockPos target = helper.absolutePos(TARGET);
		RecordingCommandSource rec = new RecordingCommandSource();

		int result = run(helper, sourceAt(helper, rec, worker), "rdp goto " + coords(target));

		helper.assertTrue(result == 1, "expected result 1, got " + result);
		List<Task<WorkerEntity>> queue = queueOf(worker);
		helper.assertTrue(queue.size() == 1 && queue.get(0) instanceof MoveToTask m && m.pos().equals(target),
				"expected one move_to " + target + ", got " + queue);
		assertFeedback(helper, rec, "Queued move to " + target.toShortString() + " for 1 worker(s)");
		helper.succeed();
	}

	@GameTest
	public void playerOrderRecordsCommander(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		BlockPos target = helper.absolutePos(TARGET);
		// Not added to the player list, so it is never told about outcomes; only the bookkeeping is checked.
		ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayer(GameType.SURVIVAL);
		RecordingCommandSource rec = new RecordingCommandSource();
		CommandSourceStack source = playerSource(helper, rec, worker.position(), LEVEL_2, player);
		helper.assertTrue(source.getPlayer() == player, "source is not the player");

		int result = run(helper, source, "rdp goto " + coords(target) + " " + worker.getStringUUID());

		helper.assertTrue(result == 1, "expected result 1, got " + result);
		List<Task<WorkerEntity>> queue = queueOf(worker);
		helper.assertTrue(queue.size() == 1 && queue.get(0) instanceof MoveToTask m && m.pos().equals(target),
				"expected one move_to " + target + ", got " + queue);
		helper.assertTrue(worker.commanders().contains(player.getUUID()),
				"player not recorded as commander: " + worker.commanders());

		WorkerEntity loaded = WorkerTaskGameTest.roundTrip(helper, worker);
		helper.assertTrue(loaded.commanders().contains(player.getUUID()),
				"commander lost on save/load: " + loaded.commanders());
		helper.succeed();
	}

	@GameTest
	public void playerSpawnRecordsCommander(GameTestHelper helper) {
		layFloor(helper);
		BlockPos target = helper.absolutePos(new BlockPos(3, 1, 3));
		ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayer(GameType.SURVIVAL);
		RecordingCommandSource rec = new RecordingCommandSource();
		CommandSourceStack source = playerSource(helper, rec, helper.absoluteVec(new Vec3(1.5, 1, 1.5)), LEVEL_2, player);

		int result = run(helper, source, "rdp spawn " + coords(target));

		helper.assertTrue(result == 1, "expected result 1, got " + result);
		List<WorkerEntity> spawned = workersAt(helper, Vec3.atBottomCenterOf(target));
		helper.assertTrue(spawned.size() == 1, "expected one worker at the target, got " + spawned);
		helper.assertTrue(spawned.get(0).commanders().contains(player.getUUID()),
				"player not recorded as commander: " + spawned.get(0).commanders());
		assertFeedback(helper, rec, "Spawned");
		helper.succeed();
	}

	@GameTest
	public void playerClearRecordsCommander(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayer(GameType.SURVIVAL);
		RecordingCommandSource rec = new RecordingCommandSource();
		CommandSourceStack source = playerSource(helper, rec, worker.position(), LEVEL_2, player);

		int result = run(helper, source, "rdp clear " + worker.getStringUUID());

		helper.assertTrue(result == 1, "expected result 1, got " + result);
		helper.assertTrue(worker.commanders().contains(player.getUUID()),
				"player not recorded as commander: " + worker.commanders());
		assertFeedback(helper, rec, "Cleared tasks of 1 worker(s)");
		helper.succeed();
	}

	@GameTest
	public void selectorMatchingSeveralWorkersQueuesEach(GameTestHelper helper) {
		layFloor(helper);
		// A tag only this test uses, so workers of tests running alongside are never selected.
		String tag = "rdp_multi_select";
		List<WorkerEntity> workers = List.of(
				helper.spawn(ModEntities.WORKER, WORKER_START),
				helper.spawn(ModEntities.WORKER, new BlockPos(3, 1, 2)),
				helper.spawn(ModEntities.WORKER, new BlockPos(2, 1, 3)));
		workers.forEach(w -> w.addTag(tag));
		BlockPos target = helper.absolutePos(TARGET);
		RecordingCommandSource rec = new RecordingCommandSource();

		int result = run(helper, sourceAt(helper, rec, workers.get(0)),
				"rdp goto " + coords(target) + " @e[type=rabodepalha:worker,tag=" + tag + "]");

		helper.assertTrue(result == 3, "expected result 3, got " + result);
		List<Task<WorkerEntity>> tasks = new ArrayList<>();
		for (WorkerEntity worker : workers) {
			List<Task<WorkerEntity>> queue = queueOf(worker);
			helper.assertTrue(queue.size() == 1 && queue.get(0) instanceof MoveToTask m && m.pos().equals(target),
					"expected one move_to " + target + ", got " + queue);
			tasks.add(queue.get(0));
		}
		helper.assertTrue(tasks.get(0) != tasks.get(1) && tasks.get(1) != tasks.get(2) && tasks.get(0) != tasks.get(2),
				"workers share a task instance: " + tasks);
		assertFeedback(helper, rec, "Queued move to " + target.toShortString() + " for 3 worker(s)");
		helper.succeed();
	}

	@GameTest
	public void partialEnqueueFailureLeavesNoWorkerChanged(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity first = helper.spawn(ModEntities.WORKER, WORKER_START);
		WorkerEntity second = helper.spawn(ModEntities.WORKER, new BlockPos(3, 1, 2));
		BlockPos target = helper.absolutePos(TARGET);
		ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayer(GameType.SURVIVAL);
		RecordingCommandSource rec = new RecordingCommandSource();
		CommandSourceStack source = playerSource(helper, rec, first.position(), LEVEL_2, player);
		SimpleCommandExceptionType boom = new SimpleCommandExceptionType(Component.literal("boom"));
		int[] calls = {0};

		try {
			// The first task is created fine; creating the second fails.
			WorkerCommands.enqueue(source, List.of(first, second), () -> {
				if (calls[0]++ > 0) {
					throw boom.create();
				}
				return new MoveToTask(target);
			});
			helper.fail("enqueue did not throw");
		} catch (CommandSyntaxException e) {
			helper.assertTrue(e.getRawMessage().getString().equals("boom"), "unexpected error: " + e.getMessage());
		}

		helper.assertTrue(calls[0] == 2, "expected 2 factory calls, got " + calls[0]);
		helper.assertTrue(first.tasks().isIdle(), "first worker kept a task: " + queueOf(first));
		helper.assertTrue(second.tasks().isIdle(), "second worker got a task: " + queueOf(second));
		helper.assertTrue(first.commanders().isEmpty() && second.commanders().isEmpty(),
				"commander recorded: " + first.commanders() + ", " + second.commanders());
		helper.assertTrue(rec.messages().isEmpty(), "unexpected feedback: " + rec.messages());
		helper.succeed();
	}

	@GameTest
	public void mineQueuesMineTask(GameTestHelper helper) {
		layFloor(helper);
		helper.setBlock(TARGET, Blocks.STONE);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		BlockPos target = helper.absolutePos(TARGET);
		RecordingCommandSource rec = new RecordingCommandSource();

		int result = run(helper, sourceAt(helper, rec, worker), "rdp mine " + coords(target));

		helper.assertTrue(result == 1, "expected result 1, got " + result);
		List<Task<WorkerEntity>> queue = queueOf(worker);
		helper.assertTrue(queue.size() == 1 && queue.get(0) instanceof MineTask m && m.pos().equals(target),
				"expected one mine " + target + ", got " + queue);
		assertFeedback(helper, rec, "Queued mine " + target.toShortString() + " for 1 worker(s)");
		helper.succeed();
	}

	@GameTest
	public void useQueuesUseTask(GameTestHelper helper) {
		layFloor(helper);
		helper.setBlock(TARGET, Blocks.LEVER.defaultBlockState()
				.setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR));
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		BlockPos target = helper.absolutePos(TARGET);
		RecordingCommandSource rec = new RecordingCommandSource();

		int result = run(helper, sourceAt(helper, rec, worker), "rdp use " + coords(target));

		helper.assertTrue(result == 1, "expected result 1, got " + result);
		List<Task<WorkerEntity>> queue = queueOf(worker);
		helper.assertTrue(queue.size() == 1 && queue.get(0) instanceof UseTask u && u.pos().equals(target),
				"expected one use " + target + ", got " + queue);
		assertFeedback(helper, rec, "Queued use " + target.toShortString() + " for 1 worker(s)");
		helper.succeed();
	}

	@GameTest
	public void digQueuesDigAreaTask(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		BlockPos from = helper.absolutePos(TARGET.above());
		BlockPos to = helper.absolutePos(new BlockPos(3, 1, 4));
		RecordingCommandSource rec = new RecordingCommandSource();

		int result = run(helper, sourceAt(helper, rec, worker), "rdp dig " + coords(from) + " " + coords(to) + " " + worker.getStringUUID());

		helper.assertTrue(result == 1, "expected result 1, got " + result);
		List<Task<WorkerEntity>> queue = queueOf(worker);
		helper.assertTrue(queue.size() == 1 && queue.get(0) instanceof DigAreaTask d && d.from().equals(from) && d.to().equals(to),
				"expected one dig " + from + " " + to + ", got " + queue);
		assertFeedback(helper, rec, "Queued dig area " + helper.absolutePos(new BlockPos(3, 1, 4)).toShortString()
				+ " to " + helper.absolutePos(new BlockPos(5, 2, 5)).toShortString() + " for 1 worker(s)");
		helper.succeed();
	}

	@GameTest
	public void digTooLargeRejected(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		BlockPos from = helper.absolutePos(new BlockPos(0, 1, 0));
		BlockPos to = from.offset(15, 16, 15);
		RecordingCommandSource rec = new RecordingCommandSource();

		String error = expectFailure(helper, sourceAt(helper, rec, worker), "rdp dig " + coords(from) + " " + coords(to));

		helper.assertTrue(error.contains("too large"), "unexpected error: " + error);
		helper.assertTrue(worker.tasks().isIdle(), "worker got tasks: " + queueOf(worker));
		helper.succeed();
	}

	@GameTest
	public void buildQueuesBuildTask(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		Identifier id = WorkerTaskGameTest.blueprint(helper, "command_build_rotated", Map.of(BlockPos.ZERO, Blocks.COBBLESTONE.defaultBlockState()));
		BlockPos origin = helper.absolutePos(TARGET);
		RecordingCommandSource rec = new RecordingCommandSource();
		// Away from the worker: only the selector can pick it.
		CommandSourceStack source = source(helper, rec, helper.absoluteVec(new Vec3(6.5, 1, 6.5)), LEVEL_2);

		int result = run(helper, source, "rdp build " + coords(origin) + " " + id + " clockwise_90 " + worker.getStringUUID());

		helper.assertTrue(result == 1, "expected result 1, got " + result);
		List<Task<WorkerEntity>> queue = queueOf(worker);
		helper.assertTrue(queue.size() == 1 && queue.get(0) instanceof BuildTask b && b.origin().equals(origin)
						&& b.blueprint().equals(id) && b.rotation() == Rotation.CLOCKWISE_90,
				"expected one build " + id + " at " + origin + " turned clockwise_90, got " + queue);
		assertFeedback(helper, rec, "Queued build " + id + " at " + origin.toShortString() + " turned clockwise_90 for 1 worker(s)");
		helper.succeed();
	}

	@GameTest
	public void buildWithoutRotationDefaultsNone(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		Identifier id = WorkerTaskGameTest.blueprint(helper, "command_build_plain", Map.of(BlockPos.ZERO, Blocks.COBBLESTONE.defaultBlockState()));
		BlockPos origin = helper.absolutePos(TARGET);
		RecordingCommandSource rec = new RecordingCommandSource();

		int result = run(helper, sourceAt(helper, rec, worker), "rdp build " + coords(origin) + " " + id);

		helper.assertTrue(result == 1, "expected result 1, got " + result);
		List<Task<WorkerEntity>> queue = queueOf(worker);
		helper.assertTrue(queue.size() == 1 && queue.get(0) instanceof BuildTask b && b.origin().equals(origin)
						&& b.blueprint().equals(id) && b.rotation() == Rotation.NONE,
				"expected one unrotated build " + id + " at " + origin + ", got " + queue);
		assertFeedback(helper, rec, "Queued build " + id + " at " + origin.toShortString() + " for 1 worker(s)");
		helper.succeed();
	}

	@GameTest
	public void buildUnknownBlueprintRejected(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		BlockPos origin = helper.absolutePos(TARGET);
		RecordingCommandSource rec = new RecordingCommandSource();

		String error = expectFailure(helper, sourceAt(helper, rec, worker),
				"rdp build " + coords(origin) + " rabodepalha:gametest/no_such_command_blueprint");

		helper.assertTrue(error.equals("Unknown blueprint rabodepalha:gametest/no_such_command_blueprint"), "unexpected error: " + error);
		helper.assertTrue(worker.tasks().isIdle(), "worker got tasks: " + queueOf(worker));
		helper.succeed();
	}

	@GameTest
	public void placeQueuesPlaceTask(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		BlockPos target = helper.absolutePos(TARGET);
		RecordingCommandSource rec = new RecordingCommandSource();

		int result = run(helper, sourceAt(helper, rec, worker), "rdp place " + coords(target) + " minecraft:cobblestone");

		helper.assertTrue(result == 1, "expected result 1, got " + result);
		List<Task<WorkerEntity>> queue = queueOf(worker);
		helper.assertTrue(queue.size() == 1 && queue.get(0) instanceof PlaceTask p && p.pos().equals(target)
						&& p.state() == Blocks.COBBLESTONE.defaultBlockState(),
				"expected one place cobblestone at " + target + ", got " + queue);
		assertFeedback(helper, rec, "Queued place minecraft:cobblestone at " + target.toShortString() + " for 1 worker(s)");
		helper.succeed();
	}

	@GameTest
	public void placeCommandKeepsProperties(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		BlockPos target = helper.absolutePos(TARGET);
		RecordingCommandSource rec = new RecordingCommandSource();

		int result = run(helper, sourceAt(helper, rec, worker), "rdp place " + coords(target) + " minecraft:oak_stairs[facing=east]");

		helper.assertTrue(result == 1, "expected result 1, got " + result);
		List<Task<WorkerEntity>> queue = queueOf(worker);
		BlockState expected = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST);
		helper.assertTrue(queue.size() == 1 && queue.get(0) instanceof PlaceTask p && p.state() == expected,
				"expected one place " + expected + ", got " + queue);
		assertFeedback(helper, rec, "facing=east");
		helper.succeed();
	}

	@GameTest
	public void placeWithNbtRejected(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		BlockPos target = helper.absolutePos(TARGET);
		RecordingCommandSource rec = new RecordingCommandSource();

		String error = expectFailure(helper, sourceAt(helper, rec, worker), "rdp place " + coords(target) + " minecraft:chest{Items:[]}");

		helper.assertTrue(error.contains("Block entity data"), "unexpected error: " + error);
		helper.assertTrue(worker.tasks().isIdle(), "worker got tasks: " + queueOf(worker));
		helper.assertBlockPresent(Blocks.AIR, TARGET);
		helper.succeed();
	}

	@GameTest
	public void depositWithComponentsRejected(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		BlockPos target = helper.absolutePos(TARGET);
		RecordingCommandSource rec = new RecordingCommandSource();
		CommandSourceStack source = sourceAt(helper, rec, worker);

		String error = expectFailure(helper, source, "rdp deposit " + coords(target) + " minecraft:dirt[custom_name=\"x\"]");
		String withSelector = expectFailure(helper, source,
				"rdp deposit " + coords(target) + " minecraft:dirt[custom_name=\"x\"] " + worker.getStringUUID());

		helper.assertTrue(error.contains("Item components"), "unexpected error: " + error);
		helper.assertTrue(withSelector.contains("Item components"), "unexpected error: " + withSelector);
		helper.assertTrue(worker.tasks().isIdle(), "worker got tasks: " + queueOf(worker));
		helper.succeed();
	}

	@GameTest
	public void withdrawWithComponentsRejected(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		BlockPos target = helper.absolutePos(TARGET);
		RecordingCommandSource rec = new RecordingCommandSource();
		CommandSourceStack source = sourceAt(helper, rec, worker);
		String item = " minecraft:dirt[custom_name=\"x\"]";

		// Every handler path: no count, count, and count with a selector.
		List<String> commands = List.of(
				"rdp withdraw " + coords(target) + item,
				"rdp withdraw " + coords(target) + item + " 4",
				"rdp withdraw " + coords(target) + item + " 4 " + worker.getStringUUID());
		for (String command : commands) {
			String error = expectFailure(helper, source, command);
			helper.assertTrue(error.contains("Item components"), "unexpected error for /" + command + ": " + error);
		}

		helper.assertTrue(worker.tasks().isIdle(), "worker got tasks: " + queueOf(worker));
		helper.succeed();
	}

	@GameTest
	public void emptyComponentsAccepted(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		BlockPos target = helper.absolutePos(TARGET);
		RecordingCommandSource rec = new RecordingCommandSource();

		run(helper, sourceAt(helper, rec, worker), "rdp withdraw " + coords(target) + " minecraft:dirt[] 3");

		List<Task<WorkerEntity>> queue = queueOf(worker);
		helper.assertTrue(queue.size() == 1 && queue.get(0) instanceof WithdrawTask w
						&& w.itemId().equals(BuiltInRegistries.ITEM.getKey(Items.DIRT)) && w.count() == 3,
				"expected one withdraw of 3 dirt, got " + queue);
		helper.succeed();
	}

	@GameTest
	public void depositNameLikeItemIsItem(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		worker.setCustomName(Component.literal("dirt"));
		BlockPos target = helper.absolutePos(TARGET);
		RecordingCommandSource rec = new RecordingCommandSource();

		// "dirt" is read as the item; the nearest worker gets the task.
		int result = run(helper, sourceAt(helper, rec, worker), "rdp deposit " + coords(target) + " dirt");

		helper.assertTrue(result == 1, "expected result 1, got " + result);
		List<Task<WorkerEntity>> queue = queueOf(worker);
		helper.assertTrue(queue.size() == 1 && queue.get(0) instanceof DepositTask d
						&& d.itemId().equals(Optional.of(BuiltInRegistries.ITEM.getKey(Items.DIRT))),
				"expected one deposit dirt, got " + queue);
		helper.succeed();
	}

	@GameTest
	public void depositNamedWorkerViaSelector(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		worker.setCustomName(Component.literal("stone"));
		BlockPos target = helper.absolutePos(TARGET);
		RecordingCommandSource rec = new RecordingCommandSource();
		// Away from the worker: only the selector can pick it. "distance" keeps other tests' workers out.
		CommandSourceStack source = source(helper, rec, helper.absoluteVec(new Vec3(6.5, 1, 6.5)), LEVEL_2);

		int result = run(helper, source, "rdp deposit " + coords(target) + " @e[type=rabodepalha:worker,name=stone,distance=..10]");

		helper.assertTrue(result == 1, "expected result 1, got " + result);
		List<Task<WorkerEntity>> queue = queueOf(worker);
		helper.assertTrue(queue.size() == 1 && queue.get(0) instanceof DepositTask d && d.itemId().isEmpty(),
				"expected one deposit all, got " + queue);
		helper.succeed();
	}

	@GameTest
	public void depositQueuesDepositTask(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		BlockPos target = helper.absolutePos(TARGET);
		RecordingCommandSource rec = new RecordingCommandSource();

		int result = run(helper, sourceAt(helper, rec, worker), "rdp deposit " + coords(target));

		helper.assertTrue(result == 1, "expected result 1, got " + result);
		List<Task<WorkerEntity>> queue = queueOf(worker);
		helper.assertTrue(queue.size() == 1 && queue.get(0) instanceof DepositTask d && d.pos().equals(target)
						&& d.itemId().isEmpty(),
				"expected one deposit all into " + target + ", got " + queue);
		assertFeedback(helper, rec, "Queued deposit all into " + target.toShortString() + " for 1 worker(s)");
		helper.succeed();
	}

	@GameTest
	public void depositItemWithSelector(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		BlockPos target = helper.absolutePos(TARGET);
		RecordingCommandSource rec = new RecordingCommandSource();
		// Away from the worker: only the selector can pick it.
		CommandSourceStack source = source(helper, rec, helper.absoluteVec(new Vec3(6.5, 1, 6.5)), LEVEL_2);

		int result = run(helper, source, "rdp deposit " + coords(target) + " minecraft:dirt " + worker.getStringUUID());

		helper.assertTrue(result == 1, "expected result 1, got " + result);
		List<Task<WorkerEntity>> queue = queueOf(worker);
		helper.assertTrue(queue.size() == 1 && queue.get(0) instanceof DepositTask d && d.pos().equals(target)
						&& d.itemId().equals(Optional.of(BuiltInRegistries.ITEM.getKey(Items.DIRT))),
				"expected one deposit dirt into " + target + ", got " + queue);
		assertFeedback(helper, rec, "Queued deposit minecraft:dirt into " + target.toShortString() + " for 1 worker(s)");
		helper.succeed();
	}

	@GameTest
	public void depositWithSelectorOnly(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		BlockPos target = helper.absolutePos(TARGET);
		RecordingCommandSource rec = new RecordingCommandSource();
		CommandSourceStack source = source(helper, rec, helper.absoluteVec(new Vec3(6.5, 1, 6.5)), LEVEL_2);

		run(helper, source, "rdp deposit " + coords(target) + " " + worker.getStringUUID());

		List<Task<WorkerEntity>> queue = queueOf(worker);
		helper.assertTrue(queue.size() == 1 && queue.get(0) instanceof DepositTask d && d.itemId().isEmpty(),
				"expected one deposit all, got " + queue);
		helper.succeed();
	}

	@GameTest
	public void withdrawQueuesWithdrawTask(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		BlockPos target = helper.absolutePos(TARGET);
		RecordingCommandSource rec = new RecordingCommandSource();

		int result = run(helper, sourceAt(helper, rec, worker), "rdp withdraw " + coords(target) + " minecraft:cobblestone");

		helper.assertTrue(result == 1, "expected result 1, got " + result);
		List<Task<WorkerEntity>> queue = queueOf(worker);
		helper.assertTrue(queue.size() == 1 && queue.get(0) instanceof WithdrawTask w && w.pos().equals(target)
						&& w.itemId().equals(BuiltInRegistries.ITEM.getKey(Items.COBBLESTONE)) && w.count() == 1,
				"expected one withdraw 1 cobblestone from " + target + ", got " + queue);
		assertFeedback(helper, rec, "Queued withdraw 1 minecraft:cobblestone from " + target.toShortString() + " for 1 worker(s)");
		helper.succeed();
	}

	@GameTest
	public void withdrawWithCountAndSelector(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		BlockPos target = helper.absolutePos(TARGET);
		RecordingCommandSource rec = new RecordingCommandSource();
		// Away from the worker: only the selector can pick it.
		CommandSourceStack source = source(helper, rec, helper.absoluteVec(new Vec3(6.5, 1, 6.5)), LEVEL_2);

		int result = run(helper, source, "rdp withdraw " + coords(target) + " minecraft:cobblestone 12 " + worker.getStringUUID());

		helper.assertTrue(result == 1, "expected result 1, got " + result);
		List<Task<WorkerEntity>> queue = queueOf(worker);
		helper.assertTrue(queue.size() == 1 && queue.get(0) instanceof WithdrawTask w && w.count() == 12,
				"expected one withdraw of 12, got " + queue);
		assertFeedback(helper, rec, "Queued withdraw 12 minecraft:cobblestone from " + target.toShortString());
		helper.succeed();
	}

	@GameTest
	public void withdrawCountWithoutSelector(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		BlockPos target = helper.absolutePos(TARGET);
		RecordingCommandSource rec = new RecordingCommandSource();

		// "7" also parses as a player name; it must be taken as the count.
		run(helper, sourceAt(helper, rec, worker), "rdp withdraw " + coords(target) + " minecraft:cobblestone 7");

		List<Task<WorkerEntity>> queue = queueOf(worker);
		helper.assertTrue(queue.size() == 1 && queue.get(0) instanceof WithdrawTask w && w.count() == 7,
				"expected one withdraw of 7, got " + queue);
		helper.succeed();
	}

	@GameTest
	public void withdrawCountZeroRejected(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		BlockPos target = helper.absolutePos(TARGET);
		RecordingCommandSource rec = new RecordingCommandSource();

		expectFailure(helper, sourceAt(helper, rec, worker), "rdp withdraw " + coords(target) + " minecraft:cobblestone 0");

		helper.assertTrue(worker.tasks().isIdle(), "worker got tasks: " + queueOf(worker));
		helper.succeed();
	}

	@GameTest
	public void giveWithCountAndSelector(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		RecordingCommandSource rec = new RecordingCommandSource();
		// Away from the worker: only the selector can pick it.
		CommandSourceStack source = source(helper, rec, helper.absoluteVec(new Vec3(6.5, 1, 6.5)), LEVEL_2);

		int result = run(helper, source, "rdp give minecraft:dirt 5 " + worker.getStringUUID());

		helper.assertTrue(result == 1, "expected result 1, got " + result);
		int dirt = worker.getInventory().countItem(Items.DIRT);
		helper.assertTrue(dirt == 5, "expected 5 dirt, got " + dirt);
		assertFeedback(helper, rec, "Gave 5 ");
		assertFeedback(helper, rec, " to 1 worker(s)");
		helper.succeed();
	}

	@GameTest
	public void giveDefaultCount(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		RecordingCommandSource rec = new RecordingCommandSource();

		run(helper, sourceAt(helper, rec, worker), "rdp give minecraft:dirt");

		int dirt = worker.getInventory().countItem(Items.DIRT);
		helper.assertTrue(dirt == 1, "expected 1 dirt, got " + dirt);
		assertFeedback(helper, rec, "Gave 1 ");
		helper.succeed();
	}

	@GameTest
	public void queueIdle(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		RecordingCommandSource rec = new RecordingCommandSource();

		int result = run(helper, sourceAt(helper, rec, worker), "rdp queue " + worker.getStringUUID());

		helper.assertTrue(result == 1, "expected result 1, got " + result);
		assertFeedback(helper, rec, " " + worker.getId() + " @ " + coords(worker.blockPosition()) + ": idle");
		helper.succeed();
	}

	@GameTest
	public void queueListsTasks(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		BlockPos first = helper.absolutePos(TARGET);
		BlockPos second = helper.absolutePos(new BlockPos(6, 1, 2));
		RecordingCommandSource rec = new RecordingCommandSource();
		CommandSourceStack source = sourceAt(helper, rec, worker);
		run(helper, source, "rdp goto " + coords(first));
		run(helper, source, "rdp goto " + coords(second));

		// The worker has not ticked yet, so nothing is running and both tasks are numbered.
		run(helper, source, "rdp queue");

		assertFeedback(helper, rec, "\n  1. move to " + first.toShortString() + "\n  2. move to " + second.toShortString());
		helper.succeed();
	}

	@GameTest
	public void clearEmptiesQueue(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		RecordingCommandSource rec = new RecordingCommandSource();
		CommandSourceStack source = sourceAt(helper, rec, worker);
		run(helper, source, "rdp goto " + coords(helper.absolutePos(TARGET)));
		run(helper, source, "rdp goto " + coords(helper.absolutePos(new BlockPos(6, 1, 2))));
		helper.assertTrue(queueOf(worker).size() == 2, "expected 2 queued tasks, got " + queueOf(worker));

		int result = run(helper, source, "rdp clear");

		helper.assertTrue(result == 1, "expected result 1, got " + result);
		helper.assertTrue(worker.tasks().isIdle(), "queue not empty after clear: " + queueOf(worker));
		assertFeedback(helper, rec, "Cleared tasks of 1 worker(s)");
		helper.succeed();
	}

	@GameTest(maxTicks = 200)
	public void clearStopsRunningTask(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, new BlockPos(1, 1, 1));
		List<TaskOutcome> outcomes = new ArrayList<>();
		worker.tasks().addListener((task, outcome) -> outcomes.add(outcome));
		// A real player commander, so any "cancelled" report would show up in its chat.
		RtsModeGameTest.JoinedPlayer joined = RtsModeGameTest.joinPlayerWithChannel(helper);
		ServerPlayer player = joined.player();
		RecordingCommandSource rec = new RecordingCommandSource();
		run(helper, playerSource(helper, rec, worker.position(), LEVEL_2, player),
				"rdp goto " + coords(helper.absolutePos(new BlockPos(6, 1, 6))) + " " + worker.getStringUUID());
		helper.assertTrue(worker.commanders().contains(player.getUUID()), "player not recorded as commander");

		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(worker.tasks().current().isPresent(), "move_to not started yet"))
				.thenExecute(() -> {
					joined.drainSystemChat();
					run(helper, playerSource(helper, rec, worker.position(), LEVEL_2, player), "rdp clear " + worker.getStringUUID());
					helper.assertTrue(worker.tasks().isIdle(), "runner not idle after clear: " + queueOf(worker));
					helper.assertTrue(worker.getNavigation().isDone(), "navigation still running after clear");
					helper.assertTrue(outcomes.equals(List.of(TaskOutcome.cancelled(Cause.CLEARED))),
							"expected one Cancelled(CLEARED), got " + outcomes);
					helper.assertFalse(WorkerEntity.notifiesCommanders(outcomes.get(0)), "cleared task would be reported");
					helper.assertTrue(rec.anyContains("Cleared tasks of 1 worker(s)"), "no clear feedback: " + rec.messages());
					List<String> cancelled = joined.drainSystemChat().stream().filter(line -> line.contains("cancelled")).toList();
					helper.assertTrue(cancelled.isEmpty(), "commander was told about the clear: " + cancelled);
				})
				.thenExecute(() -> RtsModeGameTest.leave(player))
				.thenSucceed();
	}

	@GameTest
	public void explicitSelectorTargetsOnlySelected(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity a = helper.spawn(ModEntities.WORKER, WORKER_START);
		WorkerEntity b = helper.spawn(ModEntities.WORKER, new BlockPos(3, 1, 2));
		BlockPos target = helper.absolutePos(TARGET);
		RecordingCommandSource rec = new RecordingCommandSource();
		// Standing on B, so the default would have picked B.
		CommandSourceStack source = sourceAt(helper, rec, b);

		int result = run(helper, source, "rdp goto " + coords(target) + " " + a.getStringUUID());

		helper.assertTrue(result == 1, "expected result 1, got " + result);
		helper.assertTrue(queueOf(a).size() == 1, "selected worker has no task: " + queueOf(a));
		helper.assertTrue(queueOf(b).isEmpty(), "unselected worker got a task: " + queueOf(b));
		helper.succeed();
	}

	@GameTest
	public void defaultPicksNearestWorker(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity near = helper.spawn(ModEntities.WORKER, WORKER_START);
		WorkerEntity far = helper.spawn(ModEntities.WORKER, new BlockPos(2, 1, 7));
		BlockPos target = helper.absolutePos(TARGET);
		RecordingCommandSource rec = new RecordingCommandSource();

		run(helper, sourceAt(helper, rec, near), "rdp goto " + coords(target));

		helper.assertTrue(queueOf(near).size() == 1, "nearest worker has no task: " + queueOf(near));
		helper.assertTrue(queueOf(far).isEmpty(), "farther worker got a task: " + queueOf(far));
		assertFeedback(helper, rec, "for 1 worker(s)");
		helper.succeed();
	}

	@GameTest
	public void noNearbyWorkerFails(GameTestHelper helper) {
		layFloor(helper);
		// Far above the build limit, so no worker from any test is within 64 blocks.
		Vec3 corner = helper.absoluteVec(Vec3.ZERO);
		Vec3 pos = new Vec3(corner.x, helper.getLevel().getMaxY() + 100, corner.z);
		CommandSourceStack source = source(helper, new RecordingCommandSource(), pos, LEVEL_2);

		String error = expectFailure(helper, source, "rdp clear");

		helper.assertTrue(error.equals("No worker within 64 blocks"), "unexpected error: " + error);
		helper.succeed();
	}

	@GameTest
	public void selectorMatchingNoWorkersFails(GameTestHelper helper) {
		layFloor(helper);
		Pig pig = helper.spawn(EntityTypes.PIG, WORKER_START);
		CommandSourceStack source = source(helper, new RecordingCommandSource(), pig.position(), LEVEL_2);

		// The selector matches an entity, but not a worker: the mod's own error.
		String error = expectFailure(helper, source, "rdp clear " + pig.getStringUUID());
		helper.assertTrue(error.equals("No workers selected"), "unexpected error: " + error);

		// A selector matching nothing at all is rejected by vanilla before the mod sees it.
		String vanilla = expectFailure(helper, source, "rdp clear " + new UUID(0L, 1L));
		helper.assertTrue(!vanilla.equals("No workers selected"), "expected the vanilla error, got " + vanilla);
		helper.succeed();
	}

	@GameTest
	public void levelZeroRejectedForEverySubcommand(GameTestHelper helper) {
		assertRejected(helper, LEVEL_0);
	}

	@GameTest
	public void levelOneRejectedForEverySubcommand(GameTestHelper helper) {
		assertRejected(helper, LEVEL_1);
	}

	/** Every subcommand, with valid arguments, must be rejected at {@code permissions} and change nothing. */
	private static void assertRejected(GameTestHelper helper, PermissionSet permissions) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		BlockPos spawnTarget = helper.absolutePos(new BlockPos(6, 1, 2));
		BlockPos target = helper.absolutePos(TARGET);
		RecordingCommandSource rec = new RecordingCommandSource();
		CommandSourceStack source = source(helper, rec, worker.position(), permissions);
		String uuid = worker.getStringUUID();
		List<String> commands = List.of(
				"rdp spawn " + coords(spawnTarget),
				"rdp list",
				"rdp goto " + coords(target) + " " + uuid,
				"rdp mine " + coords(target),
				"rdp place " + coords(target) + " minecraft:cobblestone",
				"rdp give minecraft:dirt 5 " + uuid,
				"rdp deposit " + coords(target) + " minecraft:dirt " + uuid,
				"rdp withdraw " + coords(target) + " minecraft:dirt 5 " + uuid,
				"rdp queue",
				"rdp clear " + uuid);

		List<String> accepted = new ArrayList<>();
		for (String command : commands) {
			try {
				helper.getLevel().getServer().getCommands().getDispatcher().execute(command, source);
				accepted.add(command);
			} catch (CommandSyntaxException e) {
				// Expected: the root "rdp" node is hidden from this source.
			}
		}

		helper.assertTrue(accepted.isEmpty(), "accepted at " + permissions + ": " + accepted);
		helper.assertTrue(workersAt(helper, Vec3.atBottomCenterOf(spawnTarget)).isEmpty(), "a worker was spawned");
		helper.assertTrue(worker.tasks().isIdle(), "worker got tasks: " + queueOf(worker));
		helper.assertTrue(worker.getInventory().isEmpty(), "worker inventory changed");
		helper.assertTrue(rec.messages().isEmpty(), "unexpected feedback: " + rec.messages());
		helper.succeed();
	}

	@GameTest
	public void levelTwoAccepted(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		RecordingCommandSource rec = new RecordingCommandSource();

		int result = run(helper, source(helper, rec, worker.position(), LEVEL_2), "rdp queue");

		helper.assertTrue(result == 1, "expected result 1, got " + result);
		assertFeedback(helper, rec, ": idle");
		helper.succeed();
	}
}
