package io.github.oshogun.rabodepalha.gametest;

import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.LEVEL_2;
import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.assertResult;
import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.assertUntouched;
import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.finish;
import static io.github.oshogun.rabodepalha.gametest.RtsOrderGameTest.rtsPlayer;

import io.github.oshogun.rabodepalha.network.RtsPlantPayload;
import io.github.oshogun.rabodepalha.registry.ModEntities;
import io.github.oshogun.rabodepalha.rts.RtsOrders;
import io.github.oshogun.rabodepalha.task.FailureReason;
import io.github.oshogun.rabodepalha.task.Task;
import io.github.oshogun.rabodepalha.task.TaskOutcome;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;
import io.github.oshogun.rabodepalha.worker.task.FieldBlocks;
import io.github.oshogun.rabodepalha.worker.task.PlantFieldTask;

import java.util.ArrayList;
import java.util.List;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * The plant_field task, the no-trample rule, {@code /rdp plant} and the RTS plant order. The field is a row of
 * three columns set into the stone floor; the worker starts in a corner.
 */
public class WorkerFarmGameTest {
	private static final BlockPos WORKER_START = new BlockPos(1, 1, 1);
	private static final BlockPos FIELD_FROM = new BlockPos(3, 0, 3);
	private static final BlockPos FIELD_TO = new BlockPos(5, 0, 3);
	/** A water source within FarmlandBlock#isNearWater range of the whole field, outside it. */
	private static final BlockPos WATER = new BlockPos(4, 0, 5);
	/** Hydrated, so a random tick cannot turn bare farmland back into dirt during a test. */
	private static final BlockState WET_FARMLAND = Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE,
			FarmlandBlock.MAX_MOISTURE);

	private static List<BlockPos> field() {
		List<BlockPos> field = new ArrayList<>();
		for (int x = FIELD_FROM.getX(); x <= FIELD_TO.getX(); x++) {
			field.add(new BlockPos(x, FIELD_FROM.getY(), FIELD_FROM.getZ()));
		}
		return field;
	}

	private static void layField(GameTestHelper helper, BlockState... ground) {
		WorkerTaskGameTest.layFloor(helper);
		List<BlockPos> field = field();
		for (int i = 0; i < field.size(); i++) {
			helper.setBlock(field.get(i), ground[i]);
		}
	}

	private static WorkerEntity spawnWorker(GameTestHelper helper, List<TaskOutcome> outcomes) {
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		worker.tasks().addListener((task, outcome) -> outcomes.add(outcome));
		return worker;
	}

	private static PlantFieldTask plant(GameTestHelper helper, Item seed) {
		return new PlantFieldTask(helper.absolutePos(FIELD_FROM), helper.absolutePos(FIELD_TO), seed);
	}

	private static String succeededNote(GameTestHelper helper, List<TaskOutcome> outcomes) {
		helper.assertTrue(outcomes.size() == 1, "plant_field not finished yet");
		helper.assertTrue(outcomes.get(0) instanceof TaskOutcome.Succeeded, "plant_field did not succeed: " + outcomes.get(0));
		return ((TaskOutcome.Succeeded) outcomes.get(0)).note();
	}

	private static String failedDetail(GameTestHelper helper, List<TaskOutcome> outcomes, FailureReason reason) {
		helper.assertTrue(outcomes.size() == 1, "plant_field not finished yet");
		helper.assertTrue(outcomes.get(0) instanceof TaskOutcome.Failed f && f.reason() == reason,
				"expected " + reason + ", got " + outcomes.get(0));
		return ((TaskOutcome.Failed) outcomes.get(0)).detail();
	}

	private static void assertPlanted(GameTestHelper helper, BlockPos ground, Block soil, Block crop) {
		helper.assertBlockPresent(soil, ground);
		helper.assertBlockPresent(crop, ground.above());
	}

	private static int damage(WorkerEntity worker, Item hoe) {
		for (int i = 0; i < worker.getInventory().getContainerSize(); i++) {
			ItemStack stack = worker.getInventory().getItem(i);
			if (stack.is(hoe)) {
				return stack.getDamageValue();
			}
		}
		return -1;
	}

	private static ItemStack nearlyBroken(Item hoe) {
		ItemStack stack = new ItemStack(hoe);
		stack.setDamageValue(stack.getMaxDamage() - 1);
		return stack;
	}

	@GameTest(maxTicks = 600)
	public void tillsGrassAndDirtWearingTheHoe(GameTestHelper helper) {
		layField(helper, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState(),
				Blocks.GRASS_BLOCK.defaultBlockState());
		helper.setBlock(WATER, Blocks.WATER);
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.IRON_HOE));
		worker.insertOrDrop(new ItemStack(Items.WHEAT_SEEDS, 5));
		worker.tasks().enqueue(plant(helper, Items.WHEAT_SEEDS));

		helper.succeedWhen(() -> {
			String note = succeededNote(helper, outcomes);
			helper.assertTrue(note.startsWith("planted 3 of 3"), "unexpected note: " + note);
			helper.assertTrue(!note.contains("no water"), "watered field warned about water: " + note);
			for (BlockPos ground : field()) {
				assertPlanted(helper, ground, Blocks.FARMLAND, Blocks.WHEAT);
			}
			helper.assertTrue(damage(worker, Items.IRON_HOE) == 3, "expected 3 damage, got " + damage(worker, Items.IRON_HOE));
			helper.assertTrue(worker.countItem(Items.WHEAT_SEEDS) == 2, "expected 2 seeds left, got " + worker.countItem(Items.WHEAT_SEEDS));
		});
	}

	@GameTest(maxTicks = 600)
	public void brokenHoeIsReplacedByTheNext(GameTestHelper helper) {
		layField(helper, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.GRASS_BLOCK.defaultBlockState(),
				Blocks.GRASS_BLOCK.defaultBlockState());
		helper.setBlock(WATER, Blocks.WATER);
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(nearlyBroken(Items.WOODEN_HOE));
		worker.insertOrDrop(new ItemStack(Items.STONE_HOE));
		worker.insertOrDrop(new ItemStack(Items.WHEAT_SEEDS, 3));
		worker.tasks().enqueue(plant(helper, Items.WHEAT_SEEDS));

		helper.succeedWhen(() -> {
			String note = succeededNote(helper, outcomes);
			helper.assertTrue(note.startsWith("planted 3 of 3"), "unexpected note: " + note);
			for (BlockPos ground : field()) {
				assertPlanted(helper, ground, Blocks.FARMLAND, Blocks.WHEAT);
			}
			helper.assertTrue(worker.countItem(Items.WOODEN_HOE) == 0, "the worn-out wooden hoe did not break");
			helper.assertTrue(damage(worker, Items.STONE_HOE) == 2, "expected 2 damage on the spare, got " + damage(worker, Items.STONE_HOE));
		});
	}

	@GameTest(maxTicks = 600)
	public void outOfHoesEndsWithCounts(GameTestHelper helper) {
		layField(helper, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.GRASS_BLOCK.defaultBlockState(),
				Blocks.GRASS_BLOCK.defaultBlockState());
		helper.setBlock(WATER, Blocks.WATER);
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(nearlyBroken(Items.WOODEN_HOE));
		worker.insertOrDrop(new ItemStack(Items.WHEAT_SEEDS, 3));
		worker.tasks().enqueue(plant(helper, Items.WHEAT_SEEDS));

		helper.succeedWhen(() -> {
			String detail = failedDetail(helper, outcomes, FailureReason.NO_TOOL);
			helper.assertTrue(detail.startsWith("planted 1 of 3: out of hoes"), "unexpected detail: " + detail);
			List<BlockPos> field = field();
			assertPlanted(helper, field.get(0), Blocks.FARMLAND, Blocks.WHEAT);
			helper.assertBlockPresent(Blocks.GRASS_BLOCK, field.get(1));
			helper.assertBlockPresent(Blocks.GRASS_BLOCK, field.get(2));
			helper.assertTrue(worker.countItem(Items.WHEAT_SEEDS) == 2, "expected 2 seeds left, got " + worker.countItem(Items.WHEAT_SEEDS));
		});
	}

	@GameTest(maxTicks = 400)
	public void refusesToTillWithoutHoe(GameTestHelper helper) {
		layField(helper, Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState(),
				Blocks.GRASS_BLOCK.defaultBlockState());
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.WHEAT_SEEDS, 3));
		worker.tasks().enqueue(plant(helper, Items.WHEAT_SEEDS));

		helper.succeedWhen(() -> {
			String detail = failedDetail(helper, outcomes, FailureReason.NO_TOOL);
			helper.assertTrue(detail.startsWith("no hoe to till"), "unexpected detail: " + detail);
			List<BlockPos> field = field();
			helper.assertBlockPresent(Blocks.GRASS_BLOCK, field.get(0));
			helper.assertBlockPresent(Blocks.DIRT, field.get(1));
			helper.assertBlockPresent(Blocks.GRASS_BLOCK, field.get(2));
			helper.assertTrue(worker.countItem(Items.WHEAT_SEEDS) == 3, "seeds were used");
		});
	}

	/** No hoe and no water: farmland needs no tilling, and the dry field is reported. */
	@GameTest(maxTicks = 600)
	public void plantsOnFarmlandAndWarnsWithoutWater(GameTestHelper helper) {
		layField(helper, WET_FARMLAND, WET_FARMLAND, WET_FARMLAND);
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.CARROT, 4));
		worker.tasks().enqueue(plant(helper, Items.CARROT));

		helper.succeedWhen(() -> {
			String note = succeededNote(helper, outcomes);
			helper.assertTrue(note.startsWith("planted 3 of 3"), "unexpected note: " + note);
			helper.assertTrue(note.contains("3 crops have no water nearby and will grow slowly"), "no water warning: " + note);
			for (BlockPos ground : field()) {
				assertPlanted(helper, ground, Blocks.FARMLAND, Blocks.CARROTS);
			}
			helper.assertTrue(worker.countItem(Items.CARROT) == 1, "expected 1 carrot left, got " + worker.countItem(Items.CARROT));
		});
	}

	@GameTest(maxTicks = 600)
	public void plantsNetherWartOnSoulSand(GameTestHelper helper) {
		BlockState soulSand = Blocks.SOUL_SAND.defaultBlockState();
		layField(helper, soulSand, soulSand, soulSand);
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.NETHER_WART, 3));
		worker.tasks().enqueue(plant(helper, Items.NETHER_WART));

		helper.succeedWhen(() -> {
			String note = succeededNote(helper, outcomes);
			helper.assertTrue(note.startsWith("planted 3 of 3"), "unexpected note: " + note);
			helper.assertTrue(!note.contains("no water"), "soul sand warned about water: " + note);
			for (BlockPos ground : field()) {
				assertPlanted(helper, ground, Blocks.SOUL_SAND, Blocks.NETHER_WART);
			}
			helper.assertTrue(worker.countItem(Items.NETHER_WART) == 0, "nether wart left over");
		});
	}

	@GameTest(maxTicks = 600)
	public void outOfSeedsEndsWithCounts(GameTestHelper helper) {
		layField(helper, WET_FARMLAND, WET_FARMLAND, WET_FARMLAND);
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, outcomes);
		worker.insertOrDrop(new ItemStack(Items.WHEAT_SEEDS, 2));
		worker.tasks().enqueue(plant(helper, Items.WHEAT_SEEDS));

		helper.succeedWhen(() -> {
			String detail = failedDetail(helper, outcomes, FailureReason.MISSING_ITEM);
			helper.assertTrue(detail.startsWith("planted 2 of 3: out of "), "unexpected detail: " + detail);
			List<BlockPos> field = field();
			assertPlanted(helper, field.get(0), Blocks.FARMLAND, Blocks.WHEAT);
			assertPlanted(helper, field.get(1), Blocks.FARMLAND, Blocks.WHEAT);
			helper.assertBlockPresent(Blocks.AIR, field.get(2).above());
		});
	}

	// No trampling.

	/** Lays wet farmland on x {@code fromX}..{@code fromX + 2}, z {@code fromZ}..{@code fromZ + 2}. */
	private static void farmlandPlate(GameTestHelper helper, int fromX, int fromZ) {
		for (int x = fromX; x < fromX + 3; x++) {
			for (int z = fromZ; z < fromZ + 3; z++) {
				helper.setBlock(new BlockPos(x, 0, z), WET_FARMLAND);
			}
		}
	}

	private static void mobGriefing(GameTestHelper helper, boolean value) {
		helper.getLevel().getGameRules().set(GameRules.MOB_GRIEFING, value, helper.getLevel().getServer());
	}

	/**
	 * Calls FarmlandBlock#fallOn directly with a fall that always tramples, so the mixin is checked without
	 * depending on where the mob lands. A cow on the same fall is the control.
	 */
	@GameTest
	public void fallOnSparesFarmlandForWorkersOnly(GameTestHelper helper) {
		WorkerTaskGameTest.layFloor(helper);
		mobGriefing(helper, true);
		BlockPos workerSoil = new BlockPos(2, 0, 2);
		BlockPos cowSoil = new BlockPos(5, 0, 5);
		helper.setBlock(workerSoil, WET_FARMLAND);
		helper.setBlock(cowSoil, WET_FARMLAND);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, workerSoil.above());
		Entity cow = helper.spawn(EntityTypes.COW, cowSoil.above());

		Blocks.FARMLAND.fallOn(helper.getLevel(), WET_FARMLAND, helper.absolutePos(workerSoil), worker, 10.0);
		Blocks.FARMLAND.fallOn(helper.getLevel(), WET_FARMLAND, helper.absolutePos(cowSoil), cow, 10.0);

		helper.assertBlockPresent(Blocks.FARMLAND, workerSoil);
		helper.assertBlockPresent(Blocks.DIRT, cowSoil);
		helper.succeed();
	}

	/** A real five-block drop onto farmland: the worker leaves it alone, the control cow tramples it. */
	@GameTest(maxTicks = 200)
	public void fallingWorkerDoesNotTrample(GameTestHelper helper) {
		WorkerTaskGameTest.layFloor(helper);
		mobGriefing(helper, true);
		farmlandPlate(helper, 1, 1);
		farmlandPlate(helper, 5, 5);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, new BlockPos(2, 6, 2));
		Entity cow = helper.spawn(EntityTypes.COW, new BlockPos(6, 6, 6));

		helper.succeedWhen(() -> {
			helper.assertTrue(worker.onGround() && cow.onGround(), "not landed yet");
			// The hurt cow may run off afterwards, so look for the trampled block rather than under the cow.
			boolean trampled = false;
			for (int x = 5; x < 8; x++) {
				for (int z = 5; z < 8; z++) {
					trampled |= helper.getBlockState(new BlockPos(x, 0, z)).is(Blocks.DIRT);
				}
			}
			helper.assertTrue(trampled, "the control cow did not trample its farmland");
			for (int x = 1; x < 4; x++) {
				for (int z = 1; z < 4; z++) {
					helper.assertBlockPresent(Blocks.FARMLAND, new BlockPos(x, 0, z));
				}
			}
		});
	}

	// The command.

	private static String expectFailure(GameTestHelper helper, CommandSourceStack source, String command) {
		try {
			helper.getLevel().getServer().getCommands().getDispatcher().execute(command, source);
		} catch (CommandSyntaxException e) {
			return e.getRawMessage().getString();
		}
		helper.fail("/" + command + " did not fail");
		return "";
	}

	private static String plantCommand(BlockPos from, BlockPos to, String seed, WorkerEntity worker) {
		return "rdp plant " + WorkerCommandGameTest.coords(from) + " " + WorkerCommandGameTest.coords(to) + " " + seed
				+ " " + worker.getStringUUID();
	}

	@GameTest
	public void plantCommandQueuesTask(GameTestHelper helper) {
		WorkerTaskGameTest.layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		BlockPos from = helper.absolutePos(FIELD_FROM);
		BlockPos to = helper.absolutePos(FIELD_TO);
		RecordingCommandSource rec = new RecordingCommandSource();

		int result = WorkerCommandGameTest.run(helper, WorkerCommandGameTest.sourceAt(helper, rec, worker),
				plantCommand(from, to, "minecraft:wheat_seeds", worker));

		helper.assertTrue(result == 1, "expected result 1, got " + result);
		List<Task<WorkerEntity>> queue = worker.tasks().snapshot();
		helper.assertTrue(queue.size() == 1 && queue.get(0) instanceof PlantFieldTask p && p.from().equals(from)
				&& p.to().equals(to) && p.seed() == Items.WHEAT_SEEDS, "expected one plant_field, got " + queue);
		helper.succeed();
	}

	@GameTest
	public void plantCommandRejectsNonSeed(GameTestHelper helper) {
		WorkerTaskGameTest.layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		RecordingCommandSource rec = new RecordingCommandSource();

		String error = expectFailure(helper, WorkerCommandGameTest.sourceAt(helper, rec, worker),
				plantCommand(helper.absolutePos(FIELD_FROM), helper.absolutePos(FIELD_TO), "minecraft:stone", worker));

		helper.assertTrue(error.contains("is not a seed"), "unexpected error: " + error);
		helper.assertTrue(worker.tasks().isIdle(), "worker got tasks: " + worker.tasks().snapshot());
		helper.succeed();
	}

	/** One column, but the corners are 17 blocks apart in height: over {@code RtsField.MAX_RISE}. */
	@GameTest
	public void plantCommandRejectsTooLargeField(GameTestHelper helper) {
		WorkerTaskGameTest.layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		BlockPos from = helper.absolutePos(FIELD_FROM);
		RecordingCommandSource rec = new RecordingCommandSource();

		String error = expectFailure(helper, WorkerCommandGameTest.sourceAt(helper, rec, worker),
				plantCommand(from, from.above(17), "minecraft:wheat_seeds", worker));

		helper.assertTrue(error.contains("too large"), "unexpected error: " + error);
		helper.assertTrue(worker.tasks().isIdle(), "worker got tasks: " + worker.tasks().snapshot());
		helper.succeed();
	}

	// The RTS order.

	private static RtsPlantPayload plantPayload(BlockPos from, BlockPos to, Item seed, WorkerEntity worker) {
		return plantPayload(from, to, BuiltInRegistries.ITEM.getKey(seed), worker);
	}

	private static RtsPlantPayload plantPayload(BlockPos from, BlockPos to, Identifier seed, WorkerEntity worker) {
		return new RtsPlantPayload(List.of(worker.getId()), from, to, seed, false);
	}

	@GameTest
	public void rtsPlantQueuesTask(GameTestHelper helper) {
		WorkerTaskGameTest.layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		ServerPlayer player = rtsPlayer(helper);
		BlockPos from = helper.absolutePos(FIELD_FROM);
		BlockPos to = helper.absolutePos(FIELD_TO);

		RtsOrders.Result result = RtsOrders.handlePlant(player, plantPayload(from, to, Items.WHEAT_SEEDS, worker), LEVEL_2);

		assertResult(helper, result, true, 1, "plant_replaced");
		List<Task<WorkerEntity>> queue = worker.tasks().snapshot();
		helper.assertTrue(queue.size() == 1 && queue.get(0) instanceof PlantFieldTask p && p.from().equals(from)
				&& p.to().equals(to) && p.seed() == Items.WHEAT_SEEDS, "expected one plant_field, got " + queue);
		finish(helper, player);
	}

	/** 33×33 columns, over {@code RtsField.MAX_COLUMNS}; rejected before any chunk is looked at. */
	@GameTest
	public void rtsPlantTooLargeRejected(GameTestHelper helper) {
		WorkerTaskGameTest.layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		ServerPlayer player = rtsPlayer(helper);
		BlockPos from = helper.absolutePos(FIELD_FROM);

		RtsOrders.Result result = RtsOrders.handlePlant(player,
				plantPayload(from, from.offset(32, 0, 32), Items.WHEAT_SEEDS, worker), LEVEL_2);

		assertResult(helper, result, false, 0, "area_too_large");
		assertUntouched(helper, worker);
		finish(helper, player);
	}

	@GameTest
	public void rtsPlantNonSeedRejected(GameTestHelper helper) {
		WorkerTaskGameTest.layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		ServerPlayer player = rtsPlayer(helper);
		BlockPos from = helper.absolutePos(FIELD_FROM);
		BlockPos to = helper.absolutePos(FIELD_TO);

		RtsOrders.Result stone = RtsOrders.handlePlant(player, plantPayload(from, to, Items.STONE, worker), LEVEL_2);
		assertResult(helper, stone, false, 0, "not_a_seed");
		RtsOrders.Result unknown = RtsOrders.handlePlant(player,
				plantPayload(from, to, Identifier.fromNamespaceAndPath("rabodepalha", "no_such_seed"), worker), LEVEL_2);
		assertResult(helper, unknown, false, 0, "not_a_seed");
		helper.assertTrue(FieldBlocks.isSeed(Items.WHEAT_SEEDS), "wheat seeds are not a seed");
		assertUntouched(helper, worker);
		finish(helper, player);
	}

	/** Uses the overload with explicit limits, so the field stays inside the loaded test area. */
	@GameTest
	public void rtsPlantTooFarRejected(GameTestHelper helper) {
		WorkerTaskGameTest.layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		ServerPlayer player = rtsPlayer(helper);
		RtsPlantPayload payload = plantPayload(helper.absolutePos(FIELD_FROM), helper.absolutePos(FIELD_TO),
				Items.WHEAT_SEEDS, worker);

		RtsOrders.Result result = RtsOrders.handlePlant(player, payload, LEVEL_2, 1.0, RtsOrders.MAX_WORKER_DISTANCE);

		assertResult(helper, result, false, 0, "target_too_far");
		assertUntouched(helper, worker);
		finish(helper, player);
	}

	/** Corners at the bottom of the world: the window below them is outside it. */
	@GameTest
	public void rtsPlantOutOfWorldRejected(GameTestHelper helper) {
		WorkerTaskGameTest.layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		ServerPlayer player = rtsPlayer(helper);
		int minY = helper.getLevel().getMinY();
		BlockPos from = helper.absolutePos(FIELD_FROM).atY(minY);
		BlockPos to = helper.absolutePos(FIELD_TO).atY(minY);

		RtsOrders.Result result = RtsOrders.handlePlant(player, plantPayload(from, to, Items.WHEAT_SEEDS, worker), LEVEL_2);

		assertResult(helper, result, false, 0, "area_out_of_world");
		assertUntouched(helper, worker);
		finish(helper, player);
	}

	@GameTest
	public void rtsPlantNotLoadedRejected(GameTestHelper helper) {
		WorkerTaskGameTest.layFloor(helper);
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, WORKER_START);
		ServerPlayer player = rtsPlayer(helper);
		BlockPos from = helper.absolutePos(FIELD_FROM).offset(100_000, 0, 100_000);
		BlockPos to = from.offset(2, 0, 2);

		RtsOrders.Result result = RtsOrders.handlePlant(player, plantPayload(from, to, Items.WHEAT_SEEDS, worker), LEVEL_2,
				1_000_000.0, RtsOrders.MAX_WORKER_DISTANCE);

		assertResult(helper, result, false, 0, "not_loaded");
		assertUntouched(helper, worker);
		finish(helper, player);
	}
}
