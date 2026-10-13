package io.github.oshogun.rabodepalha.client.mixin;

import net.minecraft.client.gui.components.spectator.SpectatorGui;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.oshogun.rabodepalha.client.rts.RtsClient;

/**
 * RTS mode runs on vanilla spectator mode, but the player should not see or open the spectator hotbar and
 * teleport menu: hide them and ignore the hotbar keys while RTS mode is active.
 */
@Mixin(SpectatorGui.class)
abstract class SpectatorGuiMixin {
	@Inject(method = {"extractHotbar", "extractAction", "onHotbarSelected", "onHotbarActionKeyPressed"},
			at = @At("HEAD"), cancellable = true)
	private void rabodepalha$hideInRts(CallbackInfo ci) {
		if (RtsClient.isActive()) {
			ci.cancel();
		}
	}
}
