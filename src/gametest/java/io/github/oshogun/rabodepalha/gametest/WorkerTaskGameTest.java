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
import java.util.UUID;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
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

	/** Total count of {@code item} in item entities within {@code radius} blocks of {@code center}. */
	private static int itemsNear(GameTestHelper helper, Vec3 center, double radius, Item item) {
		AABB box = AABB.ofSize(center, radius * 2, radius * 2, radius * 2);
		return helper.getLevel().getEntitiesOfClass(ItemEntity.class, box, entity -> entity.getItem().is(item)).stream()
				.mapToInt(entity -> entity.getItem().getCount())
				.sum();
	}

	private static void assertFailedWith(GameTestHelper helper, List<Recorded> outcomes, FailureReason reason) {
		helper.assertTrue(outcomes.size() == 1, "task not finished yet");
		helper.assertTrue(outcomes.get(0).outcome() instanceof TaskOutcome.Failed f && f.reason() == reason,
				"expected " + reason + ", got " + outcomes.get(0).outcome());
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

	@GameTest(maxTicks = 100)
	public void mineStoneWithoutToolIsNoTool(GameTestHelper helper) {
		layFloor(helper);
		BlockPos rel = new BlockPos(3, 1, 3);
		helper.setBlock(rel, Blocks.STONE);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.tasks().enqueue(new MineTask(helper.absolutePos(rel)));

		helper.succeedWhen(() -> {
			assertFailedWith(helper, outcomes, FailureReason.NO_TOOL);
			helper.assertBlockPresent(Blocks.STONE, rel);
		});
	}

	@GameTest(maxTicks = 100)
	public void mineAirIsAir(GameTestHelper helper) {
		layFloor(helper);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.tasks().enqueue(new MineTask(helper.absolutePos(new BlockPos(3, 1, 3))));

		helper.succeedWhen(() -> assertFailedWith(helper, outcomes, FailureReason.AIR));
	}

	@GameTest(maxTicks = 600)
	public void mineFailsWhenTargetChanged(GameTestHelper helper) {
		layFloor(helper);
		// Far enough that the worker has to walk, so the block can be swapped before the break ends.
		BlockPos rel = new BlockPos(6, 1, 6);
		helper.setBlock(rel, Blocks.STONE);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.WOODEN_PICKAXE));
		worker.tasks().enqueue(new MineTask(helper.absolutePos(rel)));

		helper.startSequence()
				// The task captures its target in start(), which runs on the tick it becomes current.
				.thenWaitUntil(() -> helper.assertTrue(worker.tasks().current().isPresent(), "mine not started yet"))
				.thenExecute(() -> {
					helper.assertTrue(outcomes.isEmpty(), "mine finished before the block was swapped: " + outcomes);
					helper.setBlock(rel, Blocks.COBBLESTONE);
				})
				.thenWaitUntil(() -> assertFailedWith(helper, outcomes, FailureReason.TARGET_CHANGED))
				.thenExecute(() -> helper.assertBlockPresent(Blocks.COBBLESTONE, rel))
				.thenSucceed();
	}

	@GameTest(maxTicks = 400)
	public void mineWithFullInventoryDropsAtFeet(GameTestHelper helper) {
		layFloor(helper);
		BlockPos rel = new BlockPos(2, 1, 1);
		helper.setBlock(rel, Blocks.STONE);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.STONE_PICKAXE));
		for (int i = 1; i < WorkerEntity.INVENTORY_SIZE; i++) {
			worker.insertOrDrop(new ItemStack(Items.DIRT, 64));
		}
		SimpleContainer inventory = worker.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			helper.assertTrue(!inventory.getItem(i).isEmpty(), "inventory slot " + i + " is empty");
		}
		worker.tasks().enqueue(new MineTask(helper.absolutePos(rel)));

		helper.succeedWhen(() -> {
			helper.assertTrue(outcomes.size() == 1, "mine not finished yet");
			helper.assertTrue(outcomes.get(0).outcome() instanceof TaskOutcome.Succeeded s && s.note().contains("inventory full"),
					"expected success with an overflow note, got " + outcomes.get(0).outcome());
			helper.assertBlockPresent(Blocks.AIR, rel);
			helper.assertTrue(count(worker, Items.COBBLESTONE) == 0, "cobblestone went into a full inventory");
			helper.assertTrue(itemsNear(helper, worker.position(), 2.0, Items.COBBLESTONE) == 1,
					"no cobblestone dropped at the worker's feet");
		});
	}

	@GameTest(maxTicks = 100)
	public void placeOnStoneIsNotReplaceable(GameTestHelper helper) {
		layFloor(helper);
		BlockPos rel = new BlockPos(3, 1, 3);
		helper.setBlock(rel, Blocks.STONE);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.DIRT));
		worker.tasks().enqueue(new PlaceTask(helper.absolutePos(rel), BuiltInRegistries.BLOCK.getKey(Blocks.DIRT)));

		helper.succeedWhen(() -> {
			assertFailedWith(helper, outcomes, FailureReason.NOT_REPLACEABLE);
			helper.assertBlockPresent(Blocks.STONE, rel);
			helper.assertTrue(count(worker, Items.DIRT) == 1, "dirt was consumed");
		});
	}

	@GameTest(maxTicks = 100)
	public void placeFloatingTorchCannotSurvive(GameTestHelper helper) {
		layFloor(helper);
		// Air below and on every side, so a torch has nothing to stand on.
		BlockPos rel = new BlockPos(3, 2, 3);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.TORCH));
		worker.tasks().enqueue(new PlaceTask(helper.absolutePos(rel), BuiltInRegistries.BLOCK.getKey(Blocks.TORCH)));

		helper.succeedWhen(() -> {
			assertFailedWith(helper, outcomes, FailureReason.CANNOT_SURVIVE);
			helper.assertBlockPresent(Blocks.AIR, rel);
			helper.assertTrue(count(worker, Items.TORCH) == 1, "torch was consumed");
		});
	}

	@GameTest(maxTicks = 100)
	public void placeOnArmorStandIsObstructed(GameTestHelper helper) {
		layFloor(helper);
		BlockPos rel = new BlockPos(3, 1, 3);
		ArmorStand stand = helper.spawn(EntityTypes.ARMOR_STAND, rel);
		stand.setNoGravity(true);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.DIRT));
		worker.tasks().enqueue(new PlaceTask(helper.absolutePos(rel), BuiltInRegistries.BLOCK.getKey(Blocks.DIRT)));

		helper.succeedWhen(() -> {
			assertFailedWith(helper, outcomes, FailureReason.OBSTRUCTED);
			helper.assertBlockPresent(Blocks.AIR, rel);
			helper.assertTrue(count(worker, Items.DIRT) == 1, "dirt was consumed");
		});
	}

	@GameTest(maxTicks = 400)
	public void cancelStopsNavigation(GameTestHelper helper) {
		layFloor(helper);
		BlockPos rel = new BlockPos(6, 1, 6);
		helper.setBlock(rel, Blocks.STONE);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.WOODEN_PICKAXE));
		worker.tasks().enqueue(new MineTask(helper.absolutePos(rel)));

		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(!worker.getNavigation().isDone(), "worker is not walking yet"))
				.thenExecute(() -> {
					worker.tasks().cancelCurrent(worker);
					helper.assertTrue(worker.getNavigation().isDone(), "navigation still running after cancel");
					helper.assertTrue(worker.tasks().isIdle(), "runner not idle after cancel");
					helper.assertTrue(outcomes.size() == 1 && outcomes.get(0).outcome() instanceof TaskOutcome.Cancelled,
							"expected one Cancelled outcome, got " + outcomes);
				})
				.thenExecuteAfter(40, () -> {
					helper.assertBlockPresent(Blocks.STONE, rel);
					helper.assertTrue(outcomes.size() == 1, "more outcomes after cancel: " + outcomes);
				})
				.thenSucceed();
	}

	@GameTest(maxTicks = 400)
	public void cancelMidBreakKeepsBlock(GameTestHelper helper) {
		layFloor(helper);
		// Next to the worker, so it starts breaking at once; stone with a wooden pickaxe takes ~23 ticks.
		BlockPos rel = new BlockPos(2, 1, 1);
		helper.setBlock(rel, Blocks.STONE);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.WOODEN_PICKAXE));
		worker.tasks().enqueue(new MineTask(helper.absolutePos(rel)));

		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(worker.tasks().current().isPresent(), "mine not started yet"))
				.thenExecuteAfter(5, () -> {
					helper.assertTrue(outcomes.isEmpty(), "mine finished before the cancel: " + outcomes);
					worker.tasks().cancelCurrent(worker);
					helper.assertTrue(worker.tasks().isIdle(), "runner not idle after cancel");
					helper.assertTrue(outcomes.size() == 1 && outcomes.get(0).outcome() instanceof TaskOutcome.Cancelled,
							"expected one Cancelled outcome, got " + outcomes);
				})
				.thenExecuteAfter(40, () -> helper.assertBlockPresent(Blocks.STONE, rel))
				.thenSucceed();
	}

	@GameTest(maxTicks = 400)
	public void clearDropsPendingAndStops(GameTestHelper helper) {
		layFloor(helper);
		BlockPos mineRel = new BlockPos(6, 1, 6);
		BlockPos placeRel = new BlockPos(4, 1, 6);
		helper.setBlock(mineRel, Blocks.STONE);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.WOODEN_PICKAXE));
		worker.insertOrDrop(new ItemStack(Items.DIRT));
		worker.tasks().enqueue(new MineTask(helper.absolutePos(mineRel)));
		worker.tasks().enqueue(new PlaceTask(helper.absolutePos(placeRel), BuiltInRegistries.BLOCK.getKey(Blocks.DIRT)));

		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(!worker.getNavigation().isDone(), "worker is not walking yet"))
				.thenExecute(() -> {
					worker.tasks().clear(worker);
					helper.assertTrue(worker.tasks().current().isEmpty(), "current task survived clear");
					helper.assertTrue(worker.tasks().pending().isEmpty(), "pending tasks survived clear");
					helper.assertTrue(worker.getNavigation().isDone(), "navigation still running after clear");
					helper.assertTrue(outcomes.size() == 1 && outcomes.get(0).outcome() instanceof TaskOutcome.Cancelled,
							"expected one Cancelled outcome, got " + outcomes);
				})
				.thenExecuteAfter(40, () -> {
					helper.assertBlockPresent(Blocks.STONE, mineRel);
					helper.assertBlockPresent(Blocks.AIR, placeRel);
					helper.assertTrue(count(worker, Items.DIRT) == 1, "dirt was consumed after clear");
					helper.assertTrue(outcomes.size() == 1, "more outcomes after clear: " + outcomes);
				})
				.thenSucceed();
	}

	@GameTest(maxTicks = 400)
	public void removalClearsTasks(GameTestHelper helper) {
		layFloor(helper);
		BlockPos rel = new BlockPos(6, 1, 6);
		helper.setBlock(rel, Blocks.STONE);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.WOODEN_PICKAXE));
		worker.tasks().enqueue(new MineTask(helper.absolutePos(rel)));

		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(!worker.getNavigation().isDone(), "worker is not walking yet"))
				.thenExecute(() -> {
					worker.discard();
					helper.assertTrue(worker.tasks().isIdle(), "runner not idle after removal");
					helper.assertTrue(worker.getNavigation().isDone(), "navigation still running after removal");
					helper.assertTrue(outcomes.size() == 1 && outcomes.get(0).outcome() instanceof TaskOutcome.Cancelled,
							"expected one Cancelled outcome, got " + outcomes);
				})
				.thenExecuteAfter(40, () -> helper.assertBlockPresent(Blocks.STONE, rel))
				.thenSucceed();
	}

	@GameTest(maxTicks = 100)
	public void deathDropsInventory(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		worker.insertOrDrop(new ItemStack(Items.DIRT, 5));
		worker.insertOrDrop(new ItemStack(Items.WOODEN_PICKAXE));
		Vec3 deathPos = worker.position();
		worker.kill(helper.getLevel());

		helper.succeedWhen(() -> {
			helper.assertTrue(itemsNear(helper, deathPos, 3.0, Items.DIRT) == 5,
					"expected 5 dropped dirt, got " + itemsNear(helper, deathPos, 3.0, Items.DIRT));
			helper.assertTrue(itemsNear(helper, deathPos, 3.0, Items.WOODEN_PICKAXE) == 1, "pickaxe was not dropped");
			helper.assertTrue(worker.getInventory().isEmpty(), "inventory not emptied on death");
		});
	}

	@GameTest(maxTicks = 200)
	public void workerIsPersistentAndIgnoresLoot(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		helper.assertTrue(worker.isPersistenceRequired(), "worker is not persistence-required");
		helper.assertTrue(!worker.canPickUpLoot(), "worker can pick up loot");
		ItemEntity item = helper.spawnItem(Items.DIRT, WORKER_START);
		item.setNoPickUpDelay();

		helper.startSequence()
				.thenExecuteAfter(40, () -> {
					helper.assertTrue(item.isAlive(), "dirt item entity was picked up or removed");
					helper.assertTrue(count(worker, Items.DIRT) == 0, "worker picked up the dirt");
				})
				.thenSucceed();
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
		List<UUID> commanders = List.of(new UUID(0L, 1L), new UUID(0L, 2L), new UUID(0L, 3L));
		commanders.forEach(worker::addCommander);

		// Save before the worker has ticked, so the queue is exactly what was enqueued.
		WorkerEntity loaded = roundTrip(helper, worker);

		helper.assertTrue(List.copyOf(loaded.commanders()).equals(commanders),
				"commanders differ: " + commanders + " vs " + loaded.commanders());

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

	@GameTest
	public void saveLoadCapsAndOrdersCommanders(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		List<UUID> added = new ArrayList<>();
		for (int i = 0; i < WorkerEntity.MAX_COMMANDERS + 2; i++) {
			added.add(new UUID(0L, i));
			worker.addCommander(added.get(i));
		}
		// The two oldest are gone; re-adding the oldest survivor makes it the most recent.
		UUID readded = added.get(2);
		worker.addCommander(readded);

		List<UUID> expected = new ArrayList<>(added.subList(3, added.size()));
		expected.add(readded);
		helper.assertTrue(List.copyOf(worker.commanders()).equals(expected),
				"expected " + expected + ", got " + worker.commanders());

		WorkerEntity loaded = roundTrip(helper, worker);
		helper.assertTrue(List.copyOf(loaded.commanders()).equals(expected),
				"after load expected " + expected + ", got " + loaded.commanders());
		helper.succeed();
	}

	@GameTest
	public void loadsSaveWithoutCommanders(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		worker.tasks().enqueue(new MoveToTask(helper.absolutePos(new BlockPos(5, 1, 5))));
		worker.addCommander(new UUID(0L, 1L));

		var registries = helper.getLevel().registryAccess();
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
		worker.saveWithoutId(output);
		CompoundTag tag = output.buildResult();
		helper.assertTrue(tag.contains("Commanders"), "save has no Commanders: " + tag);
		// Imitates a save made before commanders were persisted.
		tag.remove("Commanders");

		WorkerEntity loaded = ModEntities.WORKER.create(helper.getLevel(), EntitySpawnReason.LOAD);
		helper.assertTrue(loaded != null, "could not create a worker");
		loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, tag));

		helper.assertTrue(loaded.tasks().snapshot().size() == 1, "expected 1 restored task, got " + loaded.tasks().snapshot());
		helper.assertTrue(loaded.commanders().isEmpty(), "expected no commanders, got " + loaded.commanders());
		helper.succeed();
	}

	/** Saves {@code worker} and loads the result into a new, unspawned worker. */
	static WorkerEntity roundTrip(GameTestHelper helper, WorkerEntity worker) {
		var registries = helper.getLevel().registryAccess();
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
		worker.saveWithoutId(output);

		WorkerEntity loaded = ModEntities.WORKER.create(helper.getLevel(), EntitySpawnReason.LOAD);
		helper.assertTrue(loaded != null, "could not create a worker");
		loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, registries, output.buildResult()));
		return loaded;
	}
}
