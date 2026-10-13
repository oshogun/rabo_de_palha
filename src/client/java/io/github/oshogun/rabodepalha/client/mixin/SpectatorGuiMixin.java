package io.github.oshogun.rabodepalha.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.spectator.SpectatorGui;
import net.minecraft.client.player.LocalPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.oshogun.rabodepalha.client.rts.RtsClient;

/**
 * RTS mode runs on vanilla spectator mode, but the player should not see or open the spectator hotbar and
 * teleport menu: hide them and ignore the hotbar action key while RTS mode is active.
 */
@Mixin(SpectatorGui.class)
abstract class SpectatorGuiMixin {
	@Inject(method = {"extractHotbar", "extractAction", "onHotbarActionKeyPressed"},
			at = @At("HEAD"), cancellable = true)
	private void rabodepalha$hideInRts(CallbackInfo ci) {
		if (RtsClient.isActive()) {
			ci.cancel();
		}
	}

	/**
	 * Spectators route the digit keys here instead of to the inventory; in RTS mode they pick the hotbar slot
	 * whose block a place order uses.
	 */
	@Inject(method = "onHotbarSelected", at = @At("HEAD"), cancellable = true)
	private void rabodepalha$selectSlotInRts(int slot, CallbackInfo ci) {
		if (RtsClient.isActive()) {
			LocalPlayer player = Minecraft.getInstance().player;
			if (player != null) {
				player.getInventory().setSelectedSlot(slot);
			}
			ci.cancel();
		}
	}
}
