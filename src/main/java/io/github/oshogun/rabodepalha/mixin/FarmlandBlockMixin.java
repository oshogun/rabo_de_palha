package io.github.oshogun.rabodepalha.mixin;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;

import io.github.oshogun.rabodepalha.worker.WorkerEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Workers never trample farmland, whatever {@code mobGriefing} says, so they can walk their own fields. Only the
 * trample call in {@code fallOn} is skipped: fall damage still applies, and {@code turnToBaseBlock} itself, which
 * drying farmland also uses, is untouched.
 */
@Mixin(FarmlandBlock.class)
public abstract class FarmlandBlockMixin {
	@WrapWithCondition(method = "fallOn", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/FarmlandBlock;turnToBaseBlock(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V"))
	private boolean rabodepalha$workersDontTrample(FarmlandBlock block, @Nullable Entity entity, BlockState state,
			Level level, BlockPos pos) {
		return !(entity instanceof WorkerEntity);
	}
}
