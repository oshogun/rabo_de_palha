package io.github.oshogun.rabodepalha.worker.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScaffoldPlannerTest {
	/** The worker's eye height: its 1.95 hitbox height times the vanilla 0.85 default. */
	private static final double EYE = 1.95 * 0.85;
	/** Feet level on the floor: the floor blocks sit at y = 64. */
	private static final int FEET = 65;
	private static final Predicate<BlockPos> NONE = pos -> false;

	private enum Cell { SOLID, THIN, LAVA, WATER, DOOR }

	private Map<BlockPos, Cell> world;
	private ScaffoldPlanner.Cells cells;

	@BeforeEach
	void emptyWorld() {
		world = new HashMap<>();
		cells = new ScaffoldPlanner.Cells() {
			@Override
			public boolean empty(BlockPos pos) {
				Cell cell = world.get(pos);
				return cell == null || cell == Cell.LAVA || cell == Cell.WATER;
			}

			@Override
			public boolean sturdyTop(BlockPos pos) {
				return world.get(pos) == Cell.SOLID;
			}

			@Override
			public boolean lava(BlockPos pos) {
				return world.get(pos) == Cell.LAVA;
			}

			@Override
			public boolean placeable(BlockPos pos) {
				return !world.containsKey(pos);
			}

			@Override
			public boolean openable(BlockPos pos) {
				return world.get(pos) == Cell.DOOR;
			}
		};
	}

	// Pillars

	@Test
	void targetEightAboveTheFloorNeedsAPillarOfThree() {
		ground(-12, 12);
		BlockPos target = new BlockPos(0, 64 + 8, 0);
		set(target, Cell.SOLID);

		List<ScaffoldPlanner.Pillar> plans = pillars(target, centerColumnOnly());

		// Feet at 68 put the eye at ~69.66, 2.84 below the target center: within 4.5 - 0.75.
		assertEquals(List.of(new ScaffoldPlanner.Pillar(new BlockPos(0, FEET, 0), 3)), plans);
		ScaffoldPlanner.Pillar pillar = plans.get(0);
		assertEquals(List.of(new BlockPos(0, 65, 0), new BlockPos(0, 66, 0), new BlockPos(0, 67, 0)), pillar.cells());
		assertEquals(new BlockPos(0, FEET, 0), pillar.start());
		assertEquals(new BlockPos(0, 68, 0), pillar.top());
	}

	@Test
	void pillarsAreSortedByHeightThenDistanceToTheWorker() {
		ground(-12, 12);
		BlockPos target = new BlockPos(0, 72, 0);
		set(target, Cell.SOLID);
		Vec3 worker = new Vec3(4.5, FEET, 0.5);

		List<ScaffoldPlanner.Pillar> plans = ScaffoldPlanner.pillars(cells, target, worker, EYE, NONE);

		assertEquals(ScaffoldPlanner.MAX_PLANS, plans.size());
		assertEquals(3, plans.get(0).height());
		assertEquals(new BlockPos(2, FEET, 0), plans.get(0).base(), "the lowest pillar nearest the worker");
		for (int i = 1; i < plans.size(); i++) {
			ScaffoldPlanner.Pillar prev = plans.get(i - 1);
			ScaffoldPlanner.Pillar next = plans.get(i);
			assertTrue(prev.height() <= next.height(), "sorted by height at " + i);
			if (prev.height() == next.height()) {
				assertTrue(Vec3.atBottomCenterOf(prev.base()).distanceToSqr(worker)
						<= Vec3.atBottomCenterOf(next.base()).distanceToSqr(worker), "then by distance at " + i);
			}
		}
	}

	@Test
	void pillarIsCappedAtSixteen() {
		ground(-12, 12);
		BlockPos highest = new BlockPos(0, 64 + 21, 0);
		set(highest, Cell.SOLID);
		assertEquals(List.of(new ScaffoldPlanner.Pillar(new BlockPos(0, FEET, 0), ScaffoldPlanner.MAX_PILLAR)),
				pillars(highest, centerColumnOnly()));

		world.remove(highest);
		BlockPos tooHigh = new BlockPos(0, 64 + 22, 0);
		set(tooHigh, Cell.SOLID);
		assertEquals(List.of(), pillars(tooHigh, centerColumnOnly()));
	}

	@Test
	void columnWithABlockInItIsRejected() {
		ground(-12, 12);
		BlockPos target = new BlockPos(0, 72, 0);
		set(target, Cell.SOLID);
		// Not sturdy on top, so the column's floor is still found below it.
		set(new BlockPos(0, 67, 0), Cell.THIN);

		assertEquals(List.of(), pillars(target, centerColumnOnly()));
	}

	@Test
	void oneWideShaftIsRejected() {
		ground(-12, 12);
		BlockPos target = new BlockPos(0, 72, 0);
		set(target, Cell.SOLID);
		for (int y = FEET; y <= 70; y++) {
			for (Direction side : Direction.Plane.HORIZONTAL) {
				set(new BlockPos(0, y, 0).relative(side), Cell.SOLID);
			}
		}

		assertEquals(List.of(), pillars(target, centerColumnOnly()));
	}

	@Test
	void columnAgainstASingleWallIsAllowed() {
		ground(-12, 12);
		BlockPos target = new BlockPos(0, 72, 0);
		set(target, Cell.SOLID);
		for (int y = FEET; y <= 70; y++) {
			set(new BlockPos(1, y, 0), Cell.SOLID);
		}

		assertEquals(List.of(new ScaffoldPlanner.Pillar(new BlockPos(0, FEET, 0), 3)),
				pillars(target, centerColumnOnly()));
	}

	@Test
	void columnNextToADoorIsRejected() {
		ground(-12, 12);
		BlockPos target = new BlockPos(0, 72, 0);
		set(target, Cell.SOLID);
		set(new BlockPos(1, FEET, 0), Cell.DOOR);
		set(new BlockPos(1, FEET + 1, 0), Cell.DOOR);

		assertEquals(List.of(), pillars(target, centerColumnOnly()));
	}

	@Test
	void forbiddenCellExcludesItsColumn() {
		ground(-12, 12);
		BlockPos target = new BlockPos(0, 72, 0);
		set(target, Cell.SOLID);
		BlockPos kept = new BlockPos(0, 66, 0);

		List<ScaffoldPlanner.Pillar> plans = ScaffoldPlanner.pillars(cells, target, Vec3.atBottomCenterOf(kept), EYE,
				kept::equals);

		assertFalse(plans.isEmpty());
		for (ScaffoldPlanner.Pillar plan : plans) {
			assertFalse(plan.base().getX() == 0 && plan.base().getZ() == 0, "center column used: " + plan);
		}
	}

	// Bridges

	@Test
	void gapOfFourIsBridgedUntilTheTargetIsInReach() {
		// Ground on the near side only: the bridge ends in reach of a block floating past the gap.
		ground(-12, 0);
		BlockPos target = new BlockPos(7, 66, 0);
		set(target, Cell.SOLID);

		List<ScaffoldPlanner.Bridge> plans = bridges(target);

		ScaffoldPlanner.Bridge bridge = new ScaffoldPlanner.Bridge(new BlockPos(0, FEET, 0), Direction.EAST, 4);
		assertEquals(bridge, plans.get(0));
		assertEquals(List.of(new BlockPos(1, 64, 0), new BlockPos(2, 64, 0), new BlockPos(3, 64, 0),
				new BlockPos(4, 64, 0)), bridge.cells());
	}

	@Test
	void gapOfFourIsBridgedToResumedGround() {
		gapOfFour();
		BlockPos target = new BlockPos(10, 66, 0);
		set(target, Cell.SOLID);

		List<ScaffoldPlanner.Bridge> plans = bridges(target);

		assertEquals(new ScaffoldPlanner.Bridge(new BlockPos(0, FEET, 0), Direction.EAST, 4), plans.get(0));
		for (int i = 1; i < plans.size(); i++) {
			assertTrue(plans.get(i - 1).length() <= plans.get(i).length(), "sorted by length at " + i);
		}
	}

	@Test
	void gapOfTenIsNotBridged() {
		ground(-12, 0);
		ground(11, 24);
		BlockPos target = new BlockPos(12, 63, 0);

		assertEquals(List.of(), bridges(target));
	}

	@Test
	void lavaInTheGapIsNeverBridged() {
		gapOfFour();
		BlockPos target = new BlockPos(10, 66, 0);
		set(target, Cell.SOLID);
		for (int x = 1; x <= 4; x++) {
			for (int z = -12; z <= 12; z++) {
				set(new BlockPos(x, 64, z), Cell.LAVA);
				set(new BlockPos(x, 63, z), Cell.SOLID);
			}
		}

		assertEquals(List.of(), bridges(target));
	}

	@Test
	void lavaBelowTheGapIsNeverBridged() {
		gapOfFour();
		BlockPos target = new BlockPos(10, 66, 0);
		set(target, Cell.SOLID);
		for (int x = 1; x <= 4; x++) {
			for (int z = -12; z <= 12; z++) {
				// Air at 62..64 between the bridge line and the lava.
				set(new BlockPos(x, 61, z), Cell.LAVA);
			}
		}

		assertEquals(List.of(), bridges(target));
		assertTrue(ScaffoldPlanner.lavaBelow(cells, new BlockPos(2, 64, 0)));
	}

	@Test
	void waterInTheGapIsNotPlaceable() {
		gapOfFour();
		BlockPos target = new BlockPos(10, 66, 0);
		set(target, Cell.SOLID);
		for (int x = 1; x <= 4; x++) {
			for (int z = -12; z <= 12; z++) {
				set(new BlockPos(x, 64, z), Cell.WATER);
				set(new BlockPos(x, 63, z), Cell.SOLID);
			}
		}

		assertEquals(List.of(), bridges(target));
	}

	// Cleanup

	@Test
	void cleanupRemovesTheLastPlacedFirst() {
		List<BlockPos> placed = new ScaffoldPlanner.Pillar(new BlockPos(0, FEET, 0), 4).cells();

		assertEquals(List.of(new BlockPos(0, 68, 0), new BlockPos(0, 67, 0), new BlockPos(0, 66, 0),
				new BlockPos(0, 65, 0)), ScaffoldPlanner.cleanupOrder(placed));
		assertEquals(List.of(), ScaffoldPlanner.cleanupOrder(List.of()));
	}

	@Test
	void bridgeStandSpotsChainFromTheAnchor() {
		ScaffoldPlanner.Bridge bridge = new ScaffoldPlanner.Bridge(new BlockPos(0, FEET, 0), Direction.EAST, 3);
		List<BlockPos> blocks = bridge.cells();

		// Each block is placed (and later removed) standing on the one before it, the first from the anchor.
		assertEquals(new BlockPos(0, FEET, 0), bridge.start());
		assertEquals(bridge.start(), blocks.get(0).relative(Direction.WEST).above());
		assertEquals(new BlockPos(1, FEET, 0), blocks.get(0).above());
		assertEquals(new BlockPos(2, FEET, 0), blocks.get(1).above());
		for (int k = 1; k < blocks.size(); k++) {
			assertEquals(blocks.get(k - 1).above(), blocks.get(k).relative(Direction.WEST).above());
		}
	}

	private List<ScaffoldPlanner.Pillar> pillars(BlockPos target, Predicate<BlockPos> forbidden) {
		return ScaffoldPlanner.pillars(cells, target, new Vec3(0.5, FEET, 0.5), EYE, forbidden);
	}

	private List<ScaffoldPlanner.Bridge> bridges(BlockPos target) {
		return ScaffoldPlanner.bridges(cells, target, new Vec3(0.5, FEET, 0.5), EYE, NONE);
	}

	/** Forbids every column but x = z = 0, so only the column under the target is planned. */
	private static Predicate<BlockPos> centerColumnOnly() {
		return pos -> pos.getX() != 0 || pos.getZ() != 0;
	}

	/** Ground at x 0 and below and from 5 on, with a 4-wide gap floored 3 down. */
	private void gapOfFour() {
		ground(-12, 0);
		ground(5, 16);
		for (int x = 1; x <= 4; x++) {
			for (int z = -12; z <= 12; z++) {
				set(new BlockPos(x, 60, z), Cell.SOLID);
			}
		}
	}

	/** Solid floor blocks at y = 64 for x in [fromX, toX] and z in [-12, 12]. */
	private void ground(int fromX, int toX) {
		for (int x = fromX; x <= toX; x++) {
			for (int z = -12; z <= 12; z++) {
				set(new BlockPos(x, 64, z), Cell.SOLID);
			}
		}
	}

	private void set(BlockPos pos, Cell cell) {
		world.put(pos.immutable(), cell);
	}
}
