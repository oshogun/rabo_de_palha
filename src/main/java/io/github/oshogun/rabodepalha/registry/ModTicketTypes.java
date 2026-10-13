package io.github.oshogun.rabodepalha.registry;

import io.github.oshogun.rabodepalha.RaboDePalha;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.TicketType;

public final class ModTicketTypes {
	/**
	 * Keeps a busy worker's chunks loaded and simulated. Shaped like vanilla {@code PORTAL}: loading,
	 * simulation and keep-dimension-active, with a timeout the worker refreshes while it has a current
	 * or queued task, so an idle, dead or vanished worker's ticket goes away on its own.
	 *
	 * <p>The ticket is persistent: entities in unloaded chunks are not loaded, so after a server
	 * restart a busy worker would never tick again to re-add its ticket. Persisting it (as vanilla
	 * {@code FORCED} and {@code PORTAL} do) loads the worker's chunk on start, the worker ticks and
	 * takes the ticket back over, with no extra saved data to keep in sync.
	 */
	public static final TicketType WORKER = Registry.register(
			BuiltInRegistries.TICKET_TYPE,
			RaboDePalha.id("worker"),
			new TicketType(100L, TicketType.FLAG_PERSIST | TicketType.FLAG_LOADING
					| TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE)
	);

	private ModTicketTypes() {
	}

	/** Forces class loading (and so registration). */
	public static void init() {
	}
}
