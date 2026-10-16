package io.github.oshogun.rabodepalha.worker.task;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

import io.github.oshogun.rabodepalha.worker.Openables;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Plans throwaway-block scaffolds for a target no normal stand spot reaches: a pillar the worker jumps up
 * on, or a bridge it walks out on at foot level.
 *
 * <p>Reads the world through {@link Cells} and touches nothing, so it is unit-testable.
 */
public final class ScaffoldPlanner {
	/** The tallest pillar a worker builds. */
	public static final int MAX_PILLAR = 16;
	/** The longest bridge a worker builds. */
	public static final int MAX_BRIDGE = 8;
	/** The most plans of each kind returned. */
	public static final int MAX_PLANS = 8;

	private static final int PILLAR_RADIUS = 2;
	private static final int PILLAR_SCAN_DEPTH = 24;
	private static final int BRIDGE_RADIUS = MAX_BRIDGE + 4;
	private static final int BRIDGE_MIN_DY = -4;
	private static final int BRIDGE_MAX_DY = 2;
	private static final int LAVA_SCAN_DEPTH = 32;

	private ScaffoldPlanner() {
	}

	/** What the planner needs to know about each cell. */
	public interface Cells {
		/** No collision: a body fits through. */
		boolean empty(BlockPos pos);

		/** The top face can be stood on. */
		boolean sturdyTop(BlockPos pos);

		/** Holds lava. */
		boolean lava(BlockPos pos);

		/** A block can be placed here: replaceable, with no fluid. */
		boolean placeable(BlockPos pos);

		/** Holds a door or gate a worker opens on its way. */
		boolean openable(BlockPos pos);

		static Cells of(BlockGetter level) {
			return new Cells() {
				@Override
				public boolean empty(BlockPos pos) {
					return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
				}

				@Override
				public boolean sturdyTop(BlockPos pos) {
					return level.getBlockState(pos).isFaceSturdy(level, pos, Direction.UP);
				}

				@Override
				public boolean lava(BlockPos pos) {
					return level.getFluidState(pos).is(FluidTags.LAVA);
				}

				@Override
				public boolean placeable(BlockPos pos) {
					BlockState state = level.getBlockState(pos);
					return state.canBeReplaced() && level.getFluidState(pos).isEmpty();
				}

				@Override
				public boolean openable(BlockPos pos) {
					return Openables.isOpenable(level.getBlockState(pos));
				}
			};
		}
	}

	/** A planned scaffold: which cells to fill, in order, and where to walk before building. */
	public sealed interface Plan permits Pillar, Bridge {
		List<BlockPos> cells();

		BlockPos start();
	}

	/** Blocks at {@code base} up to {@code base + height - 1}; the worker ends with its feet at {@code base + height}. */
	public record Pillar(BlockPos base, int height) implements Plan {
		@Override
		public List<BlockPos> cells() {
			List<BlockPos> cells = new ArrayList<>(height);
			for (int i = 0; i < height; i++) {
				cells.add(base.above(i));
			}
			return List.copyOf(cells);
		}

		@Override
		public BlockPos start() {
			return base;
		}

		public BlockPos top() {
			return base.above(height);
		}
	}

	/** Blocks at {@code anchor.below().relative(dir, k)} for k = 1..length, walked out from {@code anchor}. */
	public record Bridge(BlockPos anchor, Direction dir, int length) implements Plan {
		@Override
		public List<BlockPos> cells() {
			List<BlockPos> cells = new ArrayList<>(length);
			BlockPos floor = anchor.below();
			for (int k = 1; k <= length; k++) {
				cells.add(floor.relative(dir, k));
			}
			return List.copyOf(cells);
		}

		@Override
		public BlockPos start() {
			return anchor;
		}
	}

	/** Whether a worker can stand with its feet at {@code spot}, as in {@link StandSpots#canStandAt}. */
	public static boolean canStandAt(Cells cells, BlockPos spot) {
		return cells.empty(spot) && cells.empty(spot.above()) && cells.sturdyTop(spot.below());
	}

	/**
	 * Up to {@link #MAX_PLANS} pillars that bring the eye within reach of {@code target}, lowest first, then
	 * nearest to {@code workerPos}. Bases are the highest floor in each column within
	 * {@value #PILLAR_RADIUS} blocks of the target column, at or below {@code target.y - 2}.
	 */
	public static List<Pillar> pillars(Cells cells, BlockPos target, Vec3 workerPos, double eyeHeight,
			Predicate<BlockPos> forbidden) {
		Vec3 targetCenter = Vec3.atCenterOf(target);
		List<Pillar> plans = new ArrayList<>();
		for (int dx = -PILLAR_RADIUS; dx <= PILLAR_RADIUS; dx++) {
			for (int dz = -PILLAR_RADIUS; dz <= PILLAR_RADIUS; dz++) {
				BlockPos base = findBase(cells, target.offset(dx, -2, dz));
				if (base == null) {
					continue;
				}
				int height = pillarHeight(base, eyeHeight, targetCenter);
				if (height > 0 && pillarClear(cells, base, height, target, forbidden)) {
					plans.add(new Pillar(base, height));
				}
			}
		}
		plans.sort(Comparator.comparingInt(Pillar::height)
				.thenComparingDouble(plan -> Vec3.atBottomCenterOf(plan.start()).distanceToSqr(workerPos)));
		return limit(plans);
	}

