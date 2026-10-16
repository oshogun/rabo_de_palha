package io.github.oshogun.rabodepalha.worker.task;

import io.github.oshogun.rabodepalha.RaboDePalha;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FallingBlock;

/**
 * The throwaway blocks a worker may place to pillar up or bridge a gap. The tag can be changed by a
 * pack; anything in it that is not a plain full, non-falling block (sand, slabs, chests) is ignored.
 */
public final class ScaffoldBlocks {
	/** Items a worker may spend as scaffold: {@code #rabodepalha:scaffold_blocks}. */
	public static final TagKey<Item> TAG = TagKey.create(Registries.ITEM, RaboDePalha.id("scaffold_blocks"));

	private ScaffoldBlocks() {
	}

	/** Whether the stack can be placed as scaffold: in the tag, and a full, plain, non-falling block. */
	public static boolean isUsable(ItemStack stack) {
		if (stack.isEmpty() || !stack.is(TAG) || !(stack.getItem() instanceof BlockItem blockItem)) {
			return false;
		}
		Block block = blockItem.getBlock();
		return !(block instanceof FallingBlock)
				&& !(block instanceof EntityBlock)
				&& block.defaultBlockState().isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
	}
}
