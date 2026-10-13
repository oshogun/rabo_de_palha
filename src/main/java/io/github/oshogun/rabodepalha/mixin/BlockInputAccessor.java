package io.github.oshogun.rabodepalha.mixin;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.nbt.CompoundTag;

/** Exposes the block-entity NBT a parsed block argument carries, which vanilla keeps private. */
@Mixin(BlockInput.class)
public interface BlockInputAccessor {
	@Accessor("tag")
	@Nullable CompoundTag rabodepalha$getTag();
}
