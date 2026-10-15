package io.github.oshogun.rabodepalha.worker;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/**
 * Walk evaluator that treats a closed fence gate like a closed wooden door, so a worker that can
 * open doors plans paths through it ({@code WorkerDoors} opens it on the way).
 *
 * <p>Only the gate's own cell changes; the cell above keeps its vanilla type, so a closed gate still
 * can't be stepped onto.
 */
public class WorkerNodeEvaluator extends WalkNodeEvaluator {
	@Override
	public PathType getPathType(PathfindingContext context, int x, int y, int z) {
		BlockState state = context.getBlockState(new BlockPos(x, y, z));
		if (state.getBlock() instanceof FenceGateBlock && !state.getValue(FenceGateBlock.OPEN)) {
			return PathType.DOOR_WOOD_CLOSED;
		}
		return super.getPathType(context, x, y, z);
	}
}
