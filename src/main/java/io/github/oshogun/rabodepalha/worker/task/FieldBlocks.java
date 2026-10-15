package io.github.oshogun.rabodepalha.worker.task;

import java.util.OptionalInt;

import io.github.oshogun.rabodepalha.rts.RtsField;
import io.github.oshogun.rabodepalha.worker.WorkerEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.PitcherCropBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import org.jspecify.annotations.Nullable;

/**
 * What a field block needs for a seed: till it, plant on it, or skip it. The rules are pure where
 * they can be so they can be unit-tested; the world overloads only read block states and tags.
 */
public final class FieldBlocks {
	/** The block a seed is planted on. */
	public enum Soil {
		FARMLAND, SOUL_SAND, NONE
	}

	/** What is on top of a field block. */
	public enum Cover {
		/** Nothing; the block can be worked right away. */
		AIR,
		/** Grass, ferns, dead bushes, snow layers and similar: broken first, then the block is worked. */
		CLEARABLE,
		/** Flowers, saplings, crops, mushrooms, fluids and solid blocks: the block is skipped. */
		BLOCKED
	}

	public enum Action {
		TILL, PLANT, SKIP
	}

	/** What to do with a field block, and whether its cover has to be broken first. */
	public record Plan(Action action, boolean clearFirst) {
		public static final Plan SKIP = new Plan(Action.SKIP, false);
	}

	private FieldBlocks() {
	}

	/** The soil {@code seed} is planted on, or {@link Soil#NONE} when it is not a seed. */
	public static Soil soilOf(Item seed) {
		if (!(seed instanceof BlockItem blockItem)) {
			return Soil.NONE;
		}
		Block block = blockItem.getBlock();
		if (block instanceof NetherWartBlock) {
			return Soil.SOUL_SAND;
		}
		if (block instanceof CropBlock || block instanceof StemBlock || block instanceof PitcherCropBlock) {
			return Soil.FARMLAND;
		}
		return Soil.NONE;
	}

	public static boolean isSeed(Item item) {
		return soilOf(item) != Soil.NONE;
	}

	public static boolean isSeed(Identifier item) {
		return BuiltInRegistries.ITEM.getOptional(item).map(FieldBlocks::isSeed).orElse(false);
	}

	/**
	 * The clearing rule. Snow layers of any depth are cleared; other replaceable blocks are cleared
	 * unless they hold a fluid (waterlogged or underwater plants) or are a flower or sapling.
	 */
	public static Cover cover(boolean air, boolean snowLayer, boolean replaceable, boolean hasFluid,
			boolean flowerOrSapling) {
		if (air) {
			return Cover.AIR;
		}
		if (snowLayer || replaceable && !hasFluid && !flowerOrSapling) {
			return Cover.CLEARABLE;
		}
		return Cover.BLOCKED;
	}

	/** What to do with a field block that is tillable, farmland or soul sand, under {@code cover}. */
	public static Plan plan(Soil soil, boolean tillable, boolean farmland, boolean soulSand, Cover cover) {
		if (cover == Cover.BLOCKED) {
			return Plan.SKIP;
		}
		boolean clearFirst = cover == Cover.CLEARABLE;
		return switch (soil) {
			case SOUL_SAND -> soulSand ? new Plan(Action.PLANT, clearFirst) : Plan.SKIP;
			case FARMLAND -> farmland ? new Plan(Action.PLANT, clearFirst)
					: tillable ? new Plan(Action.TILL, clearFirst)
					: Plan.SKIP;
			case NONE -> Plan.SKIP;
		};
	}

	public static Cover cover(BlockState above) {
		boolean air = above.isAir();
		boolean snowLayer = above.is(Blocks.SNOW);
		boolean replaceable = above.canBeReplaced();
		boolean hasFluid = !above.getFluidState().isEmpty();
		// The tags are only read when they decide the result.
		boolean flowerOrSapling = !air && !snowLayer && replaceable && !hasFluid
				&& (above.is(BlockTags.FLOWERS) || above.is(BlockTags.SAPLINGS));
		return cover(air, snowLayer, replaceable, hasFluid, flowerOrSapling);
	}

	/**
	 * Whether a block counts as a column's ground. Plants, snow layers and other replaceable blocks
	 * without fluid are cover, not ground; fluids are ground so that the column is skipped.
	 */
	public static boolean isGround(BlockState state) {
		return !state.isAir()
				&& !(state.getBlock() instanceof VegetationBlock)
				&& !state.is(Blocks.SNOW)
				&& !(state.canBeReplaced() && state.getFluidState().isEmpty());
	}

	/** The plan for the field block at {@code ground} with {@code seed}. */
	public static Plan plan(Level level, BlockPos ground, Item seed) {
		BlockState state = level.getBlockState(ground);
		return plan(soilOf(seed), state.is(BlockTags.TURNS_INTO_FARMLAND), state.is(Blocks.FARMLAND),
				state.is(Blocks.SOUL_SAND), cover(level.getBlockState(ground.above())));
	}

	/** The field block of column ({@code x}, {@code z}), or null when it has no ground in the window or is buried. */
	public static @Nullable BlockPos surface(Level level, RtsField field, int x, int z) {
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		OptionalInt y = RtsField.surfaceY(field.bottomY(), field.topY(),
				at -> isGround(level.getBlockState(cursor.set(x, at, z))));
		return y.isPresent() ? new BlockPos(x, y.getAsInt(), z) : null;
	}

	/** Copy of the private FarmlandBlock#isNearWater: water within 4 blocks across and 0-1 blocks up. */
	public static boolean nearWater(LevelReader level, BlockPos pos) {
		return level.findBlocksIn(pos.offset(-4, 0, -4), pos.offset(4, 1, 4))
				.filterState(state -> state.getFluidState().is(FluidTags.WATER))
				.anyMatched();
	}

	/** The first hoe in the worker's inventory, or {@link ItemStack#EMPTY}. */
	public static ItemStack findHoe(WorkerEntity worker) {
		SimpleContainer inventory = worker.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.is(ItemTags.HOES)) {
				return stack;
			}
		}
		return ItemStack.EMPTY;
	}

	/**
	 * Breaks the cover at {@code above} by hand, like MineTask: drops go to the worker's inventory and
	 * destroyBlock plays the sound and particles without world drops. A two-block plant is broken at its
	 * lower half, whose drops are the plant's; DoublePlantBlock#updateShape then removes the upper half
	 * without drops, so it drops once.
	 */
	public static void clear(ServerLevel level, BlockPos above, WorkerEntity worker) {
		BlockPos pos = above;
		BlockState state = level.getBlockState(pos);
		if (state.getBlock() instanceof DoublePlantBlock && state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER) {
			pos = pos.below();
			state = level.getBlockState(pos);
		}
		for (ItemStack drop : Block.getDrops(state, level, pos, level.getBlockEntity(pos), worker, ItemStack.EMPTY)) {
			worker.insertOrDrop(drop);
		}
		level.destroyBlock(pos, false, worker);
	}
}
