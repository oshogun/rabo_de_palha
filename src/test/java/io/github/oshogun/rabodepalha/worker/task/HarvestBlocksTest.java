package io.github.oshogun.rabodepalha.worker.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.oshogun.rabodepalha.worker.task.HarvestBlocks.Rule;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.BeetrootBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Gourd attachment and stalk bases in the world, and the scan, are covered by GameTests. */
class HarvestBlocksTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void rulePerBlock() {
		assertEquals(Rule.CROP, HarvestBlocks.ruleOf(Blocks.WHEAT.defaultBlockState()));
		assertEquals(Rule.CROP, HarvestBlocks.ruleOf(Blocks.CARROTS.defaultBlockState()));
		assertEquals(Rule.CROP, HarvestBlocks.ruleOf(Blocks.POTATOES.defaultBlockState()));
		assertEquals(Rule.CROP, HarvestBlocks.ruleOf(Blocks.BEETROOTS.defaultBlockState()));
		assertEquals(Rule.NETHER_WART, HarvestBlocks.ruleOf(Blocks.NETHER_WART.defaultBlockState()));
		assertEquals(Rule.GOURD, HarvestBlocks.ruleOf(Blocks.MELON.defaultBlockState()));
		assertEquals(Rule.GOURD, HarvestBlocks.ruleOf(Blocks.PUMPKIN.defaultBlockState()));
		assertEquals(Rule.STALK, HarvestBlocks.ruleOf(Blocks.SUGAR_CANE.defaultBlockState()));
		assertEquals(Rule.STALK, HarvestBlocks.ruleOf(Blocks.CACTUS.defaultBlockState()));
		assertEquals(Rule.STALK, HarvestBlocks.ruleOf(Blocks.BAMBOO.defaultBlockState()));
		assertEquals(Rule.BERRY, HarvestBlocks.ruleOf(Blocks.SWEET_BERRY_BUSH.defaultBlockState()));
		assertEquals(Rule.COCOA, HarvestBlocks.ruleOf(Blocks.COCOA.defaultBlockState()));
		for (BlockState other : new BlockState[] { Blocks.TORCHFLOWER_CROP.defaultBlockState(),
				Blocks.PITCHER_CROP.defaultBlockState(), Blocks.BAMBOO_SAPLING.defaultBlockState(),
				Blocks.MELON_STEM.defaultBlockState(), Blocks.CARVED_PUMPKIN.defaultBlockState(),
				Blocks.DIRT.defaultBlockState(), Blocks.AIR.defaultBlockState() }) {
			assertEquals(Rule.NONE, HarvestBlocks.ruleOf(other), other.toString());
		}
	}

	@Test
	void ripeAtTheBoundaries() {
		assertFalse(HarvestBlocks.ripe(Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 6)));
		assertTrue(HarvestBlocks.ripe(Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7)));
		assertFalse(HarvestBlocks.ripe(Blocks.BEETROOTS.defaultBlockState().setValue(BeetrootBlock.AGE, 2)));
		assertTrue(HarvestBlocks.ripe(Blocks.BEETROOTS.defaultBlockState().setValue(BeetrootBlock.AGE, 3)));
		assertFalse(HarvestBlocks.ripe(Blocks.NETHER_WART.defaultBlockState().setValue(NetherWartBlock.AGE, 2)));
		assertTrue(HarvestBlocks.ripe(Blocks.NETHER_WART.defaultBlockState().setValue(NetherWartBlock.AGE, 3)));
		assertFalse(HarvestBlocks.ripe(Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 1)));
		assertTrue(HarvestBlocks.ripe(Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 2)));
		assertTrue(HarvestBlocks.ripe(Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 3)));
		assertFalse(HarvestBlocks.ripe(Blocks.COCOA.defaultBlockState().setValue(CocoaBlock.AGE, 1)));
		assertTrue(HarvestBlocks.ripe(Blocks.COCOA.defaultBlockState().setValue(CocoaBlock.AGE, 2)));
		assertFalse(HarvestBlocks.ripe(Blocks.MELON.defaultBlockState()));
		assertFalse(HarvestBlocks.ripe(Blocks.SUGAR_CANE.defaultBlockState()));
	}

	@Test
	void replanting() {
		assertEquals(Blocks.WHEAT.defaultBlockState(),
				HarvestBlocks.replantState(Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7)));
		assertEquals(Items.WHEAT_SEEDS, HarvestBlocks.replantItem(Blocks.WHEAT.defaultBlockState()));
		assertEquals(Items.POTATO, HarvestBlocks.replantItem(Blocks.POTATOES.defaultBlockState()));
		assertEquals(Items.CARROT, HarvestBlocks.replantItem(Blocks.CARROTS.defaultBlockState()));
		assertEquals(Items.BEETROOT_SEEDS, HarvestBlocks.replantItem(Blocks.BEETROOTS.defaultBlockState()));
		assertEquals(Items.NETHER_WART, HarvestBlocks.replantItem(Blocks.NETHER_WART.defaultBlockState()));
		assertEquals(Blocks.NETHER_WART.defaultBlockState(),
				HarvestBlocks.replantState(Blocks.NETHER_WART.defaultBlockState().setValue(NetherWartBlock.AGE, 3)));

		BlockState pod = Blocks.COCOA.defaultBlockState().setValue(CocoaBlock.AGE, 2).setValue(CocoaBlock.FACING, Direction.EAST);
		BlockState replanted = HarvestBlocks.replantState(pod);
		assertEquals(0, replanted.getValue(CocoaBlock.AGE));
		assertEquals(Direction.EAST, replanted.getValue(CocoaBlock.FACING));
		assertEquals(Items.COCOA_BEANS, HarvestBlocks.replantItem(pod));

		for (BlockState kept : new BlockState[] { Blocks.MELON.defaultBlockState(), Blocks.SUGAR_CANE.defaultBlockState(),
				Blocks.SWEET_BERRY_BUSH.defaultBlockState(), Blocks.DIRT.defaultBlockState() }) {
			assertNull(HarvestBlocks.replantState(kept), kept.toString());
			assertNull(HarvestBlocks.replantItem(kept), kept.toString());
		}
	}

	@Test
	void pickedBushIsAgeOne() {
		BlockState bush = Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 3);
		assertEquals(1, HarvestBlocks.pickedState(bush).getValue(SweetBerryBushBlock.AGE));
	}

	@Test
	void stalkBase() {
		BlockState cane = Blocks.SUGAR_CANE.defaultBlockState();
		BlockState sand = Blocks.SAND.defaultBlockState();
		BlockState air = Blocks.AIR.defaultBlockState();
		assertTrue(HarvestBlocks.isStalkBase(cane, cane, sand));
		assertFalse(HarvestBlocks.isStalkBase(cane, air, sand));
		assertFalse(HarvestBlocks.isStalkBase(cane, cane, cane));
		assertFalse(HarvestBlocks.isStalkBase(Blocks.CACTUS.defaultBlockState(), cane, sand));
	}

	@Test
	void attachedStem() {
		BlockState melonStem = Blocks.ATTACHED_MELON_STEM.defaultBlockState().setValue(AttachedStemBlock.FACING, Direction.EAST);
		assertTrue(HarvestBlocks.attachedTo(melonStem, Direction.EAST, Blocks.MELON));
		assertFalse(HarvestBlocks.attachedTo(melonStem, Direction.WEST, Blocks.MELON));
		assertFalse(HarvestBlocks.attachedTo(melonStem, Direction.EAST, Blocks.PUMPKIN));
		BlockState pumpkinStem = Blocks.ATTACHED_PUMPKIN_STEM.defaultBlockState().setValue(AttachedStemBlock.FACING, Direction.NORTH);
		assertTrue(HarvestBlocks.attachedTo(pumpkinStem, Direction.NORTH, Blocks.PUMPKIN));
		assertFalse(HarvestBlocks.attachedTo(Blocks.MELON_STEM.defaultBlockState(), Direction.EAST, Blocks.MELON));
	}
}
