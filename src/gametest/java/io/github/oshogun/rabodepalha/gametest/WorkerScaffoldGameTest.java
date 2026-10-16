package io.github.oshogun.rabodepalha.gametest;

import io.github.oshogun.rabodepalha.task.FailureReason;
import io.github.oshogun.rabodepalha.task.TaskOutcome;
import io.github.oshogun.rabodepalha.task.TaskOutcome.Cancelled.Cause;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;
import io.github.oshogun.rabodepalha.worker.WorkerScaffold;
import io.github.oshogun.rabodepalha.worker.task.MineTask;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Workers pillaring up and bridging gaps with throwaway blocks, then taking them down again.
 *
 * <p>{@link #TALL} is an 8x12x8 room: a stone floor at y 0 and {@link #HIGH_TARGET}, a dirt block 8 above it,
 * out of reach from the floor. {@link #WIDE} is a 16x10x8 room: solid stone up to y 3, walled at its edges,
 * with a {@value #GAP}-wide, 3-deep gap across it at x 4..7. The worker starts on the near side and
 * {@link #FAR_TARGET} sits on the far side, out of reach from the near edge.
 */
public class WorkerScaffoldGameTest {
	private static final String TALL = "rabodepalha-test:scaffold_tall";
	private static final String WIDE = "rabodepalha-test:scaffold_wide";
	private static final int SCAFFOLD_COUNT = 32;

	private static final BlockPos FLOOR_START = new BlockPos(1, 1, 1);
	private static final BlockPos HIGH_TARGET = new BlockPos(4, 8, 4);
	/**
	 * Higher still, for the tests that act mid-pillar: a block 8 up is reached mid-jump from a one-block pillar,
	 * this one only from a pillar several blocks high.
	 */
	private static final BlockPos HIGHER_TARGET = new BlockPos(4, 11, 4);

	private static final int GAP = 4;
	private static final int GAP_X = 4;
	private static final int GROUND_TOP = 3;
	private static final BlockPos NEAR_START = new BlockPos(1, GROUND_TOP + 1, 3);
	private static final BlockPos FAR_TARGET = new BlockPos(13, GROUND_TOP + 1, 3);

	private static WorkerEntity spawnWithScaffold(GameTestHelper helper, BlockPos at, List<TaskOutcome> outcomes) {
		WorkerEntity worker = WorkerDoorGameTest.spawnWorker(helper, at, outcomes);
		worker.insertOrDrop(new ItemStack(Items.COBBLESTONE, SCAFFOLD_COUNT));
		return worker;
	}

	private static void buildTall(GameTestHelper helper, BlockPos target) {
		WorkerTaskGameTest.layFloor(helper);
		helper.setBlock(target, Blocks.DIRT);
	}

	/** Solid ground up to {@link #GROUND_TOP} with walls round the edge, and a gap at x 4..7 with {@code bottom} in its lowest layer. */
	private static void buildGap(GameTestHelper helper, Block bottom) {
		for (int x = 0; x < 16; x++) {
			for (int z = 0; z < 8; z++) {
				boolean edge = x == 0 || x == 15 || z == 0 || z == 7;
				boolean gap = !edge && x >= GAP_X && x < GAP_X + GAP;
				helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
				for (int y = 1; y <= GROUND_TOP; y++) {
					helper.setBlock(new BlockPos(x, y, z), gap ? (y == 1 ? bottom : Blocks.AIR) : Blocks.STONE);
				}
				if (edge) {
					for (int y = GROUND_TOP + 1; y <= GROUND_TOP + 3; y++) {
						helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
					}
				}
			}
		}
		helper.setBlock(FAR_TARGET, Blocks.DIRT);
	}

	private static int scaffoldCount(WorkerEntity worker) {
		return worker.getInventory().countItem(Items.COBBLESTONE);
	}

	/** Fails unless no block of {@code block} is left inside the structure's {@code size}. */
	private static void assertNoneLeft(GameTestHelper helper, Block block, BlockPos size) {
		for (int x = 0; x < size.getX(); x++) {
			for (int y = 0; y < size.getY(); y++) {
				for (int z = 0; z < size.getZ(); z++) {
					BlockPos rel = new BlockPos(x, y, z);
					helper.assertTrue(!helper.getBlockState(rel).is(block), "scaffold left behind at " + rel);
				}
			}
		}
	}

	private static void assertCleanedUp(GameTestHelper helper, WorkerEntity worker, BlockPos size) {
		helper.assertTrue(worker.scaffold().placed().isEmpty(), "scaffold still recorded: " + worker.scaffold().placed());
		assertNoneLeft(helper, Blocks.COBBLESTONE, size);
		helper.assertTrue(scaffoldCount(worker) == SCAFFOLD_COUNT,
				"expected " + SCAFFOLD_COUNT + " cobblestone back, got " + scaffoldCount(worker));
	}

	private static void assertUnreachable(GameTestHelper helper, List<TaskOutcome> outcomes) {
		helper.assertTrue(outcomes.size() == 1, "mine not finished yet");
		helper.assertTrue(outcomes.get(0) instanceof TaskOutcome.Failed f && f.reason() == FailureReason.UNREACHABLE,
				"expected UNREACHABLE, got " + outcomes.get(0));
	}

	private static void assertOnFloor(GameTestHelper helper, WorkerEntity worker) {
		BlockPos at = helper.relativePos(worker.blockPosition());
		helper.assertTrue(at.getY() == FLOOR_START.getY() && worker.onGround(), "worker is not back on the floor: " + at);
	}

	@GameTest(structure = TALL, maxTicks = 1200)
	public void pillarMinesHighBlock(GameTestHelper helper) {
		buildTall(helper, HIGH_TARGET);
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWithScaffold(helper, FLOOR_START, outcomes);
		WorkerClimbGameTest.watchHealth(helper, worker);
		worker.tasks().enqueue(new MineTask(helper.absolutePos(HIGH_TARGET)));

		helper.succeedWhen(() -> {
			WorkerDoorGameTest.assertSucceeded(helper, outcomes, "mine");
			helper.assertBlockPresent(Blocks.AIR, HIGH_TARGET);
			assertCleanedUp(helper, worker, new BlockPos(8, 12, 8));
			assertOnFloor(helper, worker);
		});
	}

	@GameTest(structure = WIDE, maxTicks = 1600)
	public void bridgesGapOfFour(GameTestHelper helper) {
		buildGap(helper, Blocks.AIR);
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWithScaffold(helper, NEAR_START, outcomes);
		WorkerClimbGameTest.watchHealth(helper, worker);
		boolean[] bridged = {false};
		helper.onEachTick(() -> bridged[0] |= !worker.scaffold().placed().isEmpty());
		worker.tasks().enqueue(new MineTask(helper.absolutePos(FAR_TARGET)));

		helper.succeedWhen(() -> {
			WorkerDoorGameTest.assertSucceeded(helper, outcomes, "mine");
			helper.assertBlockPresent(Blocks.AIR, FAR_TARGET);
			helper.assertTrue(bridged[0], "worker never placed a bridge block");
			for (int x = GAP_X; x < GAP_X + GAP; x++) {
				for (int y = 1; y <= GROUND_TOP; y++) {
					for (int z = 1; z < 7; z++) {
						helper.assertBlockPresent(Blocks.AIR, new BlockPos(x, y, z));
					}
				}
			}
			assertCleanedUp(helper, worker, new BlockPos(16, 10, 8));
		});
	}

	@GameTest(structure = TALL, maxTicks = 400)
	public void noScaffoldBlocksIsUnreachable(GameTestHelper helper) {
		buildTall(helper, HIGH_TARGET);
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = WorkerDoorGameTest.spawnWorker(helper, FLOOR_START, outcomes);
		worker.tasks().enqueue(new MineTask(helper.absolutePos(HIGH_TARGET)));

		helper.succeedWhen(() -> {
			assertUnreachable(helper, outcomes);
			TaskOutcome.Failed failed = (TaskOutcome.Failed) outcomes.get(0);
			helper.assertTrue(failed.detail().contains("scaffold blocks"), "no scaffold hint in: " + failed.detail());
			helper.assertBlockPresent(Blocks.DIRT, HIGH_TARGET);
			helper.assertTrue(worker.scaffold().placed().isEmpty(), "placed without scaffold blocks: " + worker.scaffold().placed());
		});
	}

	@GameTest(structure = TALL, maxTicks = 1200)
	public void cancelMidPillar(GameTestHelper helper) {
		buildTall(helper, HIGHER_TARGET);
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWithScaffold(helper, FLOOR_START, outcomes);
		WorkerClimbGameTest.watchHealth(helper, worker);
		worker.tasks().enqueue(new MineTask(helper.absolutePos(HIGHER_TARGET)));

		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(worker.scaffold().placed().size() >= 2, "pillar not two high yet"))
				.thenExecute(() -> {
					worker.tasks().cancelCurrent(worker, Cause.CLEARED);
					helper.assertTrue(outcomes.size() == 1 && outcomes.get(0).equals(TaskOutcome.cancelled(Cause.CLEARED)),
							"expected one Cancelled(CLEARED) outcome, got " + outcomes);
				})
				.thenWaitUntil(() -> {
					assertCleanedUp(helper, worker, new BlockPos(8, 12, 8));
					assertOnFloor(helper, worker);
				})
				.thenExecute(() -> helper.assertBlockPresent(Blocks.DIRT, HIGHER_TARGET))
				.thenSucceed();
	}

	@GameTest(structure = TALL, maxTicks = 2400)
	public void cleanupSurvivesReload(GameTestHelper helper) {
		buildTall(helper, HIGHER_TARGET);
		List<TaskOutcome> before = new ArrayList<>();
		WorkerEntity worker = spawnWithScaffold(helper, FLOOR_START, before);
		worker.tasks().enqueue(new MineTask(helper.absolutePos(HIGHER_TARGET)));
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity[] loaded = {null};

		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(worker.scaffold().placed().size() >= 2, "pillar not two high yet"))
				.thenExecute(() -> {
					WorkerEntity copy = WorkerTaskGameTest.roundTrip(helper, worker);
					// The copy has the same UUID, so the original must go first; it is not stopped cleanly.
					worker.discard();
					helper.assertTrue(helper.getLevel().addFreshEntity(copy), "could not add the reloaded worker");
					helper.assertTrue(!copy.scaffold().placed().isEmpty(), "scaffold record lost on reload");
					helper.assertTrue(copy.scaffold().needsCleanup(), "reloaded scaffold is not waiting for cleanup");
					copy.tasks().addListener((task, outcome) -> outcomes.add(outcome));
					loaded[0] = copy;
				})
				.thenWaitUntil(() -> {
					WorkerEntity copy = loaded[0];
					WorkerDoorGameTest.assertSucceeded(helper, outcomes, "mine");
					helper.assertBlockPresent(Blocks.AIR, HIGHER_TARGET);
					assertCleanedUp(helper, copy, new BlockPos(8, 12, 8));
				})
				.thenSucceed();
	}

	@GameTest(structure = TALL, maxTicks = 1200)
	public void neverRemovesForeignBlocks(GameTestHelper helper) {
		buildTall(helper, HIGHER_TARGET);
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWithScaffold(helper, FLOOR_START, outcomes);
		worker.tasks().enqueue(new MineTask(helper.absolutePos(HIGHER_TARGET)));
		BlockPos[] swapped = {null};
		BlockPos[] foreign = {null};

		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(worker.scaffold().placed().size() >= 3, "pillar not three high yet"))
				.thenExecute(() -> {
					List<WorkerScaffold.ScaffoldBlock> placed = worker.scaffold().placed();
					// Swapped under the worker's feet: still recorded, but no longer the block it placed.
					swapped[0] = placed.get(1).pos();
					helper.getLevel().setBlockAndUpdate(swapped[0], Blocks.ANDESITE.defaultBlockState());
					// The same block the worker scaffolds with, right beside its pillar, but never placed by it.
					BlockPos base = placed.get(0).pos();
					for (Direction side : Direction.Plane.HORIZONTAL) {
						if (helper.getLevel().getBlockState(base.relative(side)).isAir()) {
							foreign[0] = base.relative(side);
							break;
						}
					}
					helper.assertTrue(foreign[0] != null, "no room beside the pillar base " + base);
					helper.getLevel().setBlockAndUpdate(foreign[0], Blocks.COBBLESTONE.defaultBlockState());
				})
				.thenWaitUntil(() -> {
					WorkerDoorGameTest.assertSucceeded(helper, outcomes, "mine");
					helper.assertTrue(worker.scaffold().placed().isEmpty(), "scaffold still recorded: " + worker.scaffold().placed());
				})
				.thenExecute(() -> {
					helper.assertTrue(helper.getLevel().getBlockState(swapped[0]).is(Blocks.ANDESITE),
							"swapped block at " + helper.relativePos(swapped[0]) + " was removed");
					helper.assertTrue(helper.getLevel().getBlockState(foreign[0]).is(Blocks.COBBLESTONE),
							"foreign block at " + helper.relativePos(foreign[0]) + " was removed");
				})
				.thenSucceed();
	}

	@GameTest(structure = WIDE, maxTicks = 600)
	public void neverPlacesOverLava(GameTestHelper helper) {
		buildGap(helper, Blocks.LAVA);
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWithScaffold(helper, NEAR_START, outcomes);
		helper.onEachTick(() -> helper.assertTrue(worker.scaffold().placed().isEmpty(),
				"placed a block over lava: " + worker.scaffold().placed()));
		worker.tasks().enqueue(new MineTask(helper.absolutePos(FAR_TARGET)));

		helper.succeedWhen(() -> {
			assertUnreachable(helper, outcomes);
			helper.assertBlockPresent(Blocks.DIRT, FAR_TARGET);
			for (int x = GAP_X; x < GAP_X + GAP; x++) {
				for (int y = 2; y <= GROUND_TOP; y++) {
					for (int z = 1; z < 7; z++) {
						helper.assertBlockPresent(Blocks.AIR, new BlockPos(x, y, z));
					}
				}
			}
			helper.assertTrue(scaffoldCount(worker) == SCAFFOLD_COUNT, "cobblestone was spent: " + scaffoldCount(worker));
		});
	}
}