	/**
	 * Up to {@link #MAX_PLANS} bridges that cross a gap toward {@code target}, shortest first, then nearest to
	 * {@code workerPos}. A bridge ends where the eye reaches the target, or where solid ground resumes.
	 */
	public static List<Bridge> bridges(Cells cells, BlockPos target, Vec3 workerPos, double eyeHeight,
			Predicate<BlockPos> forbidden) {
		Vec3 targetCenter = Vec3.atCenterOf(target);
		List<Bridge> plans = new ArrayList<>();
		for (int dy = BRIDGE_MIN_DY; dy <= BRIDGE_MAX_DY; dy++) {
			for (int dx = -BRIDGE_RADIUS; dx <= BRIDGE_RADIUS; dx++) {
				for (int dz = -BRIDGE_RADIUS; dz <= BRIDGE_RADIUS; dz++) {
					if (dx == 0 && dz == 0) {
						continue;
					}
					BlockPos anchor = target.offset(dx, dy, dz);
					if (!canStandAt(cells, anchor)) {
						continue;
					}
					Direction dir = Math.abs(dx) >= Math.abs(dz)
							? (dx > 0 ? Direction.WEST : Direction.EAST)
							: (dz > 0 ? Direction.NORTH : Direction.SOUTH);
					if (cells.sturdyTop(anchor.below().relative(dir))) {
						continue;
					}
					int length = bridgeLength(cells, anchor, dir, eyeHeight, targetCenter, forbidden);
					if (length > 0) {
						plans.add(new Bridge(anchor, dir, length));
					}
				}
			}
		}
		plans.sort(Comparator.comparingInt(Bridge::length)
				.thenComparingDouble(plan -> Vec3.atBottomCenterOf(plan.start()).distanceToSqr(workerPos)));
		return limit(plans);
	}

	/** The placed blocks in the order to remove them: last placed first. */
	public static <T> List<T> cleanupOrder(List<T> placed) {
		return List.copyOf(placed.reversed());
	}

	private static BlockPos findBase(Cells cells, BlockPos top) {
		BlockPos.MutableBlockPos cursor = top.mutable();
		for (int i = 0; i < PILLAR_SCAN_DEPTH; i++) {
			if (canStandAt(cells, cursor)) {
				return cursor.immutable();
			}
			cursor.move(Direction.DOWN);
		}
		return null;
	}

	/** The smallest height that puts the eye in reach, or 0 if none within {@link #MAX_PILLAR}. */
	private static int pillarHeight(BlockPos base, double eyeHeight, Vec3 targetCenter) {
		for (int h = 1; h <= MAX_PILLAR; h++) {
			if (inReach(base.above(h), eyeHeight, targetCenter)) {
				return h;
			}
		}
		return 0;
	}

	private static boolean pillarClear(Cells cells, BlockPos base, int height, BlockPos target,
			Predicate<BlockPos> forbidden) {
		for (int i = 0; i <= height + 1; i++) {
			BlockPos body = base.above(i);
			if (body.equals(target) || forbidden.test(body) || !cells.empty(body) || cells.lava(body)) {
				return false;
			}
			if (i < height && (!cells.placeable(body) || nearOpenable(cells, body))) {
				return false;
			}
			if (inShaft(cells, body)) {
				return false;
			}
		}
		return true;
	}

	private static int bridgeLength(Cells cells, BlockPos anchor, Direction dir, double eyeHeight,
			Vec3 targetCenter, Predicate<BlockPos> forbidden) {
		BlockPos floor = anchor.below();
		for (int k = 1; k <= MAX_BRIDGE; k++) {
			BlockPos f = floor.relative(dir, k);
			if (cells.sturdyTop(f)) {
				return k - 1;
			}
			if (forbidden.test(f) || !cells.placeable(f) || cells.lava(f) || cells.openable(f) || lavaBelow(cells, f)) {
				return 0;
			}
			for (int up = 1; up <= 2; up++) {
				BlockPos body = f.above(up);
				if (forbidden.test(body) || !cells.empty(body) || cells.lava(body) || nearOpenable(cells, body)) {
					return 0;
				}
			}
			if (inReach(f.above(), eyeHeight, targetCenter)) {
				return k;
			}
		}
		return 0;
	}

	/** Whether lava lies below {@code pos} before the first floor that could be stood on. */
	static boolean lavaBelow(Cells cells, BlockPos pos) {
		BlockPos.MutableBlockPos cursor = pos.mutable();
		for (int i = 0; i < LAVA_SCAN_DEPTH; i++) {
			cursor.move(Direction.DOWN);
			if (cells.lava(cursor)) {
				return true;
			}
			if (cells.sturdyTop(cursor)) {
				return false;
			}
		}
		return false;
	}

	private static boolean nearOpenable(Cells cells, BlockPos pos) {
		if (cells.openable(pos)) {
			return true;
		}
		for (Direction side : Direction.Plane.HORIZONTAL) {
			if (cells.openable(pos.relative(side))) {
				return true;
			}
		}
		return false;
	}

	/** Whether all four sides of {@code pos} are blocked: a 1-wide shaft the worker could get stuck in. */
	private static boolean inShaft(Cells cells, BlockPos pos) {
		for (Direction side : Direction.Plane.HORIZONTAL) {
			if (cells.empty(pos.relative(side))) {
				return false;
			}
		}
		return true;
	}

	private static boolean inReach(BlockPos feet, double eyeHeight, Vec3 targetCenter) {
		return Vec3.atBottomCenterOf(feet).add(0, eyeHeight, 0).distanceTo(targetCenter)
				<= StandSpots.REACH - StandSpots.ARRIVAL_SLACK;
	}

	private static <T> List<T> limit(List<T> plans) {
		return plans.size() > MAX_PLANS ? List.copyOf(plans.subList(0, MAX_PLANS)) : List.copyOf(plans);
	}
}
