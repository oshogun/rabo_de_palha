package io.github.oshogun.rabodepalha.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class OpenablesTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void woodenDoorsAreOpenableDoors() {
		for (Block door : new Block[] { Blocks.OAK_DOOR, Blocks.SPRUCE_DOOR, Blocks.BIRCH_DOOR, Blocks.JUNGLE_DOOR,
				Blocks.ACACIA_DOOR, Blocks.DARK_OAK_DOOR, Blocks.MANGROVE_DOOR, Blocks.CHERRY_DOOR, Blocks.BAMBOO_DOOR,
				Blocks.CRIMSON_DOOR, Blocks.WARPED_DOOR }) {
			BlockState state = door.defaultBlockState();
			assertTrue(Openables.isOpenableDoor(state), door.toString());
			assertTrue(Openables.isOpenable(state), door.toString());
			assertFalse(Openables.isOpenableGate(state), door.toString());
		}
	}

	@Test
	void ironDoorIsNotOpenable() {
		BlockState iron = Blocks.IRON_DOOR.defaultBlockState();
		assertFalse(Openables.isOpenableDoor(iron));
		assertFalse(Openables.isOpenable(iron));
		assertFalse(Openables.isClosedOpenable(iron));
	}

	@Test
	void fenceGateIsAnOpenableGate() {
		BlockState gate = Blocks.OAK_FENCE_GATE.defaultBlockState();
		assertTrue(Openables.isOpenableGate(gate));
		assertTrue(Openables.isOpenable(gate));
		assertFalse(Openables.isOpenableDoor(gate));
	}

	@Test
	void fencesTrapdoorsAndStoneAreNotOpenable() {
		assertFalse(Openables.isOpenable(Blocks.OAK_FENCE.defaultBlockState()));
		assertFalse(Openables.isOpenable(Blocks.OAK_TRAPDOOR.defaultBlockState()));
		assertFalse(Openables.isOpenable(Blocks.STONE.defaultBlockState()));
		assertFalse(Openables.isClosedOpenable(Blocks.OAK_TRAPDOOR.defaultBlockState()));
	}

	@Test
	void closedOpenableFlipsWithOpen() {
		BlockState door = Blocks.OAK_DOOR.defaultBlockState();
		assertTrue(Openables.isClosedOpenable(door.setValue(BlockStateProperties.OPEN, false)));
		assertFalse(Openables.isClosedOpenable(door.setValue(BlockStateProperties.OPEN, true)));

		BlockState gate = Blocks.OAK_FENCE_GATE.defaultBlockState();
		assertTrue(Openables.isClosedOpenable(gate.setValue(BlockStateProperties.OPEN, false)));
		assertFalse(Openables.isClosedOpenable(gate.setValue(BlockStateProperties.OPEN, true)));
	}

	@Test
	void canonicalPosIsTheLowerDoorHalf() {
		BlockPos pos = new BlockPos(3, 64, -2);
		BlockState door = Blocks.OAK_DOOR.defaultBlockState();
		assertEquals(pos.below(), Openables.canonicalPos(door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), pos));
		assertEquals(pos, Openables.canonicalPos(door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), pos));
		assertEquals(pos, Openables.canonicalPos(Blocks.OAK_FENCE_GATE.defaultBlockState(), pos));
	}
}
