package io.github.oshogun.rabodepalha.client;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.renderer.entity.EntityRenderers;

import io.github.oshogun.rabodepalha.RaboDePalha;
import io.github.oshogun.rabodepalha.registry.ModEntities;

public class RaboDePalhaClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		EntityRenderers.register(ModEntities.WORKER, WorkerRenderer::new);
		RaboDePalha.LOGGER.info("Rabo-de-palha client initialized");
	}
}
