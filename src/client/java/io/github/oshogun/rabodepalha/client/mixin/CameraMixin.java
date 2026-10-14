package io.github.oshogun.rabodepalha.client.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.state.level.CameraRenderState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.oshogun.rabodepalha.client.cutaway.Cutaway;

/**
 * RTS cutaway: sections hidden whole keep their real occlusion data, so occlusion culling is turned off
 * while a cut is active, as vanilla does for a spectator inside a wall. Vanilla and Sodium both read this
 * flag.
 */
@Mixin(Camera.class)
abstract class CameraMixin {
	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void rabodepalha$noSmartCullInCutaway(CameraRenderState cameraState, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (Cutaway.isActive()) {
			cameraState.smartCull = false;
		}
	}
}
