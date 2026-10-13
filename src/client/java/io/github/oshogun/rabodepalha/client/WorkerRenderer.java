package io.github.oshogun.rabodepalha.client;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;

import io.github.oshogun.rabodepalha.worker.WorkerEntity;

/** Placeholder renderer: vanilla humanoid model with the default (wide) Steve skin. */
public class WorkerRenderer extends HumanoidMobRenderer<WorkerEntity, HumanoidRenderState, HumanoidModel<HumanoidRenderState>> {
	// Same file DefaultPlayerSkin uses; ClientAsset.ResourceTexture adds "textures/" and ".png".
	private static final Identifier TEXTURE = Identifier.withDefaultNamespace("textures/entity/player/wide/steve.png");

	public WorkerRenderer(EntityRendererProvider.Context ctx) {
		super(ctx, new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER)), 0.5F);
	}

	@Override
	public HumanoidRenderState createRenderState() {
		return new HumanoidRenderState();
	}

	@Override
	public Identifier getTextureLocation(HumanoidRenderState state) {
		return TEXTURE;
	}
}
