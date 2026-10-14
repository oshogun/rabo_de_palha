package io.github.oshogun.rabodepalha;

import io.github.oshogun.rabodepalha.command.WorkerCommands;
import io.github.oshogun.rabodepalha.network.ModPayloads;
import io.github.oshogun.rabodepalha.registry.ModAttachments;
import io.github.oshogun.rabodepalha.registry.ModEntities;
import io.github.oshogun.rabodepalha.registry.ModTicketTypes;
import io.github.oshogun.rabodepalha.rts.RtsMode;
import io.github.oshogun.rabodepalha.rts.RtsWatchers;
import io.github.oshogun.rabodepalha.worker.WorkerChunkTickets;
import io.github.oshogun.rabodepalha.worker.task.TaskTypes;

import net.fabricmc.api.ModInitializer;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RaboDePalha implements ModInitializer {
	public static final String MOD_ID = "rabodepalha";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ModTicketTypes.init();
		ModEntities.init();
		WorkerChunkTickets.init();
		TaskTypes.bootstrap();
		WorkerCommands.register();
		ModAttachments.init();
		ModPayloads.init();
		RtsMode.init();
		RtsWatchers.init();
		LOGGER.info("Rabo-de-palha initialized");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
