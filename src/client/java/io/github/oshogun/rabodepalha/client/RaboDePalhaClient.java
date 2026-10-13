package io.github.oshogun.rabodepalha.client;

import net.fabricmc.api.ClientModInitializer;

import io.github.oshogun.rabodepalha.RaboDePalha;

public class RaboDePalhaClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		RaboDePalha.LOGGER.info("Rabo-de-palha client initialized");
	}
}
