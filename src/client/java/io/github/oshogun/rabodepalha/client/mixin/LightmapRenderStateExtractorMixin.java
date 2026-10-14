package io.github.oshogun.rabodepalha.client.mixin;

import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.oshogun.rabodepalha.client.rts.RtsClient;
import io.github.oshogun.rabodepalha.rts.RtsBrightness;

/**
 * RTS brightness boost: raises the lightmap's night-vision intensity while RTS mode is active, without a mob
 * effect on the player. Sodium meshes terrain itself but its shaders sample the same vanilla lightmap
 * texture, so one hook brightens both renderers. The state is re-extracted every client tick, so leaving RTS
 * mode drops the boost on the next one.
 */
@Mixin(LightmapRenderStateExtractor.class)
abstract class LightmapRenderStateExtractorMixin {
	@Inject(method = "extract", at = @At("TAIL"))
	private void rabodepalha$rtsBrightness(LightmapRenderState state, float partialTicks, CallbackInfo ci) {
		// The method has one return, also reached when nothing was extracted; only touch a fresh extract.
		if (!state.needsUpdate) {
			return;
		}
		float boost = RtsClient.brightnessBoost();
		if (boost > 0.0f) {
			state.nightVisionEffectIntensity = RtsBrightness.boostedIntensity(state.nightVisionEffectIntensity, boost);
		}
	}
}
