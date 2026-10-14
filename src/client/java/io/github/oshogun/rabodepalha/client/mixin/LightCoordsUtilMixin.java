package io.github.oshogun.rabodepalha.client.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.BlockAndLightGetter;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import io.github.oshogun.rabodepalha.client.cutaway.CutawayLevelView;
import io.github.oshogun.rabodepalha.rts.CutawayRegion;
import io.github.oshogun.rabodepalha.rts.CutawayShading;

/**
 * RTS cutaway shading: a face on the cut samples light from a hidden cell. Cut-wall caps get a fixed light
 * and walkable floors a light floor, so the two read differently. Vanilla and Sodium meshing both call this
 * method; they are told apart from other callers by the {@link CutawayLevelView} the level is.
 */
@Mixin(LightCoordsUtil.class)
abstract class LightCoordsUtilMixin {
	@Inject(
		method = "getLightCoords(Lnet/minecraft/util/LightCoordsUtil$BrightnessGetter;Lnet/minecraft/world/level/BlockAndLightGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)I",
		at = @At("HEAD"),
		cancellable = true
	)
	private static void rabodepalha$shadeCut(
		LightCoordsUtil.BrightnessGetter brightnessGetter, BlockAndLightGetter level, BlockState state, BlockPos pos, CallbackInfoReturnable<Integer> cir
	) {
		if (!(level instanceof CutawayLevelView view)) {
			return;
		}
		CutawayRegion cut = view.rabodepalha$cutRegion();
		if (cut == null || !cut.contains(pos.getX(), pos.getY(), pos.getZ())) {
			return;
		}
		int real = brightnessGetter.packedBrightness(level, pos);
		boolean solid = view.rabodepalha$realSolid(pos.getX(), pos.getY(), pos.getZ());
		int[] light = CutawayShading.capLight(solid, LightCoordsUtil.block(real), LightCoordsUtil.sky(real));
		cir.setReturnValue(LightCoordsUtil.pack(light[0], light[1]));
	}
}
