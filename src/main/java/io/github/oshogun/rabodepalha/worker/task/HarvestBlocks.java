package io.github.oshogun.rabodepalha.worker.task;

import java.util.List;

import io.github.oshogun.rabodepalha.rts.RtsField;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.TorchflowerCropBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.jspecify.annotations.Nullable;

/**
 * Which field plants are ripe, what a harvest keeps and what it replants. The rules are pure functions
 * of the block state so they can be unit-tested; the world overloads only read block states, so the
 * client overlay can use them too.
 */
public final class HarvestBlocks {
	/** How a plant is harvested. */
	public enum Rule {
		/** Wheat, carrots, potatoes and beetroot: broken at max age and replanted. */
		CROP,
		/** Broken at age 3 and replanted on the soul sand. */
		NETHER_WART,
		/** Melons and pumpkins: the fruit is broken and the stem attached to it kept. */
		GOURD,
		/** Sugar cane, cactus and bamboo: everything above the bottom block is broken. */
		STALK,
		/** Sweet berries: picked when older than age 1, and the bush reset to age 1. */
		BERRY,
		/** Broken at age 2 and replanted facing the same log. */
		COCOA,
		/** Not a harvestable plant. */
		NONE
	}

	/** A ripe plant to harvest: the block itself, or the bottom block of a stalk. */
	public record Target(BlockPos pos, Rule rule) {
	}

	/** The age a picked sweet berry bush is reset to. */
	public static final int PICKED_BERRY_AGE = 1;

	private HarvestBlocks() {
	}

	/**
	 * The harvest rule of {@code state}. Torchflower and pitcher crops are not harvested: their ripe form
	 * drops the flower, not a seed.
	 */
	public static Rule ruleOf(BlockState state) {
		Block block = state.getBlock();
		if (block instanceof CropBlock) {
			return block instanceof TorchflowerCropBlock ? Rule.NONE : Rule.CROP;
		}
		if (block instanceof NetherWartBlock) {
			return Rule.NETHER_WART;
		}
		if (state.is(Blocks.MELON) || state.is(Blocks.PUMPKIN)) {
			return Rule.GOURD;
		}
		if (block instanceof SugarCaneBlock || block instanceof CactusBlock || block instanceof BambooStalkBlock) {
			return Rule.STALK;
		}
		if (block instanceof SweetBerryBushBlock) {
			return Rule.BERRY;
		}
		if (block instanceof CocoaBlock) {
			return Rule.COCOA;
		}
		return Rule.NONE;
	}

	/**
	 * Whether {@code state} is ripe from its age alone. Gourds and stalks depend on their neighbours and
	 * are never ripe here; see {@link #ripeAt}.
	 */
	public static boolean ripe(BlockState state) {
		return switch (ruleOf(state)) {
			case CROP -> ((CropBlock) state.getBlock()).isMaxAge(state);
			case NETHER_WART -> state.getValue(NetherWartBlock.AGE) == NetherWartBlock.MAX_AGE;
			case BERRY -> state.getValue(SweetBerryBushBlock.AGE) > PICKED_BERRY_AGE;
			case COCOA -> state.getValue(CocoaBlock.AGE) == CocoaBlock.MAX_AGE;
			case GOURD, STALK, NONE -> false;
		};
	}

	/** The state a harvested plant is replanted as, or null when the plant is not replanted. */
	public static @Nullable BlockState replantState(BlockState state) {
		return switch (ruleOf(state)) {
			case CROP, NETHER_WART -> state.getBlock().defaultBlockState();
			case COCOA -> state.getBlock().defaultBlockState().setValue(CocoaBlock.FACING, state.getValue(CocoaBlock.FACING));
			case GOURD, STALK, BERRY, NONE -> null;
		};
	}

	/** The item spent to replant a harvested plant, for example wheat seeds for wheat, or null when it is not replanted. */
	public static @Nullable Item replantItem(BlockState state) {
		if (replantState(state) == null) {
			return null;
		}
		Item item = state.getBlock().asItem();
		return item == Items.AIR ? null : item;
	}

	/** The state of a sweet berry bush after it is picked. */
	public static BlockState pickedState(BlockState berry) {
		return berry.setValue(SweetBerryBushBlock.AGE, PICKED_BERRY_AGE);
	}

	/** Whether a stalk block is the base of a stalk that has grown: the same block is above it and not below it. */
	public static boolean isStalkBase(BlockState state, BlockState above, BlockState below) {
		Block block = state.getBlock();
		return ruleOf(state) == Rule.STALK && above.is(block) && !below.is(block);
	}

	/** Whether {@code stem} is a stem attached to a {@code fruit} block that lies {@code stemToFruit} of it. */
	public static boolean attachedTo(BlockState stem, Direction stemToFruit, Block fruit) {
		// AttachedStemBlock keeps its fruit private, so the attached stem is matched by block instead.
		Block attached = fruit == Blocks.MELON ? Blocks.ATTACHED_MELON_STEM
				: fruit == Blocks.PUMPKIN ? Blocks.ATTACHED_PUMPKIN_STEM
				: null;
		return attached != null && stem.is(attached) && stem.getValue(AttachedStemBlock.FACING) == stemToFruit;
	}

	/** Whether a gourd at {@code pos} has a stem attached to it on one of its four sides. */
	public static boolean hasAttachedStem(BlockGetter level, BlockPos pos, BlockState gourd) {
		for (Direction side : Direction.Plane.HORIZONTAL) {
			if (attachedTo(level.getBlockState(pos.relative(side)), side.getOpposite(), gourd.getBlock())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether the plant at {@code pos} is ripe, neighbours included: a gourd with an attached stem, or a
	 * stalk base with the same block above it.
	 */
	public static boolean ripeAt(BlockGetter level, BlockPos pos, BlockState state) {
		return switch (ruleOf(state)) {
			case GOURD -> hasAttachedStem(level, pos, state);
			case STALK -> isStalkBase(state, level.getBlockState(pos.above()), level.getBlockState(pos.below()));
			default -> ripe(state);
		};
	}

	/**
	 * Adds the ripe plants of column ({@code x}, {@code z}) in the field's window to {@code ripe}, bottom
	 * up, and returns how many unripe plants it has. A stalk is counted once, at its base; a one-block
	 * stalk is unripe. A gourd without an attached stem is neither.
	 */
	public static int scan(BlockGetter level, RtsField field, int x, int z, List<Target> ripe) {
		int unripe = 0;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int y = field.bottomY(); y <= field.topY(); y++) {
			BlockState state = level.getBlockState(cursor.set(x, y, z));
			Rule rule = ruleOf(state);
			if (rule == Rule.NONE) {
				continue;
			}
			if (rule == Rule.STALK && level.getBlockState(cursor.below()).is(state.getBlock())) {
				continue;
			}
			BlockPos pos = new BlockPos(x, y, z);
			if (ripeAt(level, pos, state)) {
				ripe.add(new Target(pos, rule));
			} else if (rule != Rule.GOURD) {
				unripe++;
			}
		}
		return unripe;
	}
}
