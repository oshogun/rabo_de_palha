package io.github.oshogun.rabodepalha.worker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The guess at whether a switch near a closed iron door opens it. The pathfinder can't simulate redstone,
 * so this only looks at what is placed around the door; the worker checks the guess on arrival.
 *
 * <p>The door's passage axis is the axis of its {@link DoorBlock#FACING}. For the side a worker approaches
 * from, let {@code o} be the offset along that axis toward the worker, {@code p} the offset across it and
 * {@code dy} the height above the door's lower half. A lever or button counts when {@code 0 <= o <= 2},
 * {@code |p| <= 1} and {@code -1 <= dy <= 2}, outside the two door cells: on the door frame or the wall
 * beside it, on the block above the door, or one or two blocks out in front. A pressure plate counts only
 * on the cell right in front of the door ({@code o == 1, p == 0, dy == 0}); vanilla triggers wooden and
 * stone plates for mobs, so it needs no action.
 *
 * <p>Reads block states through a function and touches no world, so it is unit-testable.
 */
public final class IronDoorSwitches {
	/** Extra path cost for stepping into a switched iron door, so an open route of similar length wins. */
	public static final float IRON_DOOR_MALUS = 8f;
	/** How long to wait for the door to open after using a switch. */
	public static final int WAIT_TICKS = 20;
	/** How many times to use a switch before giving up on the door. */
	public static final int MAX_PRESSES = 3;
	/** How long a door that a switch failed to open stays impassable for the worker. */
	public static final int REFUSE_TICKS = 1200;

	private IronDoorSwitches() {
	}

	public enum Kind {
		LEVER,
		BUTTON,
		PLATE
	}

	/** A switch that looks like it opens a door. */
	public record Switch(BlockPos pos, Kind kind) {
	}

	/** A door that doesn't open by hand, such as an iron door. */
	public static boolean isIronDoor(BlockState state) {
		return state.getBlock() instanceof DoorBlock door && !door.type().canOpenByHand();
	}

	/** The axis a worker walks along to pass through {@code door}. */
	public static Direction.Axis passageAxis(BlockState door) {
		return door.getValue(DoorBlock.FACING).getAxis();
	}

	/** Which side of the door's cell {@code (x, z)} is on along {@code axis}: +1, -1, or 0 inside the cell. */
	public static int sideOf(BlockPos door, Direction.Axis axis, double x, double z) {
		double c = axis == Direction.Axis.X ? x : z;
		int min = door.get(axis);
		if (c < min) {
			return -1;
		}
		if (c >= min + 1) {
			return 1;
		}
		return 0;
	}

	/** Whether {@code cell} is where a lever or button for the door's {@code side} would be. */
	public static boolean inSwitchBox(BlockPos door, Direction.Axis axis, int side, BlockPos cell) {
		if (side == 0) {
			return false;
		}
		int o = alongOffset(door, axis, side, cell);
		int p = acrossOffset(door, axis, cell);
		int dy = cell.getY() - door.getY();
		if (o < 0 || o > 2 || Math.abs(p) > 1 || dy < -1 || dy > 2) {
			return false;
		}
		return !(o == 0 && p == 0 && (dy == 0 || dy == 1));
	}

	/** Whether {@code cell} is right in front of the door on {@code side}, where a pressure plate counts. */
	public static boolean isFrontPlateCell(BlockPos door, Direction.Axis axis, int side, BlockPos cell) {
		return side != 0 && alongOffset(door, axis, side, cell) == 1 && acrossOffset(door, axis, cell) == 0
				&& cell.getY() == door.getY();
	}

	/**
	 * The switches on {@code side} of the door whose lower half is at {@code door}, nearest the door first.
	 */
	public static List<Switch> switchesOnSide(BlockPos door, Direction.Axis axis, int side,
			Function<BlockPos, BlockState> states) {
		List<Switch> found = new ArrayList<>();
		if (side == 0) {
			return found;
		}
		for (BlockPos cell : BlockPos.betweenClosed(door.offset(-2, -1, -2), door.offset(2, 2, 2))) {
			BlockState state = states.apply(cell);
			if (state == null) {
				continue;
			}
			if (Usables.isSwitch(state) && inSwitchBox(door, axis, side, cell)) {
				Kind kind = Usables.kind(state).get() == Usables.Kind.LEVER ? Kind.LEVER : Kind.BUTTON;
				found.add(new Switch(cell.immutable(), kind));
			} else if (state.getBlock() instanceof BasePressurePlateBlock && isFrontPlateCell(door, axis, side, cell)) {
				found.add(new Switch(cell.immutable(), Kind.PLATE));
			}
		}
		found.sort(Comparator.<Switch>comparingDouble(s -> s.pos().distSqr(door)).thenComparing(Switch::pos));
		return found;
	}

	/** Whether either side of the door has a switch that looks like it opens it. */
	public static boolean hasAnySwitch(BlockPos door, Direction.Axis axis, Function<BlockPos, BlockState> states) {
		return !switchesOnSide(door, axis, 1, states).isEmpty() || !switchesOnSide(door, axis, -1, states).isEmpty();
	}

	private static int alongOffset(BlockPos door, Direction.Axis axis, int side, BlockPos cell) {
		return (cell.get(axis) - door.get(axis)) * side;
	}

	private static int acrossOffset(BlockPos door, Direction.Axis axis, BlockPos cell) {
		Direction.Axis across = axis == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
		return cell.get(across) - door.get(across);
	}
}
