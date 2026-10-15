package io.github.oshogun.rabodepalha.worker.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.oshogun.rabodepalha.worker.task.FieldBlocks.Action;
import io.github.oshogun.rabodepalha.worker.task.FieldBlocks.Cover;
import io.github.oshogun.rabodepalha.worker.task.FieldBlocks.Plan;
import io.github.oshogun.rabodepalha.worker.task.FieldBlocks.Soil;

import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Tags are not bound here; flowers, saplings, grass cover and tillable ground are covered by GameTests. */
class FieldBlocksTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void seedsAndTheirSoil() {
		for (Item seed : new Item[] { Items.WHEAT_SEEDS, Items.BEETROOT_SEEDS, Items.MELON_SEEDS, Items.PUMPKIN_SEEDS,
				Items.TORCHFLOWER_SEEDS, Items.PITCHER_POD, Items.CARROT, Items.POTATO }) {
			assertEquals(Soil.FARMLAND, FieldBlocks.soilOf(seed), seed.toString());
			assertTrue(FieldBlocks.isSeed(seed), seed.toString());
		}
		assertEquals(Soil.SOUL_SAND, FieldBlocks.soilOf(Items.NETHER_WART));
		for (Item other : new Item[] { Items.DIRT, Items.STICK, Items.BONE_MEAL }) {
			assertEquals(Soil.NONE, FieldBlocks.soilOf(other), other.toString());
			assertFalse(FieldBlocks.isSeed(other), other.toString());
		}
	}

	@Test
	void seedsById() {
		assertTrue(FieldBlocks.isSeed(Identifier.withDefaultNamespace("wheat_seeds")));
		assertFalse(FieldBlocks.isSeed(Identifier.withDefaultNamespace("stick")));
		assertFalse(FieldBlocks.isSeed(Identifier.fromNamespaceAndPath("rabodepalha", "no_such_item")));
	}

	@Test
	void coverRule() {
		assertEquals(Cover.AIR, FieldBlocks.cover(true, false, true, false, false));
		assertEquals(Cover.CLEARABLE, FieldBlocks.cover(false, true, false, false, false));
		assertEquals(Cover.CLEARABLE, FieldBlocks.cover(false, true, true, false, false));
		assertEquals(Cover.CLEARABLE, FieldBlocks.cover(false, false, true, false, false));
		assertEquals(Cover.BLOCKED, FieldBlocks.cover(false, false, true, false, true));
		assertEquals(Cover.BLOCKED, FieldBlocks.cover(false, false, true, true, false));
		assertEquals(Cover.BLOCKED, FieldBlocks.cover(false, false, false, false, false));
	}

	@Test
	void planRule() {
		for (Cover cover : new Cover[] { Cover.AIR, Cover.CLEARABLE }) {
			boolean clear = cover == Cover.CLEARABLE;
			assertEquals(new Plan(Action.TILL, clear), FieldBlocks.plan(Soil.FARMLAND, true, false, false, cover));
			assertEquals(new Plan(Action.PLANT, clear), FieldBlocks.plan(Soil.FARMLAND, false, true, false, cover));
			assertEquals(new Plan(Action.PLANT, clear), FieldBlocks.plan(Soil.SOUL_SAND, false, false, true, cover));
			assertEquals(Plan.SKIP, FieldBlocks.plan(Soil.FARMLAND, false, false, true, cover));
			assertEquals(Plan.SKIP, FieldBlocks.plan(Soil.SOUL_SAND, true, false, false, cover));
			assertEquals(Plan.SKIP, FieldBlocks.plan(Soil.SOUL_SAND, false, true, false, cover));
			assertEquals(Plan.SKIP, FieldBlocks.plan(Soil.FARMLAND, false, false, false, cover));
			assertEquals(Plan.SKIP, FieldBlocks.plan(Soil.NONE, true, true, true, cover));
		}
		assertEquals(Plan.SKIP, FieldBlocks.plan(Soil.FARMLAND, true, false, false, Cover.BLOCKED));
		assertEquals(Plan.SKIP, FieldBlocks.plan(Soil.FARMLAND, false, true, false, Cover.BLOCKED));
		assertEquals(Plan.SKIP, FieldBlocks.plan(Soil.SOUL_SAND, false, false, true, Cover.BLOCKED));
	}

	@Test
	void worldCoverWithoutTags() {
		assertEquals(Cover.AIR, FieldBlocks.cover(Blocks.AIR.defaultBlockState()));
		assertEquals(Cover.CLEARABLE, FieldBlocks.cover(Blocks.SNOW.defaultBlockState()));
		assertEquals(Cover.CLEARABLE, FieldBlocks.cover(Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 3)));
		assertEquals(Cover.BLOCKED, FieldBlocks.cover(Blocks.WHEAT.defaultBlockState()));
		assertEquals(Cover.BLOCKED, FieldBlocks.cover(Blocks.BROWN_MUSHROOM.defaultBlockState()));
		assertEquals(Cover.BLOCKED, FieldBlocks.cover(Blocks.STONE.defaultBlockState()));
		assertEquals(Cover.BLOCKED, FieldBlocks.cover(Blocks.WATER.defaultBlockState()));
		assertEquals(Cover.BLOCKED, FieldBlocks.cover(Blocks.SEAGRASS.defaultBlockState()));
	}

	@Test
	void groundExcludesCover() {
		assertTrue(FieldBlocks.isGround(Blocks.GRASS_BLOCK.defaultBlockState()));
		assertTrue(FieldBlocks.isGround(Blocks.FARMLAND.defaultBlockState()));
		assertTrue(FieldBlocks.isGround(Blocks.WATER.defaultBlockState()));
		assertFalse(FieldBlocks.isGround(Blocks.AIR.defaultBlockState()));
		assertFalse(FieldBlocks.isGround(Blocks.SNOW.defaultBlockState()));
		assertFalse(FieldBlocks.isGround(Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 3)));
		assertFalse(FieldBlocks.isGround(Blocks.SHORT_GRASS.defaultBlockState()));
		assertFalse(FieldBlocks.isGround(Blocks.TALL_GRASS.defaultBlockState()));
		assertFalse(FieldBlocks.isGround(Blocks.TALL_GRASS.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER)));
		assertFalse(FieldBlocks.isGround(Blocks.WHEAT.defaultBlockState()));
	}
}
