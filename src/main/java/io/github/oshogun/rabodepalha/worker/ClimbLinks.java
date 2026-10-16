package io.github.oshogun.rabodepalha.worker;

import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The rules for climb links in worker paths: up and down a column of climbable blocks, and from an upper
 * floor down into the top of a shaft. One path node per climbed block.
 *
 * <p>A cell is climbable when it is in {@link BlockTags#CLIMBABLE} (ladders, vines, twisting and weeping
 * vines, scaffolding, cave vines), or holds an open trapdoor above a ladder with the same facing, as in
 * vanilla's {@code LivingEntity#trapdoorUsableAsLadder}. A closed trapdoor of that kind that opens by hand
 * is a valid link target too; the worker opens it on the way. Iron trapdoors stay blocked.
 *
 * <p>Reads block states through a function and touches no world, so it is unit-testable.
 */
public final class ClimbLinks {
	/** The tallest column a worker climbs in one go. */
	public static final int MAX_CLIMB = 16;
	/** Extra path cost per climbed block, so stairs or ramps of similar length win over a climb. */
	public static final float CLIMB_MALUS = 1.0f;

	private ClimbLinks() {
	}

	/** Which way a path step moves along a climb column. */
	public enum Segment {
		UP,
		DOWN,
		NONE
	}

	/** Whether the block is climbable on its own, through the vanilla tag. */
	public static boolean isClimbable(BlockState state) {
		return state.is(BlockTags.CLIMBABLE);
	}

	/** Whether {@code pos} holds a trapdoor lying flat against the wall of the ladder below it, open or not. */
	public static boolean isClimbTrapdoor(Function<BlockPos, BlockState> get, BlockPos pos) {
		BlockState state = get.apply(pos);
		if (!(state.getBlock() instanceof TrapDoorBlock)) {
			return false;
		}
		BlockState below = get.apply(pos.below());
		return below.is(Blocks.LADDER) && below.getValue(LadderBlock.FACING) == state.getValue(TrapDoorBlock.FACING);
	}

	/** Whether {@code pos} holds a closed climb trapdoor that a worker can open by hand. */
	public static boolean isClosedClimbTrapdoor(Function<BlockPos, BlockState> get, BlockPos pos) {
		if (!isClimbTrapdoor(get, pos)) {
			return false;
		}
		BlockState state = get.apply(pos);
		return !state.getValue(TrapDoorBlock.OPEN)
				&& Usables.trapdoorType((TrapDoorBlock) state.getBlock()).canOpenByHand();
	}

	/** Whether a mob inside {@code pos} is climbing: a climbable block, or an open climb trapdoor. */
	public static boolean climbCell(Function<BlockPos, BlockState> get, BlockPos pos) {
		return isClimbable(get.apply(pos)) || isClimbTrapdoor(get, pos) && get.apply(pos).getValue(TrapDoorBlock.OPEN);
	}

	/** Whether a climbing worker's body fits in {@code pos}: no collision, a climb cell, or a hatch it can open. */
	public static boolean passableForClimb(Function<BlockPos, BlockState> get, BlockPos pos) {
		return noCollision(get, pos) || climbCell(get, pos) || isClosedClimbTrapdoor(get, pos);
	}

	/** How many climb cells run down from {@code pos} without a gap, counting {@code pos}, at most {@code cap}. */
	public static int climbRunBelow(Function<BlockPos, BlockState> get, BlockPos pos, int cap) {
		int run = 0;
		BlockPos.MutableBlockPos cursor = pos.mutable();
		while (run < cap && climbCell(get, cursor)) {
			run++;
			cursor.move(Direction.DOWN);
		}
		return run;
	}

	/**
	 * Whether a worker with its feet at {@code feet} can climb one block up: it is in a climb cell, the cell
	 * above is a climb cell or a hatch it can open, there is head room above that, and the column below is
	 * shorter than {@code cap}.
	 */
	public static boolean canClimbUp(Function<BlockPos, BlockState> get, BlockPos feet, int cap) {
		if (!climbCell(get, feet)) {
			return false;
		}
		BlockPos above = feet.above();
		if (!climbCell(get, above) && !isClosedClimbTrapdoor(get, above)) {
			return false;
		}
		return passableForClimb(get, above.above()) && climbRunBelow(get, feet, cap) < cap;
	}

	/** Whether a worker with its feet at {@code feet} can climb one block down into the column below. */
	public static boolean canClimbDown(Function<BlockPos, BlockState> get, BlockPos feet) {
		BlockPos below = feet.below();
		return climbCell(get, below) || isClosedClimbTrapdoor(get, below);
	}

	/**
	 * Whether a worker can step from a floor into {@code side} and down into the top of the shaft below it:
	 * {@code side} and the cell above it let the worker through, and the cell below is a climb cell or a
	 * hatch it can open.
	 */
	public static boolean climbEntryBelow(Function<BlockPos, BlockState> get, BlockPos side) {
		if (!noCollision(get, side) && !isClimbTrapdoor(get, side)) {
			return false;
		}
		if (!passableForClimb(get, side.above())) {
			return false;
		}
		BlockPos below = side.below();
		return climbCell(get, below) || isClosedClimbTrapdoor(get, below);
	}

	/**
	 * Whether a worker with its feet in the climb cell {@code feet} can step off onto the floor of {@code side},
	 * a horizontal neighbour, one block up: {@code side} has a collision top to stand on, the two cells above
	 * it are free, and the column has room to rise above {@code feet}. Vanilla links this itself only when the
	 * climbable has a collision top of its own, as a ladder does; a vine or scaffolding column needs this link.
	 */
	public static boolean climbExitAbove(Function<BlockPos, BlockState> get, BlockPos feet, BlockPos side) {
		if (!climbCell(get, feet) || noCollision(get, side)) {
			return false;
		}
		BlockPos target = side.above();
		return noCollision(get, target) && noCollision(get, target.above()) && passableForClimb(get, feet.above());
	}

	/**
	 * The direction of the wall a climber pushes into: the block a ladder hangs on, or the first horizontal
	 * face a vine clings to. {@code null} for anything else, which is climbed by jumping only.
	 */
	public static Direction wallDirection(BlockState state) {
		if (state.getBlock() instanceof LadderBlock) {
			return state.getValue(LadderBlock.FACING).getOpposite();
		}
		if (state.getBlock() instanceof VineBlock) {
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				if (state.getValue(VineBlock.getPropertyForFace(direction))) {
					return direction;
				}
			}
		}
		return null;
	}

	/** Whether the path step from {@code from} to {@code to} goes straight up or down one block. */
	public static Segment segment(BlockPos from, BlockPos to) {
		if (from.getX() != to.getX() || from.getZ() != to.getZ()) {
			return Segment.NONE;
		}
		int dy = to.getY() - from.getY();
		if (dy == 1) {
			return Segment.UP;
		}
		if (dy == -1) {
			return Segment.DOWN;
		}
		return Segment.NONE;
	}

	private static boolean noCollision(Function<BlockPos, BlockState> get, BlockPos pos) {
		return get.apply(pos).getCollisionShape(EmptyBlockGetter.INSTANCE, pos).isEmpty();
	}
}
