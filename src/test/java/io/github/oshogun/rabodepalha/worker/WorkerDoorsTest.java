package io.github.oshogun.rabodepalha.worker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

class WorkerDoorsTest {
	private static final BlockPos DOOR = new BlockPos(0, 64, 0);

	@Test
	void closesWhenNothingHoldsItOpen() {
		assertFalse(WorkerDoors.shouldKeepOpen(false, false, false, false));
	}

	@Test
	void staysOpenWhileTheWorkerIsInTheDoorway() {
		assertTrue(WorkerDoors.shouldKeepOpen(true, false, false, false));
	}

	@Test
	void staysOpenWhileTheDoorIsOnTheWorkersPath() {
		assertTrue(WorkerDoors.shouldKeepOpen(false, true, false, false));
	}

	@Test
	void staysOpenWhileAnotherEntityIsInTheDoorway() {
		assertTrue(WorkerDoors.shouldKeepOpen(false, false, true, false));
	}

	@Test
	void staysOpenWhileAnotherWorkerIsComing() {
		assertTrue(WorkerDoors.shouldKeepOpen(false, false, false, true));
	}

	@Test
	void anotherWorkerCountsWithinTwoBlocksOnly() {
		assertTrue(DOOR.closerToCenterThan(Vec3.atCenterOf(DOOR).add(1.5, 0, 0), WorkerDoors.OTHER_WORKER_RANGE));
		assertFalse(DOOR.closerToCenterThan(Vec3.atCenterOf(DOOR).add(3, 0, 0), WorkerDoors.OTHER_WORKER_RANGE));
	}

	@Test
	void pathUsesDoorWhenNextNodeIsTheDoor() {
		Path path = line(-2, 3);
		path.setNextNodeIndex(2); // node at x=0
		assertTrue(WorkerDoors.pathUsesDoor(path, DOOR, false));
	}

	@Test
	void pathUsesDoorOneNodeAheadOnlyWhenLookingAhead() {
		Path path = line(-2, 3);
		path.setNextNodeIndex(1); // next is x=-1, the door is the node after
		assertTrue(WorkerDoors.pathUsesDoor(path, DOOR, true));
		assertFalse(WorkerDoors.pathUsesDoor(path, DOOR, false));
	}

	@Test
	void pathUsesDoorJustAfterLeavingIt() {
		Path path = line(-2, 3);
		path.setNextNodeIndex(3); // previous is x=0
		assertTrue(WorkerDoors.pathUsesDoor(path, DOOR, false));
	}

	@Test
	void pathFarFromDoorDoesNotUseIt() {
		Path path = line(-2, 3);
		path.setNextNodeIndex(4); // previous x=1, next x=2
		assertFalse(WorkerDoors.pathUsesDoor(path, DOOR, true));
		assertFalse(WorkerDoors.pathUsesDoor(null, DOOR, true));
	}

	@Test
	void upperDoorHalfIsADoorCell() {
		assertTrue(WorkerDoors.isDoorCell(DOOR, DOOR));
		assertTrue(WorkerDoors.isDoorCell(DOOR.above(), DOOR));
		assertFalse(WorkerDoors.isDoorCell(DOOR.below(), DOOR));
		assertFalse(WorkerDoors.isDoorCell(DOOR.east(), DOOR));
	}

	@Test
	void opensWithinTwoBlocksHorizontally() {
		Vec3 centre = Vec3.atBottomCenterOf(DOOR);
		assertTrue(WorkerDoors.withinOpenRange(centre.add(1.9, 0, 0), DOOR));
		assertTrue(WorkerDoors.withinOpenRange(centre.add(0, 1, 0), DOOR));
		assertFalse(WorkerDoors.withinOpenRange(centre.add(2.5, 0, 0), DOOR));
		assertFalse(WorkerDoors.withinOpenRange(centre.add(0, 2, 0), DOOR));
	}

	@Test
	void climbTrapdoorOpensFromUpToTwoBlocksBelow() {
		Vec3 centre = Vec3.atBottomCenterOf(DOOR);
		assertTrue(WorkerDoors.withinClimbOpenRange(centre, DOOR));
		assertTrue(WorkerDoors.withinClimbOpenRange(centre.add(0, -1, 0), DOOR));
		assertTrue(WorkerDoors.withinClimbOpenRange(centre.add(0, -2, 0), DOOR));
		assertTrue(WorkerDoors.withinClimbOpenRange(centre.add(0, 1, 0), DOOR));
		assertTrue(WorkerDoors.withinClimbOpenRange(centre.add(1.9, -2, 0), DOOR));
	}

	@Test
	void climbTrapdoorStaysShutWhenTooFar() {
		Vec3 centre = Vec3.atBottomCenterOf(DOOR);
		assertFalse(WorkerDoors.withinClimbOpenRange(centre.add(0, -3, 0), DOOR));
		assertFalse(WorkerDoors.withinClimbOpenRange(centre.add(0, 2, 0), DOOR));
		assertFalse(WorkerDoors.withinClimbOpenRange(centre.add(2.5, -1, 0), DOOR));
	}

	/** A straight path along x from {@code fromX} to {@code toX} at the door's y and z. */
	private static Path line(int fromX, int toX) {
		List<Node> nodes = new ArrayList<>();
		for (int x = fromX; x <= toX; x++) {
			nodes.add(new Node(x, DOOR.getY(), DOOR.getZ()));
		}
		return new Path(nodes, new BlockPos(toX, DOOR.getY(), DOOR.getZ()), true);
	}
}
