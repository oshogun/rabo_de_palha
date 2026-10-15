package io.github.oshogun.rabodepalha.gametest;

import io.github.oshogun.rabodepalha.registry.ModEntities;
import io.github.oshogun.rabodepalha.rts.RtsField;
import io.github.oshogun.rabodepalha.rts.RtsIrrigation;
import io.github.oshogun.rabodepalha.task.FailureReason;
import io.github.oshogun.rabodepalha.task.TaskOutcome;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;
import io.github.oshogun.rabodepalha.worker.task.Buckets;
import io.github.oshogun.rabodepalha.worker.task.IrrigateTask;
import io.github.oshogun.rabodepalha.worker.task.IrrigationBlocks;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * The irrigate task and {@link IrrigationBlocks#holdsWater}. The irrigation structures are a stone layer
 * (y 0) under a grass layer (y 1), laid by {@link #layGround}; the worker walks at y 2. Tests that look
 * for water to fill buckets use the 56-wide {@link #ISOLATED} structure with their blocks in the middle,
 * so no water from a neighbouring test is within {@code Buckets.WATER_RANGE}.
 */
public class WorkerIrrigateGameTest {
	private static final String SMALL = "rabodepalha-test:irrigation";
	private static final int SMALL_SIZE = 24;
	private static final String ISOLATED = "rabodepalha-test:irrigation_isolated";
	private static final int ISOLATED_SIZE = 56;
	private static final int GROUND_Y = 1;

	private static void layGround(GameTestHelper helper, int size) {
		for (int x = 0; x < size; x++) {
			for (int z = 0; z < size; z++) {
				helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
				helper.setBlock(new BlockPos(x, GROUND_Y, z), Blocks.GRASS_BLOCK);
			}
		}
	}

	private static WorkerEntity spawnWorker(GameTestHelper helper, BlockPos start, List<TaskOutcome> outcomes) {
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, start);
		worker.tasks().addListener((task, outcome) -> outcomes.add(outcome));
		return worker;
	}

	/** An iron hoe and {@code seeds} wheat seeds. */
	private static void giveFarmingKit(WorkerEntity worker, int seeds) {
		worker.insertOrDrop(new ItemStack(Items.IRON_HOE));
		for (int left = seeds; left > 0; left -= 64) {
			worker.insertOrDrop(new ItemStack(Items.WHEAT_SEEDS, Math.min(64, left)));
		}
	}

	private static String succeededNote(GameTestHelper helper, List<TaskOutcome> outcomes) {
		helper.assertTrue(outcomes.size() == 1, "task not finished yet");
		helper.assertTrue(outcomes.get(0) instanceof TaskOutcome.Succeeded, "task did not succeed: " + outcomes.get(0));
		return ((TaskOutcome.Succeeded) outcomes.get(0)).note();
	}

	private static String failedDetail(GameTestHelper helper, List<TaskOutcome> outcomes, FailureReason reason) {
		helper.assertTrue(outcomes.size() == 1, "task not finished yet");
		helper.assertTrue(outcomes.get(0) instanceof TaskOutcome.Failed f && f.reason() == reason,
				"expected " + reason + ", got " + outcomes.get(0));
		return ((TaskOutcome.Failed) outcomes.get(0)).detail();
	}

	/**
	 * The holes the task would dig now, in relative positions. GameTestHelper#relativePos turns an unrotated
	 * test by 180°, so the origin is subtracted instead.
	 */
	private static List<BlockPos> plannedHoles(GameTestHelper helper, IrrigateTask task) {
		BlockPos origin = helper.absolutePos(BlockPos.ZERO);
		List<BlockPos> holes = new ArrayList<>();
		for (RtsIrrigation.Column hole : IrrigationBlocks.plan(helper.getLevel(), task.field()).holes()) {
			holes.add(new BlockPos(hole.x(), hole.y(), hole.z()).subtract(origin));
		}
		return holes;
	}

	private static boolean isWaterSource(GameTestHelper helper, BlockPos rel) {
		return helper.getBlockState(rel).is(Blocks.WATER) && helper.getBlockState(rel).getFluidState().isSource();
	}

	/** Every water source in the structure, in relative positions. */
	private static List<BlockPos> waterSources(GameTestHelper helper, int size) {
		List<BlockPos> water = new ArrayList<>();
		for (BlockPos rel : BlockPos.betweenClosed(0, 0, 0, size - 1, 5, size - 1)) {
			if (isWaterSource(helper, rel)) {
				water.add(rel.immutable());
			}
		}
		return water;
	}

	/** Fails if water flowed anywhere in the structure: every water block is a source. */
	private static void assertNoFlowingWater(GameTestHelper helper, int size) {
		for (BlockPos rel : BlockPos.betweenClosed(0, 0, 0, size - 1, 5, size - 1)) {
			FluidState fluid = helper.getBlockState(rel).getFluidState();
			helper.assertTrue(fluid.isEmpty() || fluid.isSource(), "water flowed to " + rel.toShortString());
		}
	}

	/**
	 * Every column of the field between {@code from} and {@code to} (relative, at {@link #GROUND_Y}) is either
	 * one of {@code holes} as water, or farmland with wheat that a water source in {@code water} keeps wet, as
	 * FarmlandBlock#isNearWater sees it.
	 */
	private static void assertIrrigated(GameTestHelper helper, BlockPos from, BlockPos to, List<BlockPos> holes,
			List<BlockPos> water) {
		for (int x = from.getX(); x <= to.getX(); x++) {
			for (int z = from.getZ(); z <= to.getZ(); z++) {
				BlockPos ground = new BlockPos(x, GROUND_Y, z);
				if (holes.contains(ground)) {
					helper.assertTrue(isWaterSource(helper, ground), "hole " + ground.toShortString() + " holds no water");
					continue;
				}
				helper.assertBlockPresent(Blocks.FARMLAND, ground);
				helper.assertBlockPresent(Blocks.WHEAT, ground.above());
				helper.assertTrue(water.stream().anyMatch(w -> RtsIrrigation.hydrates(w.getX(), w.getY(), w.getZ(),
						ground.getX(), ground.getY(), ground.getZ())), ground.toShortString() + " is out of water range");
			}
		}
	}

	/** Nothing in the field between {@code from} and {@code to} changed: still {@code ground} with air above. */
	private static void assertUntouched(GameTestHelper helper, BlockPos from, BlockPos to, Block ground) {
		for (int x = from.getX(); x <= to.getX(); x++) {
			for (int z = from.getZ(); z <= to.getZ(); z++) {
				BlockPos pos = new BlockPos(x, GROUND_Y, z);
				helper.assertBlockPresent(ground, pos);
				helper.assertBlockPresent(Blocks.AIR, pos.above());
			}
		}
	}

	// Plot and field.

	@GameTest(structure = SMALL, maxTicks = 6000)
	public void plotDigsFillsAndPlantsAround(GameTestHelper helper) {
		layGround(helper, SMALL_SIZE);
		BlockPos centre = new BlockPos(12, GROUND_Y, 12);
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, new BlockPos(2, 2, 2), outcomes);
		worker.insertOrDrop(new ItemStack(Items.WATER_BUCKET));
		giveFarmingKit(worker, 80);
		BlockPos abs = helper.absolutePos(centre);
		worker.tasks().enqueue(new IrrigateTask(abs, abs, Items.WHEAT_SEEDS));

		helper.succeedWhen(() -> {
			String note = succeededNote(helper, outcomes);
			helper.assertTrue(note.startsWith("dug and filled 1 holes; planted 80 of 80"), "unexpected note: " + note);
			helper.assertTrue(!note.contains("no water"), "irrigated plot warned about water: " + note);
			assertIrrigated(helper, centre.offset(-4, 0, -4), centre.offset(4, 0, 4), List.of(centre), List.of(centre));
			helper.assertTrue(waterSources(helper, SMALL_SIZE).equals(List.of(centre)), "water outside the hole");
			helper.assertTrue(worker.countItem(Items.WATER_BUCKET) == 0 && worker.countItem(Items.BUCKET) == 1,
					"the empty bucket did not come back");
			helper.assertTrue(worker.countItem(Items.DIRT) >= 1, "the dug-out block was not kept");
			helper.assertTrue(worker.countItem(Items.WHEAT_SEEDS) == 0, "seeds left over");
		});
	}

	@GameTest(structure = SMALL, maxTicks = 8000)
	public void fieldEndsWithEveryPlantedBlockInRange(GameTestHelper helper) {
		layGround(helper, SMALL_SIZE);
		BlockPos from = new BlockPos(3, GROUND_Y, 3);
		BlockPos to = new BlockPos(18, GROUND_Y, 8);
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, new BlockPos(1, 2, 1), outcomes);
		worker.insertOrDrop(new ItemStack(Items.WATER_BUCKET));
		worker.insertOrDrop(new ItemStack(Items.WATER_BUCKET));
		worker.insertOrDrop(new ItemStack(Items.WATER_BUCKET));
		worker.insertOrDrop(new ItemStack(Items.WATER_BUCKET));
		giveFarmingKit(worker, 128);
		IrrigateTask task = new IrrigateTask(helper.absolutePos(from), helper.absolutePos(to), Items.WHEAT_SEEDS);
		List<BlockPos> holes = plannedHoles(helper, task);
		helper.assertTrue(holes.size() >= 2 && holes.size() <= 4, "expected 2 to 4 holes, planned " + holes);
		worker.tasks().enqueue(task);

		helper.succeedWhen(() -> {
			String note = succeededNote(helper, outcomes);
			helper.assertTrue(note.startsWith("dug and filled " + holes.size() + " holes; "), "unexpected note: " + note);
			helper.assertTrue(!note.contains("no water"), "irrigated field warned about water: " + note);
			assertIrrigated(helper, from, to, holes, holes);
			helper.assertTrue(waterSources(helper, SMALL_SIZE).size() == holes.size(), "water outside the holes");
			assertNoFlowingWater(helper, SMALL_SIZE);
		});
	}

	/** Water already runs past the field, so it needs no hole: the worker has no bucket and still succeeds. */
	@GameTest(structure = SMALL, maxTicks = 6000)
	public void fieldBesideRiverNeedsNoHoles(GameTestHelper helper) {
		layGround(helper, SMALL_SIZE);
		List<BlockPos> river = new ArrayList<>();
		for (int x = 2; x <= 20; x++) {
			BlockPos pos = new BlockPos(x, GROUND_Y, 12);
			helper.setBlock(pos, Blocks.WATER);
			river.add(pos);
		}
		BlockPos from = new BlockPos(4, GROUND_Y, 8);
		BlockPos to = new BlockPos(16, GROUND_Y, 11);
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, new BlockPos(2, 2, 2), outcomes);
		giveFarmingKit(worker, 64);
		IrrigateTask task = new IrrigateTask(helper.absolutePos(from), helper.absolutePos(to), Items.WHEAT_SEEDS);
		helper.assertTrue(plannedHoles(helper, task).isEmpty(), "planned holes beside the river");
		worker.tasks().enqueue(task);

		helper.succeedWhen(() -> {
			String note = succeededNote(helper, outcomes);
			helper.assertTrue(note.startsWith("dug and filled 0 holes; planted 52 of 52"), "unexpected note: " + note);
			assertIrrigated(helper, from, to, List.of(), river);
			helper.assertTrue(waterSources(helper, SMALL_SIZE).equals(river), "the river changed");
		});
	}

	// Getting water.

	/**
	 * Two holes and one empty bucket: the worker fills it at the 2×2 pool, an infinite source, pours, and goes
	 * back to the pool rather than to its first hole. The pool is still full at the end.
	 */
	@GameTest(structure = ISOLATED, maxTicks = 8000)
	public void refillsAtInfiniteSourceNeverAtOwnHoles(GameTestHelper helper) {
		layGround(helper, ISOLATED_SIZE);
		List<BlockPos> pool = List.of(new BlockPos(37, GROUND_Y, 30), new BlockPos(38, GROUND_Y, 30),
				new BlockPos(37, GROUND_Y, 31), new BlockPos(38, GROUND_Y, 31));
		pool.forEach(pos -> helper.setBlock(pos, Blocks.WATER));
		BlockPos from = new BlockPos(20, GROUND_Y, 20);
		BlockPos to = new BlockPos(33, GROUND_Y, 24);
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, new BlockPos(35, 2, 27), outcomes);
		worker.insertOrDrop(new ItemStack(Items.BUCKET));
		giveFarmingKit(worker, 70);
		IrrigateTask task = new IrrigateTask(helper.absolutePos(from), helper.absolutePos(to), Items.WHEAT_SEEDS);
		List<BlockPos> holes = plannedHoles(helper, task);
		helper.assertTrue(holes.size() == 2, "expected 2 holes, planned " + holes);
		worker.tasks().enqueue(task);

		helper.succeedWhen(() -> {
			String note = succeededNote(helper, outcomes);
			helper.assertTrue(note.startsWith("dug and filled 2 holes; "), "unexpected note: " + note);
			assertIrrigated(helper, from, to, holes, holes);
			for (BlockPos pos : pool) {
				helper.assertTrue(isWaterSource(helper, pos), "the pool lost " + pos.toShortString());
			}
			List<BlockPos> expected = new ArrayList<>(holes);
			expected.addAll(pool);
			helper.assertTrue(waterSources(helper, ISOLATED_SIZE).containsAll(expected)
					&& waterSources(helper, ISOLATED_SIZE).size() == expected.size(), "water moved: "
					+ waterSources(helper, ISOLATED_SIZE));
			helper.assertTrue(worker.countItem(Items.BUCKET) == 1 && worker.countItem(Items.WATER_BUCKET) == 0,
					"expected the one empty bucket back");
		});
	}

	/**
	 * A long field with its only water beyond the east end: the first hole is at the west end, more than
	 * {@code Buckets.WATER_RANGE} from the pool, but the pool is in range of the field's centre, where the
	 * task checked for water before digging. The worker still walks back to it for every refill.
	 */
	@GameTest(structure = ISOLATED, maxTicks = 16000)
	public void largeFieldRefillsFromFarCornerAtSourceNearCentre(GameTestHelper helper) {
		layGround(helper, ISOLATED_SIZE);
		List<BlockPos> pool = List.of(new BlockPos(43, GROUND_Y, 34), new BlockPos(44, GROUND_Y, 34),
				new BlockPos(43, GROUND_Y, 35), new BlockPos(44, GROUND_Y, 35));
		pool.forEach(pos -> helper.setBlock(pos, Blocks.WATER));
		BlockPos from = new BlockPos(12, GROUND_Y, 26);
		BlockPos to = new BlockPos(43, GROUND_Y, 30);
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, new BlockPos(40, 2, 32), outcomes);
		worker.insertOrDrop(new ItemStack(Items.BUCKET));
		giveFarmingKit(worker, 192);
		IrrigateTask task = new IrrigateTask(helper.absolutePos(from), helper.absolutePos(to), Items.WHEAT_SEEDS);
		List<BlockPos> holes = plannedHoles(helper, task);
		helper.assertTrue(holes.size() >= 2, "expected several holes, planned " + holes);
		// A refill must follow a hole the pool is out of range from.
		helper.assertTrue(holes.subList(0, holes.size() - 1).stream()
				.anyMatch(hole -> hole.getX() <= 43 - Buckets.WATER_RANGE - 3),
				"no hole before the last is out of the pool's range: " + holes);
		worker.tasks().enqueue(task);

		helper.succeedWhen(() -> {
			String note = succeededNote(helper, outcomes);
			helper.assertTrue(note.startsWith("dug and filled " + holes.size() + " holes; "), "unexpected note: " + note);
			List<BlockPos> water = new ArrayList<>(holes);
			water.addAll(pool);
			assertIrrigated(helper, from, to, holes, water);
			for (BlockPos pos : pool) {
				helper.assertTrue(isWaterSource(helper, pos), "the pool lost " + pos.toShortString());
			}
			assertNoFlowingWater(helper, ISOLATED_SIZE);
		});
	}

	@GameTest(structure = SMALL, maxTicks = 400)
	public void noBucketFailsBeforeDigging(GameTestHelper helper) {
		layGround(helper, SMALL_SIZE);
		BlockPos centre = new BlockPos(12, GROUND_Y, 12);
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, new BlockPos(2, 2, 2), outcomes);
		giveFarmingKit(worker, 80);
		BlockPos abs = helper.absolutePos(centre);
		worker.tasks().enqueue(new IrrigateTask(abs, abs, Items.WHEAT_SEEDS));

		helper.succeedWhen(() -> {
			String detail = failedDetail(helper, outcomes, FailureReason.MISSING_ITEM);
			helper.assertTrue(detail.equals("needs a bucket"), "unexpected detail: " + detail);
			assertUntouched(helper, centre.offset(-4, 0, -4), centre.offset(4, 0, 4), Blocks.GRASS_BLOCK);
			helper.assertTrue(worker.countItem(Items.WHEAT_SEEDS) == 80, "seeds were used");
		});
	}

	@GameTest(structure = ISOLATED, maxTicks = 400)
	public void noWaterInRangeFailsBeforeDigging(GameTestHelper helper) {
		layGround(helper, ISOLATED_SIZE);
		BlockPos centre = new BlockPos(28, GROUND_Y, 28);
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, new BlockPos(22, 2, 22), outcomes);
		worker.insertOrDrop(new ItemStack(Items.BUCKET));
		giveFarmingKit(worker, 80);
		BlockPos abs = helper.absolutePos(centre);
		worker.tasks().enqueue(new IrrigateTask(abs, abs, Items.WHEAT_SEEDS));

		helper.succeedWhen(() -> {
			String detail = failedDetail(helper, outcomes, FailureReason.MISSING_ITEM);
			helper.assertTrue(detail.startsWith("no water within 24 blocks"), "unexpected detail: " + detail);
			assertUntouched(helper, centre.offset(-4, 0, -4), centre.offset(4, 0, 4), Blocks.GRASS_BLOCK);
			helper.assertTrue(worker.countItem(Items.BUCKET) == 1, "the bucket was lost");
		});
	}

	// Holes that hold water.

	/**
	 * The plot's centre is on the edge of a one-block step: water there would spill down the east side, so
	 * the hole moves or is skipped, and no water flows anywhere.
	 */
	@GameTest(structure = SMALL, maxTicks = 6000)
	public void slopeEdgeIsNotFlooded(GameTestHelper helper) {
		layGround(helper, SMALL_SIZE);
		for (int x = 0; x <= 12; x++) {
			for (int z = 0; z < SMALL_SIZE; z++) {
				helper.setBlock(new BlockPos(x, GROUND_Y, z), Blocks.DIRT);
				helper.setBlock(new BlockPos(x, GROUND_Y + 1, z), Blocks.GRASS_BLOCK);
			}
		}
		BlockPos edge = new BlockPos(12, GROUND_Y + 1, 12);
		helper.assertFalse(IrrigationBlocks.holdsWater(helper.getLevel(), helper.absolutePos(edge)),
				"the step edge holds water");
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, new BlockPos(2, 3, 2), outcomes);
		worker.insertOrDrop(new ItemStack(Items.WATER_BUCKET));
		worker.insertOrDrop(new ItemStack(Items.WATER_BUCKET));
		giveFarmingKit(worker, 128);
		BlockPos abs = helper.absolutePos(edge);
		IrrigateTask task = new IrrigateTask(abs, abs, Items.WHEAT_SEEDS);
		helper.assertFalse(plannedHoles(helper, task).contains(edge), "planned a hole on the step edge");
		worker.tasks().enqueue(task);

		helper.succeedWhen(() -> {
			helper.assertTrue(outcomes.size() == 1, "task not finished yet");
			helper.assertFalse(isWaterSource(helper, edge), "poured on the step edge");
			assertNoFlowingWater(helper, SMALL_SIZE);
		});
	}

	/** {@link IrrigationBlocks#holdsWater} on a 3×3 dirt pad set into the stone floor, one rule at a time. */
	@GameTest(structure = SMALL)
	public void holdsWaterNeedsSolidFloorAndSides(GameTestHelper helper) {
		layGround(helper, SMALL_SIZE);
		BlockPos ground = new BlockPos(5, GROUND_Y, 5);
		BlockPos east = ground.east();

		pad(helper, ground);
		assertHolds(helper, ground, true, "a dirt pad");

		helper.setBlock(east, Blocks.AIR);
		assertHolds(helper, ground, false, "an air side");
		pad(helper, ground);

		// The step edge: the north side is one block lower.
		helper.setBlock(ground.north(), Blocks.AIR);
		helper.setBlock(ground.north().below(), Blocks.GRASS_BLOCK);
		assertHolds(helper, ground, false, "a slope edge");
		pad(helper, ground);

		helper.setBlock(ground.below(), Blocks.AIR);
		assertHolds(helper, ground, false, "no floor (a cave below)");
		pad(helper, ground);

		helper.setBlock(east, Blocks.TORCH);
		assertHolds(helper, ground, false, "a torch side, which water washes away");
		pad(helper, ground);

		helper.setBlock(east, Blocks.WATER);
		assertHolds(helper, ground, false, "a water side");
		pad(helper, ground);

		helper.setBlock(east, Blocks.FARMLAND);
		assertHolds(helper, ground, true, "a farmland side");
		pad(helper, ground);

		// Lava sealed into the stone floor diagonally below, so it cannot flow.
		BlockPos lava = ground.offset(1, -1, 1);
		helper.setBlock(lava, Blocks.LAVA);
		assertHolds(helper, ground, false, "lava diagonally below");
		helper.setBlock(lava, Blocks.STONE);

		helper.setBlock(ground, Blocks.BEDROCK);
		assertHolds(helper, ground, false, "an unbreakable ground block");
		pad(helper, ground);

		helper.setBlock(ground.above(), Blocks.STONE);
		assertHolds(helper, ground, false, "a solid block above");
		pad(helper, ground);

		helper.succeed();
	}

	/** Dirt on the 3×3 around {@code ground}, stone under it and air over it. */
	private static void pad(GameTestHelper helper, BlockPos ground) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				BlockPos pos = ground.offset(dx, 0, dz);
				helper.setBlock(pos.below(), Blocks.STONE);
				helper.setBlock(pos, Blocks.DIRT);
				helper.setBlock(pos.above(), Blocks.AIR);
			}
		}
	}

	private static void assertHolds(GameTestHelper helper, BlockPos ground, boolean expected, String what) {
		boolean holds = IrrigationBlocks.holdsWater(helper.getLevel(), helper.absolutePos(ground));
		helper.assertTrue(holds == expected, "holdsWater with " + what + " was " + holds);
	}
}
