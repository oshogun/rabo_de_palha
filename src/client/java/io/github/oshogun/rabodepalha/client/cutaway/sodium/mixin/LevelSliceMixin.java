package io.github.oshogun.rabodepalha.client.cutaway.sodium.mixin;

import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.caffeinemc.mods.sodium.client.world.cloned.ChunkRenderContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
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
import io.github.oshogun.rabodepalha.rts.CutawayShading;

/**
 * RTS cutaway on Sodium meshing: the slice a section is meshed from reads hidden blocks as air, so the cut
 * faces get meshed. The {@code BlockPos} overloads and {@code getFluidState} delegate to the hooked int
 * overloads. Slices are pooled, so the region is captured again on every {@code copyData}, on the main thread.
 */
@Mixin(LevelSlice.class)
abstract class LevelSliceMixin implements CutawayLevelView {
	@Unique
	private @Nullable CutawayRegion rabodepalha$cut;

	@Shadow
	@Final
	private BlockState[][] blockArrays;

	@Shadow
	private int originBlockX;

	@Shadow
	private int originBlockY;

	@Shadow
	private int originBlockZ;

	@Shadow
	private BoundingBox volume;

	@Inject(method = "copyData", at = @At("TAIL"))
	private void rabodepalha$captureCut(ChunkRenderContext context, CallbackInfo ci) {
		CutawayRegion region = Cutaway.current();
		SectionPos origin = context.getOrigin();
		this.rabodepalha$cut = region.classify(origin.getX(), origin.getY(), origin.getZ()) == CutawayRegion.Section.BOUNDARY
			? region
			: null;
	}

	@Inject(method = "getBlockState(III)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("HEAD"), cancellable = true)
	private void rabodepalha$hideBlock(int x, int y, int z, CallbackInfoReturnable<BlockState> cir) {
		if (this.rabodepalha$hides(x, y, z)) {
			cir.setReturnValue(Blocks.AIR.defaultBlockState());
		}
	}

	@Inject(method = "getBlockEntity(III)Lnet/minecraft/world/level/block/entity/BlockEntity;", at = @At("HEAD"), cancellable = true)
	private void rabodepalha$hideBlockEntity(int x, int y, int z, CallbackInfoReturnable<@Nullable BlockEntity> cir) {
		if (this.rabodepalha$hides(x, y, z)) {
			cir.setReturnValue(null);
		}
	}

	/** Emissive blocks read light here instead of through {@code LightCoordsUtil}; shade the cut the same way. */
	@Inject(method = "getBrightness", at = @At("RETURN"), cancellable = true)
	private void rabodepalha$shadeCut(LightLayer type, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
		if (!this.rabodepalha$hides(pos.getX(), pos.getY(), pos.getZ())) {
			return;
		}
		int real = cir.getReturnValueI();
		boolean solid = this.rabodepalha$realSolid(pos.getX(), pos.getY(), pos.getZ());
		if (type == LightLayer.BLOCK) {
			cir.setReturnValue(CutawayShading.capLight(solid, real, 0)[0]);
		} else {
			cir.setReturnValue(CutawayShading.capLight(solid, 0, real)[1]);
		}
	}

	@Unique
	private boolean rabodepalha$hides(int x, int y, int z) {
		CutawayRegion cut = this.rabodepalha$cut;
		return cut != null && cut.contains(x, y, z);
	}

	@Override
	public @Nullable CutawayRegion rabodepalha$cutRegion() {
		return this.rabodepalha$cut;
	}

	@Override
	public boolean rabodepalha$realSolid(int x, int y, int z) {
		if (!this.volume.isInside(x, y, z)) {
			return false;
		}
		int relX = x - this.originBlockX;
		int relY = y - this.originBlockY;
		int relZ = z - this.originBlockZ;
		BlockState state = this.blockArrays[LevelSlice.getLocalSectionIndex(relX >> 4, relY >> 4, relZ >> 4)]
			[LevelSlice.getLocalBlockIndex(relX & 15, relY & 15, relZ & 15)];
		return state.isSolidRender();
	}
}
