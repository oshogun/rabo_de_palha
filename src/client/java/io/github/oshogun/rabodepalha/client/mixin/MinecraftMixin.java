package io.github.oshogun.rabodepalha.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import io.github.oshogun.rabodepalha.client.rts.RtsClient;
import io.github.oshogun.rabodepalha.client.rts.RtsSelectionClient;

/**
 * RTS mode tweaks to {@link Minecraft}. The player does not use their inventory in RTS mode, and the
 * default rotate-right key (E) is also the vanilla inventory key: drop inventory key presses while RTS
 * mode is active, so E only rotates. Selected workers are marked with the vanilla glow outline.
 */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	@Shadow
	@Final
	public Options options;

	@Inject(method = "handleKeybinds", at = @At("HEAD"))
	private void rabodepalha$ignoreInventoryKeyInRts(CallbackInfo ci) {
		if (RtsClient.isActive()) {
			while (this.options.keyInventory.consumeClick()) {
			}
		}
	}

	@Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true)
	private void rabodepalha$glowSelectedWorkers(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (RtsClient.isActive() && RtsSelectionClient.isSelected(entity)) {
			cir.setReturnValue(true);
		}
	}
}
