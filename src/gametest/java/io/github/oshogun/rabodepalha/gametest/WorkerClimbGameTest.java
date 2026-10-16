package io.github.oshogun.rabodepalha.gametest;

import io.github.oshogun.rabodepalha.task.FailureReason;
import io.github.oshogun.rabodepalha.task.TaskOutcome;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;
import io.github.oshogun.rabodepalha.worker.task.MineTask;
import io.github.oshogun.rabodepalha.worker.task.MoveToTask;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.Vec3;

/**
 * Workers climbing between two floors. {@link #buildTwoFloors} raises a solid stone block over z 4..7, its top
 * the upper floor. A shaft at {@link #COLUMN_X}, z 5 goes up through it, ending in a one-cell hole in the upper
 * floor, and a corridor one block lower than the floor leads into the shaft from the ground floor (z 0..3).
 * The climbable column stands in the shaft against the stone at z 6. The two floors are joined only by it.
 */
public class WorkerClimbGameTest {
	private static final BlockPos GROUND = new BlockPos(1, 1, 1);
	private static final int COLUMN_X = 3;
	private static final int COLUMN_Z = 5;

	/** A ladder on the north face of the stone at z 6. */
	private static final BlockState LADDER = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH);
	/** A vine hanging on the stone at z 6. */
	private static final BlockState VINE = Blocks.VINE.defaultBlockState().setValue(VineBlock.SOUTH, true);
	/** Scaffolding already settled on the ground, so the tower doesn't collapse before its first tick. */
	private static final BlockState SCAFFOLDING = Blocks.SCAFFOLDING.defaultBlockState().setValue(ScaffoldingBlock.DISTANCE, 0);

	/**
	 * Lays the ground floor and the stone block of the upper floor, whose top is at {@code floorY}, then fills the
	 * notch with {@code climbable} from y 1 up to {@code columnTop}. The stone goes in first so ladders and vines
	 * have their wall.
	 */
	private static void buildTwoFloors(GameTestHelper helper, int floorY, BlockState climbable, int columnTop) {
		WorkerTaskGameTest.layFloor(helper);
		for (int x = 0; x < 8; x++) {
			for (int z = COLUMN_Z - 1; z < 8; z++) {
				for (int y = 1; y <= floorY; y++) {
					helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
				}
			}
		}
		for (int y = 1; y <= floorY; y++) {
			helper.setBlock(column(y), Blocks.AIR);
			if (y < floorY) {
				helper.setBlock(column(y).north(), Blocks.AIR);
			}
		}
		for (int y = 1; y <= columnTop; y++) {
			helper.setBlock(column(y), climbable);
		}
	}

	private static BlockPos column(int y) {
		return new BlockPos(COLUMN_X, y, COLUMN_Z);
	}

	/** Where the worker stands on the upper floor whose top is at {@code floorY}. */
	private static BlockPos upstairs(int floorY) {
		return new BlockPos(5, floorY + 1, 6);
	}

	/** Fails the test the moment the worker loses health. */
	private static void watchHealth(GameTestHelper helper, WorkerEntity worker) {
		helper.onEachTick(() -> helper.assertTrue(worker.getHealth() >= worker.getMaxHealth(),
				"worker took damage: " + worker.getHealth() + "/" + worker.getMaxHealth() + " from "
						+ (worker.getLastDamageSource() == null ? "?" : worker.getLastDamageSource().getMsgId())
						+ " at " + helper.relativePos(worker.blockPosition())));
	}

	/** Climbs from the ground to the upper floor at y 4 and back, without damage, and ends not sneaking. */
	private static void upAndDown(GameTestHelper helper, BlockState climbable) {
		int floorY = 4;
		buildTwoFloors(helper, floorY, climbable, floorY);
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = WorkerDoorGameTest.spawnWorker(helper, GROUND, outcomes);
		watchHealth(helper, worker);
		BlockPos up = helper.absolutePos(upstairs(floorY));
		BlockPos back = helper.absolutePos(GROUND);
		boolean[] wasUp = {false};
		helper.onEachTick(() -> wasUp[0] |= worker.position().distanceTo(Vec3.atBottomCenterOf(up)) <= 1.5);
		worker.tasks().enqueue(new MoveToTask(up));
		worker.tasks().enqueue(new MoveToTask(back));

		helper.succeedWhen(() -> {
			helper.assertTrue(outcomes.size() == 2, "both moves not finished yet: " + outcomes);
			helper.assertTrue(outcomes.get(0) instanceof TaskOutcome.Succeeded, "move up did not succeed: " + outcomes.get(0));
			helper.assertTrue(outcomes.get(1) instanceof TaskOutcome.Succeeded, "move down did not succeed: " + outcomes.get(1));
			helper.assertTrue(wasUp[0], "worker never reached the upper floor");
			helper.assertTrue(worker.position().distanceTo(Vec3.atBottomCenterOf(back)) <= 1.5, "worker is not back on the ground");
			helper.assertTrue(!worker.isShiftKeyDown(), "worker is still sneaking");
		});
	}

	@GameTest(maxTicks = 600)
	public void ladderUpAndDown(GameTestHelper helper) {
		upAndDown(helper, LADDER);
	}

	@GameTest(maxTicks = 600)
	public void vineUpAndDown(GameTestHelper helper) {
		upAndDown(helper, VINE);
	}

	@GameTest(maxTicks = 600)
	public void scaffoldingUpAndDown(GameTestHelper helper) {
		upAndDown(helper, SCAFFOLDING);
	}

	@GameTest(maxTicks = 600)
	public void trapdoorShaft(GameTestHelper helper) {
		int floorY = 4;
		buildTwoFloors(helper, floorY, LADDER, floorY - 1);
		BlockPos hatch = column(floorY);
		// Flush with the upper floor, lying against the same wall as the ladder when open.
		helper.setBlock(hatch, Blocks.OAK_TRAPDOOR.defaultBlockState()
				.setValue(TrapDoorBlock.FACING, Direction.NORTH)
				.setValue(TrapDoorBlock.HALF, Half.TOP)
				.setValue(TrapDoorBlock.OPEN, false));
		boolean[] opened = {false};
		helper.onEachTick(() -> opened[0] |= helper.getBlockState(hatch).getValue(TrapDoorBlock.OPEN));
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = WorkerDoorGameTest.spawnWorker(helper, GROUND, outcomes);
		BlockPos target = helper.absolutePos(upstairs(floorY));
		worker.tasks().enqueue(new MoveToTask(target));

		helper.succeedWhen(() -> {
			WorkerDoorGameTest.assertSucceeded(helper, outcomes, "move_to");
			helper.assertTrue(worker.position().distanceTo(Vec3.atBottomCenterOf(target)) <= 1.5, "worker is not upstairs");
			helper.assertTrue(opened[0], "trapdoor was never opened");
			helper.assertBlockProperty(hatch, TrapDoorBlock.OPEN, false);
			helper.assertTrue(worker.openedDoors().isEmpty(), "worker still tracks " + worker.openedDoors());
		});
	}

	@GameTest(maxTicks = 600)
	public void mineOnUpperFloor(GameTestHelper helper) {
		int floorY = 4;
		buildTwoFloors(helper, floorY, LADDER, floorY);
		// On the far corner of the upper floor: out of reach from anywhere on the ground floor.
		BlockPos rel = new BlockPos(6, floorY + 1, 7);
		helper.setBlock(rel, Blocks.STONE);
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = WorkerDoorGameTest.spawnWorker(helper, GROUND, outcomes);
		worker.insertOrDrop(new ItemStack(Items.WOODEN_PICKAXE));
		worker.tasks().enqueue(new MineTask(helper.absolutePos(rel)));

		helper.succeedWhen(() -> {
			WorkerDoorGameTest.assertSucceeded(helper, outcomes, "mine");
			helper.assertBlockPresent(Blocks.AIR, rel);
		});
	}

	@GameTest(maxTicks = 400)
	public void ladderShortOfFloor(GameTestHelper helper) {
		int floorY = 4;
		buildTwoFloors(helper, floorY, LADDER, floorY - 2);
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = WorkerDoorGameTest.spawnWorker(helper, GROUND, outcomes);
		worker.tasks().enqueue(new MoveToTask(helper.absolutePos(upstairs(floorY)), 200));

		EnumSet<FailureReason> expected = EnumSet.of(FailureReason.UNREACHABLE, FailureReason.STUCK, FailureReason.TIMEOUT);
		helper.succeedWhen(() -> {
			helper.assertTrue(outcomes.size() == 1, "move_to not finished yet");
			helper.assertTrue(outcomes.get(0) instanceof TaskOutcome.Failed f && expected.contains(f.reason()),
					"expected UNREACHABLE/STUCK/TIMEOUT, got " + outcomes.get(0));
			helper.assertTrue(!worker.isShiftKeyDown(), "worker is still sneaking");
		});
	}

	/**
	 * A worker standing on a one-block step inside {@code climbable}, on the step's edge, walks down to the
	 * ground. Stone at z 5, y 2 holds a vine up.
	 */
	private static void stepDownFrom(GameTestHelper helper, BlockState climbable) {
		WorkerTaskGameTest.layFloor(helper);
		for (int x = 0; x < 8; x++) {
			for (int z = 4; z < 8; z++) {
				helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
			}
		}
		helper.setBlock(new BlockPos(2, 2, 5), Blocks.STONE);
		BlockPos start = new BlockPos(2, 2, 4);
		helper.setBlock(start, climbable);
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = WorkerDoorGameTest.spawnWorker(helper, start, outcomes);
		BlockPos target = helper.absolutePos(new BlockPos(2, 1, 1));
		worker.tasks().enqueue(new MoveToTask(target));

		helper.succeedWhen(() -> {
			WorkerDoorGameTest.assertSucceeded(helper, outcomes, "move_to");
			helper.assertTrue(worker.position().distanceTo(Vec3.atBottomCenterOf(target)) <= 1.5, "worker is not on the ground");
			helper.assertTrue(!worker.isShiftKeyDown(), "worker is still sneaking");
		});
	}

	@GameTest(maxTicks = 300)
	public void stepDownFromGroundVine(GameTestHelper helper) {
		stepDownFrom(helper, VINE);
	}

	@GameTest(maxTicks = 300)
	public void stepDownFromScaffolding(GameTestHelper helper) {
		stepDownFrom(helper, SCAFFOLDING);
	}

	@GameTest(maxTicks = 600)
	public void noFallDamageLongLadder(GameTestHelper helper) {
		// Five ladder blocks, the tallest that fits the 8-high test structure with head room upstairs.
		int floorY = 5;
		buildTwoFloors(helper, floorY, LADDER, floorY);
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = WorkerDoorGameTest.spawnWorker(helper, upstairs(floorY), outcomes);
		watchHealth(helper, worker);
		BlockPos target = helper.absolutePos(GROUND);
		worker.tasks().enqueue(new MoveToTask(target));

		helper.succeedWhen(() -> {
			WorkerDoorGameTest.assertSucceeded(helper, outcomes, "move_to");
			helper.assertTrue(worker.position().distanceTo(Vec3.atBottomCenterOf(target)) <= 1.5, "worker is not on the ground");
			helper.assertTrue(worker.getHealth() >= worker.getMaxHealth(), "worker took damage");
			helper.assertTrue(!worker.isShiftKeyDown(), "worker is still sneaking");
		});
	}
}
