package io.github.oshogun.rabodepalha.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import io.github.oshogun.rabodepalha.rts.RtsMode;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * Skips the "moved too quickly" check for players in RTS mode, so fast camera panning is not rubber-banded.
 * The check stays in place for every other player.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
	@Shadow
	public ServerPlayer player;

	@Inject(method = "shouldCheckPlayerMovement", at = @At("HEAD"), cancellable = true)
	private void rabodepalha$skipRtsMovementCheck(boolean isFallFlying, CallbackInfoReturnable<Boolean> cir) {
		if (RtsMode.isActive(this.player)) {
			cir.setReturnValue(false);
		}
	}
}
