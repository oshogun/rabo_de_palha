package io.github.oshogun.rabodepalha.gametest;

import io.github.oshogun.rabodepalha.registry.ModEntities;
import io.github.oshogun.rabodepalha.task.FailureReason;
import io.github.oshogun.rabodepalha.task.Task;
import io.github.oshogun.rabodepalha.task.TaskOutcome;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;
import io.github.oshogun.rabodepalha.worker.task.BuildTask;
import io.github.oshogun.rabodepalha.worker.task.DepositTask;
import io.github.oshogun.rabodepalha.worker.task.DigAreaTask;
import io.github.oshogun.rabodepalha.worker.task.MineTask;
import io.github.oshogun.rabodepalha.worker.task.MoveToTask;
import io.github.oshogun.rabodepalha.worker.task.PlaceTask;
import io.github.oshogun.rabodepalha.worker.task.TaskTypes;
import io.github.oshogun.rabodepalha.worker.task.WithdrawTask;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class WorkerTaskGameTest {
	private static final BlockPos WORKER_START = new BlockPos(1, 1, 1);

	/** Records every terminal outcome the worker's task runner reports, in order. */
	private record Recorded(Task<WorkerEntity> task, TaskOutcome outcome) {
	}

	static void layFloor(GameTestHelper helper) {
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
		worker.tasks().enqueue(PlaceTask.of(helper.absolutePos(rel), Blocks.DIRT));

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
		worker.tasks().enqueue(PlaceTask.of(abs, Blocks.DIRT));

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
		worker.tasks().enqueue(PlaceTask.of(helper.absolutePos(rel), Blocks.DIRT));

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
		worker.tasks().enqueue(PlaceTask.of(helper.absolutePos(rel), Blocks.DIRT));

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
		worker.tasks().enqueue(PlaceTask.of(helper.absolutePos(rel), Blocks.TORCH));

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
		worker.tasks().enqueue(PlaceTask.of(helper.absolutePos(rel), Blocks.DIRT));

		helper.succeedWhen(() -> {
			assertFailedWith(helper, outcomes, FailureReason.OBSTRUCTED);
			helper.assertBlockPresent(Blocks.AIR, rel);
			helper.assertTrue(count(worker, Items.DIRT) == 1, "dirt was consumed");
		});
	}

	@GameTest(maxTicks = 400)
	public void placeStairsFacingEast(GameTestHelper helper) {
		layFloor(helper);
		BlockPos rel = new BlockPos(5, 1, 5);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.OAK_STAIRS));
		BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState()
				.setValue(StairBlock.FACING, Direction.EAST)
				.setValue(StairBlock.HALF, Half.TOP);
		worker.tasks().enqueue(new PlaceTask(helper.absolutePos(rel), stairs));

		helper.succeedWhen(() -> {
			assertSucceeded(helper, outcomes);
			BlockState placed = helper.getBlockState(rel);
			helper.assertTrue(placed == stairs, "expected " + stairs + ", got " + placed);
			helper.assertTrue(count(worker, Items.OAK_STAIRS) == 0, "stairs were not consumed");
		});
	}

	@GameTest(maxTicks = 400)
	public void legacyPlaceEntryDecodes(GameTestHelper helper) {
		layFloor(helper);
		BlockPos rel = new BlockPos(5, 1, 5);
		BlockPos abs = helper.absolutePos(rel);
		// Queue entries saved before PlaceTask stored full states hold a bare block id.
		JsonObject json = new JsonObject();
		json.addProperty("type", PlaceTask.TYPE_ID);
		json.add("pos", BlockPos.CODEC.encodeStart(JsonOps.INSTANCE, abs).getOrThrow());
		json.addProperty("block", "minecraft:oak_stairs");
		CompoundTag tag = new CompoundTag();
		tag.putString("type", PlaceTask.TYPE_ID);
		tag.put("pos", BlockPos.CODEC.encodeStart(NbtOps.INSTANCE, abs).getOrThrow());
		tag.putString("block", "minecraft:oak_stairs");

		Task<WorkerEntity> fromJson = TaskTypes.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
		Task<WorkerEntity> fromNbt = TaskTypes.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
		BlockState expected = Blocks.OAK_STAIRS.defaultBlockState();
		for (Task<WorkerEntity> task : List.of(fromJson, fromNbt)) {
			helper.assertTrue(task instanceof PlaceTask p && p.pos().equals(abs) && p.state() == expected,
					"legacy entry decoded to " + task.describe());
		}

		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.OAK_STAIRS));
		worker.tasks().enqueue(fromNbt);

		helper.succeedWhen(() -> {
			assertSucceeded(helper, outcomes);
			BlockState placed = helper.getBlockState(rel);
			helper.assertTrue(placed == expected, "expected " + expected + ", got " + placed);
		});
	}

	@GameTest(maxTicks = 400)
	public void placeDoorWhole(GameTestHelper helper) {
		layFloor(helper);
		BlockPos rel = new BlockPos(5, 1, 5);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.OAK_DOOR));
		BlockState door = Blocks.OAK_DOOR.defaultBlockState()
				.setValue(DoorBlock.FACING, Direction.EAST)
				.setValue(DoorBlock.HINGE, DoorHingeSide.RIGHT);
		worker.tasks().enqueue(new PlaceTask(helper.absolutePos(rel), door));

		helper.succeedWhen(() -> {
			assertSucceeded(helper, outcomes);
			BlockState lower = helper.getBlockState(rel);
			BlockState upper = helper.getBlockState(rel.above());
			helper.assertTrue(lower == door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), "lower half is " + lower);
			helper.assertTrue(upper == door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), "upper half is " + upper);
			helper.assertTrue(count(worker, Items.OAK_DOOR) == 0, "door was not consumed");
		});
	}

	@GameTest(maxTicks = 400)
	public void placeDoorUpperBlockedFailsCleanly(GameTestHelper helper) {
		layFloor(helper);
		BlockPos rel = new BlockPos(5, 1, 5);
		helper.setBlock(rel.above(), Blocks.STONE);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.OAK_DOOR));
		worker.tasks().enqueue(PlaceTask.of(helper.absolutePos(rel), Blocks.OAK_DOOR));

		helper.succeedWhen(() -> {
			assertFailedWith(helper, outcomes, FailureReason.NOT_REPLACEABLE);
			helper.assertTrue(outcomes.get(0).outcome() instanceof TaskOutcome.Failed f && f.detail().contains("upper half"),
					"failure does not name the upper half: " + outcomes.get(0).outcome());
			helper.assertBlockPresent(Blocks.AIR, rel);
			helper.assertBlockPresent(Blocks.STONE, rel.above());
			helper.assertTrue(count(worker, Items.OAK_DOOR) == 1, "door was consumed");
		});
	}

	@GameTest(maxTicks = 400)
	public void placeDoorUpperHalfRequestNormalizes(GameTestHelper helper) {
		layFloor(helper);
		BlockPos rel = new BlockPos(5, 1, 5);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.OAK_DOOR));
		BlockState upperRequest = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
		worker.tasks().enqueue(new PlaceTask(helper.absolutePos(rel), upperRequest));

		helper.succeedWhen(() -> {
			assertSucceeded(helper, outcomes);
			BlockState lower = helper.getBlockState(rel);
			BlockState upper = helper.getBlockState(rel.above());
			helper.assertTrue(lower == upperRequest.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), "lower half is " + lower);
			helper.assertTrue(upper == upperRequest, "upper half is " + upper);
		});
	}

	@GameTest(maxTicks = 400)
	public void placeBedWhole(GameTestHelper helper) {
		layFloor(helper);
		BlockPos rel = new BlockPos(5, 1, 5);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		Item bedItem = Items.BED.pick(DyeColor.RED);
		worker.insertOrDrop(new ItemStack(bedItem));
		BlockState bed = Blocks.BED.pick(DyeColor.RED).defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
		BlockPos foot = helper.absolutePos(rel);
		worker.tasks().enqueue(new PlaceTask(foot, bed));

		helper.succeedWhen(() -> {
			assertSucceeded(helper, outcomes);
			BlockState footState = helper.getLevel().getBlockState(foot);
			BlockState headState = helper.getLevel().getBlockState(foot.north());
			helper.assertTrue(footState == bed.setValue(BedBlock.PART, BedPart.FOOT), "foot is " + footState);
			helper.assertTrue(headState == bed.setValue(BedBlock.PART, BedPart.HEAD), "head is " + headState);
			helper.assertTrue(count(worker, bedItem) == 0, "bed was not consumed");
		});
	}

	@GameTest(maxTicks = 400)
	public void placeBedHeadBlockedFailsCleanly(GameTestHelper helper) {
		layFloor(helper);
		BlockPos rel = new BlockPos(5, 1, 5);
		BlockPos headRel = rel.north();
		helper.setBlock(headRel, Blocks.STONE);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		Item bedItem = Items.BED.pick(DyeColor.RED);
		worker.insertOrDrop(new ItemStack(bedItem));
		BlockState bed = Blocks.BED.pick(DyeColor.RED).defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
		worker.tasks().enqueue(new PlaceTask(helper.absolutePos(rel), bed));

		helper.succeedWhen(() -> {
			assertFailedWith(helper, outcomes, FailureReason.NOT_REPLACEABLE);
			helper.assertTrue(outcomes.get(0).outcome() instanceof TaskOutcome.Failed f && f.detail().contains("head"),
					"failure does not name the head: " + outcomes.get(0).outcome());
			helper.assertBlockPresent(Blocks.AIR, rel);
			helper.assertBlockPresent(Blocks.STONE, headRel);
			helper.assertTrue(count(worker, bedItem) == 1, "bed was consumed");
		});
	}

	/** Lays dirt under {@code rel}, since tall plants cannot stand on the stone floor. */
	private static void soilUnder(GameTestHelper helper, BlockPos rel) {
		helper.setBlock(rel.below(), Blocks.DIRT);
	}

	@GameTest(maxTicks = 400)
	public void placeSunflowerWhole(GameTestHelper helper) {
		layFloor(helper);
		BlockPos rel = new BlockPos(5, 1, 5);
		soilUnder(helper, rel);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.SUNFLOWER, 2));
		worker.tasks().enqueue(PlaceTask.of(helper.absolutePos(rel), Blocks.SUNFLOWER));

		helper.succeedWhen(() -> {
			assertSucceeded(helper, outcomes);
			BlockState lower = helper.getBlockState(rel);
			BlockState upper = helper.getBlockState(rel.above());
			helper.assertTrue(lower.is(Blocks.SUNFLOWER) && lower.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER,
					"lower half is " + lower);
			helper.assertTrue(upper.is(Blocks.SUNFLOWER) && upper.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER,
					"upper half is " + upper);
			helper.assertTrue(count(worker, Items.SUNFLOWER) == 1, "expected one sunflower used, " + count(worker, Items.SUNFLOWER) + " left");
		});
	}

	@GameTest(maxTicks = 400)
	public void placeTallPlantUpperBlockedFailsCleanly(GameTestHelper helper) {
		layFloor(helper);
		BlockPos rel = new BlockPos(5, 1, 5);
		soilUnder(helper, rel);
		helper.setBlock(rel.above(), Blocks.STONE);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.SUNFLOWER));
		worker.tasks().enqueue(PlaceTask.of(helper.absolutePos(rel), Blocks.SUNFLOWER));

		helper.succeedWhen(() -> {
			assertFailedWith(helper, outcomes, FailureReason.NOT_REPLACEABLE);
			helper.assertTrue(outcomes.get(0).outcome() instanceof TaskOutcome.Failed f && f.detail().contains("upper half"),
					"failure does not name the upper half: " + outcomes.get(0).outcome());
			helper.assertBlockPresent(Blocks.AIR, rel);
			helper.assertBlockPresent(Blocks.STONE, rel.above());
			helper.assertTrue(count(worker, Items.SUNFLOWER) == 1, "sunflower was consumed");
		});
	}

	@GameTest(maxTicks = 400)
	public void placeTallGrassOverMatchingUpperSucceeds(GameTestHelper helper) {
		layFloor(helper);
		BlockPos rel = new BlockPos(5, 1, 5);
		soilUnder(helper, rel);
		BlockState upper = Blocks.TALL_GRASS.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
		// An orphan upper half: setting the same state there again makes setBlock return false.
		helper.setBlock(rel.above(), upper);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.TALL_GRASS, 2));
		worker.tasks().enqueue(PlaceTask.of(helper.absolutePos(rel), Blocks.TALL_GRASS));

		helper.succeedWhen(() -> {
			assertSucceeded(helper, outcomes);
			BlockState lower = helper.getBlockState(rel);
			helper.assertTrue(lower == upper.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER), "lower half is " + lower);
			helper.assertTrue(helper.getBlockState(rel.above()) == upper, "upper half is " + helper.getBlockState(rel.above()));
			helper.assertTrue(count(worker, Items.TALL_GRASS) == 1, "expected one tall grass used, " + count(worker, Items.TALL_GRASS) + " left");
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

	/** One crack overlay update a mine task sent. */
	private record Crack(BlockPos pos, int stage) {
	}

	/** A mine task whose crack overlay updates are recorded into {@code cracks}, then sent as usual. */
	private static MineTask recordingMine(BlockPos pos, List<Crack> cracks) {
		return new MineTask(pos).withOverlay((worker, at, stage) -> {
			cracks.add(new Crack(at, stage));
			MineTask.CrackOverlay.LEVEL.send(worker, at, stage);
		});
	}

	private static void assertBreakStarted(GameTestHelper helper, List<Crack> cracks, BlockPos pos) {
		helper.assertTrue(cracks.stream().anyMatch(c -> c.pos().equals(pos) && c.stage() >= 0), "break not started yet: " + cracks);
	}

	private static void assertCrackCleared(GameTestHelper helper, List<Crack> cracks, BlockPos pos) {
		helper.assertTrue(!cracks.isEmpty() && cracks.get(cracks.size() - 1).equals(new Crack(pos, -1)),
				"crack overlay not cleared last: " + cracks);
	}

	@GameTest(maxTicks = 400)
	public void mineCancelMidBreakClearsCrack(GameTestHelper helper) {
		layFloor(helper);
		BlockPos rel = new BlockPos(2, 1, 1);
		helper.setBlock(rel, Blocks.STONE);
		BlockPos abs = helper.absolutePos(rel);
		List<Recorded> outcomes = new ArrayList<>();
		List<Crack> cracks = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.WOODEN_PICKAXE));
		worker.tasks().enqueue(recordingMine(abs, cracks));

		helper.startSequence()
				.thenWaitUntil(() -> assertBreakStarted(helper, cracks, abs))
				.thenExecute(() -> {
					helper.assertTrue(outcomes.isEmpty(), "mine finished before the cancel: " + outcomes);
					worker.tasks().cancelCurrent(worker);
					helper.assertTrue(outcomes.size() == 1 && outcomes.get(0).outcome() instanceof TaskOutcome.Cancelled,
							"expected one Cancelled outcome, got " + outcomes);
					helper.assertBlockPresent(Blocks.STONE, rel);
					assertCrackCleared(helper, cracks, abs);
				})
				.thenSucceed();
	}

	@GameTest(maxTicks = 400)
	public void mineFailsWhenTargetChangedMidBreak(GameTestHelper helper) {
		layFloor(helper);
		BlockPos rel = new BlockPos(2, 1, 1);
		helper.setBlock(rel, Blocks.STONE);
		BlockPos abs = helper.absolutePos(rel);
		List<Recorded> outcomes = new ArrayList<>();
		List<Crack> cracks = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.WOODEN_PICKAXE));
		worker.tasks().enqueue(recordingMine(abs, cracks));

		helper.startSequence()
				// A crack stage is only sent once the approach has arrived and break progress has built up.
				.thenWaitUntil(() -> assertBreakStarted(helper, cracks, abs))
				.thenExecute(() -> {
					helper.assertTrue(outcomes.isEmpty(), "mine finished before the block was swapped: " + outcomes);
					helper.setBlock(rel, Blocks.COBBLESTONE);
				})
				.thenWaitUntil(() -> assertFailedWith(helper, outcomes, FailureReason.TARGET_CHANGED))
				.thenExecute(() -> {
					helper.assertBlockPresent(Blocks.COBBLESTONE, rel);
					assertCrackCleared(helper, cracks, abs);
				})
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
		worker.tasks().enqueue(PlaceTask.of(helper.absolutePos(placeRel), Blocks.DIRT));

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
		worker.tasks().enqueue(PlaceTask.of(abs, Blocks.DIRT));
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

	@GameTest(maxTicks = 200)
	public void offlineCommanderSkippedAfterReload(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		// Never in the player list, like a commander who has logged off since the order.
		UUID offline = UUID.randomUUID();
		worker.addCommander(offline);

		WorkerEntity loaded = roundTrip(helper, worker);
		// The copy has the same UUID, so the original must go before the copy can be added.
		worker.discard();
		helper.assertTrue(helper.getLevel().addFreshEntity(loaded), "could not add the reloaded worker");
		helper.assertTrue(loaded.commanders().contains(offline), "commander lost on reload: " + loaded.commanders());
		List<Recorded> outcomes = new ArrayList<>();
		loaded.tasks().addListener((task, outcome) -> outcomes.add(new Recorded(task, outcome)));
		// The commander notification listener was added in the constructor, so it runs before this one.
		loaded.tasks().enqueue(new MoveToTask(helper.absolutePos(WORKER_START.east())));

		helper.succeedWhen(() -> {
			assertSucceeded(helper, outcomes);
			helper.assertTrue(loaded.commanders().contains(offline), "commander dropped: " + loaded.commanders());
		});
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

	/** Far enough from {@link #WORKER_START} that the worker has to walk to reach it. */
	private static final BlockPos CONTAINER = new BlockPos(6, 1, 6);

	private static ChestBlockEntity chestAt(GameTestHelper helper, BlockPos rel) {
		return helper.getBlockEntity(rel, ChestBlockEntity.class);
	}

	private static ChestBlockEntity placeChest(GameTestHelper helper, BlockPos rel) {
		helper.setBlock(rel, Blocks.CHEST);
		return chestAt(helper, rel);
	}

	private static void fill(Container container, ItemStack stack) {
		for (int i = 0; i < container.getContainerSize(); i++) {
			container.setItem(i, stack.copy());
		}
	}

	private static void assertSucceeded(GameTestHelper helper, List<Recorded> outcomes) {
		helper.assertTrue(outcomes.size() == 1, "task not finished yet");
		helper.assertTrue(outcomes.get(0).outcome() instanceof TaskOutcome.Succeeded,
				"task did not succeed: " + outcomes.get(0).outcome());
	}

	@GameTest(maxTicks = 400)
	public void depositAllIntoChest(GameTestHelper helper) {
		layFloor(helper);
		ChestBlockEntity chest = placeChest(helper, CONTAINER);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.COBBLESTONE, 10));
		worker.insertOrDrop(new ItemStack(Items.DIRT, 3));
		worker.tasks().enqueue(new DepositTask(helper.absolutePos(CONTAINER), Optional.empty()));

		helper.succeedWhen(() -> {
			assertSucceeded(helper, outcomes);
			helper.assertTrue(chest.countItem(Items.COBBLESTONE) == 10, "expected 10 cobblestone in the chest");
			helper.assertTrue(chest.countItem(Items.DIRT) == 3, "expected 3 dirt in the chest");
			helper.assertTrue(worker.getInventory().isEmpty(), "worker still holds items");
		});
	}

	@GameTest(maxTicks = 400)
	public void depositOnlyGivenItem(GameTestHelper helper) {
		layFloor(helper);
		ChestBlockEntity chest = placeChest(helper, CONTAINER);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.COBBLESTONE, 10));
		worker.insertOrDrop(new ItemStack(Items.DIRT, 3));
		worker.tasks().enqueue(new DepositTask(helper.absolutePos(CONTAINER), Optional.of(BuiltInRegistries.ITEM.getKey(Items.DIRT))));

		helper.succeedWhen(() -> {
			assertSucceeded(helper, outcomes);
			helper.assertTrue(chest.countItem(Items.DIRT) == 3, "expected 3 dirt in the chest");
			helper.assertTrue(chest.countItem(Items.COBBLESTONE) == 0, "cobblestone was deposited");
			helper.assertTrue(count(worker, Items.COBBLESTONE) == 10, "worker lost cobblestone");
			helper.assertTrue(count(worker, Items.DIRT) == 0, "worker still holds dirt");
		});
	}

	@GameTest(maxTicks = 400)
	public void depositIntoDoubleChest(GameTestHelper helper) {
		layFloor(helper);
		// Facing north, a LEFT half connects east (clockwise) to the RIGHT half.
		BlockPos leftRel = new BlockPos(5, 1, 6);
		BlockPos rightRel = new BlockPos(6, 1, 6);
		BlockState chest = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH);
		helper.setBlock(leftRel, chest.setValue(ChestBlock.TYPE, ChestType.LEFT));
		helper.setBlock(rightRel, chest.setValue(ChestBlock.TYPE, ChestType.RIGHT));
		ChestBlockEntity left = chestAt(helper, leftRel);
		ChestBlockEntity right = chestAt(helper, rightRel);
		fill(left, new ItemStack(Items.COBBLESTONE, 64));
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.COBBLESTONE, 10));
		worker.tasks().enqueue(new DepositTask(helper.absolutePos(leftRel), Optional.empty()));

		helper.succeedWhen(() -> {
			assertSucceeded(helper, outcomes);
			helper.assertTrue(right.countItem(Items.COBBLESTONE) == 10,
					"expected 10 cobblestone in the right half, got " + right.countItem(Items.COBBLESTONE));
			helper.assertTrue(worker.getInventory().isEmpty(), "worker still holds items");
		});
	}

	@GameTest(maxTicks = 400)
	public void depositIntoBarrel(GameTestHelper helper) {
		layFloor(helper);
		helper.setBlock(CONTAINER, Blocks.BARREL);
		BarrelBlockEntity barrel = helper.getBlockEntity(CONTAINER, BarrelBlockEntity.class);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.DIRT, 7));
		worker.tasks().enqueue(new DepositTask(helper.absolutePos(CONTAINER), Optional.empty()));

		helper.succeedWhen(() -> {
			assertSucceeded(helper, outcomes);
			helper.assertTrue(barrel.countItem(Items.DIRT) == 7, "expected 7 dirt in the barrel");
			helper.assertTrue(worker.getInventory().isEmpty(), "worker still holds items");
		});
	}

	@GameTest(maxTicks = 400)
	public void depositIntoFullChestIsContainerFull(GameTestHelper helper) {
		layFloor(helper);
		ChestBlockEntity chest = placeChest(helper, CONTAINER);
		fill(chest, new ItemStack(Items.COBBLESTONE, 64));
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.DIRT, 5));
		worker.tasks().enqueue(new DepositTask(helper.absolutePos(CONTAINER), Optional.empty()));

		helper.succeedWhen(() -> {
			assertFailedWith(helper, outcomes, FailureReason.CONTAINER_FULL);
			helper.assertTrue(count(worker, Items.DIRT) == 5, "worker lost dirt");
			helper.assertTrue(chest.countItem(Items.DIRT) == 0, "dirt went into a full chest");
		});
	}

	@GameTest(maxTicks = 100)
	public void depositMissingItemIsMissingItem(GameTestHelper helper) {
		layFloor(helper);
		ChestBlockEntity chest = placeChest(helper, CONTAINER);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.COBBLESTONE, 4));
		worker.tasks().enqueue(new DepositTask(helper.absolutePos(CONTAINER), Optional.of(BuiltInRegistries.ITEM.getKey(Items.DIRT))));

		helper.succeedWhen(() -> {
			assertFailedWith(helper, outcomes, FailureReason.MISSING_ITEM);
			helper.assertTrue(chest.isEmpty(), "chest is not empty");
			helper.assertTrue(count(worker, Items.COBBLESTONE) == 4, "worker lost cobblestone");
		});
	}

	@GameTest(maxTicks = 400)
	public void depositItemLostWhileWalkingIsMissingItem(GameTestHelper helper) {
		layFloor(helper);
		ChestBlockEntity chest = placeChest(helper, CONTAINER);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.DIRT, 3));
		worker.tasks().enqueue(new DepositTask(helper.absolutePos(CONTAINER), Optional.of(BuiltInRegistries.ITEM.getKey(Items.DIRT))));

		helper.startSequence()
				// start() checks the inventory on the tick the task becomes current; take the dirt after that.
				.thenWaitUntil(() -> helper.assertTrue(worker.tasks().current().isPresent(), "deposit not started yet"))
				.thenExecute(() -> {
					helper.assertTrue(outcomes.isEmpty(), "deposit finished before the dirt was taken: " + outcomes);
					worker.getInventory().removeAllItems();
				})
				.thenWaitUntil(() -> assertFailedWith(helper, outcomes, FailureReason.MISSING_ITEM))
				.thenExecute(() -> helper.assertTrue(chest.isEmpty(), "chest is not empty"))
				.thenSucceed();
	}

	@GameTest(maxTicks = 400)
	public void depositAllWithEmptyInventorySucceeds(GameTestHelper helper) {
		layFloor(helper);
		ChestBlockEntity chest = placeChest(helper, CONTAINER);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.tasks().enqueue(new DepositTask(helper.absolutePos(CONTAINER), Optional.empty()));

		helper.succeedWhen(() -> {
			assertSucceeded(helper, outcomes);
			helper.assertTrue(chest.isEmpty(), "chest is not empty");
		});
	}

	@GameTest(maxTicks = 400)
	public void depositIntoNonContainerIsNotAContainer(GameTestHelper helper) {
		layFloor(helper);
		helper.setBlock(CONTAINER, Blocks.STONE);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.DIRT, 5));
		worker.tasks().enqueue(new DepositTask(helper.absolutePos(CONTAINER), Optional.empty()));

		helper.succeedWhen(() -> {
			assertFailedWith(helper, outcomes, FailureReason.NOT_A_CONTAINER);
			helper.assertTrue(count(worker, Items.DIRT) == 5, "worker lost dirt");
		});
	}

	@GameTest(maxTicks = 400)
	public void withdrawFromChest(GameTestHelper helper) {
		layFloor(helper);
		ChestBlockEntity chest = placeChest(helper, CONTAINER);
		chest.setItem(3, new ItemStack(Items.COBBLESTONE, 10));
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.tasks().enqueue(new WithdrawTask(helper.absolutePos(CONTAINER), BuiltInRegistries.ITEM.getKey(Items.COBBLESTONE), 5));

		helper.succeedWhen(() -> {
			assertSucceeded(helper, outcomes);
			helper.assertTrue(chest.countItem(Items.COBBLESTONE) == 5, "expected 5 cobblestone left in the chest");
			helper.assertTrue(count(worker, Items.COBBLESTONE) == 5, "expected 5 cobblestone on the worker");
		});
	}

	@GameTest(maxTicks = 400)
	public void withdrawMissingItemIsItemNotFound(GameTestHelper helper) {
		layFloor(helper);
		ChestBlockEntity chest = placeChest(helper, CONTAINER);
		chest.setItem(0, new ItemStack(Items.COBBLESTONE, 10));
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.tasks().enqueue(new WithdrawTask(helper.absolutePos(CONTAINER), BuiltInRegistries.ITEM.getKey(Items.DIRT), 1));

		helper.succeedWhen(() -> {
			assertFailedWith(helper, outcomes, FailureReason.ITEM_NOT_FOUND);
			helper.assertTrue(worker.getInventory().isEmpty(), "worker took something");
			helper.assertTrue(chest.countItem(Items.COBBLESTONE) == 10, "chest lost cobblestone");
		});
	}

	@GameTest(maxTicks = 400)
	public void withdrawMoreThanAvailableIsItemNotFound(GameTestHelper helper) {
		layFloor(helper);
		ChestBlockEntity chest = placeChest(helper, CONTAINER);
		chest.setItem(0, new ItemStack(Items.COBBLESTONE, 2));
		chest.setItem(5, new ItemStack(Items.COBBLESTONE, 1));
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.tasks().enqueue(new WithdrawTask(helper.absolutePos(CONTAINER), BuiltInRegistries.ITEM.getKey(Items.COBBLESTONE), 5));

		helper.succeedWhen(() -> {
			assertFailedWith(helper, outcomes, FailureReason.ITEM_NOT_FOUND);
			helper.assertTrue(outcomes.get(0).outcome() instanceof TaskOutcome.Failed f && f.detail().contains("took 3 of 5"),
					"detail does not say what was taken: " + outcomes.get(0).outcome());
			helper.assertTrue(count(worker, Items.COBBLESTONE) == 3, "worker did not keep the partial amount");
			helper.assertTrue(chest.countItem(Items.COBBLESTONE) == 0, "chest still holds cobblestone");
		});
	}

	@GameTest(maxTicks = 400)
	public void withdrawFromNonContainerIsNotAContainer(GameTestHelper helper) {
		layFloor(helper);
		helper.setBlock(CONTAINER, Blocks.STONE);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.tasks().enqueue(new WithdrawTask(helper.absolutePos(CONTAINER), BuiltInRegistries.ITEM.getKey(Items.STONE), 1));

		helper.succeedWhen(() -> {
			assertFailedWith(helper, outcomes, FailureReason.NOT_A_CONTAINER);
			helper.assertTrue(worker.getInventory().isEmpty(), "worker took something");
		});
	}

	@GameTest(maxTicks = 400)
	public void withdrawIntoFullWorkerInventoryIsInventoryFull(GameTestHelper helper) {
		layFloor(helper);
		ChestBlockEntity chest = placeChest(helper, CONTAINER);
		chest.setItem(0, new ItemStack(Items.COBBLESTONE, 20));
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		// Room for exactly 4 cobblestone.
		worker.insertOrDrop(new ItemStack(Items.COBBLESTONE, 60));
		for (int i = 1; i < WorkerEntity.INVENTORY_SIZE; i++) {
			worker.insertOrDrop(new ItemStack(Items.DIRT, 64));
		}
		worker.tasks().enqueue(new WithdrawTask(helper.absolutePos(CONTAINER), BuiltInRegistries.ITEM.getKey(Items.COBBLESTONE), 10));

		helper.succeedWhen(() -> {
			assertFailedWith(helper, outcomes, FailureReason.INVENTORY_FULL);
			helper.assertTrue(count(worker, Items.COBBLESTONE) == 64, "expected 64 cobblestone on the worker");
			helper.assertTrue(chest.countItem(Items.COBBLESTONE) == 16, "expected 16 cobblestone left in the chest");
			helper.assertTrue(itemsNear(helper, worker.position(), 3.0, Items.COBBLESTONE) == 0, "cobblestone was dropped");
		});
	}

	@GameTest(maxTicks = 400)
	public void withdrawRenamedItemThatDoesNotFitStaysInChest(GameTestHelper helper) {
		layFloor(helper);
		ChestBlockEntity chest = placeChest(helper, CONTAINER);
		ItemStack renamed = new ItemStack(Items.COBBLESTONE, 10);
		renamed.set(DataComponents.CUSTOM_NAME, Component.literal("Special"));
		chest.setItem(0, renamed.copy());
		chest.setItem(1, new ItemStack(Items.COBBLESTONE, 5));
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		// No empty slot: only plain cobblestone fits, in the one partial stack.
		worker.insertOrDrop(new ItemStack(Items.COBBLESTONE, 32));
		for (int i = 1; i < WorkerEntity.INVENTORY_SIZE; i++) {
			worker.insertOrDrop(new ItemStack(Items.DIRT, 64));
		}
		worker.tasks().enqueue(new WithdrawTask(helper.absolutePos(CONTAINER), BuiltInRegistries.ITEM.getKey(Items.COBBLESTONE), 10));

		helper.succeedWhen(() -> {
			assertFailedWith(helper, outcomes, FailureReason.INVENTORY_FULL);
			helper.assertTrue(outcomes.get(0).outcome() instanceof TaskOutcome.Failed f && f.detail().contains("took 5 of 10"),
					"detail does not count what was taken: " + outcomes.get(0).outcome());
			helper.assertTrue(count(worker, Items.COBBLESTONE) == 37, "expected 37 cobblestone on the worker");
			helper.assertTrue(ItemStack.matches(chest.getItem(0), renamed), "renamed cobblestone left the chest: " + chest.getItem(0));
			helper.assertTrue(chest.getItem(1).isEmpty(), "plain cobblestone stayed in the chest");
			helper.assertTrue(itemsNear(helper, worker.position(), 3.0, Items.COBBLESTONE) == 0, "cobblestone was dropped");
		});
	}

	private static void assertDetail(GameTestHelper helper, List<Recorded> outcomes, String detail) {
		helper.assertTrue(outcomes.get(0).outcome() instanceof TaskOutcome.Failed f && f.detail().equals(detail),
				"expected detail \"" + detail + "\": " + outcomes.get(0).outcome());
	}

	@GameTest(maxTicks = 400)
	public void depositPartlyFitsIsContainerFull(GameTestHelper helper) {
		layFloor(helper);
		ChestBlockEntity chest = placeChest(helper, CONTAINER);
		fill(chest, new ItemStack(Items.STONE, 64));
		// Room for exactly 4 cobblestone, in the last slot.
		chest.setItem(chest.getContainerSize() - 1, new ItemStack(Items.COBBLESTONE, 60));
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.COBBLESTONE, 10));
		worker.tasks().enqueue(new DepositTask(helper.absolutePos(CONTAINER), Optional.of(BuiltInRegistries.ITEM.getKey(Items.COBBLESTONE))));

		helper.succeedWhen(() -> {
			assertFailedWith(helper, outcomes, FailureReason.CONTAINER_FULL);
			assertDetail(helper, outcomes, "deposited 4, 6 left");
			helper.assertTrue(count(worker, Items.COBBLESTONE) == 6, "expected 6 cobblestone on the worker");
			helper.assertTrue(chest.getItem(chest.getContainerSize() - 1).getCount() == 64, "last slot is not topped up");
			helper.assertTrue(itemsNear(helper, worker.position(), 3.0, Items.COBBLESTONE) == 0, "cobblestone was dropped");
		});
	}

	@GameTest(maxTicks = 400)
	public void withdrawFromDoubleChest(GameTestHelper helper) {
		layFloor(helper);
		// Facing north, a LEFT half connects east (clockwise) to the RIGHT half at CONTAINER.
		BlockPos leftRel = new BlockPos(5, 1, 6);
		BlockState chest = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH);
		helper.setBlock(leftRel, chest.setValue(ChestBlock.TYPE, ChestType.LEFT));
		helper.setBlock(CONTAINER, chest.setValue(ChestBlock.TYPE, ChestType.RIGHT));
		ChestBlockEntity left = chestAt(helper, leftRel);
		ChestBlockEntity right = chestAt(helper, CONTAINER);
		// Only the half the task is not aimed at holds the items.
		left.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.tasks().enqueue(new WithdrawTask(helper.absolutePos(CONTAINER), BuiltInRegistries.ITEM.getKey(Items.COBBLESTONE), 64));

		helper.succeedWhen(() -> {
			assertSucceeded(helper, outcomes);
			helper.assertTrue(count(worker, Items.COBBLESTONE) == 64, "expected 64 cobblestone on the worker");
			helper.assertTrue(left.isEmpty(), "left half still holds items");
			helper.assertTrue(right.isEmpty(), "right half holds items");
		});
	}

	@GameTest(maxTicks = 400)
	public void depositShulkerIntoShulkerIsItemRefused(GameTestHelper helper) {
		layFloor(helper);
		helper.setBlock(CONTAINER, Blocks.SHULKER_BOX);
		ShulkerBoxBlockEntity box = helper.getBlockEntity(CONTAINER, ShulkerBoxBlockEntity.class);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.SHULKER_BOX));
		worker.tasks().enqueue(new DepositTask(helper.absolutePos(CONTAINER), Optional.empty()));

		helper.succeedWhen(() -> {
			assertFailedWith(helper, outcomes, FailureReason.ITEM_REFUSED);
			assertDetail(helper, outcomes, "deposited 0, 1 not accepted");
			helper.assertTrue(count(worker, Items.SHULKER_BOX) == 1, "worker lost the shulker box");
			helper.assertTrue(box.isEmpty(), "shulker box went into a shulker box");
		});
	}

	@GameTest(maxTicks = 400)
	public void depositIntoShulkerBox(GameTestHelper helper) {
		layFloor(helper);
		helper.setBlock(CONTAINER, Blocks.SHULKER_BOX);
		ShulkerBoxBlockEntity box = helper.getBlockEntity(CONTAINER, ShulkerBoxBlockEntity.class);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.COBBLESTONE, 10));
		worker.tasks().enqueue(new DepositTask(helper.absolutePos(CONTAINER), Optional.empty()));

		helper.succeedWhen(() -> {
			assertSucceeded(helper, outcomes);
			helper.assertTrue(box.countItem(Items.COBBLESTONE) == 10, "expected 10 cobblestone in the shulker box");
			helper.assertTrue(worker.getInventory().isEmpty(), "worker still holds items");
		});
	}

	@GameTest(maxTicks = 400)
	public void depositDirtIntoBrewingStandIsItemRefused(GameTestHelper helper) {
		layFloor(helper);
		helper.setBlock(CONTAINER, Blocks.BREWING_STAND);
		BrewingStandBlockEntity stand = helper.getBlockEntity(CONTAINER, BrewingStandBlockEntity.class);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.DIRT, 16));
		worker.tasks().enqueue(new DepositTask(helper.absolutePos(CONTAINER), Optional.empty()));

		helper.succeedWhen(() -> {
			assertFailedWith(helper, outcomes, FailureReason.ITEM_REFUSED);
			assertDetail(helper, outcomes, "deposited 0, 16 not accepted");
			helper.assertTrue(count(worker, Items.DIRT) == 16, "worker lost dirt");
			helper.assertTrue(stand.isEmpty(), "dirt went into the brewing stand");
		});
	}

	@GameTest(maxTicks = 400)
	public void depositMixedIntoBrewingStandRefusesOnlyDirt(GameTestHelper helper) {
		layFloor(helper);
		helper.setBlock(CONTAINER, Blocks.BREWING_STAND);
		BrewingStandBlockEntity stand = helper.getBlockEntity(CONTAINER, BrewingStandBlockEntity.class);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.BLAZE_POWDER));
		worker.insertOrDrop(new ItemStack(Items.DIRT, 16));
		worker.tasks().enqueue(new DepositTask(helper.absolutePos(CONTAINER), Optional.empty()));

		helper.succeedWhen(() -> {
			assertFailedWith(helper, outcomes, FailureReason.ITEM_REFUSED);
			assertDetail(helper, outcomes, "deposited 1, 16 not accepted");
			helper.assertTrue(stand.countItem(Items.BLAZE_POWDER) == 1, "blaze powder is not in the brewing stand");
			helper.assertTrue(count(worker, Items.DIRT) == 16, "worker lost dirt");
		});
	}

	@GameTest(maxTicks = 400)
	public void depositContainerBrokenWhileWalkingIsNotAContainer(GameTestHelper helper) {
		layFloor(helper);
		placeChest(helper, CONTAINER);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.DIRT, 5));
		worker.tasks().enqueue(new DepositTask(helper.absolutePos(CONTAINER), Optional.empty()));

		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(worker.tasks().current().isPresent(), "deposit not started yet"))
				.thenExecute(() -> {
					helper.assertTrue(outcomes.isEmpty(), "deposit finished before the chest was broken: " + outcomes);
					helper.setBlock(CONTAINER, Blocks.AIR);
				})
				.thenWaitUntil(() -> assertFailedWith(helper, outcomes, FailureReason.NOT_A_CONTAINER))
				.thenExecute(() -> helper.assertTrue(count(worker, Items.DIRT) == 5, "worker lost dirt"))
				.thenSucceed();
	}

	@GameTest(maxTicks = 400)
	public void withdrawContainerReplacedWhileWalkingIsNotAContainer(GameTestHelper helper) {
		layFloor(helper);
		ChestBlockEntity chest = placeChest(helper, CONTAINER);
		chest.setItem(0, new ItemStack(Items.COBBLESTONE, 10));
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.tasks().enqueue(new WithdrawTask(helper.absolutePos(CONTAINER), BuiltInRegistries.ITEM.getKey(Items.COBBLESTONE), 5));

		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(worker.tasks().current().isPresent(), "withdraw not started yet"))
				.thenExecute(() -> {
					helper.assertTrue(outcomes.isEmpty(), "withdraw finished before the chest was replaced: " + outcomes);
					// Empty it first so the replaced chest drops nothing the worker could pick up.
					chest.clearContent();
					helper.setBlock(CONTAINER, Blocks.STONE);
				})
				.thenWaitUntil(() -> assertFailedWith(helper, outcomes, FailureReason.NOT_A_CONTAINER))
				.thenExecute(() -> helper.assertTrue(worker.getInventory().isEmpty(), "worker took something"))
				.thenSucceed();
	}

	@GameTest
	public void saveLoadKeepsHaulTasks(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		BlockPos abs = helper.absolutePos(CONTAINER);
		worker.tasks().enqueue(new DepositTask(abs, Optional.empty()));
		worker.tasks().enqueue(new DepositTask(abs, Optional.of(BuiltInRegistries.ITEM.getKey(Items.DIRT))));
		worker.tasks().enqueue(new WithdrawTask(abs, BuiltInRegistries.ITEM.getKey(Items.COBBLESTONE), 12));

		WorkerEntity loaded = roundTrip(helper, worker);

		Codec<List<Task<WorkerEntity>>> listCodec = Codec.list(TaskTypes.CODEC);
		var savedQueue = listCodec.encodeStart(JsonOps.INSTANCE, worker.tasks().snapshot()).getOrThrow();
		var loadedQueue = listCodec.encodeStart(JsonOps.INSTANCE, loaded.tasks().snapshot()).getOrThrow();
		helper.assertTrue(loaded.tasks().snapshot().size() == 3, "expected 3 restored tasks, got " + loaded.tasks().snapshot());
		helper.assertTrue(savedQueue.equals(loadedQueue), "queue differs: " + savedQueue + " vs " + loadedQueue);
		helper.succeed();
	}

	private static final BlockPos DIG_FROM = new BlockPos(3, 1, 3);
	private static final BlockPos DIG_TO = new BlockPos(5, 2, 5);

	/** Fills the box between {@code from} and {@code to} (relative) with {@code block}. */
	private static void fill(GameTestHelper helper, BlockPos from, BlockPos to, Block block) {
		BlockPos.betweenClosed(from, to).forEach(pos -> helper.setBlock(pos, block));
	}

	private static void assertAllAir(GameTestHelper helper, BlockPos from, BlockPos to, BlockPos except) {
		for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
			if (!pos.equals(except)) {
				helper.assertBlockPresent(Blocks.AIR, pos);
			}
		}
	}

	/** Cobblestone in the worker's inventory plus what lies around the test area. */
	private static int cobblestoneAround(GameTestHelper helper, WorkerEntity worker) {
		return count(worker, Items.COBBLESTONE) + itemsNear(helper, helper.absoluteVec(new Vec3(4, 1, 4)), 8.0, Items.COBBLESTONE);
	}

	private static String succeededNote(GameTestHelper helper, List<Recorded> outcomes) {
		helper.assertTrue(outcomes.size() == 1, "dig not finished yet");
		if (outcomes.get(0).outcome() instanceof TaskOutcome.Succeeded s) {
			return s.note();
		}
		helper.fail("dig did not succeed: " + outcomes.get(0).outcome());
		return "";
	}

	@GameTest(maxTicks = 2400)
	public void digsStoneArea(GameTestHelper helper) {
		layFloor(helper);
		fill(helper, DIG_FROM, DIG_TO, Blocks.STONE);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.WOODEN_PICKAXE));
		worker.tasks().enqueue(new DigAreaTask(helper.absolutePos(DIG_FROM), helper.absolutePos(DIG_TO)));

		helper.succeedWhen(() -> {
			String note = succeededNote(helper, outcomes);
			helper.assertTrue(note.equals("dug 18 blocks"), "unexpected note: " + note);
			assertAllAir(helper, DIG_FROM, DIG_TO, null);
			int drops = cobblestoneAround(helper, worker);
			helper.assertTrue(drops == 18, "expected 18 cobblestone in the inventory or nearby, got " + drops);
		});
	}

	@GameTest(maxTicks = 2400)
	public void digAreaSkipsBedrock(GameTestHelper helper) {
		layFloor(helper);
		fill(helper, DIG_FROM, DIG_TO, Blocks.STONE);
		BlockPos bedrock = new BlockPos(4, 2, 4);
		helper.setBlock(bedrock, Blocks.BEDROCK);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.WOODEN_PICKAXE));
		worker.tasks().enqueue(new DigAreaTask(helper.absolutePos(DIG_FROM), helper.absolutePos(DIG_TO)));

		helper.succeedWhen(() -> {
			String note = succeededNote(helper, outcomes);
			helper.assertTrue(note.startsWith("dug 17 blocks; skipped: Bedrock @ " + helper.absolutePos(bedrock).toShortString() + " (UNBREAKABLE)"),
					"bedrock not reported: " + note);
			helper.assertBlockPresent(Blocks.BEDROCK, bedrock);
			assertAllAir(helper, DIG_FROM, DIG_TO, bedrock);
			int drops = cobblestoneAround(helper, worker);
			helper.assertTrue(drops == 17, "expected 17 cobblestone in the inventory or nearby, got " + drops);
		});
	}

	@GameTest(maxTicks = 2400)
	public void digAreaWorkerStartsInside(GameTestHelper helper) {
		layFloor(helper);
		// A full layer the worker stands on, and a ring around it at its feet; it has to step off to finish.
		BlockPos from = new BlockPos(1, 1, 1);
		BlockPos to = new BlockPos(3, 2, 3);
		BlockPos feet = new BlockPos(2, 2, 2);
		fill(helper, from, to, Blocks.STONE);
		helper.setBlock(feet, Blocks.AIR);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, feet);
		worker.tasks().addListener((task, outcome) -> outcomes.add(new Recorded(task, outcome)));
		worker.insertOrDrop(new ItemStack(Items.STONE_PICKAXE));
		worker.tasks().enqueue(new DigAreaTask(helper.absolutePos(from), helper.absolutePos(to)));

		helper.succeedWhen(() -> {
			String note = succeededNote(helper, outcomes);
			helper.assertTrue(note.equals("dug 17 blocks"), "unexpected note: " + note);
			assertAllAir(helper, from, to, null);
		});
	}

	@GameTest(maxTicks = 1200)
	public void digAreaLeavesWater(GameTestHelper helper) {
		layFloor(helper);
		BlockPos from = new BlockPos(3, 1, 3);
		BlockPos to = new BlockPos(5, 1, 5);
		BlockPos water = new BlockPos(5, 1, 5);
		fill(helper, from, to, Blocks.STONE);
		helper.setBlock(water, Blocks.WATER);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.STONE_PICKAXE));
		worker.tasks().enqueue(new DigAreaTask(helper.absolutePos(from), helper.absolutePos(to)));

		helper.succeedWhen(() -> {
			String note = succeededNote(helper, outcomes);
			helper.assertTrue(note.equals("dug 8 blocks"), "unexpected note: " + note);
			helper.assertTrue(helper.getBlockState(water).getFluidState().isSource(), "water source is gone");
			// The freed water flows into the dug blocks, so they are air or water.
			for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
				helper.assertTrue(helper.getBlockState(pos).isAir() || helper.getBlockState(pos).is(Blocks.WATER),
						"not dug at " + pos.toShortString() + ": " + helper.getBlockState(pos));
			}
		});
	}

	@GameTest(maxTicks = 400)
	public void digAreaOutOfReachIsUnreachable(GameTestHelper helper) {
		layFloor(helper);
		// Floating too high to be reached from the floor, with nothing to stand on near it.
		BlockPos stone = new BlockPos(6, 6, 6);
		helper.setBlock(stone, Blocks.STONE);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.STONE_PICKAXE));
		BlockPos abs = helper.absolutePos(stone);
		worker.tasks().enqueue(new DigAreaTask(abs, abs));

		helper.succeedWhen(() -> {
			assertFailedWith(helper, outcomes, FailureReason.UNREACHABLE);
			helper.assertBlockPresent(Blocks.STONE, stone);
		});
	}

	@GameTest(maxTicks = 400)
	public void digAreaNeverStandsOnItsTarget(GameTestHelper helper) {
		layFloor(helper);
		// A floating block whose only stand spot is on top of it, reachable by dropping off a walkway
		// whose own spots are too high to reach it. Slabs under it keep the floor from being a stand spot.
		BlockPos stone = new BlockPos(4, 3, 4);
		fill(helper, new BlockPos(0, 0, 0), new BlockPos(7, 0, 7), Blocks.STONE_SLAB);
		fill(helper, new BlockPos(1, 5, 4), new BlockPos(3, 5, 4), Blocks.STONE);
		helper.setBlock(stone, Blocks.STONE);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, new BlockPos(2, 6, 4));
		worker.tasks().addListener((task, outcome) -> outcomes.add(new Recorded(task, outcome)));
		worker.insertOrDrop(new ItemStack(Items.STONE_PICKAXE));
		BlockPos abs = helper.absolutePos(stone);
		worker.tasks().enqueue(new DigAreaTask(abs, abs));

		helper.succeedWhen(() -> {
			assertFailedWith(helper, outcomes, FailureReason.UNREACHABLE);
			helper.assertBlockPresent(Blocks.STONE, stone);
			helper.assertTrue(worker.getY() >= helper.absoluteVec(new Vec3(0, 6, 0)).y,
					"worker left the walkway: " + worker.position());
		});
	}

	@GameTest
	public void saveLoadKeepsDigArea(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		BlockPos from = helper.absolutePos(DIG_TO);
		BlockPos to = helper.absolutePos(DIG_FROM);
		worker.tasks().enqueue(new DigAreaTask(from, to));

		WorkerEntity loaded = roundTrip(helper, worker);

		List<Task<WorkerEntity>> queue = loaded.tasks().snapshot();
		helper.assertTrue(queue.size() == 1 && queue.get(0) instanceof DigAreaTask d && d.from().equals(from) && d.to().equals(to),
				"expected the dig area back, got " + queue);
		helper.succeed();
	}

	/**
	 * Registers a structure template {@code rabodepalha:gametest/<name>} holding {@code blocks} (keyed by
	 * position within the template), as if saved by a structure block. Names must be unique per test.
	 */
	static Identifier blueprint(GameTestHelper helper, String name, Map<BlockPos, BlockState> blocks) {
		Identifier id = Identifier.fromNamespaceAndPath("rabodepalha", "gametest/" + name);
		ListTag palette = new ListTag();
		ListTag blockList = new ListTag();
		List<BlockState> seen = new ArrayList<>();
		int sizeX = 1, sizeY = 1, sizeZ = 1;
		for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
			BlockPos pos = entry.getKey();
			int index = seen.indexOf(entry.getValue());
			if (index < 0) {
				index = seen.size();
				seen.add(entry.getValue());
				palette.add(NbtUtils.writeBlockState(entry.getValue()));
			}
			CompoundTag block = new CompoundTag();
			ListTag posTag = new ListTag();
			posTag.add(IntTag.valueOf(pos.getX()));
			posTag.add(IntTag.valueOf(pos.getY()));
			posTag.add(IntTag.valueOf(pos.getZ()));
			block.put("pos", posTag);
			block.putInt("state", index);
			blockList.add(block);
			sizeX = Math.max(sizeX, pos.getX() + 1);
			sizeY = Math.max(sizeY, pos.getY() + 1);
			sizeZ = Math.max(sizeZ, pos.getZ() + 1);
		}
		CompoundTag tag = new CompoundTag();
		ListTag size = new ListTag();
		size.add(IntTag.valueOf(sizeX));
		size.add(IntTag.valueOf(sizeY));
		size.add(IntTag.valueOf(sizeZ));
		tag.put("size", size);
		tag.put("palette", palette);
		tag.put("blocks", blockList);
		tag.put("entities", new ListTag());
		StructureTemplate template = helper.getLevel().getStructureTemplateManager().getOrCreate(id);
		template.load(helper.getLevel().registryAccess().lookupOrThrow(Registries.BLOCK), tag);
		return id;
	}

	private static final BlockState COBBLE = Blocks.COBBLESTONE.defaultBlockState();
	private static final BlockState PLANKS = Blocks.OAK_PLANKS.defaultBlockState();
	private static final BlockState HUT_DOOR = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
	private static final BlockPos BUILD_ORIGIN = new BlockPos(3, 1, 3);

	/** A 3x3 hut: two layers of cobblestone walls with a door on the north side, and a plank roof. */
	private static Map<BlockPos, BlockState> hut() {
		Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
		for (int y = 0; y < 2; y++) {
			for (int x = 0; x < 3; x++) {
				for (int z = 0; z < 3; z++) {
					if (x == 1 && z == 1) {
						continue;
					}
					boolean door = x == 1 && z == 0;
					blocks.put(new BlockPos(x, y, z), !door ? COBBLE
							: HUT_DOOR.setValue(DoorBlock.HALF, y == 0 ? DoubleBlockHalf.LOWER : DoubleBlockHalf.UPPER));
				}
			}
		}
		for (int x = 0; x < 3; x++) {
			for (int z = 0; z < 3; z++) {
				blocks.put(new BlockPos(x, 2, z), PLANKS);
			}
		}
		return blocks;
	}

	private static String buildNote(GameTestHelper helper, List<Recorded> outcomes) {
		helper.assertTrue(outcomes.size() == 1, "build not finished yet");
		if (outcomes.get(0).outcome() instanceof TaskOutcome.Succeeded s) {
			return s.note();
		}
		helper.fail("build did not succeed: " + outcomes.get(0).outcome());
		return "";
	}

	@GameTest(maxTicks = 2400)
	public void buildsHutWithMaterials(GameTestHelper helper) {
		layFloor(helper);
		Map<BlockPos, BlockState> hut = hut();
		Identifier id = blueprint(helper, "hut", hut);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.COBBLESTONE, 14));
		worker.insertOrDrop(new ItemStack(Items.OAK_PLANKS, 9));
		worker.insertOrDrop(new ItemStack(Items.OAK_DOOR));
		worker.tasks().enqueue(new BuildTask(helper.absolutePos(BUILD_ORIGIN), id, Rotation.NONE));

		helper.succeedWhen(() -> {
			String note = buildNote(helper, outcomes);
			helper.assertTrue(note.equals("built " + id + ": placed 24, 0 already correct"), "unexpected note: " + note);
			hut.forEach((pos, state) -> {
				BlockState placed = helper.getBlockState(BUILD_ORIGIN.offset(pos));
				helper.assertTrue(placed == state, "at " + pos.toShortString() + " expected " + state + ", got " + placed);
			});
			helper.assertTrue(worker.getInventory().isEmpty(), "worker has materials left: " + worker.getInventory());
		});
	}

	@GameTest(maxTicks = 1200)
	public void buildRotatedClockwise90(GameTestHelper helper) {
		layFloor(helper);
		Map<BlockPos, BlockState> wall = new LinkedHashMap<>();
		wall.put(new BlockPos(0, 0, 0), COBBLE);
		wall.put(new BlockPos(1, 0, 0), COBBLE);
		wall.put(new BlockPos(2, 0, 0), COBBLE);
		BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST);
		wall.put(new BlockPos(0, 1, 0), stairs);
		Identifier id = blueprint(helper, "rotated_wall", wall);
		BlockPos origin = new BlockPos(5, 1, 3);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.COBBLESTONE, 3));
		worker.insertOrDrop(new ItemStack(Items.OAK_STAIRS));
		worker.tasks().enqueue(new BuildTask(helper.absolutePos(origin), id, Rotation.CLOCKWISE_90));

		helper.succeedWhen(() -> {
			buildNote(helper, outcomes);
			// Turned clockwise, the wall runs south (+z) from the origin and the stairs face south.
			helper.assertBlockPresent(Blocks.COBBLESTONE, origin);
			helper.assertBlockPresent(Blocks.COBBLESTONE, origin.south());
			helper.assertBlockPresent(Blocks.COBBLESTONE, origin.south(2));
			helper.assertBlockPresent(Blocks.AIR, origin.east());
			BlockState placed = helper.getBlockState(origin.above());
			BlockState expected = stairs.setValue(StairBlock.FACING, Direction.SOUTH);
			helper.assertTrue(placed == expected, "expected " + expected + ", got " + placed);
		});
	}

	@GameTest(maxTicks = 100)
	public void buildMissingMaterialsFails(GameTestHelper helper) {
		layFloor(helper);
		Map<BlockPos, BlockState> hut = hut();
		Identifier id = blueprint(helper, "hut_short", hut);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.COBBLESTONE, 5));
		worker.tasks().enqueue(new BuildTask(helper.absolutePos(BUILD_ORIGIN), id, Rotation.NONE));

		helper.succeedWhen(() -> {
			assertFailedWith(helper, outcomes, FailureReason.MISSING_ITEM);
			String detail = ((TaskOutcome.Failed) outcomes.get(0).outcome()).detail();
			for (String part : List.of("9 minecraft:cobblestone", "9 minecraft:oak_planks", "1 minecraft:oak_door")) {
				helper.assertTrue(detail.contains(part), "detail does not list " + part + ": " + detail);
			}
			hut.keySet().forEach(pos -> helper.assertBlockPresent(Blocks.AIR, BUILD_ORIGIN.offset(pos)));
			helper.assertTrue(count(worker, Items.COBBLESTONE) == 5, "worker lost cobblestone");
		});
	}

	@GameTest(maxTicks = 1200)
	public void buildSkipsAlreadyCorrect(GameTestHelper helper) {
		layFloor(helper);
		BlockState wetStairs = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.WATERLOGGED, true);
		BlockState dryStairs = wetStairs.setValue(StairBlock.WATERLOGGED, false);
		Map<BlockPos, BlockState> wall = new LinkedHashMap<>();
		wall.put(new BlockPos(0, 0, 0), COBBLE);
		wall.put(new BlockPos(1, 0, 0), COBBLE);
		wall.put(new BlockPos(2, 0, 0), COBBLE);
		wall.put(new BlockPos(3, 0, 0), wetStairs);
		Identifier id = blueprint(helper, "partly_built_wall", wall);
		helper.setBlock(BUILD_ORIGIN, COBBLE);
		helper.setBlock(BUILD_ORIGIN.east(), COBBLE);
		// Already correct for the waterlogged entry, which is built dry.
		helper.setBlock(BUILD_ORIGIN.east(3), dryStairs);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.COBBLESTONE));
		worker.tasks().enqueue(new BuildTask(helper.absolutePos(BUILD_ORIGIN), id, Rotation.NONE));

		helper.succeedWhen(() -> {
			String note = buildNote(helper, outcomes);
			helper.assertTrue(note.equals("built " + id + ": placed 1, 3 already correct"), "unexpected note: " + note);
			helper.assertBlockPresent(Blocks.COBBLESTONE, BUILD_ORIGIN.east(2));
			helper.assertTrue(helper.getBlockState(BUILD_ORIGIN.east(3)) == dryStairs, "stairs changed");
			helper.assertTrue(count(worker, Items.COBBLESTONE) == 0, "cobblestone was not consumed");
		});
	}

	@GameTest(maxTicks = 1200)
	public void buildWithWorkerInsideFootprint(GameTestHelper helper) {
		layFloor(helper);
		Map<BlockPos, BlockState> wall = new LinkedHashMap<>();
		wall.put(new BlockPos(0, 0, 0), COBBLE);
		wall.put(new BlockPos(1, 0, 0), COBBLE);
		wall.put(new BlockPos(2, 0, 0), COBBLE);
		wall.put(new BlockPos(1, 1, 0), COBBLE);
		Identifier id = blueprint(helper, "wall_through_worker", wall);
		// The wall runs through the worker's start cell, (1, 1, 1), and the cell above it.
		BlockPos origin = WORKER_START.west();
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.COBBLESTONE, 4));
		worker.tasks().enqueue(new BuildTask(helper.absolutePos(origin), id, Rotation.NONE));

		helper.succeedWhen(() -> {
			String note = buildNote(helper, outcomes);
			helper.assertTrue(note.equals("built " + id + ": placed 4, 0 already correct"), "unexpected note: " + note);
			wall.keySet().forEach(pos -> helper.assertBlockPresent(Blocks.COBBLESTONE, origin.offset(pos)));
			helper.assertTrue(count(worker, Items.COBBLESTONE) == 0, "cobblestone was not consumed");
		});
	}

	@GameTest(maxTicks = 100)
	public void buildUnknownBlueprintFails(GameTestHelper helper) {
		layFloor(helper);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		Identifier id = Identifier.fromNamespaceAndPath("rabodepalha", "gametest/no_such_blueprint");
		worker.tasks().enqueue(new BuildTask(helper.absolutePos(BUILD_ORIGIN), id, Rotation.NONE));

		helper.succeedWhen(() -> {
			assertFailedWith(helper, outcomes, FailureReason.ERROR);
			helper.assertTrue(((TaskOutcome.Failed) outcomes.get(0).outcome()).detail().contains("unknown blueprint " + id),
					"detail does not name the blueprint: " + outcomes.get(0).outcome());
		});
	}

	@GameTest(maxTicks = 1600)
	public void buildNormalizesContextStates(GameTestHelper helper) {
		layFloor(helper);
		BlockState wetStairs = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.WATERLOGGED, true);
		BlockState extendedPiston = Blocks.PISTON.defaultBlockState()
				.setValue(BlockStateProperties.FACING, Direction.UP)
				.setValue(BlockStateProperties.EXTENDED, true);
		BlockState head = Blocks.PISTON_HEAD.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP);
		// Facing north, a LEFT half connects east to the RIGHT half.
		BlockState chest = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH);
		Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
		blocks.put(new BlockPos(0, 0, 0), wetStairs);
		blocks.put(new BlockPos(2, 0, 0), extendedPiston);
		blocks.put(new BlockPos(2, 1, 0), head);
		// A half captured without its partner, which would be at (1, 0, 2).
		blocks.put(new BlockPos(0, 0, 2), chest.setValue(ChestBlock.TYPE, ChestType.LEFT));
		blocks.put(new BlockPos(3, 0, 2), chest.setValue(ChestBlock.TYPE, ChestType.LEFT));
		blocks.put(new BlockPos(4, 0, 2), chest.setValue(ChestBlock.TYPE, ChestType.RIGHT));
		Identifier id = blueprint(helper, "context_states", blocks);
		BlockPos origin = new BlockPos(2, 1, 3);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.OAK_STAIRS));
		worker.insertOrDrop(new ItemStack(Items.PISTON));
		worker.insertOrDrop(new ItemStack(Items.CHEST, 3));
		worker.tasks().enqueue(new BuildTask(helper.absolutePos(origin), id, Rotation.NONE));

		helper.succeedWhen(() -> {
			String note = buildNote(helper, outcomes);
			helper.assertTrue(note.startsWith("built " + id + ": placed 5, 0 already correct, 1 placed dry, 1 unplaceable"),
					"unexpected note: " + note);
			BlockState stairs = helper.getBlockState(origin);
			helper.assertTrue(stairs == wetStairs.setValue(StairBlock.WATERLOGGED, false), "stairs are " + stairs);
			helper.assertTrue(stairs.getFluidState().isEmpty(), "water at the stairs");
			BlockState piston = helper.getBlockState(origin.offset(2, 0, 0));
			helper.assertTrue(piston == extendedPiston.setValue(BlockStateProperties.EXTENDED, false), "piston is " + piston);
			helper.assertBlockPresent(Blocks.AIR, origin.offset(2, 1, 0));
			BlockState lone = helper.getBlockState(origin.offset(0, 0, 2));
			helper.assertTrue(lone == chest.setValue(ChestBlock.TYPE, ChestType.SINGLE), "lone half is " + lone);
			BlockState left = helper.getBlockState(origin.offset(3, 0, 2));
			BlockState right = helper.getBlockState(origin.offset(4, 0, 2));
			helper.assertTrue(left == chest.setValue(ChestBlock.TYPE, ChestType.LEFT), "left half is " + left);
			helper.assertTrue(right == chest.setValue(ChestBlock.TYPE, ChestType.RIGHT), "right half is " + right);
			helper.assertTrue(worker.getInventory().isEmpty(), "worker has materials left: " + worker.getInventory());
		});
	}

	@GameTest
	public void saveLoadKeepsBuild(GameTestHelper helper) {
		layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		BlockPos origin = helper.absolutePos(BUILD_ORIGIN);
		Identifier id = Identifier.fromNamespaceAndPath("rabodepalha", "gametest/saved_build");
		worker.tasks().enqueue(new BuildTask(origin, id, Rotation.CLOCKWISE_180));

		WorkerEntity loaded = roundTrip(helper, worker);

		List<Task<WorkerEntity>> queue = loaded.tasks().snapshot();
		helper.assertTrue(queue.size() == 1 && queue.get(0) instanceof BuildTask b && b.origin().equals(origin)
				&& b.blueprint().equals(id) && b.rotation() == Rotation.CLOCKWISE_180,
				"expected the build back, got " + queue);
		helper.succeed();
	}

	@GameTest(maxTicks = 400)
	public void depositAllKeepsTools(GameTestHelper helper) {
		layFloor(helper);
		ChestBlockEntity chest = placeChest(helper, CONTAINER);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.DIAMOND_PICKAXE));
		worker.insertOrDrop(new ItemStack(Items.COBBLESTONE, 16));
		worker.tasks().enqueue(new DepositTask(helper.absolutePos(CONTAINER), Optional.empty()));

		helper.succeedWhen(() -> {
			assertSucceeded(helper, outcomes);
			String note = ((TaskOutcome.Succeeded) outcomes.get(0).outcome()).note();
			helper.assertTrue(note.contains("kept 1"), "note does not mention the kept pickaxe: " + note);
			helper.assertTrue(chest.countItem(Items.COBBLESTONE) == 16, "expected 16 cobblestone in the chest");
			helper.assertTrue(chest.countItem(Items.DIAMOND_PICKAXE) == 0, "pickaxe went into the chest");
			helper.assertTrue(count(worker, Items.DIAMOND_PICKAXE) == 1, "worker lost the pickaxe");
			helper.assertTrue(count(worker, Items.COBBLESTONE) == 0, "worker still holds cobblestone");
		});
	}

	@GameTest(maxTicks = 400)
	public void depositNamedToolDeposits(GameTestHelper helper) {
		layFloor(helper);
		ChestBlockEntity chest = placeChest(helper, CONTAINER);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.DIAMOND_PICKAXE));
		worker.tasks().enqueue(new DepositTask(helper.absolutePos(CONTAINER),
				Optional.of(BuiltInRegistries.ITEM.getKey(Items.DIAMOND_PICKAXE))));

		helper.succeedWhen(() -> {
			assertSucceeded(helper, outcomes);
			helper.assertTrue(chest.countItem(Items.DIAMOND_PICKAXE) == 1, "pickaxe is not in the chest");
			helper.assertTrue(count(worker, Items.DIAMOND_PICKAXE) == 0, "worker still holds the pickaxe");
		});
	}

	@GameTest(maxTicks = 400)
	public void depositAllOnlyToolsSucceeds(GameTestHelper helper) {
		layFloor(helper);
		ChestBlockEntity chest = placeChest(helper, CONTAINER);
		List<Recorded> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.DIAMOND_PICKAXE));
		worker.tasks().enqueue(new DepositTask(helper.absolutePos(CONTAINER), Optional.empty()));

		helper.succeedWhen(() -> {
			assertSucceeded(helper, outcomes);
			String note = ((TaskOutcome.Succeeded) outcomes.get(0).outcome()).note();
			helper.assertTrue(note.startsWith("nothing to deposit, kept 1"), "unexpected note: " + note);
			helper.assertTrue(chest.isEmpty(), "chest is not empty");
			helper.assertTrue(count(worker, Items.DIAMOND_PICKAXE) == 1, "worker lost the pickaxe");
		});
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
