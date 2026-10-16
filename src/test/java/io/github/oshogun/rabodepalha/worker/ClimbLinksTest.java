package io.github.oshogun.rabodepalha.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClimbLinksTest {
	private static final BlockPos BASE = new BlockPos(0, 64, 0);
	/** The ladder faces north, so it hangs on the wall to its south. */
	private static final Direction FACING = Direction.NORTH;

	private Map<BlockPos, BlockState> blocks;
	private Function<BlockPos, BlockState> get;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		// Unit tests load no data packs, so bind the vanilla climbable tag by hand.
		List<Holder<Block>> climbable = Stream.of(Blocks.LADDER, Blocks.VINE, Blocks.SCAFFOLDING, Blocks.WEEPING_VINES,
				Blocks.WEEPING_VINES_PLANT, Blocks.TWISTING_VINES, Blocks.TWISTING_VINES_PLANT, Blocks.CAVE_VINES,
				Blocks.CAVE_VINES_PLANT).<Holder<Block>>map(Block::builtInRegistryHolder).toList();
		// The test bootstrap leaves the registry unfrozen; freezing binds every other tag to empty.
		MappedRegistry<Block> registry = (MappedRegistry<Block>) BuiltInRegistries.BLOCK;
		registry.bindAllTagsToEmpty();
		registry.bindTags(Map.of(BlockTags.CLIMBABLE, climbable));
		registry.freeze();
	}

	@BeforeEach
	void emptyWorld() {
		blocks = new HashMap<>();
		get = pos -> blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
	}

	@Test
	void ladderColumnLinksUpToItsTopAndDownFromAbove() {
		ladders(0, 4);
		for (int i = 0; i < 3; i++) {
			assertTrue(ClimbLinks.canClimbUp(get, at(i), ClimbLinks.MAX_CLIMB), "up from " + i);
		}
		assertFalse(ClimbLinks.canClimbUp(get, at(3), ClimbLinks.MAX_CLIMB), "top cell has no up link");
		assertTrue(ClimbLinks.canClimbDown(get, at(4)));
		assertTrue(ClimbLinks.canClimbDown(get, at(1)));
		assertFalse(ClimbLinks.canClimbDown(get, at(0)));
	}

	@Test
	void vineColumnLinksUp() {
		column(Blocks.VINE.defaultBlockState().setValue(VineBlock.SOUTH, true), 0, 3);
		assertTrue(ClimbLinks.canClimbUp(get, at(0), ClimbLinks.MAX_CLIMB));
		assertTrue(ClimbLinks.canClimbUp(get, at(1), ClimbLinks.MAX_CLIMB));
		assertFalse(ClimbLinks.canClimbUp(get, at(2), ClimbLinks.MAX_CLIMB));
		assertTrue(ClimbLinks.canClimbDown(get, at(3)));
	}

	@Test
	void twistingAndWeepingVineColumnsLinkUp() {
		for (Block vine : new Block[] { Blocks.TWISTING_VINES_PLANT, Blocks.WEEPING_VINES_PLANT }) {
			emptyWorld();
			column(vine.defaultBlockState(), 0, 3);
			assertTrue(ClimbLinks.canClimbUp(get, at(0), ClimbLinks.MAX_CLIMB), vine.toString());
			assertFalse(ClimbLinks.canClimbUp(get, at(2), ClimbLinks.MAX_CLIMB), vine.toString());
			assertTrue(ClimbLinks.canClimbDown(get, at(3)), vine.toString());
		}
	}

	@Test
	void scaffoldingColumnLinksUpAndDown() {
		column(Blocks.SCAFFOLDING.defaultBlockState(), 0, 4);
		assertTrue(ClimbLinks.climbCell(get, at(0)));
		assertTrue(ClimbLinks.canClimbUp(get, at(0), ClimbLinks.MAX_CLIMB));
		assertTrue(ClimbLinks.canClimbUp(get, at(2), ClimbLinks.MAX_CLIMB));
		assertFalse(ClimbLinks.canClimbUp(get, at(3), ClimbLinks.MAX_CLIMB));
		assertTrue(ClimbLinks.canClimbDown(get, at(4)));
	}

	@Test
	void gapInTheColumnStopsTheUpLink() {
		ladders(0, 2);
		ladders(3, 5);
		assertTrue(ClimbLinks.canClimbUp(get, at(0), ClimbLinks.MAX_CLIMB));
		assertFalse(ClimbLinks.canClimbUp(get, at(1), ClimbLinks.MAX_CLIMB), "cell above is the gap");
		assertFalse(ClimbLinks.canClimbUp(get, at(2), ClimbLinks.MAX_CLIMB), "inside the gap");
		assertFalse(ClimbLinks.canClimbDown(get, at(3)), "the gap is below");
		assertTrue(ClimbLinks.canClimbDown(get, at(2)), "back onto the lower part");
		assertEquals(2, ClimbLinks.climbRunBelow(get, at(1), ClimbLinks.MAX_CLIMB));
		assertEquals(0, ClimbLinks.climbRunBelow(get, at(2), ClimbLinks.MAX_CLIMB));
	}

	@Test
	void heightCapStopsTheUpLink() {
		int height = ClimbLinks.MAX_CLIMB + 2;
		ladders(0, height);
		for (int i = 0; i < height - 1; i++) {
			boolean expected = i + 1 < ClimbLinks.MAX_CLIMB;
			assertEquals(expected, ClimbLinks.canClimbUp(get, at(i), ClimbLinks.MAX_CLIMB), "up from " + i);
		}
		assertEquals(ClimbLinks.MAX_CLIMB, ClimbLinks.climbRunBelow(get, at(height - 1), ClimbLinks.MAX_CLIMB));
	}

	@Test
	void smallCapStopsTheUpLinkEarly() {
		ladders(0, 6);
		assertTrue(ClimbLinks.canClimbUp(get, at(0), 2));
		assertFalse(ClimbLinks.canClimbUp(get, at(1), 2));
		assertFalse(ClimbLinks.canClimbUp(get, at(4), 2));
	}

	@Test
	void openTrapdoorOverTheLadderIsAClimbCell() {
		ladders(0, 3);
		blocks.put(at(3), trapdoor(Blocks.OAK_TRAPDOOR, FACING, true));
		assertTrue(ClimbLinks.isClimbTrapdoor(get, at(3)));
		assertTrue(ClimbLinks.climbCell(get, at(3)));
		assertFalse(ClimbLinks.isClosedClimbTrapdoor(get, at(3)));
		assertTrue(ClimbLinks.canClimbUp(get, at(2), ClimbLinks.MAX_CLIMB));
	}

	@Test
	void closedWoodenTrapdoorIsAnUpTarget() {
		ladders(0, 3);
		blocks.put(at(3), trapdoor(Blocks.OAK_TRAPDOOR, FACING, false));
		assertFalse(ClimbLinks.climbCell(get, at(3)));
		assertTrue(ClimbLinks.isClosedClimbTrapdoor(get, at(3)));
		assertTrue(ClimbLinks.passableForClimb(get, at(3)));
		assertTrue(ClimbLinks.canClimbUp(get, at(2), ClimbLinks.MAX_CLIMB));
		assertTrue(ClimbLinks.canClimbDown(get, at(4)));
	}

	@Test
	void ironTrapdoorBlocksTheShaft() {
		ladders(0, 3);
		blocks.put(at(3), trapdoor(Blocks.IRON_TRAPDOOR, FACING, false));
		assertTrue(ClimbLinks.isClimbTrapdoor(get, at(3)));
		assertFalse(ClimbLinks.isClosedClimbTrapdoor(get, at(3)));
		assertFalse(ClimbLinks.canClimbUp(get, at(2), ClimbLinks.MAX_CLIMB));
		assertFalse(ClimbLinks.canClimbDown(get, at(4)));
	}

	@Test
	void trapdoorFacingAnotherWayIsNotPartOfTheShaft() {
		ladders(0, 3);
		blocks.put(at(3), trapdoor(Blocks.OAK_TRAPDOOR, Direction.EAST, true));
		assertFalse(ClimbLinks.isClimbTrapdoor(get, at(3)));
		assertFalse(ClimbLinks.climbCell(get, at(3)));
		blocks.put(at(3), trapdoor(Blocks.OAK_TRAPDOOR, Direction.EAST, false));
		assertFalse(ClimbLinks.isClosedClimbTrapdoor(get, at(3)));
		assertFalse(ClimbLinks.canClimbUp(get, at(2), ClimbLinks.MAX_CLIMB));
	}

	@Test
	void trapdoorWithoutALadderBelowIsNotPartOfTheShaft() {
		blocks.put(at(3), trapdoor(Blocks.OAK_TRAPDOOR, FACING, false));
		assertFalse(ClimbLinks.isClimbTrapdoor(get, at(3)));
		assertFalse(ClimbLinks.isClosedClimbTrapdoor(get, at(3)));
	}

	@Test
	void entryFromAboveNeedsAShaftBelow() {
		ladders(0, 3);
		assertTrue(ClimbLinks.climbEntryBelow(get, at(3)), "over the ladder top");
		blocks.put(at(3), trapdoor(Blocks.OAK_TRAPDOOR, FACING, false));
		assertTrue(ClimbLinks.climbEntryBelow(get, at(4)), "over a closed hatch");
		blocks.put(at(3), trapdoor(Blocks.OAK_TRAPDOOR, FACING, true));
		assertTrue(ClimbLinks.climbEntryBelow(get, at(3)), "inside an open hatch");

		BlockPos hole = BASE.east(5);
		assertFalse(ClimbLinks.climbEntryBelow(get, hole), "a plain hole");
		blocks.put(hole, Blocks.STONE.defaultBlockState());
		assertFalse(ClimbLinks.climbEntryBelow(get, hole.above()), "solid floor");
	}

	@Test
	void entryFromAboveNeedsHeadRoom() {
		ladders(0, 3);
		blocks.put(at(4), Blocks.STONE.defaultBlockState());
		assertFalse(ClimbLinks.climbEntryBelow(get, at(3)));
	}

	@Test
	void exitFromTheTopOfAVineColumnOntoTheFloorBeside() {
		column(Blocks.VINE.defaultBlockState().setValue(VineBlock.SOUTH, true), 0, 4);
		BlockPos floor = at(3).west();
		blocks.put(floor, Blocks.STONE.defaultBlockState());
		assertTrue(ClimbLinks.climbExitAbove(get, at(3), floor));
		assertFalse(ClimbLinks.climbExitAbove(get, at(3), at(3).east()), "no floor on that side");
		assertFalse(ClimbLinks.climbExitAbove(get, at(4), floor.above()), "not in the column");
		blocks.put(at(4), Blocks.STONE.defaultBlockState());
		assertFalse(ClimbLinks.climbExitAbove(get, at(3), floor), "no room to rise out of the column");
	}

	@Test
	void exitNeedsRoomOnTheFloor() {
		column(Blocks.VINE.defaultBlockState().setValue(VineBlock.SOUTH, true), 0, 4);
		BlockPos floor = at(3).west();
		blocks.put(floor, Blocks.STONE.defaultBlockState());
		blocks.put(floor.above(2), Blocks.STONE.defaultBlockState());
		assertFalse(ClimbLinks.climbExitAbove(get, at(3), floor));
	}

	@Test
	void blockedHeadRoomGivesNoUpLink() {
		ladders(0, 3);
		blocks.put(at(3), Blocks.STONE.defaultBlockState());
		assertFalse(ClimbLinks.canClimbUp(get, at(1), ClimbLinks.MAX_CLIMB));
		assertTrue(ClimbLinks.canClimbUp(get, at(0), ClimbLinks.MAX_CLIMB));
	}

	@Test
	void wallDirectionPointsAtTheSupport() {
		assertEquals(Direction.SOUTH, ClimbLinks.wallDirection(ladder(FACING)));
		assertEquals(Direction.WEST, ClimbLinks.wallDirection(ladder(Direction.EAST)));
		assertEquals(Direction.EAST,
				ClimbLinks.wallDirection(Blocks.VINE.defaultBlockState().setValue(VineBlock.EAST, true)));
		assertNull(ClimbLinks.wallDirection(Blocks.VINE.defaultBlockState()));
		assertNull(ClimbLinks.wallDirection(Blocks.SCAFFOLDING.defaultBlockState()));
		assertNull(ClimbLinks.wallDirection(Blocks.TWISTING_VINES_PLANT.defaultBlockState()));
	}

	@Test
	void segmentIsStraightUpOrDown() {
		assertEquals(ClimbLinks.Segment.UP, ClimbLinks.segment(BASE, BASE.above()));
		assertEquals(ClimbLinks.Segment.DOWN, ClimbLinks.segment(BASE, BASE.below()));
		assertEquals(ClimbLinks.Segment.NONE, ClimbLinks.segment(BASE, BASE.east()));
		assertEquals(ClimbLinks.Segment.NONE, ClimbLinks.segment(BASE, BASE.east().above()));
		assertEquals(ClimbLinks.Segment.NONE, ClimbLinks.segment(BASE, BASE.above(2)));
		assertEquals(ClimbLinks.Segment.NONE, ClimbLinks.segment(BASE, BASE));
	}

	private static BlockPos at(int dy) {
		return BASE.above(dy);
	}

	/** Ladders from {@code from} (inclusive) to {@code to} (exclusive), on a stone wall to the south. */
	private void ladders(int from, int to) {
		for (int y = from; y < to; y++) {
			blocks.put(at(y).relative(FACING.getOpposite()), Blocks.STONE.defaultBlockState());
		}
		column(ladder(FACING), from, to);
	}

	private void column(BlockState state, int from, int to) {
		for (int y = from; y < to; y++) {
			blocks.put(at(y), state);
		}
	}

	private static BlockState ladder(Direction facing) {
		return Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, facing);
	}

	private static BlockState trapdoor(Block block, Direction facing, boolean open) {
		return block.defaultBlockState().setValue(TrapDoorBlock.FACING, facing).setValue(TrapDoorBlock.OPEN, open);
	}
}
