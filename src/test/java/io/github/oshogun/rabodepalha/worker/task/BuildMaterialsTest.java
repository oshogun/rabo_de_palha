package io.github.oshogun.rabodepalha.worker.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BuildMaterialsTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void countsEachPlaceableBlockByItem() {
		Map<BlockPos, BlockState> blueprint = new LinkedHashMap<>();
		blueprint.put(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
		blueprint.put(new BlockPos(1, 0, 0), Blocks.STONE.defaultBlockState());
		blueprint.put(new BlockPos(2, 0, 0), Blocks.OAK_PLANKS.defaultBlockState());
		blueprint.put(new BlockPos(3, 0, 0), Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true));

		assertEquals(Map.of(Items.STONE, 2, Items.OAK_PLANKS, 1, Items.OAK_STAIRS, 1), BuildTask.materials(blueprint));
	}

	@Test
	void ignoredAndUnplaceableBlocksTakeNothing() {
		Map<BlockPos, BlockState> blueprint = new LinkedHashMap<>();
		blueprint.put(new BlockPos(0, 0, 0), Blocks.AIR.defaultBlockState());
		blueprint.put(new BlockPos(1, 0, 0), Blocks.STRUCTURE_VOID.defaultBlockState());
		blueprint.put(new BlockPos(2, 0, 0), Blocks.PISTON_HEAD.defaultBlockState());
		blueprint.put(new BlockPos(3, 0, 0), Blocks.FIRE.defaultBlockState());

		assertTrue(BuildTask.materials(blueprint).isEmpty());
	}

	@Test
	void doorCountsOnce() {
		Map<BlockPos, BlockState> blueprint = new LinkedHashMap<>();
		BlockState door = Blocks.OAK_DOOR.defaultBlockState();
		blueprint.put(new BlockPos(0, 0, 0), door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER));
		blueprint.put(new BlockPos(0, 1, 0), door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));

		Map<Item, Integer> needed = BuildTask.materials(blueprint);

		assertEquals(Map.of(Items.OAK_DOOR, 1), needed);
	}
}
