package io.github.oshogun.rabodepalha.client.cutaway.sodium.mixin;

import net.caffeinemc.mods.sodium.client.render.chunk.lists.VisibleChunkCollector;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.oshogun.rabodepalha.client.cutaway.Cutaway;
import io.github.oshogun.rabodepalha.rts.CutawayRegion;

/**
 * RTS cutaway on Sodium: sections wholly above the view level are left out of the render lists, so they
 * hide at once without a rebuild. This runs on Sodium's cull thread and reads only the volatile region.
 */
@Mixin(VisibleChunkCollector.class)
abstract class VisibleChunkCollectorMixin {
	@Inject(method = "visit", at = @At("HEAD"), cancellable = true)
	private void rabodepalha$skipHidden(int x, int y, int z, CallbackInfo ci) {
		if (Cutaway.current().classify(x, y, z) == CutawayRegion.Section.INSIDE) {
			ci.cancel();
		}
	}
}
