package io.github.oshogun.rabodepalha.worker.task;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import io.github.oshogun.rabodepalha.rts.RtsField;
import io.github.oshogun.rabodepalha.rts.RtsIrrigation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import org.jspecify.annotations.Nullable;

/**
 * Reads the world for {@link RtsIrrigation}: which field columns can take a water hole and which water
 * is already there. It only reads block states through a {@link BlockGetter}, so the client overlay can
 * plan with the same rules as the worker.
 */
public final class IrrigationBlocks {
	private IrrigationBlocks() {
	}

	/** The 9×9 field a hole at {@code centre} keeps wet: an irrigated plot. */
	public static RtsField plotField(BlockPos centre) {
		return RtsField.of(centre.getX() - RtsIrrigation.RANGE, centre.getY(), centre.getZ() - RtsIrrigation.RANGE,
				centre.getX() + RtsIrrigation.RANGE, centre.getY(), centre.getZ() + RtsIrrigation.RANGE);
	}

	/**
	 * Whether digging out {@code ground} and pouring water in leaves the water where it is. The ground
	 * must be a solid, breakable block without a block entity or fluid; the block below must hold it up;
	 * each side neighbour must be something water can neither flow into nor replace, so farmland counts
	 * even though its side face is not sturdy; above must be air or clearable cover; and there must be
	 * no lava in the 3×3×3 around it.
	 */
	public static boolean holdsWater(BlockGetter level, BlockPos ground) {
		BlockState state = level.getBlockState(ground);
		if (!state.isSolid() || state.getDestroySpeed(level, ground) < 0 || state.hasBlockEntity()
				|| !state.getFluidState().isEmpty()) {
			return false;
		}
		BlockPos below = ground.below();
		if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
			return false;
		}
		for (Direction side : Direction.Plane.HORIZONTAL) {
			BlockState neighbour = level.getBlockState(ground.relative(side));
			if (neighbour.isAir() || !neighbour.getFluidState().isEmpty() || neighbour.canBeReplaced(Fluids.WATER)
					|| neighbour.getBlock() instanceof LiquidBlockContainer) {
				return false;
			}
		}
		if (FieldBlocks.cover(level.getBlockState(ground.above())) == FieldBlocks.Cover.BLOCKED) {
			return false;
		}
		for (BlockPos around : BlockPos.betweenClosed(ground.offset(-1, -1, -1), ground.offset(1, 1, 1))) {
			if (level.getFluidState(around).is(FluidTags.LAVA)) {
				return false;
			}
		}
		return true;
	}

	/** The field block of column ({@code x}, {@code z}), as {@link FieldBlocks#surface} finds it. */
	public static @Nullable BlockPos surface(BlockGetter level, RtsField field, int x, int z) {
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		OptionalInt y = RtsField.surfaceY(field.bottomY(), field.topY(),
				at -> FieldBlocks.isGround(level.getBlockState(cursor.set(x, at, z))));
		return y.isPresent() ? new BlockPos(x, y.getAsInt(), z) : null;
	}

	/** The field's columns that have a dry surface, each marked with whether a hole there holds water. */
	public static List<RtsIrrigation.Column> columns(BlockGetter level, RtsField field) {
		List<RtsIrrigation.Column> columns = new ArrayList<>();
		for (int z = field.minZ(); z <= field.maxZ(); z++) {
			for (int x = field.minX(); x <= field.maxX(); x++) {
				BlockPos ground = surface(level, field, x, z);
				if (ground == null || !level.getFluidState(ground).isEmpty()) {
					continue;
				}
				columns.add(new RtsIrrigation.Column(x, z, ground.getY(), holdsWater(level, ground)));
			}
		}
		return columns;
	}

	/**
	 * The water already in reach of the field: {@link RtsIrrigation#RANGE} blocks beyond its edges, from
	 * the bottom of its window to one block above the top, as farmland also sees water one block up.
	 */
	public static List<RtsIrrigation.Water> existingWater(BlockGetter level, RtsField field) {
		List<RtsIrrigation.Water> water = new ArrayList<>();
		int range = RtsIrrigation.RANGE;
		for (BlockPos pos : BlockPos.betweenClosed(field.minX() - range, field.bottomY(), field.minZ() - range,
				field.maxX() + range, field.topY() + 1, field.maxZ() + range)) {
			if (level.getFluidState(pos).is(FluidTags.WATER)) {
				water.add(new RtsIrrigation.Water(pos.getX(), pos.getY(), pos.getZ()));
			}
		}
		return water;
	}

	/** The holes that keep {@code field} wet, given what is in the world now. */
	public static RtsIrrigation.Plan plan(BlockGetter level, RtsField field) {
		return RtsIrrigation.plan(field, columns(level, field), existingWater(level, field));
	}
}
