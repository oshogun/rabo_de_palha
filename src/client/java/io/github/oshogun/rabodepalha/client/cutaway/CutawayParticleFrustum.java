package io.github.oshogun.rabodepalha.client.cutaway;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.util.Mth;

import io.github.oshogun.rabodepalha.rts.CutawayRegion;

/**
 * The frustum particles are extracted with while the RTS cutaway is active: a particle inside the hidden
 * region counts as outside the frustum, so it is not drawn.
 */
public final class CutawayParticleFrustum extends Frustum {
	private final CutawayRegion region;

	public CutawayParticleFrustum(Frustum frustum, CutawayRegion region) {
		super(frustum);
		this.region = region;
	}

	@Override
	public boolean pointInFrustum(double x, double y, double z) {
		return !this.region.contains(Mth.floor(x), Mth.floor(y), Mth.floor(z)) && super.pointInFrustum(x, y, z);
	}
}
