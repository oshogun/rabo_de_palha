package io.github.oshogun.rabodepalha.worker.task;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Finds positions a worker can stand on to work on a block within reach. */
public final class StandSpots {
	public static final double REACH = 4.5;
	public static final int MAX_CANDIDATES = 8;

	/** Navigation stops up to ~0.45 blocks per axis off a node's center, so spots keep this margin. */
	private static final double ARRIVAL_SLACK = 0.75;
	private static final int HORIZONTAL_RADIUS = 4;
	private static final int MIN_DY = -5;
	private static final int MAX_DY = 3;

	private StandSpots() {
	}

	/**
	 * Up to {@link #MAX_CANDIDATES} stand spots for working on {@code target}, nearest to
	 * {@code workerPos} first. A spot has two free blocks, a sturdy floor, is not the target itself,
	 * and puts the eye (at {@code eyeHeight} above its bottom center) within {@link #REACH} minus a
	 * small arrival margin of the target's center. Line of sight is not checked.
	 */
	public static List<BlockPos> candidates(Level level, BlockPos target, Vec3 workerPos, double eyeHeight) {
		Vec3 targetCenter = Vec3.atCenterOf(target);
		List<BlockPos> spots = new ArrayList<>();
		for (int dy = MIN_DY; dy <= MAX_DY; dy++) {
			for (int dx = -HORIZONTAL_RADIUS; dx <= HORIZONTAL_RADIUS; dx++) {
				for (int dz = -HORIZONTAL_RADIUS; dz <= HORIZONTAL_RADIUS; dz++) {
					BlockPos spot = target.offset(dx, dy, dz);
					if (!spot.equals(target) && canStandAt(level, spot)
							&& Vec3.atBottomCenterOf(spot).add(0, eyeHeight, 0).distanceTo(targetCenter) <= REACH - ARRIVAL_SLACK) {
						spots.add(spot);
					}
				}
			}
		}
		spots.sort(Comparator.comparingDouble(spot -> Vec3.atBottomCenterOf(spot).distanceToSqr(workerPos)));
		return spots.size() > MAX_CANDIDATES ? List.copyOf(spots.subList(0, MAX_CANDIDATES)) : List.copyOf(spots);
	}

	public static boolean canStandAt(Level level, BlockPos spot) {
		BlockPos below = spot.below();
		BlockPos above = spot.above();
		return level.getBlockState(spot).getCollisionShape(level, spot).isEmpty()
				&& level.getBlockState(above).getCollisionShape(level, above).isEmpty()
				&& level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
	}
}
