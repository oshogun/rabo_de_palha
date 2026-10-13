package io.github.oshogun.rabodepalha.gametest;

import io.github.oshogun.rabodepalha.registry.ModEntities;
import io.github.oshogun.rabodepalha.task.FailureReason;
import io.github.oshogun.rabodepalha.task.Task;
import io.github.oshogun.rabodepalha.task.TaskOutcome;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;
import io.github.oshogun.rabodepalha.worker.task.MineTask;
import io.github.oshogun.rabodepalha.worker.task.MoveToTask;
import io.github.oshogun.rabodepalha.worker.task.PlaceTask;
import io.github.oshogun.rabodepalha.worker.task.TaskTypes;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

public class WorkerTaskGameTest {
	private static final BlockPos WORKER_START = new BlockPos(1, 1, 1);

	/** Records every terminal outcome the worker's task runner reports, in order. */
	private record Recorded(Task<WorkerEntity> task, TaskOutcome outcome) {
	}

	private static void layFloor(GameTestHelper helper) {
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
			}
		}
	}

	private static WorkerEntity spawnWorker(GameTestHelper helper, List<Recorded> outcomes) {
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		worker.tasks().addListener((task, outcome) -> outcomes.add(new Recorded(task, outcome)));
		return worker;
	}

	private static int count(WorkerEntity worker, Item item) {
		return worker.getInventory().countItem(item);
	}

	@GameTest(maxTicks = 400)
	public void moveToReachesTarget(GameTestHelper helper) {
		layFloor(helper);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		BlockPos target = helper.absolutePos(new BlockPos(6, 1, 6));
		worker.tasks().enqueue(new MoveToTask(target));

		helper.succeedWhen(() -> {
			helper.assertTrue(outcomes.size() == 1, "move_to not finished yet");
			helper.assertTrue(outcomes.get(0).outcome() instanceof TaskOutcome.Succeeded,
					"move_to did not succeed: " + outcomes.get(0).outcome());
			helper.assertTrue(worker.position().distanceTo(Vec3.atBottomCenterOf(target)) <= 1.5,
					"worker is not at the target");
		});
	}

	@GameTest(maxTicks = 600)
	public void minesStoneIntoInventory(GameTestHelper helper) {
		layFloor(helper);
		BlockPos rel = new BlockPos(5, 1, 5);
		helper.setBlock(rel, Blocks.STONE);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.WOODEN_PICKAXE));
		worker.tasks().enqueue(new MineTask(helper.absolutePos(rel)));

		helper.succeedWhen(() -> {
			helper.assertTrue(outcomes.size() == 1, "mine not finished yet");
			helper.assertTrue(outcomes.get(0).outcome() instanceof TaskOutcome.Succeeded,
					"mine did not succeed: " + outcomes.get(0).outcome());
			helper.assertBlockPresent(Blocks.AIR, rel);
			helper.assertTrue(count(worker, Items.COBBLESTONE) == 1, "no cobblestone in inventory");
		});
	}

	@GameTest(maxTicks = 400)
	public void placesDirtFromInventory(GameTestHelper helper) {
		layFloor(helper);
		BlockPos rel = new BlockPos(5, 1, 5);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.DIRT));
		worker.tasks().enqueue(new PlaceTask(helper.absolutePos(rel), BuiltInRegistries.BLOCK.getKey(Blocks.DIRT)));

		helper.succeedWhen(() -> {
			helper.assertTrue(outcomes.size() == 1, "place not finished yet");
			helper.assertTrue(outcomes.get(0).outcome() instanceof TaskOutcome.Succeeded,
					"place did not succeed: " + outcomes.get(0).outcome());
			helper.assertBlockPresent(Blocks.DIRT, rel);
			helper.assertTrue(count(worker, Items.DIRT) == 0, "dirt was not consumed");
		});
	}

	@GameTest(maxTicks = 800)
	public void mineThenPlaceRunsInOrder(GameTestHelper helper) {
		layFloor(helper);
		BlockPos rel = new BlockPos(5, 1, 5);
		helper.setBlock(rel, Blocks.STONE);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.WOODEN_PICKAXE));
		worker.insertOrDrop(new ItemStack(Items.DIRT));
		BlockPos abs = helper.absolutePos(rel);
		worker.tasks().enqueue(new MineTask(abs));
		worker.tasks().enqueue(new PlaceTask(abs, BuiltInRegistries.BLOCK.getKey(Blocks.DIRT)));

		helper.succeedWhen(() -> {
			helper.assertTrue(outcomes.size() == 2, "tasks not finished yet");
			helper.assertTrue(outcomes.get(0).task() instanceof MineTask, "mine did not run first");
			helper.assertTrue(outcomes.get(1).task() instanceof PlaceTask, "place did not run second");
			helper.assertTrue(outcomes.get(0).outcome() instanceof TaskOutcome.Succeeded,
					"mine did not succeed: " + outcomes.get(0).outcome());
			helper.assertTrue(outcomes.get(1).outcome() instanceof TaskOutcome.Succeeded,
					"place did not succeed: " + outcomes.get(1).outcome());
			helper.assertBlockPresent(Blocks.DIRT, rel);
			helper.assertTrue(count(worker, Items.COBBLESTONE) == 1, "no cobblestone in inventory");
		});
	}

	@GameTest(maxTicks = 100)
	public void mineBedrockIsUnbreakable(GameTestHelper helper) {
		layFloor(helper);
		BlockPos rel = new BlockPos(3, 1, 3);
		helper.setBlock(rel, Blocks.BEDROCK);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.tasks().enqueue(new MineTask(helper.absolutePos(rel)));

		helper.succeedWhen(() -> {
			helper.assertTrue(outcomes.size() == 1, "mine not finished yet");
			helper.assertTrue(outcomes.get(0).outcome() instanceof TaskOutcome.Failed f
					&& f.reason() == FailureReason.UNBREAKABLE, "expected UNBREAKABLE, got " + outcomes.get(0).outcome());
			helper.assertBlockPresent(Blocks.BEDROCK, rel);
		});
	}

	@GameTest(maxTicks = 100)
	public void placeWithoutItemIsMissingItem(GameTestHelper helper) {
		layFloor(helper);
		BlockPos rel = new BlockPos(3, 1, 3);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.tasks().enqueue(new PlaceTask(helper.absolutePos(rel), BuiltInRegistries.BLOCK.getKey(Blocks.DIRT)));

		helper.succeedWhen(() -> {
			helper.assertTrue(outcomes.size() == 1, "place not finished yet");
			helper.assertTrue(outcomes.get(0).outcome() instanceof TaskOutcome.Failed f
					&& f.reason() == FailureReason.MISSING_ITEM, "expected MISSING_ITEM, got " + outcomes.get(0).outcome());
			helper.assertBlockPresent(Blocks.AIR, rel);
		});
	}

	@GameTest(maxTicks = 400)
	public void unreachableMoveToFails(GameTestHelper helper) {
		layFloor(helper);
		// Box in (6,1,6): walls on all four sides two blocks high and a roof.
		BlockPos center = new BlockPos(6, 1, 6);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int y = 1; y <= 3; y++) {
					BlockPos pos = new BlockPos(center.getX() + dx, y, center.getZ() + dz);
					boolean inside = dx == 0 && dz == 0 && y < 3;
					if (!inside) {
						helper.setBlock(pos, Blocks.STONE);
					}
				}
			}
		}
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.tasks().enqueue(new MoveToTask(helper.absolutePos(center), 200));

		EnumSet<FailureReason> expected = EnumSet.of(FailureReason.UNREACHABLE, FailureReason.STUCK, FailureReason.TIMEOUT);
		helper.succeedWhen(() -> {
			helper.assertTrue(outcomes.size() == 1, "move_to not finished yet");
			helper.assertTrue(outcomes.get(0).outcome() instanceof TaskOutcome.Failed f && expected.contains(f.reason()),
					"expected UNREACHABLE/STUCK/TIMEOUT, got " + outcomes.get(0).outcome());
		});
	}

	@GameTest
	public void giveCommandAcceptsCount(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		// Run at the worker's position so it is the nearest worker, even with other tests nearby.
		CommandSourceStack source = helper.getLevel().getServer().createCommandSourceStack()
				.withLevel(helper.getLevel())
				.withPosition(worker.position())
				.withSuppressedOutput();
		try {
			helper.getLevel().getServer().getCommands().getDispatcher().execute("rdp give dirt 5", source);
		} catch (CommandSyntaxException e) {
			helper.fail("/rdp give dirt 5 failed: " + e.getMessage());
		}
		helper.assertTrue(count(worker, Items.DIRT) == 5, "expected 5 dirt, got " + count(worker, Items.DIRT));
		helper.succeed();
	}

	@GameTest
	public void saveLoadKeepsQueueAndInventory(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		worker.insertOrDrop(new ItemStack(Items.WOODEN_PICKAXE));
		worker.insertOrDrop(new ItemStack(Items.DIRT, 5));
		BlockPos abs = helper.absolutePos(new BlockPos(5, 1, 5));
		worker.tasks().enqueue(new MineTask(abs));
		worker.tasks().enqueue(new PlaceTask(abs, BuiltInRegistries.BLOCK.getKey(Blocks.DIRT)));
		worker.tasks().enqueue(new MoveToTask(abs.above(), 321));

		// Save before the worker has ticked, so the queue is exactly what was enqueued.
		var registries = helper.getLevel().registryAccess();
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
		worker.saveWithoutId(output);

		WorkerEntity loaded = ModEntities.WORKER.create(helper.getLevel(), EntitySpawnReason.LOAD);
		helper.assertTrue(loaded != null, "could not create a worker");
		loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, output.buildResult()));

		Codec<List<Task<WorkerEntity>>> listCodec = Codec.list(TaskTypes.CODEC);
		var savedQueue = listCodec.encodeStart(JsonOps.INSTANCE, worker.tasks().snapshot()).getOrThrow();
		var loadedQueue = listCodec.encodeStart(JsonOps.INSTANCE, loaded.tasks().snapshot()).getOrThrow();
		helper.assertTrue(loaded.tasks().snapshot().size() == 3, "expected 3 restored tasks, got " + loaded.tasks().snapshot());
		helper.assertTrue(savedQueue.equals(loadedQueue), "queue differs: " + savedQueue + " vs " + loadedQueue);

		SimpleContainer before = worker.getInventory();
		SimpleContainer after = loaded.getInventory();
		for (int i = 0; i < before.getContainerSize(); i++) {
			helper.assertTrue(ItemStack.matches(before.getItem(i), after.getItem(i)),
					"inventory slot " + i + " differs: " + before.getItem(i) + " vs " + after.getItem(i));
		}
		helper.succeed();
	}
}
