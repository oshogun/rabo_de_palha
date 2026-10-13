package io.github.oshogun.rabodepalha.worker.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BuildNormalizeTest {
	private static final BlockPos POS = new BlockPos(3, 64, -2);

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void waterloggedStairBecomesDry() {
		BlockState wet = Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true);

		BuildTask.Normalized normalized = BuildTask.normalize(wet, POS, Map.of(POS, wet)).orElseThrow();

		assertEquals(wet.setValue(BlockStateProperties.WATERLOGGED, false), normalized.state());
		assertTrue(normalized.dried());
	}

	@Test
	void extendedPistonBecomesRetracted() {
		for (Block piston : List.of(Blocks.PISTON, Blocks.STICKY_PISTON)) {
			BlockState extended = piston.defaultBlockState().setValue(BlockStateProperties.EXTENDED, true);

			BuildTask.Normalized normalized = BuildTask.normalize(extended, POS, Map.of(POS, extended)).orElseThrow();

			assertEquals(extended.setValue(BlockStateProperties.EXTENDED, false), normalized.state(), piston.toString());
			assertFalse(normalized.dried());
		}
	}

	@Test
	void pistonHeadAndMovingPistonDropped() {
		for (Block block : List.of(Blocks.PISTON_HEAD, Blocks.MOVING_PISTON)) {
			BlockState state = block.defaultBlockState();
			assertEquals(Optional.empty(), BuildTask.normalize(state, POS, Map.of(POS, state)), block.toString());
		}
	}

	@Test
	void loneChestHalfBecomesSingle() {
		for (Block chest : List.of(Blocks.CHEST, Blocks.TRAPPED_CHEST)) {
			BlockState left = chestHalf(chest, Direction.NORTH, ChestType.LEFT);
			BlockPos partnerPos = POS.relative(ChestBlock.getConnectedDirection(left));
			BlockState single = left.setValue(BlockStateProperties.CHEST_TYPE, ChestType.SINGLE);

			// No partner at all.
			assertEquals(single, normalizedState(left, POS, Map.of(POS, left)), chest.toString());
			// Partner with the wrong type.
			assertEquals(single, normalizedState(left, POS,
					Map.of(POS, left, partnerPos, chestHalf(chest, Direction.NORTH, ChestType.LEFT))), chest.toString());
			// Partner with the wrong facing.
			assertEquals(single, normalizedState(left, POS,
					Map.of(POS, left, partnerPos, chestHalf(chest, Direction.SOUTH, ChestType.RIGHT))), chest.toString());
		}
	}

	@Test
	void loneChestHalfWithOtherChestKindBecomesSingle() {
		BlockState left = chestHalf(Blocks.CHEST, Direction.NORTH, ChestType.LEFT);
		BlockPos partnerPos = POS.relative(ChestBlock.getConnectedDirection(left));

		assertEquals(left.setValue(BlockStateProperties.CHEST_TYPE, ChestType.SINGLE), normalizedState(left, POS,
				Map.of(POS, left, partnerPos, chestHalf(Blocks.TRAPPED_CHEST, Direction.NORTH, ChestType.RIGHT))));
	}

	@Test
	void doubleChestKept() {
		BlockState left = chestHalf(Blocks.CHEST, Direction.NORTH, ChestType.LEFT);
		BlockState right = chestHalf(Blocks.CHEST, Direction.NORTH, ChestType.RIGHT);
		BlockPos rightPos = POS.relative(ChestBlock.getConnectedDirection(left));
		assertDoubleChestKept(Map.of(POS, left, rightPos, right));

		Map<BlockPos, BlockState> rotated = new HashMap<>();
		rotated.put(POS.rotate(Rotation.CLOCKWISE_90), left.rotate(Rotation.CLOCKWISE_90));
		rotated.put(rightPos.rotate(Rotation.CLOCKWISE_90), right.rotate(Rotation.CLOCKWISE_90));
		assertDoubleChestKept(rotated);
	}

	@Test
	void plainStateUnchanged() {
		BlockState stone = Blocks.STONE.defaultBlockState();

		BuildTask.Normalized normalized = BuildTask.normalize(stone, POS, Map.of(POS, stone)).orElseThrow();

		assertEquals(stone, normalized.state());
		assertFalse(normalized.dried());
	}

	private static void assertDoubleChestKept(Map<BlockPos, BlockState> blueprint) {
		for (Map.Entry<BlockPos, BlockState> entry : blueprint.entrySet()) {
			assertEquals(entry.getValue(), normalizedState(entry.getValue(), entry.getKey(), blueprint));
		}
	}

	private static BlockState normalizedState(BlockState state, BlockPos pos, Map<BlockPos, BlockState> blueprint) {
		return BuildTask.normalize(state, pos, blueprint).orElseThrow().state();
	}

	private static BlockState chestHalf(Block chest, Direction facing, ChestType type) {
		return chest.defaultBlockState().setValue(ChestBlock.FACING, facing).setValue(BlockStateProperties.CHEST_TYPE, type);
	}
}
