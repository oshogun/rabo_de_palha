package io.github.oshogun.rabodepalha.client;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.renderer.entity.EntityRenderers;

import io.github.oshogun.rabodepalha.RaboDePalha;
import io.github.oshogun.rabodepalha.client.cutaway.Cutaway;
import io.github.oshogun.rabodepalha.client.rts.RtsBlueprintOverlay;
import io.github.oshogun.rabodepalha.client.rts.RtsClient;
import io.github.oshogun.rabodepalha.client.rts.RtsDigOverlay;
import io.github.oshogun.rabodepalha.client.rts.RtsHarvestOverlay;
import io.github.oshogun.rabodepalha.client.rts.RtsHoverOverlay;
import io.github.oshogun.rabodepalha.client.rts.RtsHud;
import io.github.oshogun.rabodepalha.client.rts.RtsIrrigateOverlay;
import io.github.oshogun.rabodepalha.client.rts.RtsPlantOverlay;
import io.github.oshogun.rabodepalha.client.rts.RtsQueuedOrdersOverlay;
import io.github.oshogun.rabodepalha.registry.ModEntities;

public class RaboDePalhaClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		EntityRenderers.register(ModEntities.WORKER, WorkerRenderer::new);
		RtsClient.init();
		Cutaway.init();
		RtsHud.init();
		RtsHoverOverlay.init();
		RtsQueuedOrdersOverlay.init();
		RtsDigOverlay.init();
		RtsPlantOverlay.init();
		RtsHarvestOverlay.init();
		RtsIrrigateOverlay.init();
		RtsBlueprintOverlay.init();
		RaboDePalha.LOGGER.info("Rabo-de-palha client initialized");
	}
}
