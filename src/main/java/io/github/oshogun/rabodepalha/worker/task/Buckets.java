package io.github.oshogun.rabodepalha.worker.task;

import java.util.Set;

import org.jspecify.annotations.Nullable;

import io.github.oshogun.rabodepalha.worker.WorkerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.material.Fluids;

/**
 * Water bucket handling for workers, done the way a player's bucket does it but with no player:
 * pouring with {@link BucketItem#emptyContents} and filling with {@link BucketPickup#pickupBlock}.
 * The empty/full bucket swap is explicit, because {@link WorkerEntity#consumeOne} gives no remainder.
 */
public final class Buckets {
	/** How far a worker looks for water to fill its buckets, in blocks along each axis. */
	public static final int WATER_RANGE = 24;

	private Buckets() {
	}

	public static int countWater(WorkerEntity worker) {
		return worker.countItem(Items.WATER_BUCKET);
	}

	public static int countEmpty(WorkerEntity worker) {
		return worker.countItem(Items.BUCKET);
	}

	/**
	 * Pours one water bucket at {@code pos} and puts the empty bucket back in the inventory. Succeeds only
	 * if the block is a water source afterwards, so a dimension where water evaporates keeps the bucket.
	 */
	public static boolean pour(WorkerEntity worker, ServerLevel level, BlockPos pos) {
		if (countWater(worker) == 0 || !(Items.WATER_BUCKET instanceof BucketItem bucket)) {
			return false;
		}
		if (!bucket.emptyContents(worker, level, pos, null) || !isWaterSource(level, pos)) {
			return false;
		}
		worker.consumeOne(Items.WATER_BUCKET);
		worker.insertOrDrop(new ItemStack(Items.BUCKET));
		return true;
	}

	/**
	 * Fills one empty bucket from the block at {@code pos}, putting the result in the inventory.
	 *
	 * @return whether a bucket was filled
	 */
	public static boolean fill(WorkerEntity worker, ServerLevel level, BlockPos pos) {
		if (countEmpty(worker) == 0) {
			return false;
		}
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof BucketPickup pickup)) {
			return false;
		}
		ItemStack filled = pickup.pickupBlock(worker, level, pos, state);
		if (filled.isEmpty()) {
			return false;
		}
		worker.consumeOne(Items.BUCKET);
		worker.insertOrDrop(filled);
		return true;
	}

	/** Whether {@code pos} is a plain water source block (not a waterlogged block). */
	public static boolean isWaterSource(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return state.is(Blocks.WATER) && state.getFluidState().isSourceOfType(Fluids.WATER);
	}

	/**
	 * Whether the water source at {@code pos} comes back after a bucket takes it: the
	 * {@link GameRules#WATER_SOURCE_CONVERSION} rule is on, at least two horizontal neighbours are sources,
	 * and the block below is solid or a source, as in {@code FlowingFluid.getNewLiquid}.
	 */
	public static boolean isInfiniteSource(ServerLevel level, BlockPos pos) {
		if (!isWaterSource(level, pos) || !level.getGameRules().get(GameRules.WATER_SOURCE_CONVERSION)) {
			return false;
		}
		if (horizontalSources(level, pos) < 2) {
			return false;
		}
		BlockPos below = pos.below();
		BlockState belowState = level.getBlockState(below);
		return belowState.isSolid() || belowState.getFluidState().isSourceOfType(Fluids.WATER);
	}

	/**
	 * The nearest infinite water source within {@code range} of {@code origin} on every axis. Failing
	 * that, the nearest source with at least one horizontal source beside it, so a lone hole is never
	 * drained. Positions in {@code exclude} (the worker's own holes) are never returned.
	 */
	public static @Nullable BlockPos findSource(ServerLevel level, BlockPos origin, int range, Set<BlockPos> exclude) {
		BlockPos bestInfinite = null;
		double bestInfiniteDist = Double.MAX_VALUE;
		BlockPos bestShared = null;
		double bestSharedDist = Double.MAX_VALUE;
		int minY = Math.max(level.getMinY(), origin.getY() - range);
		int maxY = Math.min(level.getMaxY(), origin.getY() + range);
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int y = minY; y <= maxY; y++) {
			for (int x = origin.getX() - range; x <= origin.getX() + range; x++) {
				for (int z = origin.getZ() - range; z <= origin.getZ() + range; z++) {
					cursor.set(x, y, z);
					if (!isWaterSource(level, cursor) || exclude.contains(cursor)) {
						continue;
					}
					double dist = cursor.distSqr(origin);
					if (dist < bestInfiniteDist && isInfiniteSource(level, cursor)) {
						bestInfinite = cursor.immutable();
						bestInfiniteDist = dist;
					} else if (dist < bestSharedDist && horizontalSources(level, cursor) >= 1) {
						bestShared = cursor.immutable();
						bestSharedDist = dist;
					}
				}
			}
		}
		return bestInfinite != null ? bestInfinite : bestShared;
	}

	private static int horizontalSources(ServerLevel level, BlockPos pos) {
		int sources = 0;
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			if (level.getFluidState(pos.relative(direction)).isSourceOfType(Fluids.WATER)) {
				sources++;
			}
		}
		return sources;
	}
}
