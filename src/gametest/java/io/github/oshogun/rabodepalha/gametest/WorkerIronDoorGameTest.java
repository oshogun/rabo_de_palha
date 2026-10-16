package io.github.oshogun.rabodepalha.gametest;

import static io.github.oshogun.rabodepalha.gametest.WorkerDoorGameTest.DOORWAY;
import static io.github.oshogun.rabodepalha.gametest.WorkerDoorGameTest.INSIDE;
import static io.github.oshogun.rabodepalha.gametest.WorkerDoorGameTest.WORKER_START;

import io.github.oshogun.rabodepalha.task.FailureReason;
import io.github.oshogun.rabodepalha.task.TaskOutcome;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;
import io.github.oshogun.rabodepalha.worker.task.MoveToTask;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.Vec3;

/**
 * Workers getting through iron doors with the switches beside them. The room is
 * {@link WorkerDoorGameTest#buildRoom}'s, with an iron door in its doorway. Wall switches hang on the wall block
 * {@link #WALL} next to the door, which powers the door when a switch on it is on.
 */
public class WorkerIronDoorGameTest {
	/** The wall block beside the door that the wall switches are attached to. */
	private static final BlockPos WALL = new BlockPos(4, 1, 2);
	/** Outside, on the wall beside the door; carved out of the solid fill. */
	private static final BlockPos OUTSIDE_SWITCH = new BlockPos(3, 1, 2);
	/** Inside the room, on the wall beside the door. */
	private static final BlockPos INSIDE_SWITCH = new BlockPos(5, 1, 2);

	private static void ironRoom(GameTestHelper helper) {
		WorkerDoorGameTest.buildRoom(helper);
		WorkerDoorGameTest.placeDoor(helper, Blocks.IRON_DOOR, false);
	}

	/** A switch on {@link #WALL}: {@code facing} points away from the wall. */
	private static void wallSwitch(GameTestHelper helper, Block block, BlockPos pos, Direction facing) {
		helper.setBlock(pos, block.defaultBlockState()
				.setValue(ButtonBlock.FACE, AttachFace.WALL)
				.setValue(ButtonBlock.FACING, facing));
	}

	private static void outsideSwitch(GameTestHelper helper, Block block) {
		wallSwitch(helper, block, OUTSIDE_SWITCH, Direction.WEST);
	}

	private static void insideSwitch(GameTestHelper helper, Block block) {
		wallSwitch(helper, block, INSIDE_SWITCH, Direction.EAST);
	}

	private static boolean isOpen(GameTestHelper helper) {
		return WorkerDoorGameTest.isOpen(helper);
	}

	private static String doorText(GameTestHelper helper) {
		BlockPos door = helper.absolutePos(DOORWAY);
		return door.getX() + " " + door.getY() + " " + door.getZ();
	}

	@GameTest(maxTicks = 600)
	public void buttonEachSideBothWays(GameTestHelper helper) {
		ironRoom(helper);
		outsideSwitch(helper, Blocks.STONE_BUTTON);
		insideSwitch(helper, Blocks.STONE_BUTTON);
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = WorkerDoorGameTest.spawnWorker(helper, WORKER_START, outcomes);
		BlockPos inside = helper.absolutePos(INSIDE);
		BlockPos back = helper.absolutePos(WORKER_START);
		worker.tasks().enqueue(new MoveToTask(inside));
		worker.tasks().enqueue(new MoveToTask(back));

		helper.succeedWhen(() -> {
			helper.assertTrue(outcomes.size() == 2, "both moves not finished yet: " + outcomes);
			helper.assertTrue(outcomes.get(0) instanceof TaskOutcome.Succeeded, "move inside did not succeed: " + outcomes.get(0));
			helper.assertTrue(outcomes.get(1) instanceof TaskOutcome.Succeeded, "move back did not succeed: " + outcomes.get(1));
			helper.assertTrue(worker.position().distanceTo(Vec3.atBottomCenterOf(back)) <= 1.5, "worker is not back outside");
			helper.assertBlockProperty(DOORWAY, DoorBlock.OPEN, false);
		});
	}

	@GameTest(maxTicks = 400)
	public void leverEachSideEndsClosed(GameTestHelper helper) {
		ironRoom(helper);
		outsideSwitch(helper, Blocks.LEVER);
		insideSwitch(helper, Blocks.LEVER);
		boolean[] opened = {false};
		helper.onEachTick(() -> opened[0] |= isOpen(helper));
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = WorkerDoorGameTest.spawnWorker(helper, WORKER_START, outcomes);
		BlockPos target = helper.absolutePos(INSIDE);
		worker.tasks().enqueue(new MoveToTask(target));

		helper.succeedWhen(() -> {
			WorkerDoorGameTest.assertSucceeded(helper, outcomes, "move_to");
			helper.assertTrue(worker.position().distanceTo(Vec3.atBottomCenterOf(target)) <= 1.5, "worker is not at the target");
			helper.assertTrue(opened[0], "door was never opened");
			helper.assertBlockProperty(DOORWAY, DoorBlock.OPEN, false);
			helper.assertBlockProperty(OUTSIDE_SWITCH, LeverBlock.POWERED, false);
			helper.assertBlockProperty(INSIDE_SWITCH, LeverBlock.POWERED, false);
		});
	}

