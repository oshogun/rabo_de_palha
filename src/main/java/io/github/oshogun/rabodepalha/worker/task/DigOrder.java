package io.github.oshogun.rabodepalha.worker.task;

import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;

/** World-free dig order for {@link DigAreaTask}: top layer down, nearest first within a layer. */
final class DigOrder {
	private DigOrder() {
	}

	/** The lower-x/y/z corner of the cuboid spanned by {@code a} and {@code b}. */
	static BlockPos min(BlockPos a, BlockPos b) {
		return new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
	}

	/** The upper-x/y/z corner of the cuboid spanned by {@code a} and {@code b}. */
	static BlockPos max(BlockPos a, BlockPos b) {
		return new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
	}

	/** Number of blocks in the cuboid spanned by {@code a} and {@code b}, corners included. */
	static long volume(BlockPos a, BlockPos b) {
		return (Math.abs((long) a.getX() - b.getX()) + 1)
				* (Math.abs((long) a.getY() - b.getY()) + 1)
				* (Math.abs((long) a.getZ() - b.getZ()) + 1);
	}

	/**
	 * The next block to dig in the cuboid spanned by {@code a} and {@code b}: in the highest layer that
	 * has a candidate, the candidate nearest to {@code workerPos} (ties by lower x, then lower z).
	 * {@code standingOn} is never returned. {@code isCandidate} may get a mutable position; it must not keep it.
	 */
	static Optional<BlockPos> next(BlockPos a, BlockPos b, BlockPos workerPos, BlockPos standingOn, Predicate<BlockPos> isCandidate) {
		BlockPos min = min(a, b);
		BlockPos max = max(a, b);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int y = max.getY(); y >= min.getY(); y--) {
			BlockPos best = null;
			double bestDistance = Double.MAX_VALUE;
			// x then z ascending, and only a strictly nearer block replaces the best: ties go to lower x, then z.
			for (int x = min.getX(); x <= max.getX(); x++) {
				for (int z = min.getZ(); z <= max.getZ(); z++) {
					pos.set(x, y, z);
					if (pos.equals(standingOn)) {
						continue;
					}
					double distance = pos.distSqr(workerPos);
					if (distance < bestDistance && isCandidate.test(pos)) {
						best = pos.immutable();
						bestDistance = distance;
					}
				}
			}
			if (best != null) {
				return Optional.of(best);
			}
		}
		return Optional.empty();
	}
}
