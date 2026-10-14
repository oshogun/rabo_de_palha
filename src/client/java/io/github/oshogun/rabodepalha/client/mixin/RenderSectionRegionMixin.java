package io.github.oshogun.rabodepalha.client.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCopy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import io.github.oshogun.rabodepalha.client.cutaway.Cutaway;
import io.github.oshogun.rabodepalha.client.cutaway.CutawayLevelView;
import io.github.oshogun.rabodepalha.rts.CutawayRegion;

/**
 * RTS cutaway on vanilla meshing: the block view a section is compiled from reads hidden blocks as air,
 * so the cut faces get meshed. Only sections the cut crosses are affected; the region is captured on the
 * main thread when the view is built, so one mesh reads one region from start to end.
 */
@Mixin(RenderSectionRegion.class)
abstract class RenderSectionRegionMixin implements CutawayLevelView {
	@Unique
	private @Nullable CutawayRegion rabodepalha$cut;

	@Shadow
	private SectionCopy getSection(int sectionX, int sectionY, int sectionZ) {
		throw new AssertionError();
	}

	@Inject(method = "<init>", at = @At("TAIL"))
	private void rabodepalha$captureCut(ClientLevel level, int minSectionX, int minSectionY, int minSectionZ, SectionCopy[] sections, CallbackInfo ci) {
		// The region spans RADIUS = 1 section around the section being built.
		CutawayRegion region = Cutaway.current();
		if (region.classify(minSectionX + 1, minSectionY + 1, minSectionZ + 1) == CutawayRegion.Section.BOUNDARY) {
			this.rabodepalha$cut = region;
		}
	}

	@Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
	private void rabodepalha$hideBlock(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
		if (this.rabodepalha$hides(pos)) {
			cir.setReturnValue(Blocks.AIR.defaultBlockState());
		}
	}

	@Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
	private void rabodepalha$hideFluid(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
		if (this.rabodepalha$hides(pos)) {
			cir.setReturnValue(Fluids.EMPTY.defaultFluidState());
		}
	}

	@Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
	private void rabodepalha$hideBlockEntity(BlockPos pos, CallbackInfoReturnable<@Nullable BlockEntity> cir) {
		if (this.rabodepalha$hides(pos)) {
			cir.setReturnValue(null);
		}
	}

	@Unique
	private boolean rabodepalha$hides(BlockPos pos) {
		CutawayRegion cut = this.rabodepalha$cut;
		return cut != null && cut.contains(pos.getX(), pos.getY(), pos.getZ());
	}

	@Override
	public @Nullable CutawayRegion rabodepalha$cutRegion() {
		return this.rabodepalha$cut;
	}

	@Override
	public boolean rabodepalha$realSolid(int x, int y, int z) {
		BlockPos pos = new BlockPos(x, y, z);
		SectionCopy section = this.getSection(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(y), SectionPos.blockToSectionCoord(z));
		return section.getBlockState(pos).isSolidRender();
	}
}
