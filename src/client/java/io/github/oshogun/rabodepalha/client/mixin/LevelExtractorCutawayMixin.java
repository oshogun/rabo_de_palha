package io.github.oshogun.rabodepalha.client.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.SectionPos;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.oshogun.rabodepalha.client.cutaway.Cutaway;
import io.github.oshogun.rabodepalha.client.cutaway.CutawayParticleFrustum;
import io.github.oshogun.rabodepalha.rts.CutawayRegion;

/**
 * RTS cutaway on vanilla culling: sections wholly inside the hidden region are dropped from the visible
 * lists without re-meshing, and particles inside the region are culled. Under Sodium {@code applyFrustum}
 * is a no-op, so the first hook does not fire there; Sodium skips those sections itself.
 */
@Mixin(LevelExtractor.class)
abstract class LevelExtractorCutawayMixin {
	@Shadow
	@Final
	private LevelRenderer levelRenderer;

	@Inject(method = "applyFrustum", at = @At("TAIL"))
	private void rabodepalha$hideInsideSections(Frustum frustum, CallbackInfo ci) {
		CutawayRegion region = Cutaway.current();
		if (!region.active()) {
			return;
		}
		this.levelRenderer.visibleSections().removeIf(section -> rabodepalha$inside(region, section));
		this.levelRenderer.nearbyVisibleSections().removeIf(section -> rabodepalha$inside(region, section));
	}

	@WrapOperation(method = "extract", at = @At(value = "NEW", target = "Lnet/minecraft/client/renderer/culling/Frustum;<init>(Lnet/minecraft/client/renderer/culling/Frustum;)V"))
	private Frustum rabodepalha$cutParticles(Frustum frustum, Operation<Frustum> original) {
		CutawayRegion region = Cutaway.current();
		if (!region.active()) {
			return original.call(frustum);
		}
		return new CutawayParticleFrustum(frustum, region);
	}

	private static boolean rabodepalha$inside(CutawayRegion region, SectionRenderDispatcher.RenderSection section) {
		long node = section.getSectionNode();
		return region.classify(SectionPos.x(node), SectionPos.y(node), SectionPos.z(node)) == CutawayRegion.Section.INSIDE;
	}
}
