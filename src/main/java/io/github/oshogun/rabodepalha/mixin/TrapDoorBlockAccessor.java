package io.github.oshogun.rabodepalha.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.properties.BlockSetType;

/**
 * Exposes a trapdoor's block set type, which vanilla keeps behind a protected getter, so a worker can tell
 * whether it opens by hand and play its open and close sounds.
 */
@Mixin(TrapDoorBlock.class)
public interface TrapDoorBlockAccessor {
	@Accessor("type")
	BlockSetType rabodepalha$type();
}
