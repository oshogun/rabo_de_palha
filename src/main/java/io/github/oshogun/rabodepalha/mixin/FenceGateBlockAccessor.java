package io.github.oshogun.rabodepalha.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.properties.WoodType;

/** Exposes a fence gate's wood type, which vanilla keeps private, so a worker can play the gate's open and close sounds. */
@Mixin(FenceGateBlock.class)
public interface FenceGateBlockAccessor {
	@Accessor("type")
	WoodType rabodepalha$type();
}
