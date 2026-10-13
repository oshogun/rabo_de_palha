package io.github.oshogun.rabodepalha.client.mixin;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.oshogun.rabodepalha.client.rts.RtsClient;
import io.github.oshogun.rabodepalha.client.rts.RtsSelectionClient;

/**
 * In RTS mode Escape first cancels a selection drag, then clears the selection, and only pauses the
 * game once there is nothing left to clear.
 */
@Mixin(KeyboardHandler.class)
abstract class KeyboardHandlerMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	@Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
	private void rabodepalha$rtsEscapeClearsSelection(long handle, int action, KeyEvent event, CallbackInfo ci) {
		if (!RtsClient.isActive()
				|| action != 1
				|| !event.isEscape()
				|| handle == 0L
				|| handle != this.minecraft.getWindow().handle()
				|| this.minecraft.gui.screen() != null
				|| this.minecraft.gui.overlay() != null) {
			return;
		}
		if (RtsSelectionClient.isDragging()) {
			RtsSelectionClient.cancelDrag();
			ci.cancel();
		} else if (!RtsSelectionClient.isEmpty()) {
			RtsSelectionClient.clear();
			ci.cancel();
		}
	}
}