	@GameTest(maxTicks = 400)
	public void leverOneSideLeftOpen(GameTestHelper helper) {
		ironRoom(helper);
		outsideSwitch(helper, Blocks.LEVER);
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = WorkerDoorGameTest.spawnWorker(helper, WORKER_START, outcomes);
		BlockPos target = helper.absolutePos(INSIDE);
		worker.tasks().enqueue(new MoveToTask(target));

		helper.succeedWhen(() -> {
			WorkerDoorGameTest.assertSucceeded(helper, outcomes, "move_to");
			helper.assertTrue(worker.position().distanceTo(Vec3.atBottomCenterOf(target)) <= 1.5, "worker is not at the target");
			helper.assertBlockProperty(DOORWAY, DoorBlock.OPEN, true);
			String note = ((TaskOutcome.Succeeded) outcomes.get(0)).note();
			helper.assertTrue(note.contains("left the iron door at " + doorText(helper) + " open"),
					"report does not say the door was left open: " + note);
		});
	}

	@GameTest(maxTicks = 400)
	public void pressurePlateInFront(GameTestHelper helper) {
		ironRoom(helper);
		helper.setBlock(new BlockPos(3, 1, 1), Blocks.STONE_PRESSURE_PLATE);
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = WorkerDoorGameTest.spawnWorker(helper, WORKER_START, outcomes);
		BlockPos target = helper.absolutePos(INSIDE);
		worker.tasks().enqueue(new MoveToTask(target));

		helper.succeedWhen(() -> {
			WorkerDoorGameTest.assertSucceeded(helper, outcomes, "move_to");
			helper.assertTrue(worker.position().distanceTo(Vec3.atBottomCenterOf(target)) <= 1.5, "worker is not at the target");
		});
	}

	@GameTest(maxTicks = 400)
	public void unwiredSwitchFailsCleanly(GameTestHelper helper) {
		ironRoom(helper);
		// A floor button in the switch box that only powers the floor under it, not the door.
		BlockPos button = new BlockPos(2, 1, 2);
		helper.setBlock(button, Blocks.STONE_BUTTON.defaultBlockState()
				.setValue(ButtonBlock.FACE, AttachFace.FLOOR)
				.setValue(ButtonBlock.FACING, Direction.NORTH));
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = WorkerDoorGameTest.spawnWorker(helper, WORKER_START, outcomes);
		worker.tasks().enqueue(new MoveToTask(helper.absolutePos(INSIDE)));
		helper.onEachTick(() -> helper.assertBlockProperty(DOORWAY, DoorBlock.OPEN, false));

		BlockPos door = helper.absolutePos(DOORWAY);
		helper.succeedWhen(() -> {
			helper.assertTrue(outcomes.size() == 1, "move_to not finished yet");
			helper.assertTrue(outcomes.get(0) instanceof TaskOutcome.Failed f && f.reason() == FailureReason.UNREACHABLE
							&& f.detail().contains("no way through the iron door at " + door.toShortString()),
					"expected UNREACHABLE through the iron door, got " + outcomes.get(0));
			helper.assertTrue(worker.ironDoors().isRefused(door, helper.getLevel().getGameTime()),
					"door is not remembered as refused");
		});
	}

	@GameTest(maxTicks = 400)
	public void openRouteBeatsSwitchedDoor(GameTestHelper helper) {
		ironRoom(helper);
		outsideSwitch(helper, Blocks.STONE_BUTTON);
		insideSwitch(helper, Blocks.STONE_BUTTON);
		// An open route: a corridor down x 1 and along z 6 to a second opening in the room's west wall.
		for (int y = 1; y <= 2; y++) {
			for (int z = 2; z <= 6; z++) {
				helper.setBlock(new BlockPos(1, y, z), Blocks.AIR);
			}
			for (int x = 2; x <= 4; x++) {
				helper.setBlock(new BlockPos(x, y, 6), Blocks.AIR);
			}
		}
		List<TaskOutcome> outcomes = new ArrayList<>();
		WorkerEntity worker = WorkerDoorGameTest.spawnWorker(helper, WORKER_START, outcomes);
		BlockPos target = helper.absolutePos(INSIDE);
		worker.tasks().enqueue(new MoveToTask(target));
		helper.onEachTick(() -> {
			helper.assertBlockProperty(DOORWAY, DoorBlock.OPEN, false);
			helper.assertBlockProperty(OUTSIDE_SWITCH, ButtonBlock.POWERED, false);
			helper.assertBlockProperty(INSIDE_SWITCH, ButtonBlock.POWERED, false);
		});

		helper.succeedWhen(() -> {
			WorkerDoorGameTest.assertSucceeded(helper, outcomes, "move_to");
			helper.assertTrue(worker.position().distanceTo(Vec3.atBottomCenterOf(target)) <= 1.5, "worker is not at the target");
		});
	}
}
