package io.github.oshogun.rabodepalha.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class IronDoorSwitchesTest {
	/** Lower half of an east-facing door, so the passage runs along X. */
	private static final BlockPos DOOR = new BlockPos(10, 64, 20);
	private static final Direction.Axis AXIS = Direction.Axis.X;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void onlyDoorsThatDontOpenByHandAreIronDoors() {
		assertTrue(IronDoorSwitches.isIronDoor(Blocks.IRON_DOOR.defaultBlockState()));
		assertFalse(IronDoorSwitches.isIronDoor(Blocks.OAK_DOOR.defaultBlockState()));
		assertFalse(IronDoorSwitches.isIronDoor(Blocks.COPPER_DOOR.weathering().unaffected().defaultBlockState()));
		assertFalse(IronDoorSwitches.isIronDoor(Blocks.STONE.defaultBlockState()));
	}

	@Test
	void passageAxisFollowsFacing() {
		BlockState door = Blocks.IRON_DOOR.defaultBlockState();
		assertEquals(Direction.Axis.X, IronDoorSwitches.passageAxis(door.setValue(DoorBlock.FACING, Direction.EAST)));
		assertEquals(Direction.Axis.Z, IronDoorSwitches.passageAxis(door.setValue(DoorBlock.FACING, Direction.NORTH)));
	}

	@Test
	void sideOfSignsAndInsideTheCell() {
		assertEquals(1, IronDoorSwitches.sideOf(DOOR, AXIS, 11.5, 20.5));
		assertEquals(1, IronDoorSwitches.sideOf(DOOR, AXIS, 11.0, 20.5));
		assertEquals(-1, IronDoorSwitches.sideOf(DOOR, AXIS, 9.5, 20.5));
		assertEquals(0, IronDoorSwitches.sideOf(DOOR, AXIS, 10.0, 20.5));
		assertEquals(0, IronDoorSwitches.sideOf(DOOR, AXIS, 10.9, 99.0));
		assertEquals(-1, IronDoorSwitches.sideOf(DOOR, Direction.Axis.Z, 10.5, 19.5));
		assertEquals(1, IronDoorSwitches.sideOf(DOOR, Direction.Axis.Z, 10.5, 21.5));
	}

	@Test
	void switchBoxBounds() {
		// Frame and wall beside the door, the block above it, and up to two blocks out in front.
		assertTrue(IronDoorSwitches.inSwitchBox(DOOR, AXIS, 1, DOOR.offset(0, 0, 1)));
		assertTrue(IronDoorSwitches.inSwitchBox(DOOR, AXIS, 1, DOOR.offset(0, 2, 0)));
		assertTrue(IronDoorSwitches.inSwitchBox(DOOR, AXIS, 1, DOOR.offset(0, -1, 0)));
		assertTrue(IronDoorSwitches.inSwitchBox(DOOR, AXIS, 1, DOOR.offset(1, 0, -1)));
		assertTrue(IronDoorSwitches.inSwitchBox(DOOR, AXIS, 1, DOOR.offset(2, 2, 1)));
		assertTrue(IronDoorSwitches.inSwitchBox(DOOR, AXIS, -1, DOOR.offset(-2, -1, -1)));
		// The two door cells themselves.
		assertFalse(IronDoorSwitches.inSwitchBox(DOOR, AXIS, 1, DOOR));
		assertFalse(IronDoorSwitches.inSwitchBox(DOOR, AXIS, 1, DOOR.above()));
		// Out of range.
		assertFalse(IronDoorSwitches.inSwitchBox(DOOR, AXIS, 1, DOOR.offset(3, 0, 0)));
		assertFalse(IronDoorSwitches.inSwitchBox(DOOR, AXIS, 1, DOOR.offset(1, 0, 2)));
		assertFalse(IronDoorSwitches.inSwitchBox(DOOR, AXIS, 1, DOOR.offset(1, 3, 0)));
		assertFalse(IronDoorSwitches.inSwitchBox(DOOR, AXIS, 1, DOOR.offset(1, -2, 0)));
		// The far side.
		assertFalse(IronDoorSwitches.inSwitchBox(DOOR, AXIS, 1, DOOR.offset(-1, 0, 0)));
		assertFalse(IronDoorSwitches.inSwitchBox(DOOR, AXIS, -1, DOOR.offset(1, 0, 1)));
		// Inside the door cell there is no side.
		assertFalse(IronDoorSwitches.inSwitchBox(DOOR, AXIS, 0, DOOR.offset(0, 0, 1)));
	}

	@Test
	void frontPlateCellIsRightInFront() {
		assertTrue(IronDoorSwitches.isFrontPlateCell(DOOR, AXIS, 1, DOOR.offset(1, 0, 0)));
		assertTrue(IronDoorSwitches.isFrontPlateCell(DOOR, AXIS, -1, DOOR.offset(-1, 0, 0)));
		assertFalse(IronDoorSwitches.isFrontPlateCell(DOOR, AXIS, 1, DOOR.offset(-1, 0, 0)));
		assertFalse(IronDoorSwitches.isFrontPlateCell(DOOR, AXIS, 1, DOOR.offset(2, 0, 0)));
		assertFalse(IronDoorSwitches.isFrontPlateCell(DOOR, AXIS, 1, DOOR.offset(1, 0, 1)));
		assertFalse(IronDoorSwitches.isFrontPlateCell(DOOR, AXIS, 1, DOOR.offset(1, 1, 0)));
	}

	@Test
	void findsSwitchesOnTheApproachSideNearestFirst() {
		Map<BlockPos, BlockState> layout = new HashMap<>();
		BlockPos wallButton = DOOR.offset(1, 0, 1);
		BlockPos leverAbove = DOOR.offset(1, 2, 0);
		BlockPos plate = DOOR.offset(1, 0, 0);
		layout.put(wallButton, Blocks.STONE_BUTTON.defaultBlockState());
		layout.put(leverAbove, Blocks.LEVER.defaultBlockState());
		layout.put(plate, Blocks.STONE_PRESSURE_PLATE.defaultBlockState());
		layout.put(DOOR.offset(2, 0, 0), Blocks.OAK_PRESSURE_PLATE.defaultBlockState());
		layout.put(DOOR.offset(-1, 0, 1), Blocks.OAK_BUTTON.defaultBlockState());

		List<IronDoorSwitches.Switch> near = IronDoorSwitches.switchesOnSide(DOOR, AXIS, 1, lookup(layout));
		assertEquals(List.of(new IronDoorSwitches.Switch(plate, IronDoorSwitches.Kind.PLATE),
				new IronDoorSwitches.Switch(wallButton, IronDoorSwitches.Kind.BUTTON),
				new IronDoorSwitches.Switch(leverAbove, IronDoorSwitches.Kind.LEVER)), near);

		List<IronDoorSwitches.Switch> far = IronDoorSwitches.switchesOnSide(DOOR, AXIS, -1, lookup(layout));
		assertEquals(List.of(new IronDoorSwitches.Switch(DOOR.offset(-1, 0, 1), IronDoorSwitches.Kind.BUTTON)), far);

		assertTrue(IronDoorSwitches.switchesOnSide(DOOR, AXIS, 0, lookup(layout)).isEmpty());
	}

	@Test
	void plateTwoCellsOutDoesNotCount() {
		Map<BlockPos, BlockState> layout = new HashMap<>();
		layout.put(DOOR.offset(2, 0, 0), Blocks.STONE_PRESSURE_PLATE.defaultBlockState());
		assertTrue(IronDoorSwitches.switchesOnSide(DOOR, AXIS, 1, lookup(layout)).isEmpty());
		assertFalse(IronDoorSwitches.hasAnySwitch(DOOR, AXIS, lookup(layout)));
	}

	@Test
	void hasAnySwitch() {
		assertFalse(IronDoorSwitches.hasAnySwitch(DOOR, AXIS, lookup(Map.of())));
		Map<BlockPos, BlockState> layout = Map.of(DOOR.offset(-1, 0, -1), Blocks.LEVER.defaultBlockState());
		assertTrue(IronDoorSwitches.hasAnySwitch(DOOR, AXIS, lookup(layout)));
	}

	private static Function<BlockPos, BlockState> lookup(Map<BlockPos, BlockState> layout) {
		return pos -> layout.getOrDefault(pos.immutable(), Blocks.AIR.defaultBlockState());
	}
}
