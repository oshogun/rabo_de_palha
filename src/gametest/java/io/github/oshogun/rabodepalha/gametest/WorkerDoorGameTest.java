package io.github.oshogun.rabodepalha.gametest;

import io.github.oshogun.rabodepalha.registry.ModEntities;
import io.github.oshogun.rabodepalha.task.FailureReason;
import io.github.oshogun.rabodepalha.task.TaskOutcome;
import io.github.oshogun.rabodepalha.worker.WorkerDoors;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class WorkerDoorGameTest {
	private static final BlockPos WORKER_START = new BlockPos(1, 1, 1);
	/** The single opening in the room's west wall, where the door or gate goes. */
	private static final BlockPos DOORWAY = new BlockPos(4, 1, 1);
	private static final BlockPos INSIDE = new BlockPos(6, 1, 5);

	/**
	 * A stone room on x 4..7, z 0..7: walls three blocks high, a stone roof, and one opening at
	 * {@link #DOORWAY} two blocks high. The interior is x 5..6, z 1..6. Outside, only a corridor
	 * along z 0..1 leads to the door; the rest is solid, so nothing in the room's far end is within
	 * mining reach from outside.
	 */
	private static void buildRoom(GameTestHelper helper) {
		WorkerTaskGameTest.layFloor(helper);
		for (int x = 0; x <= 3; x++) {
			for (int z = 2; z <= 7; z++) {
				for (int y = 1; y <= 3; y++) {
					helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
				}
			}
		}
		for (int x = 4; x <= 7; x++) {
			for (int z = 0; z <= 7; z++) {
				boolean wall = x == 4 || x == 7 || z == 0 || z == 7;
				for (int y = 1; y <= 3; y++) {
					if (wall) {
						helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
					}
				}
				helper.setBlock(new BlockPos(x, 4, z), Blocks.STONE);
			}
		}
		helper.setBlock(DOORWAY, Blocks.AIR);
		helper.setBlock(DOORWAY.above(), Blocks.AIR);
	}

	/** Puts {@code door} (both halves) in the doorway, facing along the passage. */
	private static void placeDoor(GameTestHelper helper, Block door, boolean open) {
		BlockState lower = door.defaultBlockState()
				.setValue(DoorBlock.FACING, Direction.EAST)
				.setValue(DoorBlock.OPEN, open);
		helper.setBlock(DOORWAY, lower.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
		helper.setBlock(DOORWAY.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
	}

	/** Puts a closed oak fence gate in the doorway; the cell above stays open so the worker fits. */
	private static void placeGate(GameTestHelper helper) {
		helper.setBlock(DOORWAY, Blocks.OAK_FENCE_GATE.defaultBlockState()
				.setValue(FenceGateBlock.FACING, Direction.EAST)
				.setValue(FenceGateBlock.OPEN, false));
	}

	private static WorkerEntity spawnWorker(GameTestHelper helper, BlockPos at, List<TaskOutcome> outcomes) {
		WorkerEntity worker = helper.spawn(ModEntities.WORKER, at);
		worker.tasks().addListener((task, outcome) -> outcomes.add(outcome));
		return worker;
	}

	private static boolean isOpen(GameTestHelper helper) {
		return helper.getBlockState(DOORWAY).getValue(BlockStateProperties.OPEN);
	}

	/** Records whether the doorway was ever seen open. */
	private static boolean[] watchOpened(GameTestHelper helper) {
		boolean[] seen = {false};
		helper.onEachTick(() -> seen[0] |= isOpen(helper));
		return seen;
	}

	private static void assertSucceeded(GameTestHelper helper, List<TaskOutcome> outcomes, String what) {
		helper.assertTrue(outcomes.size() == 1, what + " not finished yet");
		helper.assertTrue(outcomes.get(0) instanceof TaskOutcome.Succeeded, what + " did not succeed: " + outcomes.get(0));
	}

	private static void assertClosedAndUntracked(GameTestHelper helper, WorkerEntity... workers) {
		helper.assertTrue(!isOpen(helper), "doorway is still open");
		for (WorkerEntity worker : workers) {
			helper.assertTrue(worker.openedDoors().isEmpty(), "worker still tracks " + worker.openedDoors());
		}
	}

	@GameTest(maxTicks = 400)
	public void moveThroughWoodenDoor(GameTestHelper helper) {
		buildRoom(helper);
		placeDoor(helper, Blocks.OAK_DOOR, false);
		boolean[] opened = watchOpened(helper);
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, WORKER_START, outcomes);
		BlockPos target = helper.absolutePos(INSIDE);
		worker.tasks().enqueue(new MoveToTask(target));

		helper.succeedWhen(() -> {
			assertSucceeded(helper, outcomes, "move_to");
			helper.assertTrue(worker.position().distanceTo(Vec3.atBottomCenterOf(target)) <= 1.5, "worker is not at the target");
			helper.assertTrue(opened[0], "door was never opened");
			helper.assertBlockProperty(DOORWAY, DoorBlock.OPEN, false);
			assertClosedAndUntracked(helper, worker);
		});
	}

	@GameTest(maxTicks = 400)
	public void moveThroughFenceGate(GameTestHelper helper) {
		buildRoom(helper);
		placeGate(helper);
		boolean[] opened = watchOpened(helper);
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, WORKER_START, outcomes);
		BlockPos target = helper.absolutePos(INSIDE);
		worker.tasks().enqueue(new MoveToTask(target));

		helper.succeedWhen(() -> {
			assertSucceeded(helper, outcomes, "move_to");
			helper.assertTrue(worker.position().distanceTo(Vec3.atBottomCenterOf(target)) <= 1.5, "worker is not at the target");
			helper.assertTrue(opened[0], "gate was never opened");
			helper.assertBlockProperty(DOORWAY, FenceGateBlock.OPEN, false);
			assertClosedAndUntracked(helper, worker);
		});
	}

	@GameTest(maxTicks = 400)
	public void openDoorStaysOpen(GameTestHelper helper) {
		buildRoom(helper);
		placeDoor(helper, Blocks.OAK_DOOR, true);
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, WORKER_START, outcomes);
		worker.tasks().enqueue(new MoveToTask(helper.absolutePos(INSIDE)));
		helper.onEachTick(() -> helper.assertBlockProperty(DOORWAY, DoorBlock.OPEN, true));

		helper.succeedWhen(() -> {
			assertSucceeded(helper, outcomes, "move_to");
			helper.assertBlockProperty(DOORWAY, DoorBlock.OPEN, true);
			helper.assertTrue(worker.openedDoors().isEmpty(), "worker tracks a door it found open: " + worker.openedDoors());
		});
	}

	@GameTest(maxTicks = 400)
	public void ironDoorUnreachable(GameTestHelper helper) {
		buildRoom(helper);
		placeDoor(helper, Blocks.IRON_DOOR, false);
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, WORKER_START, outcomes);
		worker.tasks().enqueue(new MoveToTask(helper.absolutePos(INSIDE), 200));
		helper.onEachTick(() -> helper.assertBlockProperty(DOORWAY, DoorBlock.OPEN, false));

		EnumSet<FailureReason> expected = EnumSet.of(FailureReason.UNREACHABLE, FailureReason.STUCK, FailureReason.TIMEOUT);
		helper.succeedWhen(() -> {
			helper.assertTrue(outcomes.size() == 1, "move_to not finished yet");
			helper.assertTrue(outcomes.get(0) instanceof TaskOutcome.Failed f && expected.contains(f.reason()),
					"expected UNREACHABLE/STUCK/TIMEOUT, got " + outcomes.get(0));
			helper.assertBlockProperty(DOORWAY, DoorBlock.OPEN, false);
			helper.assertTrue(worker.openedDoors().isEmpty(), "worker tracks an iron door: " + worker.openedDoors());
		});
	}

	/** Whether {@code path}'s next node is in the doorway's cells. */
	private static boolean nextNodeIsDoor(Path path, BlockPos door) {
		if (path == null || path.isDone()) {
			return false;
		}
		BlockPos next = path.getNextNodePos();
		return next.equals(door) || next.equals(door.above());
	}

	@GameTest(maxTicks = 400)
	public void twoWorkersSameDoor(GameTestHelper helper) {
		buildRoom(helper);
		placeDoor(helper, Blocks.OAK_DOOR, false);
		boolean[] opened = watchOpened(helper);
		List<TaskOutcome> firstOutcomes = new ArrayList<>();
		List<TaskOutcome> secondOutcomes = new ArrayList<>();
		// In a row towards the door, a step apart.
		WorkerEntity first = spawnWorker(helper, new BlockPos(2, 1, 1), firstOutcomes);
		WorkerEntity second = spawnWorker(helper, new BlockPos(1, 1, 1), secondOutcomes);
		first.tasks().enqueue(new MoveToTask(helper.absolutePos(new BlockPos(6, 1, 2))));
		second.tasks().enqueue(new MoveToTask(helper.absolutePos(new BlockPos(6, 1, 5))));

		BlockPos door = helper.absolutePos(DOORWAY);
		AABB doorCells = new AABB(door).expandTowards(0, 1, 0);
		Vec3 doorCenter = Vec3.atCenterOf(door);
		boolean[] wasOpen = {false};
		helper.onEachTick(() -> {
			boolean open = isOpen(helper);
			if (!open) {
				for (WorkerEntity worker : List.of(first, second)) {
					helper.assertTrue(!worker.getBoundingBox().intersects(doorCells), "door is closed on a worker standing in it");
					// The moment it shuts, no worker close by may be about to step into it.
					if (wasOpen[0]) {
						helper.assertTrue(!(nextNodeIsDoor(worker.getNavigation().getPath(), door)
										&& worker.position().closerThan(doorCenter, WorkerDoors.OTHER_WORKER_RANGE)),
								"door was shut while a worker was about to walk through it");
					}
				}
			}
			wasOpen[0] = open;
		});

		helper.succeedWhen(() -> {
			assertSucceeded(helper, firstOutcomes, "first move_to");
			assertSucceeded(helper, secondOutcomes, "second move_to");
			helper.assertTrue(opened[0], "door was never opened");
			assertClosedAndUntracked(helper, first, second);
		});
	}

	@GameTest(maxTicks = 400)
	public void mineInsideRoom(GameTestHelper helper) {
		buildRoom(helper);
		placeDoor(helper, Blocks.OAK_DOOR, false);
		BlockPos rel = new BlockPos(6, 1, 6);
		helper.setBlock(rel, Blocks.STONE);
		boolean[] opened = watchOpened(helper);
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = spawnWorker(helper, WORKER_START, outcomes);
		worker.insertOrDrop(new ItemStack(Items.WOODEN_PICKAXE));
		worker.tasks().enqueue(new MineTask(helper.absolutePos(rel)));

		helper.succeedWhen(() -> {
			assertSucceeded(helper, outcomes, "mine");
			helper.assertBlockPresent(Blocks.AIR, rel);
			helper.assertTrue(opened[0], "door was never opened");
			assertClosedAndUntracked(helper, worker);
		});
	}
}
