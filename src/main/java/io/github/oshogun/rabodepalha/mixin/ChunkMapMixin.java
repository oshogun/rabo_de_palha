package io.github.oshogun.rabodepalha.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import io.github.oshogun.rabodepalha.rts.RtsMode;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;

/**
 * Keeps players in RTS mode loading chunks even when spectators don't generate chunks, since the RTS camera is a
 * spectator internally. Plain spectators still follow the game rule.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
	@Inject(method = "skipPlayer", at = @At("HEAD"), cancellable = true)
	private void rabodepalha$keepRtsPlayer(ServerPlayer player, CallbackInfoReturnable<Boolean> cir) {
		if (RtsMode.isActive(player)) {
			cir.setReturnValue(false);
		}
	}
}
