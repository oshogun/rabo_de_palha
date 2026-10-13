package io.github.oshogun.rabodepalha.worker;

import io.github.oshogun.rabodepalha.registry.ModTicketTypes;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * Keeps a busy worker's chunks loaded with a {@link ModTicketTypes#WORKER} ticket centred on the
 * worker's current chunk.
 *
 * <p>Vanilla keeps one ticket per (type, level, chunk), so workers in the same chunk share one
 * ticket. {@link #HOLDERS} tracks which workers rely on each ticket, and the ticket is removed only
 * when its last holder releases it.
 *
 * <p>After a restart the holder sets are empty while the persisted tickets still exist; each busy
 * worker re-registers itself on its first tick, so nothing needs restoring here.
 */
public final class WorkerChunkTickets {
	/**
	 * Radius of the ticket around the worker's chunk. A ticket at radius r makes the centre chunk
	 * entity-ticking out to r - 2 ({@code ENTITY_TICKING} is two levels below {@code FULL}), so
	 * radius 3 gives the worker's chunk and its 8 neighbours entity-ticking: a worker at a chunk edge
	 * can path and act into the next chunk, and blocks it mines or places there are fully loaded.
	 */
	public static final int TICKET_RADIUS = 3;
	/**
	 * Chunks one ticket brings to full status: the (2r+1)^2 square at radius {@link #TICKET_RADIUS}
	 * (the ticket sits at level 33 - r; the further border chunks it loads are not counted).
	 */
	public static final int CHUNKS_PER_WORKER = (2 * TICKET_RADIUS + 1) * (2 * TICKET_RADIUS + 1);
	/** Cap on chunks one worker may keep loaded; a worker holds one ticket at a time. */
	public static final int MAX_CHUNKS_PER_WORKER = 49;
	/** Cap on chunks all workers together may keep loaded. */
	public static final int MAX_TOTAL_CHUNKS = 1568;
	/** Number of workers that may hold a ticket at once, derived from the chunk caps. */
	public static final int MAX_TICKETED_WORKERS = MAX_TOTAL_CHUNKS / CHUNKS_PER_WORKER;
	/** Ticks between ticket refreshes; well under the ticket's 100-tick timeout. */
	public static final int REFRESH_INTERVAL = 20;

	/** Server-wide budget of workers allowed to hold a ticket. */
	public static final Budget BUDGET = new Budget(MAX_TICKETED_WORKERS);
	/** Which workers hold the ticket on each chunk. */
	public static final Holders HOLDERS = new Holders();

	private WorkerChunkTickets() {
	}

	/** Clears the in-memory state when the server stops, so it does not leak into the next world. */
	public static void init() {
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			BUDGET.clear();
			HOLDERS.clear();
		});
	}

	/** Registers {@code worker} as a holder of the ticket on {@code chunk} and adds or refreshes it. */
	public static void acquire(ServerLevel level, ChunkPos chunk, UUID worker) {
		HOLDERS.add(levelKey(level), chunk.pack(), worker);
		refresh(level, chunk);
		level.resetEmptyTime();
	}

	/** Re-adds the ticket on {@code chunk}, which resets its timeout. */
	public static void refresh(ServerLevel level, ChunkPos chunk) {
		level.getChunkSource().addTicketWithRadius(ModTicketTypes.WORKER, chunk, TICKET_RADIUS);
	}

	/** Drops {@code worker} as a holder of {@code chunk}, removing the ticket if it was the last one. */
	public static void release(ServerLevel level, ChunkPos chunk, UUID worker) {
		if (HOLDERS.remove(levelKey(level), chunk.pack(), worker)) {
			level.getChunkSource().removeTicketWithRadius(ModTicketTypes.WORKER, chunk, TICKET_RADIUS);
		}
	}

	/**
	 * Drops {@code worker} as a holder of {@code chunk} in memory only, leaving the ticket in place;
	 * used when the worker unloads, so its persisted ticket brings it back after a restart.
	 */
	public static void forget(ServerLevel level, ChunkPos chunk, UUID worker) {
		HOLDERS.remove(levelKey(level), chunk.pack(), worker);
	}

	/** The workers currently holding the ticket on {@code chunk}. */
	public static Set<UUID> holdersAt(ServerLevel level, ChunkPos chunk) {
		return HOLDERS.holders(levelKey(level), chunk.pack());
	}

	private static String levelKey(ServerLevel level) {
		return level.dimension().identifier().toString();
	}

	/** A fixed number of slots, each held by at most one worker. Idempotent per worker. */
	public static final class Budget {
		private final int cap;
		private final Set<UUID> holders = new LinkedHashSet<>();

		public Budget(int cap) {
			this.cap = cap;
		}

		/** Takes a slot for {@code worker}; true if it holds one afterwards. */
		public boolean tryAcquire(UUID worker) {
			if (holders.contains(worker)) {
				return true;
			}
			if (holders.size() >= cap) {
				return false;
			}
			return holders.add(worker);
		}

		public void release(UUID worker) {
			holders.remove(worker);
		}

		public boolean holds(UUID worker) {
			return holders.contains(worker);
		}

		public int size() {
			return holders.size();
		}

		public int cap() {
			return cap;
		}

		public void clear() {
			holders.clear();
		}
	}

	/** The workers holding a ticket, per level and chunk. Idempotent per worker. */
	public static final class Holders {
		private final Map<String, Map<Long, Set<UUID>>> byLevel = new HashMap<>();

		/** Adds {@code worker} to the chunk's holders; true if it is the first holder. */
		public boolean add(String level, long chunk, UUID worker) {
			Set<UUID> set = byLevel.computeIfAbsent(level, k -> new HashMap<>())
					.computeIfAbsent(chunk, k -> new HashSet<>());
			boolean first = set.isEmpty();
			set.add(worker);
			return first;
		}

		/** Removes {@code worker} from the chunk's holders; true if that left the chunk with none. */
		public boolean remove(String level, long chunk, UUID worker) {
			Map<Long, Set<UUID>> chunks = byLevel.get(level);
			if (chunks == null) {
				return false;
			}
			Set<UUID> set = chunks.get(chunk);
			if (set == null || !set.remove(worker)) {
				return false;
			}
			if (!set.isEmpty()) {
				return false;
			}
			chunks.remove(chunk);
			if (chunks.isEmpty()) {
				byLevel.remove(level);
			}
			return true;
		}

		public Set<UUID> holders(String level, long chunk) {
			Map<Long, Set<UUID>> chunks = byLevel.get(level);
			Set<UUID> set = chunks == null ? null : chunks.get(chunk);
			return set == null ? Set.of() : Collections.unmodifiableSet(new HashSet<>(set));
		}

		public void clear() {
			byLevel.clear();
		}
	}
}
